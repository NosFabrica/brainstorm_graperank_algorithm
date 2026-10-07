package com.nosfabrica.graperank.grape;

import com.nosfabrica.graperank.db.IGraphDB;
import com.nosfabrica.graperank.db.IRelationshipsCache;
import com.nosfabrica.graperank.db.IncomingRelationships;
import com.nosfabrica.graperank.db.ReachableUser;
import com.nosfabrica.graperank.db.RelationshipInfo;
import com.nosfabrica.graperank.exceptions.ErrorCode;
import com.nosfabrica.graperank.exceptions.UnknownRelationshipException;
import com.nosfabrica.graperank.rank.ScoreCard;

import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class GrapeRankAlgorithm {
    private final IGraphDB db;
    private final IRelationshipsCache relationshipsCache;

    public GrapeRankAlgorithm(IGraphDB db, IRelationshipsCache relationshipsCache) {
        this.db = db;
        this.relationshipsCache = relationshipsCache;
    }

    public static double convertInputToConfidence(double input, double rigor) {
        double rigority = -Math.log(rigor);
        double fooB = -input * rigority;
        double fooA = Math.exp(fooB);
        double confidence = 1 - fooA;
        return confidence;
    }

    /** Append each relationship between two relevant users as a rating edge of its kind; the
     * Observer's own follows are their own kind. Returns how many it kept. */
    private static int addRatings(List<RelationshipInfo> relationships, ScoreGraph graph, ScoreGraph.EdgeList ratings) {
        int added = 0;
        for (RelationshipInfo rel : relationships) {
            Integer rater = graph.idOf.get(rel.getSource());
            Integer ratee = graph.idOf.get(rel.getTarget());
            if (rater == null || ratee == null) continue;

            byte kind = switch (rel.getRelationship()) {
                case "FOLLOWS" -> rel.getSource().equals(graph.observer)
                        ? ScoreGraph.OBSERVER_FOLLOW
                        : ScoreGraph.FOLLOW;
                case "MUTES" -> ScoreGraph.MUTE;
                case "REPORTS" -> ScoreGraph.REPORT;
                default -> throw new UnknownRelationshipException(rel.getRelationship());
            };
            ratings.add(ratee, rater, kind);
            added++;
        }
        return added;
    }

    /** Pubkeys the Observer designated in their kind-10040 (the keys they trust to
     * publish assertions for them), minus the Observer. */
    static Set<String> designatedUsersOf(String observer, Collection<String> designatedPubkeys) {
        Set<String> designated = new HashSet<>();
        if (designatedPubkeys != null) {
            for (String pubkey : designatedPubkeys) {
                if (pubkey != null && !pubkey.equals(observer)) {
                    designated.add(pubkey);
                }
            }
        }
        return designated;
    }

    private static final int BATCH_SIZE = 1000;
    private record TimedFetch(IncomingRelationships incoming, long fetchMillis) {}

    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while fetching relationships", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        }
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "relationship-fetch-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public static <T> List<List<T>> chunked(List<T> seq, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < seq.size(); i += size) {
            chunks.add(seq.subList(i, Math.min(i + size, seq.size())));
        }
        return chunks;
    }

    public GrapeRankResult graperankAllSteps(String observer) {
        return graperankAllSteps(observer, Constants.DEFAULT_PARAMS);
    }

    public GrapeRankResult graperankAllSteps(String observer, GrapeRankParams params) {
        return graperankAllSteps(observer, params, List.of());
    }

    /** `designatedPubkeys`: the keys the Observer listed in their kind-10040. They
     * are scored at {@link Constants#DESIGNATED_KEY_INFLUENCE} regardless of the
     * graph, and scored even when the Observer's graph never reaches them. */
    public GrapeRankResult graperankAllSteps(
            String observer, GrapeRankParams params, Collection<String> designatedPubkeys) {
        long startTime = System.currentTimeMillis();

        long reachStartTime = System.currentTimeMillis();
        Map<String, ReachableUser> reachable = db.getReachableUsers(observer);
        Map<String, Double> previousInfluence = new HashMap<>();
        Map<String, Double> userDistanceMap = new HashMap<>();
        for (Map.Entry<String, ReachableUser> entry : reachable.entrySet()) {
            previousInfluence.put(entry.getKey(), entry.getValue().previousInfluence());
            if (entry.getValue().hops() <= Constants.MAX_HOPS) {
                userDistanceMap.put(entry.getKey(), (double) entry.getValue().hops());
            }
        }
        List<String> relevantUsers = new ArrayList<>(previousInfluence.keySet());
        Set<String> designatedUsers = designatedUsersOf(observer, designatedPubkeys);
        for (String pubkey : designatedUsers) {
            if (!previousInfluence.containsKey(pubkey)) {
                relevantUsers.add(pubkey);
            }
        }
        System.out.println("TIMING reachable-users fetch took "
                + (System.currentTimeMillis() - reachStartTime) / 1000.0
                + " seconds (" + relevantUsers.size() + " relevant users, "
                + userDistanceMap.size() + " within " + Constants.MAX_HOPS + " hops)");

        int numOfIts = (int) Math.round((double) relevantUsers.size() / BATCH_SIZE);
        System.out.println("How many Neo4j iterations: " + numOfIts);

        long initStartTime = System.currentTimeMillis();
        ScoreGraph graph = new ScoreGraph(observer, relevantUsers, userDistanceMap);
        for (String pubkey : designatedUsers) {
            graph.pin(graph.idOf.get(pubkey));
        }
        int n = graph.size();
        System.out.println("TIMING scorecard init took "
                + (System.currentTimeMillis() - initStartTime) / 1000.0 + " seconds");

        ScoreGraph.EdgeList ratings = new ScoreGraph.EdgeList();

        long gatherStartTime = System.currentTimeMillis();
        long fetchMillis = 0;
        long waitMillis = 0;
        long edgeCount = 0;
        // Each ratee is in exactly one batch, so batch order doesn't affect the CSR rows.
        int parallelism = relationshipsCache.maxConcurrentFetches();
        List<List<String>> batches = chunked(relevantUsers, BATCH_SIZE);
        ExecutorService fetchers = Executors.newFixedThreadPool(parallelism, daemonThreads());
        try {
            Deque<Future<TimedFetch>> inFlight = new ArrayDeque<>();
            int next = 0;
            for (int iteration = 0; iteration < batches.size(); iteration++) {
                while (next < batches.size() && inFlight.size() < parallelism) {
                    List<String> batch = batches.get(next++);
                    inFlight.add(fetchers.submit(() -> {
                        long start = System.currentTimeMillis();
                        IncomingRelationships fetched = relationshipsCache.getIncomingBulk(batch);
                        return new TimedFetch(fetched, System.currentTimeMillis() - start);
                    }));
                }

                long waitStart = System.currentTimeMillis();
                TimedFetch timed = await(inFlight.removeFirst());
                waitMillis += System.currentTimeMillis() - waitStart;
                fetchMillis += timed.fetchMillis();
                IncomingRelationships incoming = timed.incoming();

                // Per ratee the sweep sums follows, then mutes, then reports; the order is part of the result.
                edgeCount += addRatings(incoming.follows(), graph, ratings);
                edgeCount += addRatings(incoming.mutes(), graph, ratings);
                edgeCount += addRatings(incoming.reports(), graph, ratings);
            }
        } finally {
            fetchers.shutdownNow();
        }

        ScoreGraph.Csr ratingsByUser = ratings.toCsr(n);

        long gatherMillis = System.currentTimeMillis() - gatherStartTime;
        System.out.println("TIMING relationship gather took " + gatherMillis / 1000.0
                + " seconds (fetch " + fetchMillis / 1000.0
                + "s summed over " + parallelism + " threads, waited " + waitMillis / 1000.0
                + "s, build " + (gatherMillis - waitMillis) / 1000.0
                + "s, " + edgeCount + " edges)");

        long algoStartTime = System.currentTimeMillis();
        int rounds = graph.iterate(ratingsByUser, params);
        long algoEndTime = System.currentTimeMillis();
        System.out.println("Algorithm took " + (algoEndTime - algoStartTime) / 1000.0 + " seconds");


        System.out.println("Getting trusted followers for each pubkey...");
        long trustedStartTime = System.currentTimeMillis();
        graph.countTrustedRaters(ratingsByUser, params);

        System.out.println("TIMING trusted follower/reporter counts took "
                + (System.currentTimeMillis() - trustedStartTime) / 1000.0 + " seconds");

        long diffStartTime = System.currentTimeMillis();
        List<String> changedScorePubkeys = new ArrayList<>();
        List<String> droppedBelowCutoffPubkeys = new ArrayList<>();
        double cutoff = 0.02;

        for (int i = 0; i < n; i++) {
            String pubkey = graph.pubkeys[i];
            double newScore = graph.influence[i];
            double newRounded = Math.round(newScore * 100.0) / 100.0;

            Double prev = previousInfluence.get(pubkey);
            boolean hasPrev = prev != null;
            double prevRounded = hasPrev ? Math.round(prev * 100.0) / 100.0 : 0.0;

            // Compare on the 2-decimal rounded value, matching the publish/index
            // gates (`round(influence, 2) >= cutoff` on both the relay and Vespa
            // sides). Using the raw score here would miss a new score like 0.0195
            // that rounds to 0.02 (published, but not flagged as changed).
            if (hasPrev && prevRounded >= cutoff && newRounded < cutoff) {
                droppedBelowCutoffPubkeys.add(pubkey);
            } else if (hasPrev) {
                if (Double.compare(newRounded, prevRounded) != 0) {
                    changedScorePubkeys.add(pubkey);
                }
            } else if (newRounded >= cutoff) {
                changedScorePubkeys.add(pubkey);
            }
        }

        System.out.println("TIMING changed/dropped diff took "
                + (System.currentTimeMillis() - diffStartTime) / 1000.0 + " seconds");

        Map<String, ScoreCard> scorecards = graph.toScorecards(params.verifiedFollowersInfluenceCutoff());

        long finalTime = System.currentTimeMillis() - startTime;
        System.out.println("Entire process took " + (finalTime) / 1000.0 + " seconds");

        // Graph users only: designated keys are added regardless of the graph, so an
        // Observer with no follows but a kind-10040 is still "not connected".
        boolean success = previousInfluence.size() > 1;
        GrapeRankError error = success
                ? null
                : new GrapeRankError(ErrorCode.NO_ELIGIBLE_USERS, "Observer is not connected to any other users in the graph");

        return new GrapeRankResult(
                scorecards,
                rounds,
                finalTime / 1000.0,
                success,
                changedScorePubkeys,
                droppedBelowCutoffPubkeys,
                error);

    }

}