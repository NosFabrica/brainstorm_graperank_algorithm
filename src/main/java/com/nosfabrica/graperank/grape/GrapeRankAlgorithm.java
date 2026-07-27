package com.nosfabrica.graperank.grape;

import com.nosfabrica.graperank.db.IGraphDB;
import com.nosfabrica.graperank.db.IRelationshipsCache;
import com.nosfabrica.graperank.db.RelationshipInfo;
import com.nosfabrica.graperank.exceptions.ErrorCode;
import com.nosfabrica.graperank.exceptions.UnknownRelationshipException;
import com.nosfabrica.graperank.rank.ScoreCard;

import java.util.Map;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GrapeRankAlgorithm {
    private final IGraphDB db;
    private final IRelationshipsCache relationshipsCache;

    public GrapeRankAlgorithm(IGraphDB db, IRelationshipsCache relationshipsCache) {
        this.db = db;
        this.relationshipsCache = relationshipsCache;
    }

    public static GrapeRankAlgorithmResult graperankAlgorithm(
            Map<String, List<GrapeRankInput>> graperankInputs,
            Map<String, ScoreCard> graperankScorecards,
            GrapeRankParams params) {

        int rounds = 0;
        boolean shouldBreak;

        while (true) {
            shouldBreak = true;

            for (Map.Entry<String, ScoreCard> entry : graperankScorecards.entrySet()) {
                ScoreCard scorecard = entry.getValue();

                if (scorecard.getObserver().equals(scorecard.getObservee())) {
                    continue;
                }

                // handling empty case. to investigate later
                List<GrapeRankInput> relevantDataPoints = graperankInputs.getOrDefault(scorecard.getObservee(),List.of());

                double sumOfWeights = 0;
                double sumOfWxr = 0;

                for (GrapeRankInput relevantDataPoint : relevantDataPoints) {
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
            System.out.println("NUMBER OF ROUNDS: " + rounds);

            if (shouldBreak) {
                break;
            }
        }

        for (ScoreCard scorecard : graperankScorecards.values()) {
            scorecard.setVerified(scorecard.getInfluence() >= params.verifiedFollowersInfluenceCutoff());
        }

        return new GrapeRankAlgorithmResult(graperankScorecards, rounds);

    }

    public static double convertInputToConfidence(double input, double rigor) {
        double rigority = -Math.log(rigor);
        double fooB = -input * rigority;
        double fooA = Math.exp(fooB);
        double confidence = 1 - fooA;
        return confidence;
    }

    public List<GrapeRankInput> getGrapeRankInputsOfRelationships(
            List<RelationshipInfo> outgoingRelationships,
            String observer,
            GrapeRankParams params) {
        List<GrapeRankInput> graperankInputs = new ArrayList<>();

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

            GrapeRankInput newInput = new GrapeRankInput(outgoingRelationshipSource, outgoingRelationshipTarget, rating,
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
        long startTime = System.currentTimeMillis();

        long prevInfluenceStartTime = System.currentTimeMillis();
        Map<String, Double> previousInfluence = db.getUsersConnectedToObserverWithPreviousInfluence(observer);
        List<String> relevantUsers = new ArrayList<>(previousInfluence.keySet());
        Map<String, Double> userDistanceMap = new HashMap<>();
        System.out.println("TIMING previous-influence fetch took "
                + (System.currentTimeMillis() - prevInfluenceStartTime) / 1000.0
                + " seconds (" + relevantUsers.size() + " relevant users)");

        Map<Integer, List<String>> hopsMap = new HashMap<>();
        long hopsStartTime = System.currentTimeMillis();
        for (int hop = 8; hop >= 1; hop--) {
            long hopStartTime = System.currentTimeMillis();
            List<String> usersAtHop = db.getUsersConnectedToObserver(observer, hop);
            hopsMap.put(hop, usersAtHop);
            System.out.println("TIMING hop query " + hop + " took "
                    + (System.currentTimeMillis() - hopStartTime) / 1000.0
                    + " seconds (" + usersAtHop.size() + " users)");
        }
        System.out.println("TIMING all 8 hop queries took "
                + (System.currentTimeMillis() - hopsStartTime) / 1000.0 + " seconds");

        for (int hop = 8; hop >= 1; hop--) {
            List<String> usersAtHop = hopsMap.get(hop);
            for (String user : usersAtHop) {
                userDistanceMap.put(user, (double) hop);
            }
        }



        int numOfIts = (int) Math.round((double) relevantUsers.size() / BATCH_SIZE);
        System.out.println("How many Neo4j iterations: " + numOfIts);

        Map<String, List<GrapeRankInput>> graperankInputs = new HashMap<>();

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
            System.out.println(
                    iteration + " :: Getting relationships batched took " + (batchEndTime - batchStartTime) / 1000.0 + " seconds");

            // Raters must be in relevantUsers so every GrapeRankInput has a scorecard entry in graperankAlgorithm.
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

            List<GrapeRankInput> graperankInputsOfUser = getGrapeRankInputsOfRelationships(
                   ratingsForBatch, observer,params);


            edgeCount += graperankInputsOfUser.size();
            for (GrapeRankInput grprIn : graperankInputsOfUser) {
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
        System.out.println("TIMING relationship gather took " + gatherMillis / 1000.0
                + " seconds (redis " + redisFetchMillis / 1000.0
                + "s, build " + (gatherMillis - redisFetchMillis) / 1000.0
                + "s, " + edgeCount + " edges)");

        long initStartTime = System.currentTimeMillis();
        Map<String, ScoreCard> scorecards = initGrapeRankScorecards(relevantUsers, observer,userDistanceMap);
        System.out.println("TIMING scorecard init took "
                + (System.currentTimeMillis() - initStartTime) / 1000.0 + " seconds");

        long algoStartTime = System.currentTimeMillis();
        GrapeRankAlgorithmResult algorithmResult = graperankAlgorithm(graperankInputs, scorecards, params);
        long algoEndTime = System.currentTimeMillis();
        System.out.println("Algorithm took " + (algoEndTime - algoStartTime) / 1000.0 + " seconds");


        System.out.println("Getting trusted followers for each pubkey...");
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

        System.out.println("TIMING trusted follower/reporter counts took "
                + (System.currentTimeMillis() - trustedStartTime) / 1000.0 + " seconds");

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

        System.out.println("TIMING changed/dropped diff took "
                + (System.currentTimeMillis() - diffStartTime) / 1000.0 + " seconds");

        long finalTime = System.currentTimeMillis() - startTime;
        System.out.println("Entire process took " + (finalTime) / 1000.0 + " seconds");

        boolean success = relevantUsers.size() > 1;
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