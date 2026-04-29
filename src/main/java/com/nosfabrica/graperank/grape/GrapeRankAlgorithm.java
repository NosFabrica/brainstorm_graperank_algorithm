package com.nosfabrica.graperank.grape;

import com.nosfabrica.graperank.db.IGraphDB;
import com.nosfabrica.graperank.db.Neo4jHelper;
import com.nosfabrica.graperank.db.RedisRelationshipsHelper;
import com.nosfabrica.graperank.db.RelationshipInfo;
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
    private final RedisRelationshipsHelper redis;

    public GrapeRankAlgorithm(IGraphDB db, RedisRelationshipsHelper redis) {
        this.db = db;
        this.redis = redis;
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
                    throw new IllegalArgumentException("Unknown relationship type: " + outgoingRelationship);
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
                    throw new IllegalArgumentException("Unknown relationship type: " + outgoingRelationship);
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
                Double distance = userDistanceMap.getOrDefault(user,(double) 999);
                

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

        List<String> relevantUsers = db.getUsersConnectedToObserver(observer, 992);
        Map<String, Double> userDistanceMap = new HashMap<>();

        Map<Integer, List<String>> hopsMap = new HashMap<>();
        hopsMap.put(8, db.getUsersConnectedToObserver(observer, 8));
        hopsMap.put(7, db.getUsersConnectedToObserver(observer, 7));
        hopsMap.put(6, db.getUsersConnectedToObserver(observer, 6));
        hopsMap.put(5, db.getUsersConnectedToObserver(observer, 5));
        hopsMap.put(4, db.getUsersConnectedToObserver(observer, 4));
        hopsMap.put(3, db.getUsersConnectedToObserver(observer, 3));
        hopsMap.put(2, db.getUsersConnectedToObserver(observer, 2));
        hopsMap.put(1, db.getUsersConnectedToObserver(observer, 1));


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

        Map<String, List<String>> reportersByUser = new HashMap<>();

        Set<String> relevantUsersSet = new HashSet<>(relevantUsers);

        int iteration = 0;
        for (List<String> usersBatch : chunked(relevantUsers, BATCH_SIZE)) {

            long batchStartTime = System.currentTimeMillis();
            List<RelationshipInfo> incomingFollowRelationships = redis.getIncomingFollowsBulk(
                    usersBatch);

            List<RelationshipInfo> incomingMuteRelationships = redis.getIncomingMutesBulk(
                    usersBatch);

            List<RelationshipInfo> incomingReportRelationships = redis.getIncomingReportsBulk(
                    usersBatch);


            long batchEndTime = System.currentTimeMillis();
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

            for (RelationshipInfo rel : incomingReportRelationships) {

                String reportedUser = rel.getTarget(); 
                String reporter = rel.getSource();     

                reportersByUser
                    .computeIfAbsent(reportedUser, k -> new ArrayList<>())
                    .add(reporter);
            }

            iteration++;
        }

        Map<String, ScoreCard> scorecards = initGrapeRankScorecards(relevantUsers, observer,userDistanceMap);

        long algoStartTime = System.currentTimeMillis();
        GrapeRankAlgorithmResult algorithmResult = graperankAlgorithm(graperankInputs, scorecards, params);
        long algoEndTime = System.currentTimeMillis();
        System.out.println("Algorithm took " + (algoEndTime - algoStartTime) / 1000.0 + " seconds");


        System.out.println("Getting trusted followers for each pubkey...");
        Map<String, ScoreCard> finalScorecards = algorithmResult.getScorecards();

        for (Map.Entry<String, ScoreCard> entry : finalScorecards.entrySet()) {
            String userPubkey = entry.getKey();
            ScoreCard scoreCard = entry.getValue();

            
            List<String> followers = followersByUser.getOrDefault(userPubkey, Collections.emptyList());


            long trustedFollowersCount = followers.stream()
                .filter(followerPubkey -> {
                    ScoreCard followerScoreCard = finalScorecards.get(followerPubkey);
                    return followerScoreCard != null && followerScoreCard.getInfluence() > params.verifiedFollowersInfluenceCutoff();
                })
                .count();


            scoreCard.setTrustedFollowers((double) trustedFollowersCount);

            //

            List<String> reporters = reportersByUser.getOrDefault(userPubkey, Collections.emptyList());


            long trustedReportersCount = reporters.stream()
                .filter(reporterPubkey -> {
                    ScoreCard reporterScoreCard = finalScorecards.get(reporterPubkey);
                    return reporterScoreCard != null && reporterScoreCard.getInfluence() > params.verifiedReportersInfluenceCutoff();
                })
                .count();


            scoreCard.setTrustedReporters((double) trustedReportersCount);
        }



        long finalTime = System.currentTimeMillis() - startTime;
        System.out.println("Entire process took " + (finalTime) / 1000.0 + " seconds");

        return new GrapeRankResult(

                algorithmResult.getScorecards(),
                algorithmResult.getRounds(),
                finalTime / 1000.0,
                relevantUsers.size() > 1);

    }

}