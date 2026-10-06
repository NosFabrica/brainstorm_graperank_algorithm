package com.nosfabrica.graperank.grape;

import com.nosfabrica.graperank.rank.ScoreCard;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One GrapeRank run as int ids and primitive columns. Edges are CSR, grouped by ratee.
 *
 * Ids follow the iteration order of a HashMap keyed by the relevant users, which is the order
 * the String-keyed implementation swept in. The sweep updates in place, so that order is part
 * of the result. */
final class ScoreGraph {

    final String observer;
    final String[] pubkeys;
    final Map<String, Integer> idOf;
    private final List<String> insertionOrder;

    final double[] hops;
    final double[] averageScore;
    final double[] input;
    final double[] confidence;
    final double[] influence;
    final boolean[] pinned;

    Csr ratings;
    double[] ratingConfidence;
    double[] rating;

    double[] trustedFollowers;
    double[] trustedReporters;
    double[] trustedMuters;

    ScoreGraph(String observer, List<String> relevantUsers, Map<String, Double> userDistanceMap) {
        this.observer = observer;
        this.insertionOrder = relevantUsers;
        Map<String, Integer> ids = new HashMap<>();
        for (String user : relevantUsers) ids.put(user, 0);
        int n = ids.size();
        pubkeys = new String[n];
        int next = 0;
        for (Map.Entry<String, Integer> entry : ids.entrySet()) {
            pubkeys[next] = entry.getKey();
            entry.setValue(next++);
        }
        idOf = ids;

        hops = new double[n];
        averageScore = new double[n];
        input = new double[n];
        confidence = new double[n];
        influence = new double[n];
        pinned = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (pubkeys[i].equals(observer)) {
                averageScore[i] = 1.0;
                input[i] = Double.POSITIVE_INFINITY;
                confidence[i] = 1.0;
                influence[i] = 1.0;
                pinned[i] = true;
            } else {
                hops[i] = userDistanceMap.getOrDefault(pubkeys[i], Constants.UNREACHABLE_HOPS);
            }
        }
    }

    int size() {
        return pubkeys.length;
    }

    /** Seed a designated key at the fixed designated Influence; it is never recomputed. */
    void pin(int id) {
        averageScore[id] = Constants.DESIGNATED_KEY_INFLUENCE;
        input[id] = Double.POSITIVE_INFINITY;
        confidence[id] = 1.0;
        influence[id] = Constants.DESIGNATED_KEY_INFLUENCE;
        pinned[id] = true;
    }

    /** Sweep until no Influence moves by more than the loop-break threshold. Returns rounds. */
    int iterate(GrapeRankParams params) {
        final int[] off = ratings.off;
        final int[] src = ratings.src;
        final double attenuation = params.attenuationFactor();
        final double rigor = params.rigor();
        int rounds = 0;

        while (true) {
            boolean shouldBreak = true;
            for (int i = 0; i < pubkeys.length; i++) {
                if (pinned[i]) continue;

                double sumOfWeights = 0;
                double sumOfWxr = 0;
                for (int e = off[i], end = off[i + 1]; e < end; e++) {
                    double weight = ratingConfidence[e] * influence[src[e]] * attenuation;
                    sumOfWeights += weight;
                    sumOfWxr += weight * rating[e];
                }

                double avgScore = (sumOfWeights != 0) ? sumOfWxr / sumOfWeights : 0;
                averageScore[i] = avgScore;
                input[i] = sumOfWeights;
                confidence[i] = GrapeRankAlgorithm.convertInputToConfidence(sumOfWeights, rigor);

                double computedInfluence = Math.max(avgScore * confidence[i], 0);
                if (Math.abs(computedInfluence - influence[i])
                        > Constants.THRESHOLD_OF_LOOP_BREAK_GIVEN_MINIMUM_DELTA_INFLUENCE) {
                    shouldBreak = false;
                }
                influence[i] = computedInfluence;
            }

            rounds++;
            System.out.println("NUMBER OF ROUNDS: " + rounds);
            if (shouldBreak) return rounds;
        }
    }

    /** Per ratee, how many raters clear `cutoff` — raw Influence, strict `>`. */
    double[] countTrustedRaters(Csr raters, double cutoff) {
        double[] counts = new double[pubkeys.length];
        for (int i = 0; i < pubkeys.length; i++) {
            long count = 0;
            for (int e = raters.off[i]; e < raters.off[i + 1]; e++) {
                if (influence[raters.src[e]] > cutoff) count++;
            }
            counts[i] = count;
        }
        return counts;
    }

    /** Scorecards keyed like the String-keyed implementation's map, so JSON order is unchanged. */
    Map<String, ScoreCard> toScorecards(double verifiedCutoff) {
        ScoreCard[] cards = new ScoreCard[pubkeys.length];
        for (int i = 0; i < cards.length; i++) {
            ScoreCard card = new ScoreCard(observer, pubkeys[i], hops[i]);
            card.setAverageScore(averageScore[i]);
            card.setInput(input[i]);
            card.setConfidence(confidence[i]);
            card.setInfluence(influence[i]);
            card.setVerified(influence[i] > verifiedCutoff);
            card.setTrustedFollowers(trustedFollowers[i]);
            card.setTrustedReporters(trustedReporters[i]);
            card.setTrustedMuters(trustedMuters[i]);
            cards[i] = card;
        }
        Map<String, ScoreCard> result = new HashMap<>();
        for (String user : insertionOrder) result.put(user, cards[idOf.get(user)]);
        return result;
    }

    /** Compressed sparse rows: row `i`'s entries are `src[off[i] .. off[i + 1])`. */
    static final class Csr {
        final int[] off;
        final int[] src;

        private Csr(int[] off, int[] src) {
            this.off = off;
            this.src = src;
        }
    }

    /** Edges appended in arrival order; {@link #toCsr} groups them by row, keeping that order. */
    static final class EdgeList {
        private int[] rows = new int[1024];
        private int[] cols = new int[1024];
        private double[] confidences;
        private double[] ratings;
        private int size;

        EdgeList(boolean withWeights) {
            if (withWeights) {
                confidences = new double[1024];
                ratings = new double[1024];
            }
        }

        void add(int row, int col) {
            grow();
            rows[size] = row;
            cols[size++] = col;
        }

        void add(int row, int col, double confidence, double rating) {
            grow();
            rows[size] = row;
            cols[size] = col;
            confidences[size] = confidence;
            ratings[size++] = rating;
        }

        int size() {
            return size;
        }

        private void grow() {
            if (size < rows.length) return;
            int capacity = rows.length * 2;
            rows = Arrays.copyOf(rows, capacity);
            cols = Arrays.copyOf(cols, capacity);
            if (confidences != null) {
                confidences = Arrays.copyOf(confidences, capacity);
                ratings = Arrays.copyOf(ratings, capacity);
            }
        }

        /** Stable counting sort by row. Fills `graph`'s rating weights when this list carries them. */
        Csr toCsr(int rowCount, ScoreGraph graph) {
            int[] off = new int[rowCount + 1];
            for (int e = 0; e < size; e++) off[rows[e] + 1]++;
            for (int i = 0; i < rowCount; i++) off[i + 1] += off[i];

            int[] cursor = Arrays.copyOf(off, rowCount);
            int[] src = new int[size];
            double[] sortedConfidences = confidences == null ? null : new double[size];
            double[] sortedRatings = ratings == null ? null : new double[size];
            for (int e = 0; e < size; e++) {
                int p = cursor[rows[e]]++;
                src[p] = cols[e];
                if (sortedConfidences != null) {
                    sortedConfidences[p] = confidences[e];
                    sortedRatings[p] = ratings[e];
                }
            }
            if (sortedConfidences != null) {
                graph.ratingConfidence = sortedConfidences;
                graph.rating = sortedRatings;
            }
            return new Csr(off, src);
        }
    }
}
