package org.example.grape;

import java.util.Map;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GrapeRankAlgorithm {

    public static GrapeRankAlgorithmResult graperankAlgorithm(
            Map<String, List<GrapeRankInput>> graperankInputs,
            Map<String, ScoreCard> graperankScorecards) {

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
                            * Constants.GLOBAL_ATTENUATION_FACTOR;

                    double wxr = weight * relevantDataPoint.getRating();

                    sumOfWeights += weight;
                    sumOfWxr += wxr;
                }

                double avgScore = (sumOfWeights != 0) ? sumOfWxr / sumOfWeights : 0;
                scorecard.setAverageScore(avgScore);
                scorecard.setInput(sumOfWeights);

                // Convert input to confidence (you need to define the logic for this)
                scorecard.setConfidence(convertInputToConfidence(scorecard.getInput(), Constants.GLOBAL_RIGOR));

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
            scorecard.setVerified(scorecard.getInfluence() >= Constants.DEFAULT_CUTOFF_OF_VALID_USER);
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
            List<Neo4jHelper.RelationshipInfo> outgoingRelationships,
            String observer) {
        List<GrapeRankInput> graperankInputs = new ArrayList<>();

        for (Neo4jHelper.RelationshipInfo outgoingRelationshipObj : outgoingRelationships) {
            String outgoingRelationship = outgoingRelationshipObj.getRelationship();
            String outgoingRelationshipTarget = outgoingRelationshipObj.getTarget();
            String outgoingRelationshipSource = outgoingRelationshipObj.getSource();

            double rating = 0;

            switch (outgoingRelationship) {
                case "FOLLOWS":
                    rating = Constants.DEFAULT_RATING_FOR_FOLLOW;
                    break;
                case "MUTES":
                    rating = Constants.DEFAULT_RATING_FOR_MUTE;
                    break;
                case "REPORTS":
                    rating = Constants.DEFAULT_RATING_FOR_REPORT;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown relationship type: " + outgoingRelationship);
            }

            double confidence = 0;

            switch (outgoingRelationship) {
                case "FOLLOWS":
                    if (outgoingRelationshipSource.equals(observer)) {
                        confidence = Constants.DEFAULT_CONFIDENCE_FOR_FOLLOW_FROM_OBSERVER;
                    } else {
                        confidence = Constants.DEFAULT_CONFIDENCE_FOR_FOLLOW;
                    }
                    break;
                case "MUTES":
                    confidence = Constants.DEFAULT_CONFIDENCE_FOR_MUTE;
                    break;
                case "REPORTS":
                    confidence = Constants.DEFAULT_CONFIDENCE_FOR_REPORT;
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
        long startTime = System.currentTimeMillis();

        Neo4jHelper neo4jHelper = new Neo4jHelper();
        GrapeRankInputsCacheHelper cacheHelper = new GrapeRankInputsCacheHelper();

        List<String> relevantUsers = neo4jHelper.getUsersConnectedToObserver(observer, 992);
        Map<String, Double> userDistanceMap = new HashMap<>();

        Map<Integer, List<String>> hopsMap = new HashMap<>();
        hopsMap.put(8, neo4jHelper.getUsersConnectedToObserver(observer, 8));
        hopsMap.put(7, neo4jHelper.getUsersConnectedToObserver(observer, 7));
        hopsMap.put(6, neo4jHelper.getUsersConnectedToObserver(observer, 6));
        hopsMap.put(5, neo4jHelper.getUsersConnectedToObserver(observer, 5));
        hopsMap.put(4, neo4jHelper.getUsersConnectedToObserver(observer, 4));
        hopsMap.put(3, neo4jHelper.getUsersConnectedToObserver(observer, 3));
        hopsMap.put(2, neo4jHelper.getUsersConnectedToObserver(observer, 2));
        hopsMap.put(1, neo4jHelper.getUsersConnectedToObserver(observer, 1));

        for (int hop = 8; hop >= 1; hop--) {
            List<String> usersAtHop = hopsMap.get(hop);
            for (String user : usersAtHop) {
                userDistanceMap.put(user, (double) hop);
            }
        }

        int numOfIts = (int) Math.round((double) relevantUsers.size() / BATCH_SIZE);
        System.out.println("How many Neo4j iterations: " + numOfIts);

        // Always fetch incoming relationships (needed for trusted follower/reporter counts)
        Map<String, List<String>> followersByUser = new HashMap<>();
        Map<String, List<String>> reportersByUser = new HashMap<>();

        int iteration = 0;
        for (List<String> usersBatch : chunked(relevantUsers, BATCH_SIZE)) {
            long batchStartTime = System.currentTimeMillis();

            List<Neo4jHelper.RelationshipInfo> incomingFollowRelationships = neo4jHelper.getIncomingFollowRelationshipsBulk(usersBatch);
            List<Neo4jHelper.RelationshipInfo> incomingReportRelationships = neo4jHelper.getIncomingReportRelationshipsBulk(usersBatch);

            long batchEndTime = System.currentTimeMillis();
            System.out.println(iteration + " :: Getting incoming relationships took " + (batchEndTime - batchStartTime) / 1000.0 + " seconds");

            for (Neo4jHelper.RelationshipInfo rel : incomingFollowRelationships) {
                followersByUser.computeIfAbsent(rel.getTarget(), k -> new ArrayList<>()).add(rel.getSource());
            }
            for (Neo4jHelper.RelationshipInfo rel : incomingReportRelationships) {
                reportersByUser.computeIfAbsent(rel.getTarget(), k -> new ArrayList<>()).add(rel.getSource());
            }

            iteration++;
        }

        // Fetch graperankInputs using cache when available
        Map<String, List<GrapeRankInput>> graperankInputs = fetchGrapeRankInputsWithCache(
                relevantUsers, observer, neo4jHelper, cacheHelper);

        Map<String, ScoreCard> scorecards = initGrapeRankScorecards(relevantUsers, observer, userDistanceMap);

        long algoStartTime = System.currentTimeMillis();
        GrapeRankAlgorithmResult algorithmResult = graperankAlgorithm(graperankInputs, scorecards);
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
                    return followerScoreCard != null && followerScoreCard.getInfluence() > Constants.DEFAULT_CUTOFF_OF_VALID_USER;
                })
                .count();


            scoreCard.setTrustedFollowers((double) trustedFollowersCount);

            //

            List<String> reporters = reportersByUser.getOrDefault(userPubkey, Collections.emptyList());


            long trustedReportersCount = reporters.stream()
                .filter(reporterPubkey -> {
                    ScoreCard reporterScoreCard = finalScorecards.get(reporterPubkey);
                    return reporterScoreCard != null && reporterScoreCard.getInfluence() > Constants.DEFAULT_CUTOFF_OF_TRUSTED_REPORTER;
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

    private Map<String, List<GrapeRankInput>> fetchGrapeRankInputsWithCache(
            List<String> relevantUsers,
            String observer,
            Neo4jHelper neo4jHelper,
            GrapeRankInputsCacheHelper cacheHelper) {

        Set<String> relevantUsersSet = new HashSet<>(relevantUsers);
        GrapeRankInputsCache cached = cacheHelper.getFromCache();

        if (cached != null) {
            System.out.println("Cache HIT for graperank inputs");
            Map<String, List<GrapeRankInput>> inputs = new HashMap<>(cached.getInputs());

            // Remove ratees that are not in the current relevantUsers set
            inputs.keySet().retainAll(relevantUsersSet);

            // Find raters in relevantUsers not covered by the cache
            Set<String> missingRaters = new HashSet<>(relevantUsersSet);
            missingRaters.removeAll(cached.getRaters());

            if (!missingRaters.isEmpty()) {
                System.out.println("Fetching " + missingRaters.size() + " missing raters from Neo4j");
                int batchIteration = 0;
                for (List<String> batch : chunked(new ArrayList<>(missingRaters), BATCH_SIZE)) {
                    long batchStartTime = System.currentTimeMillis();
                    List<Neo4jHelper.RelationshipInfo> outgoingRels = neo4jHelper.getOutgoingRelationshipsBulk(batch);
                    long batchEndTime = System.currentTimeMillis();
                    System.out.println(batchIteration + " :: Fetching missing outgoing relationships took " + (batchEndTime - batchStartTime) / 1000.0 + " seconds");

                    List<GrapeRankInput> newInputs = getGrapeRankInputsOfRelationships(outgoingRels, observer);
                    for (GrapeRankInput input : newInputs) {
                        inputs.computeIfAbsent(input.getRatee(), k -> new ArrayList<>()).add(input);
                    }
                    batchIteration++;
                }
            }

            // Remove any ratee keys added by missing raters that are outside relevantUsers,
            // then strip individual inputs whose rater has no scorecard (prevents NPE in algorithm)
            inputs.keySet().retainAll(relevantUsersSet);
            for (List<GrapeRankInput> inputList : inputs.values()) {
                inputList.removeIf(input -> !relevantUsersSet.contains(input.getRater()));
            }

            // Fix observer-specific confidence: cached entries used generic confidence (0.03),
            // but the current observer's own follows should use the higher value (0.5)
            for (List<GrapeRankInput> inputList : inputs.values()) {
                for (GrapeRankInput input : inputList) {
                    if (input.getRater().equals(observer)
                            && input.getRating() == Constants.DEFAULT_RATING_FOR_FOLLOW) {
                        input.setConfidence(Constants.DEFAULT_CONFIDENCE_FOR_FOLLOW_FROM_OBSERVER);
                    }
                }
            }

            return inputs;

        } else {
            System.out.println("Cache MISS for graperank inputs, fetching all from Neo4j");
            Map<String, List<GrapeRankInput>> inputs = new HashMap<>();
            Set<String> allRaters = new HashSet<>();

            int batchIteration = 0;
            for (List<String> usersBatch : chunked(relevantUsers, BATCH_SIZE)) {
                long batchStartTime = System.currentTimeMillis();
                List<Neo4jHelper.RelationshipInfo> outgoingRelationships = neo4jHelper.getOutgoingRelationshipsBulk(usersBatch);
                long batchEndTime = System.currentTimeMillis();
                System.out.println(batchIteration + " :: Getting outgoing relationships took " + (batchEndTime - batchStartTime) / 1000.0 + " seconds");

                List<GrapeRankInput> batchInputs = getGrapeRankInputsOfRelationships(outgoingRelationships, observer);
                for (GrapeRankInput input : batchInputs) {
                    inputs.computeIfAbsent(input.getRatee(), k -> new ArrayList<>()).add(input);
                }

                allRaters.addAll(usersBatch);
                batchIteration++;
            }

            // Normalize observer-specific confidence before caching so that future observers
            // get the generic value (0.03) and can apply their own correction on load
            Map<String, List<GrapeRankInput>> inputsForCache = new HashMap<>();
            for (Map.Entry<String, List<GrapeRankInput>> entry : inputs.entrySet()) {
                List<GrapeRankInput> normalized = new ArrayList<>();
                for (GrapeRankInput input : entry.getValue()) {
                    if (input.getRater().equals(observer)
                            && input.getRating() == Constants.DEFAULT_RATING_FOR_FOLLOW) {
                        normalized.add(new GrapeRankInput(
                                input.getRater(), input.getRatee(),
                                input.getRating(), Constants.DEFAULT_CONFIDENCE_FOR_FOLLOW));
                    } else {
                        normalized.add(input);
                    }
                }
                inputsForCache.put(entry.getKey(), normalized);
            }

            cacheHelper.saveToCache(new GrapeRankInputsCache(inputsForCache, allRaters));

            return inputs;
        }
    }

}