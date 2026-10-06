package com.nosfabrica.graperank.grape;

import com.nosfabrica.graperank.db.IGraphDB;
import com.nosfabrica.graperank.db.IRelationshipsCache;
import com.nosfabrica.graperank.db.ReachableUser;
import com.nosfabrica.graperank.db.RelationshipInfo;
import com.nosfabrica.graperank.exceptions.ErrorCode;
import com.nosfabrica.graperank.exceptions.UnknownRelationshipException;
import com.nosfabrica.graperank.rank.ScoreCard;

import java.util.Collection;
import java.util.Map;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Frozen copy of the pre-SoA GrapeRankAlgorithm (HashMap<String, ScoreCard> core). The SoA
 * implementation must match it exactly; see GrapeRankEquivalenceTest. */
final class ReferenceGrapeRank {
    private final IGraphDB db;
    private final IRelationshipsCache relationshipsCache;

    record Input(String rater, String ratee, double rating, double confidence) {
        String getRater() { return rater; }
        String getRatee() { return ratee; }
        double getRating() { return rating; }
        double getConfidence() { return confidence; }
    }

    record AlgorithmResult(Map<String, ScoreCard> getScorecards, int getRounds) {}

    ReferenceGrapeRank(IGraphDB db, IRelationshipsCache relationshipsCache) {
        this.db = db;
        this.relationshipsCache = relationshipsCache;
    }

    public static AlgorithmResult graperankAlgorithm(
            Map<String, List<Input>> graperankInputs,
            Map<String, ScoreCard> graperankScorecards,
            GrapeRankParams params) {
        return graperankAlgorithm(graperankInputs, graperankScorecards, params, Set.of());
    }

    /** `pinnedUsers` keep the Influence their scorecard was seeded with — like the
     * Observer, their scorecard is never recomputed from ratings. */
    public static AlgorithmResult graperankAlgorithm(
            Map<String, List<Input>> graperankInputs,
            Map<String, ScoreCard> graperankScorecards,
            GrapeRankParams params,
            Set<String> pinnedUsers) {

        int rounds = 0;
        boolean shouldBreak;

        while (true) {
            shouldBreak = true;

            for (Map.Entry<String, ScoreCard> entry : graperankScorecards.entrySet()) {
                ScoreCard scorecard = entry.getValue();

                if (scorecard.getObserver().equals(scorecard.getObservee())
                        || pinnedUsers.contains(scorecard.getObservee())) {
                    continue;
                }

                // handling empty case. to investigate later
                List<Input> relevantDataPoints = graperankInputs.getOrDefault(scorecard.getObservee(),List.of());

                double sumOfWeights = 0;
                double sumOfWxr = 0;

                for (Input relevantDataPoint : relevantDataPoints) {
                    double infOfRater = graperankScorecards.get(relevantDataPoint.getRater()).getInfluence();
                    double weight = relevantDataPoint.getConfidence()
                            * infOfRater
                            * params.attenuationFactor();

                    double wxr = weight * relevantDataPoint.getRating();

                    sumOfWeights += weight;
                    sumOfWxr += wxr;
                }

                double avgScore = (sumOfWeights != 0) ? sumOfWxr / sumOfWeights : 0;
                scorecard.setAverageScore(avgScore);
                scorecard.setInput(sumOfWeights);

                // Convert input to confidence (you need to define the logic for this)
                scorecard.setConfidence(convertInputToConfidence(scorecard.getInput(), params.rigor()));

                double computedInfluence = Math.max(scorecard.getAverageScore() * scorecard.getConfidence(), 0);
                double deltaInfluence = Math.abs(computedInfluence - scorecard.getInfluence());

                if (deltaInfluence > Constants.THRESHOLD_OF_LOOP_BREAK_GIVEN_MINIMUM_DELTA_INFLUENCE) {
                    shouldBreak = false;
                }

                scorecard.setInfluence(computedInfluence);
            }

            rounds++;

            if (shouldBreak) {
                break;
            }
        }

        for (ScoreCard scorecard : graperankScorecards.values()) {
            scorecard.setVerified(scorecard.getInfluence() > params.verifiedFollowersInfluenceCutoff());
        }

        return new AlgorithmResult(graperankScorecards, rounds);

    }

    public static double convertInputToConfidence(double input, double rigor) {
        double rigority = -Math.log(rigor);
        double fooB = -input * rigority;
        double fooA = Math.exp(fooB);
        double confidence = 1 - fooA;
        return confidence;
    }

