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
import java.util.TreeSet;
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

    /** Each designation by someone other than the Observer, as a rating of its kind:
     * designators in relevant-user order, their keys sorted, so the edge order — part
     * of the result — doesn't depend on fetch order. The Observer's own are pinned
     * instead. Returns how many it kept. */
    private static int addDesignations(
            List<String> designators, Map<String, Set<String>> designations,
            ScoreGraph graph, ScoreGraph.EdgeList ratings) {
        int added = 0;
        for (String designator : designators) {
            Set<String> designated = designations.get(designator);
            if (designated == null || designator.equals(graph.observer)) continue;
            Integer rater = graph.idOf.get(designator);
            if (rater == null) continue;
            for (String pubkey : new TreeSet<>(designated)) {
                Integer ratee = graph.idOf.get(pubkey);
                if (ratee == null || pubkey.equals(designator)) continue;
                ratings.add(ratee, rater, ScoreGraph.DESIGNATE);
                added++;
            }
        }
        return added;
    }

    /** Every designator's `designates:` set, fetched in batches on `parallelism` threads. */
    private Map<String, Set<String>> fetchDesignations(List<String> designators, int parallelism) {
        Map<String, Set<String>> designations = new HashMap<>();
        ExecutorService fetchers = Executors.newFixedThreadPool(parallelism, daemonThreads());
        try {
            List<Future<Map<String, Set<String>>>> batches = new ArrayList<>();
            for (List<String> batch : chunked(designators, BATCH_SIZE)) {
                batches.add(fetchers.submit(() -> relationshipsCache.getDesignationsBulk(batch)));
            }
            for (Future<Map<String, Set<String>>> batch : batches) designations.putAll(await(batch));
        } finally {
            fetchers.shutdownNow();
        }
        return designations;
    }

    /** Each relevant user's NIP-05 penalty (ADR 0006): from the follow walk for users it reached,
     * from one lookup for the rest (keys reached only by designation). Returns how many apply. */
    private int applyNip05Penalties(ScoreGraph graph, Map<String, ReachableUser> reachable, List<String> relevantUsers) {
        Map<String, String> statuses = new HashMap<>();
        List<String> unreached = new ArrayList<>();
        for (String pubkey : relevantUsers) {
            ReachableUser user = reachable.get(pubkey);
            if (user == null) unreached.add(pubkey);
            else if (user.nip05Status() != null) statuses.put(pubkey, user.nip05Status());
        }
        for (List<String> batch : chunked(unreached, BATCH_SIZE)) statuses.putAll(db.getPenalisedNip05Statuses(batch));
        int applied = 0;
        for (Map.Entry<String, String> entry : statuses.entrySet()) {
            double f = Constants.nip05Factor(entry.getValue());
            Integer id = graph.idOf.get(entry.getKey());
            if (id != null && f != 1.0 && !graph.pinned[id]) {
                graph.penalize(id, f);
                applied++;
            }
        }
        return applied;
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

    /** `designatedPubkeys`: the keys the Observer listed in their kind-10040, as the
     * request carries them; the Observer's own `designates:` set adds to them. They
     * are scored at {@link Constants#DESIGNATED_KEY_INFLUENCE} regardless of the
     * graph, and scored even when the Observer's graph never reaches them.
     *
     * Everyone else the Observer reaches rates their designated keys at
     * {@link Constants#DESIGNATION_RATING}, and those keys join the run even when no
     * follow reaches them. One level only: a key reached just by designation doesn't
     * have its own designations read. */
    public GrapeRankResult graperankAllSteps(
            String observer, GrapeRankParams params, Collection<String> designatedPubkeys) {
        long startTime = System.currentTimeMillis();

        long reachStartTime = System.currentTimeMillis();
        Map<String, ReachableUser> reachable = db.getReachableUsers(observer);
        Map<String, Double> storedInfluence = new HashMap<>();
        Map<String, Double> userDistanceMap = new HashMap<>();
        for (Map.Entry<String, ReachableUser> entry : reachable.entrySet()) {
            storedInfluence.put(entry.getKey(), entry.getValue().storedInfluence());
            if (entry.getValue().hops() <= Constants.MAX_HOPS) {
                userDistanceMap.put(entry.getKey(), (double) entry.getValue().hops());
            }
        }
        List<String> designators = new ArrayList<>(storedInfluence.keySet());
        System.out.println("TIMING reachable-users fetch took "
                + (System.currentTimeMillis() - reachStartTime) / 1000.0
                + " seconds (" + designators.size() + " reachable users, "
                + userDistanceMap.size() + " within " + Constants.MAX_HOPS + " hops)");

        long designationStartTime = System.currentTimeMillis();
        int parallelism = relationshipsCache.maxConcurrentFetches();
        // The Observer's own are read even when the follow walk returned nothing (no follows yet):
        // a zap signer is known only from `zaps_via:`.
        List<String> designationsToRead = designators;
        if (!storedInfluence.containsKey(observer)) {
            designationsToRead = new ArrayList<>(designators);
            designationsToRead.add(observer);
        }
        Map<String, Set<String>> designations = fetchDesignations(designationsToRead, parallelism);
        Set<String> designatedUsers = designatedUsersOf(observer, designatedPubkeys);
        designatedUsers.addAll(designatedUsersOf(observer, designations.get(observer)));

        List<String> relevantUsers = new ArrayList<>(designators);
        Set<String> relevant = new HashSet<>(designators);
        for (String designator : designators) {
            Set<String> designated = designations.get(designator);
            if (designated == null) continue;
            for (String pubkey : new TreeSet<>(designated)) {
                if (relevant.add(pubkey)) relevantUsers.add(pubkey);
            }
        }
        for (String pubkey : designatedUsers) {
            if (relevant.add(pubkey)) relevantUsers.add(pubkey);
        }
        System.out.println("TIMING designations fetch took "
                + (System.currentTimeMillis() - designationStartTime) / 1000.0
                + " seconds (" + designations.size() + " designators, "
                + relevantUsers.size() + " relevant users)");

        int numOfIts = (int) Math.round((double) relevantUsers.size() / BATCH_SIZE);
        System.out.println("How many Neo4j iterations: " + numOfIts);

        long initStartTime = System.currentTimeMillis();
        ScoreGraph graph = new ScoreGraph(observer, relevantUsers, userDistanceMap);
        for (String pubkey : designatedUsers) {
            graph.pin(graph.idOf.get(pubkey));
        }
        int penalised = applyNip05Penalties(graph, reachable, relevantUsers);
        int n = graph.size();
        System.out.println("TIMING scorecard init took "
                + (System.currentTimeMillis() - initStartTime) / 1000.0 + " seconds ("
                + penalised + " NIP-05 penalties)");

        ScoreGraph.EdgeList ratings = new ScoreGraph.EdgeList();

        long gatherStartTime = System.currentTimeMillis();
        long fetchMillis = 0;
        long waitMillis = 0;
        long edgeCount = 0;
        // Each ratee is in exactly one batch, so batch order doesn't affect the CSR rows.
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
        // After every batch, so each ratee's designations follow its follows, mutes and reports.
        edgeCount += addDesignations(designators, designations, graph, ratings);

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

        // Before anything reads Influence: counts, verified, the publish diff and the write set.
        int held = graph.holdInfluence(storedInfluence, Constants.INFLUENCE_DEAD_ZONE, Constants.PERSIST_FLOOR);
        System.out.println("TIMING influence dead zone: held " + held + " of " + n);

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

            Double prev = storedInfluence.get(pubkey);
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
        List<String> neo4jWritePubkeys = neo4jWritePubkeys(scorecards, reachable, Constants.PERSIST_FLOOR);
        List<String> neo4jClearPubkeys = neo4jClearPubkeys(scorecards, reachable, Constants.PERSIST_FLOOR);
        System.out.println("TIMING neo4j write set: " + neo4jWritePubkeys.size() + " of " + scorecards.size()
                + ", clear set: " + neo4jClearPubkeys.size());

        long finalTime = System.currentTimeMillis() - startTime;
        System.out.println("Entire process took " + (finalTime) / 1000.0 + " seconds");

        // Graph users only: designated keys are added regardless of the graph, so an
        // Observer with no follows but a kind-10040 is still "not connected".
        boolean success = storedInfluence.size() > 1;
        GrapeRankError error = success
                ? null
                : new GrapeRankError(ErrorCode.NO_ELIGIBLE_USERS, "Observer is not connected to any other users in the graph");

        GrapeRankResult result = new GrapeRankResult(
                scorecards,
                rounds,
                finalTime / 1000.0,
                success,
                changedScorePubkeys,
                droppedBelowCutoffPubkeys,
                error);
        result.setNeo4jWritePubkeys(neo4jWritePubkeys);
        result.setNeo4jClearPubkeys(neo4jClearPubkeys);
        result.setNeo4jPersistFloor(Constants.PERSIST_FLOOR);
        return result;
    }

    /** Scorecards above the persist floor whose persisted fields differ from what Neo4j holds, or
     * that Neo4j holds none for. Influence is compared exactly: the dead zone already returned the
     * stored value for noise, and the stored value must follow every move past it, or the next
     * run's dead zone would measure from a stale value and flip the cutoffs back. */
    static List<String> neo4jWritePubkeys(Map<String, ScoreCard> scorecards, Map<String, ReachableUser> stored,
                                          double floor) {
        List<String> out = new ArrayList<>();
        for (ScoreCard card : scorecards.values()) {
            if (card.getInfluence() <= floor) continue;
            ReachableUser prev = stored.get(card.getObservee());
            if (prev == null
                    || prev.storedInfluence() == null
                    || Double.compare(prev.storedInfluence(), card.getInfluence()) != 0
                    || !Long.valueOf(card.getHops()).equals(prev.storedHops())
                    || !Long.valueOf(card.getTrustedFollowers()).equals(prev.storedTrustedFollowers())) {
                out.add(card.getObservee());
            }
        }
        return out;
    }

    /** Scorecards at or below the persist floor that Neo4j still holds any score for. */
    static List<String> neo4jClearPubkeys(Map<String, ScoreCard> scorecards, Map<String, ReachableUser> stored,
                                          double floor) {
        List<String> out = new ArrayList<>();
        for (ScoreCard card : scorecards.values()) {
            if (card.getInfluence() > floor) continue;
            ReachableUser prev = stored.get(card.getObservee());
            if (prev != null && (prev.storedInfluence() != null || prev.storedHops() != null
                    || prev.storedTrustedFollowers() != null)) {
                out.add(card.getObservee());
            }
        }
        return out;
    }

}