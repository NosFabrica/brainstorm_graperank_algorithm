package com.nosfabrica.graperank.grape;

import com.nosfabrica.graperank.rank.ScoreCard;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    final double[] hops;
    final double[] averageScore;
    final double[] input;
    final double[] confidence;
    final double[] influence;
    final boolean[] pinned;

    double[] trustedFollowers;
    double[] trustedReporters;
    double[] trustedMuters;

    ScoreGraph(String observer, List<String> relevantUsers, Map<String, Double> userDistanceMap) {
        this.observer = observer;
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
    int iterate(Csr ratings, GrapeRankParams params) {
        final int[] off = ratings.off;
        final int[] src = ratings.src;
        final byte[] kind = ratings.kind;
        final double[] ratingConfidence = {
                params.followConfidence(), params.followConfidenceOfObserver(),
                params.muteConfidence(), params.reportConfidence()};
        final double[] rating = {
                params.followRating(), params.followRating(), params.muteRating(), params.reportRating()};
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
                    double weight = ratingConfidence[kind[e]] * influence[src[e]] * attenuation;
                    sumOfWeights += weight;
                    sumOfWxr += weight * rating[kind[e]];
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

    /** Per ratee, how many followers / muters / reporters clear their preset cutoff — raw
     * Influence, strict `>`. The Observer's own follows count as follows. */
    void countTrustedRaters(Csr ratings, GrapeRankParams params) {
        double[] cutoff = {
                params.verifiedFollowersInfluenceCutoff(), params.verifiedFollowersInfluenceCutoff(),
                params.verifiedMutersInfluenceCutoff(), params.verifiedReportersInfluenceCutoff()};
        int n = pubkeys.length;
        trustedFollowers = new double[n];
        trustedMuters = new double[n];
        trustedReporters = new double[n];
        for (int i = 0; i < n; i++) {
            long followers = 0, muters = 0, reporters = 0;
            for (int e = ratings.off[i]; e < ratings.off[i + 1]; e++) {
                byte k = ratings.kind[e];
                if (influence[ratings.src[e]] > cutoff[k]) {
                    switch (k) {
                        case FOLLOW, OBSERVER_FOLLOW -> followers++;
                        case MUTE -> muters++;
                        default -> reporters++;
                    }
                }
            }
            trustedFollowers[i] = followers;
            trustedMuters[i] = muters;
            trustedReporters[i] = reporters;
        }
    }

    /** Scorecards in id order, which is the String-keyed implementation's map order, so JSON is unchanged. */
    Map<String, ScoreCard> toScorecards(double verifiedCutoff) {
        Map<String, ScoreCard> result = new LinkedHashMap<>(pubkeys.length * 4 / 3 + 1);
        for (int i = 0; i < pubkeys.length; i++) {
            ScoreCard card = new ScoreCard(observer, pubkeys[i], hops[i]);
            card.setAverageScore(averageScore[i]);
            card.setInput(input[i]);
            card.setConfidence(confidence[i]);
            card.setInfluence(influence[i]);
            card.setVerified(influence[i] > verifiedCutoff);
            card.setTrustedFollowers(trustedFollowers[i]);
            card.setTrustedReporters(trustedReporters[i]);
            card.setTrustedMuters(trustedMuters[i]);
            result.put(pubkeys[i], card);
        }
        return result;
    }

    /** Edge kinds: index into the per-run confidence / rating / cutoff tables. */
    static final byte FOLLOW = 0;
    static final byte OBSERVER_FOLLOW = 1;
    static final byte MUTE = 2;
    static final byte REPORT = 3;

    /** Compressed sparse rows: row `i`'s edges are `src[off[i] .. off[i + 1])`, with each edge's kind
     * at the same position. */
    static final class Csr {
        final int[] off;
        final int[] src;
        final byte[] kind;

        private Csr(int[] off, int[] src, byte[] kind) {
            this.off = off;
            this.src = src;
            this.kind = kind;
        }
    }

    /** Edges appended in arrival order; {@link #toCsr} groups them by row, keeping that order,
     * and releases the buffers. */
    static final class EdgeList {
        private int[] rows = new int[1024];
        private int[] cols = new int[1024];
        private byte[] kinds = new byte[1024];
        private int size;

        private static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;

        void add(int row, int col, byte kind) {
            if (size == rows.length) grow();
            rows[size] = row;
            cols[size] = col;
            kinds[size++] = kind;
        }

        int size() {
            return size;
        }

        private void grow() {
            int capacity = (int) Math.min(2L * rows.length, MAX_CAPACITY);
            if (capacity == size) throw new IllegalStateException("more than " + MAX_CAPACITY + " edges");
            rows = Arrays.copyOf(rows, capacity);
            cols = Arrays.copyOf(cols, capacity);
            kinds = Arrays.copyOf(kinds, capacity);
        }

        /** Stable counting sort by row. */
        Csr toCsr(int rowCount) {
            int[] off = new int[rowCount + 1];
            for (int e = 0; e < size; e++) off[rows[e] + 1]++;
            for (int i = 0; i < rowCount; i++) off[i + 1] += off[i];

            int[] cursor = Arrays.copyOf(off, rowCount);
            int[] src = new int[size];
            byte[] kind = new byte[size];
            for (int e = 0; e < size; e++) {
                int p = cursor[rows[e]]++;
                src[p] = cols[e];
                kind[p] = kinds[e];
            }
            rows = cols = null;
            kinds = null;
            return new Csr(off, src, kind);
        }
    }
}