    public List<Input> getInputsOfRelationships(
            List<RelationshipInfo> outgoingRelationships,
            String observer,
            GrapeRankParams params) {
        List<Input> graperankInputs = new ArrayList<>();

        for (RelationshipInfo outgoingRelationshipObj : outgoingRelationships) {
            String outgoingRelationship = outgoingRelationshipObj.getRelationship();
            String outgoingRelationshipTarget = outgoingRelationshipObj.getTarget();
            String outgoingRelationshipSource = outgoingRelationshipObj.getSource();

            double rating = 0;

            switch (outgoingRelationship) {
                case "FOLLOWS":
                    rating = params.followRating();
                    break;
                case "MUTES":
                    rating = params.muteRating();
                    break;
                case "REPORTS":
                    rating = params.reportRating();
                    break;
                default:
                    throw new UnknownRelationshipException(outgoingRelationship);
            }

            double confidence = 0;

            switch (outgoingRelationship) {
                case "FOLLOWS":
                    if (outgoingRelationshipSource.equals(observer)) {
                        confidence = params.followConfidenceOfObserver();
                    } else {
                        confidence = params.followConfidence();
                    }
                    break;
                case "MUTES":
                    confidence = params.muteConfidence();
                    break;
                case "REPORTS":
                    confidence = params.reportConfidence();
                    break;
                default:
                    throw new UnknownRelationshipException(outgoingRelationship);
            }

            Input newInput = new Input(outgoingRelationshipSource, outgoingRelationshipTarget, rating,
                    confidence);
            graperankInputs.add(newInput);
        }

        return graperankInputs;
    }

    public Map<String, ScoreCard> initGrapeRankScorecards(List<String> relevantUsers, String observer, Map<String, Double> userDistanceMap) {
        Map<String, ScoreCard> result = new HashMap<>();

        for (String user : relevantUsers) {
            if (!user.equals(observer)) {
                Double distance = userDistanceMap.getOrDefault(user, Constants.UNREACHABLE_HOPS);
                

                result.put(user, new ScoreCard(observer, user, distance));
            } else {

                result.put(user, new ScoreCard(
                        observer,
                        user,
                        1.0,
                        Double.POSITIVE_INFINITY,
                        1.0,
                        1.0));
            }
        }

        return result;
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

    /** Seed each designated key's scorecard at the fixed designated Influence. */
    static void pinDesignatedScorecards(Map<String, ScoreCard> scorecards, Set<String> designated) {
        for (String pubkey : designated) {
            ScoreCard scorecard = scorecards.get(pubkey);
            scorecard.setAverageScore(Constants.DESIGNATED_KEY_INFLUENCE);
            scorecard.setInput(Double.POSITIVE_INFINITY);
            scorecard.setConfidence(1.0);
            scorecard.setInfluence(Constants.DESIGNATED_KEY_INFLUENCE);
        }
    }

    /** How many of `ratee`'s raters clear `cutoff` — raw Influence, strict `>`.
     * The one rule behind the trusted follower / reporter / muter counts, which
     * differ only in which reverse-set and which preset cutoff they read. */
    private static double countTrustedRaters(
            Map<String, List<String>> ratersByRatee,
            String ratee,
            Map<String, ScoreCard> scorecards,
            double cutoff) {
        return ratersByRatee.getOrDefault(ratee, Collections.emptyList()).stream()
                .filter(rater -> {
                    ScoreCard raterScoreCard = scorecards.get(rater);
                    return raterScoreCard != null && raterScoreCard.getInfluence() > cutoff;
                })
                .count();
    }

    private static final int BATCH_SIZE = 1000;

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

        int numOfIts = (int) Math.round((double) relevantUsers.size() / BATCH_SIZE);

        Map<String, List<Input>> graperankInputs = new HashMap<>();

        Map<String, List<String>> followersByUser = new HashMap<>();

        Map<String, List<String>> mutersByUser = new HashMap<>();

        Map<String, List<String>> reportersByUser = new HashMap<>();

        Set<String> relevantUsersSet = new HashSet<>(relevantUsers);

        long gatherStartTime = System.currentTimeMillis();
        long redisFetchMillis = 0;
        long edgeCount = 0;
        int iteration = 0;
        for (List<String> usersBatch : chunked(relevantUsers, BATCH_SIZE)) {

            long batchStartTime = System.currentTimeMillis();
            List<RelationshipInfo> incomingFollowRelationships = relationshipsCache.getIncomingFollowsBulk(
                    usersBatch);

            List<RelationshipInfo> incomingMuteRelationships = relationshipsCache.getIncomingMutesBulk(
                    usersBatch);

            List<RelationshipInfo> incomingReportRelationships = relationshipsCache.getIncomingReportsBulk(
                    usersBatch);


            long batchEndTime = System.currentTimeMillis();
            redisFetchMillis += batchEndTime - batchStartTime;

            // Raters must be in relevantUsers so every Input has a scorecard entry in graperankAlgorithm.
            List<RelationshipInfo> ratingsForBatch = new ArrayList<>(
                    incomingFollowRelationships.size()
                            + incomingMuteRelationships.size()
                            + incomingReportRelationships.size());
            for (RelationshipInfo rel : incomingFollowRelationships) {
                if (relevantUsersSet.contains(rel.getSource())) ratingsForBatch.add(rel);
            }
            for (RelationshipInfo rel : incomingMuteRelationships) {
                if (relevantUsersSet.contains(rel.getSource())) ratingsForBatch.add(rel);
            }
            for (RelationshipInfo rel : incomingReportRelationships) {
                if (relevantUsersSet.contains(rel.getSource())) ratingsForBatch.add(rel);
            }

            List<Input> graperankInputsOfUser = getInputsOfRelationships(
                   ratingsForBatch, observer,params);


            edgeCount += graperankInputsOfUser.size();
            for (Input grprIn : graperankInputsOfUser) {
                graperankInputs.computeIfAbsent(grprIn.getRatee(), k -> new ArrayList<>()).add(grprIn);
            }

            for (RelationshipInfo rel : incomingFollowRelationships) {

                String followedUser = rel.getTarget(); 
                String follower = rel.getSource();     

                followersByUser
                    .computeIfAbsent(followedUser, k -> new ArrayList<>())
                    .add(follower);
            }

            for (RelationshipInfo rel : incomingMuteRelationships) {

                String mutedUser = rel.getTarget();
                String muter = rel.getSource();

                mutersByUser
                    .computeIfAbsent(mutedUser, k -> new ArrayList<>())
                    .add(muter);
            }

            for (RelationshipInfo rel : incomingReportRelationships) {

                String reportedUser = rel.getTarget();
                String reporter = rel.getSource();     

                reportersByUser
                    .computeIfAbsent(reportedUser, k -> new ArrayList<>())
                    .add(reporter);
            }

            iteration++;
        }

        long gatherMillis = System.currentTimeMillis() - gatherStartTime;

        long initStartTime = System.currentTimeMillis();
        Map<String, ScoreCard> scorecards = initGrapeRankScorecards(relevantUsers, observer,userDistanceMap);
        pinDesignatedScorecards(scorecards, designatedUsers);

        long algoStartTime = System.currentTimeMillis();
        AlgorithmResult algorithmResult = graperankAlgorithm(graperankInputs, scorecards, params, designatedUsers);
        long algoEndTime = System.currentTimeMillis();
        long trustedStartTime = System.currentTimeMillis();
        Map<String, ScoreCard> finalScorecards = algorithmResult.getScorecards();

        for (Map.Entry<String, ScoreCard> entry : finalScorecards.entrySet()) {
            String userPubkey = entry.getKey();
            ScoreCard scoreCard = entry.getValue();

            scoreCard.setTrustedFollowers(countTrustedRaters(
                    followersByUser, userPubkey, finalScorecards, params.verifiedFollowersInfluenceCutoff()));
            scoreCard.setTrustedReporters(countTrustedRaters(
                    reportersByUser, userPubkey, finalScorecards, params.verifiedReportersInfluenceCutoff()));
            scoreCard.setTrustedMuters(countTrustedRaters(
                    mutersByUser, userPubkey, finalScorecards, params.verifiedMutersInfluenceCutoff()));
        }

        long diffStartTime = System.currentTimeMillis();
        List<String> changedScorePubkeys = new ArrayList<>();
        List<String> droppedBelowCutoffPubkeys = new ArrayList<>();
        double cutoff = 0.02;

        for (Map.Entry<String, ScoreCard> entry : finalScorecards.entrySet()) {
            String pubkey = entry.getKey();
            double newScore = entry.getValue().getInfluence();
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

        long finalTime = System.currentTimeMillis() - startTime;

        // Graph users only: designated keys are added regardless of the graph, so an
        // Observer with no follows but a kind-10040 is still "not connected".
        boolean success = previousInfluence.size() > 1;
        GrapeRankError error = success
                ? null
                : new GrapeRankError(ErrorCode.NO_ELIGIBLE_USERS, "Observer is not connected to any other users in the graph");

        return new GrapeRankResult(
                algorithmResult.getScorecards(),
                algorithmResult.getRounds(),
                finalTime / 1000.0,
                success,
                changedScorePubkeys,
                droppedBelowCutoffPubkeys,
                error);

    }

}