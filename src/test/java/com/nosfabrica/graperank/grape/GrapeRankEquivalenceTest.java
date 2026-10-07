package com.nosfabrica.graperank.grape;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nosfabrica.graperank.db.IGraphDB;
import com.nosfabrica.graperank.db.IRelationshipsCache;
import com.nosfabrica.graperank.db.IncomingRelationships;
import com.nosfabrica.graperank.db.ReachableUser;
import com.nosfabrica.graperank.db.RelationshipInfo;
import com.nosfabrica.graperank.rank.ScoreCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The SoA implementation must reproduce {@link ReferenceGrapeRank} exactly: every scorecard
 * field, the scorecard map's iteration order, rounds and the changed / dropped lists. */
class GrapeRankEquivalenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double[] PREVIOUS_ON_ROUNDING_EDGES = {0.0149, 0.015, 0.0195, 0.02, 0.025, 0.5};

    private record Fixture(String observer, IGraphDB graph, IRelationshipsCache cache, List<String> designated) {}

    private static String pubkey(Random random, int i) {
        return String.format("%016x%016x%016x%016x", random.nextLong(), random.nextLong(), random.nextLong(), i);
    }

    private static Fixture fixture(long seed, int users, int meanFollows, boolean connected) {
        Random random = new Random(seed);
        List<String> pubkeys = new ArrayList<>();
        for (int i = 0; i < users; i++) pubkeys.add(pubkey(random, i));
        List<String> outsiders = new ArrayList<>();
        for (int i = 0; i < users / 10 + 1; i++) outsiders.add(pubkey(random, users + i));
        String observer = pubkeys.get(0);

        Map<String, ReachableUser> reachable = new HashMap<>();
        if (connected) {
            reachable.put(observer, new ReachableUser(0, random.nextBoolean() ? 1.0 : null));
            for (int i = 1; i < users; i++) {
                int hops = 1 + random.nextInt(Constants.MAX_HOPS + 2);
                double roll = random.nextDouble();
                Double previous = roll < 0.3 ? null
                        : roll < 0.5 ? PREVIOUS_ON_ROUNDING_EDGES[random.nextInt(PREVIOUS_ON_ROUNDING_EDGES.length)]
                        : random.nextDouble();
                reachable.put(pubkeys.get(i), new ReachableUser(hops, previous));
            }
        }

        Map<String, List<String>> followedBy = new HashMap<>();
        Map<String, List<String>> mutedBy = new HashMap<>();
        Map<String, List<String>> reportedBy = new HashMap<>();
        List<String> sources = new ArrayList<>(pubkeys);
        sources.addAll(outsiders);
        for (String source : sources) {
            int follows = source.equals(observer) ? 40 : random.nextInt(2 * meanFollows + 1);
            for (int k = 0; k < follows; k++) {
                String target = pubkeys.get((int) (users * Math.pow(random.nextDouble(), 2)));
                followedBy.computeIfAbsent(target, x -> new ArrayList<>()).add(source);
            }
            if (random.nextDouble() < 0.15) {
                mutedBy.computeIfAbsent(pubkeys.get(random.nextInt(users)), x -> new ArrayList<>()).add(source);
            }
            if (random.nextDouble() < 0.05) {
                reportedBy.computeIfAbsent(pubkeys.get(random.nextInt(users)), x -> new ArrayList<>()).add(source);
            }
        }

        List<String> designated = List.of(observer, pubkeys.get(3), pubkeys.get(users - 1), outsiders.get(0));

        IGraphDB graph = o -> new HashMap<>(reachable);
        IRelationshipsCache cache = new IRelationshipsCache() {
            @Override
            public List<RelationshipInfo> getIncomingFollowsBulk(List<String> batch) {
                return edges(batch, followedBy, "FOLLOWS");
            }

            @Override
            public List<RelationshipInfo> getIncomingMutesBulk(List<String> batch) {
                return edges(batch, mutedBy, "MUTES");
            }

            @Override
            public List<RelationshipInfo> getIncomingReportsBulk(List<String> batch) {
                return edges(batch, reportedBy, "REPORTS");
            }
        };
        return new Fixture(observer, graph, cache, designated);
    }

    private static List<RelationshipInfo> edges(List<String> batch, Map<String, List<String>> by, String type) {
        List<RelationshipInfo> out = new ArrayList<>();
        for (String target : batch) {
            for (String source : by.getOrDefault(target, List.of())) {
                out.add(new RelationshipInfo(source, type, target));
            }
        }
        return out;
    }

    @ParameterizedTest
    @CsvSource({
            "1, 50, 5, true",
            "2, 400, 20, true",
            "3, 2500, 15, true",
            "4, 200, 10, false",
    })
    void matchesTheReferenceImplementationExactly(long seed, int users, int meanFollows, boolean connected)
            throws Exception {
        Fixture f = fixture(seed, users, meanFollows, connected);
        for (GrapeRankParams params : List.of(Constants.DEFAULT_PARAMS, customParams())) {

            GrapeRankResult expected = new ReferenceGrapeRank(f.graph(), f.cache())
                    .graperankAllSteps(f.observer(), params, f.designated());
            GrapeRankResult actual = new GrapeRankAlgorithm(f.graph(), f.cache())
                    .graperankAllSteps(f.observer(), params, f.designated());

            assertEquals(expected.isSuccess(), actual.isSuccess());
            assertEquals(expected.getRounds(), actual.getRounds());
            assertEquals(expected.getChangedScorePubkeys(), actual.getChangedScorePubkeys());
            assertEquals(expected.getDroppedBelowCutoffPubkeys(), actual.getDroppedBelowCutoffPubkeys());
            assertEquals(new ArrayList<>(expected.getScorecards().keySet()),
                    new ArrayList<>(actual.getScorecards().keySet()));
            for (Map.Entry<String, ScoreCard> entry : expected.getScorecards().entrySet()) {
                assertEquals(MAPPER.writeValueAsString(entry.getValue()),
                        MAPPER.writeValueAsString(actual.getScorecards().get(entry.getKey())), entry.getKey());
                assertSameDoubles(entry.getValue(), actual.getScorecards().get(entry.getKey()));
            }
        }
    }

    /** Within each window of in-flight fetches the earliest-started batch sleeps longest, so batches
     * finish out of the order they started in; the result must still match the reference.
     * 6,000 reachable users + 1 unreachable designated key = 7 batches, 4 fetched at once. */
    @Test
    void matchesTheReferenceWhenBatchesCompleteOutOfOrder() throws Exception {
        Fixture f = fixture(5, 6000, 8, true);
        AtomicInteger started = new AtomicInteger();
        List<Integer> finished = Collections.synchronizedList(new ArrayList<>());
        IRelationshipsCache slow = new DelegatingCache(f.cache()) {
            @Override
            public IncomingRelationships getIncomingBulk(List<String> batch) {
                int call = started.getAndIncrement();
                try {
                    Thread.sleep(10L * (3 - call % 4));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                IncomingRelationships incoming = super.getIncomingBulk(batch);
                finished.add(call);
                return incoming;
            }
        };

        GrapeRankResult expected = new ReferenceGrapeRank(f.graph(), f.cache())
                .graperankAllSteps(f.observer(), Constants.DEFAULT_PARAMS, f.designated());
        GrapeRankResult actual = new GrapeRankAlgorithm(f.graph(), slow)
                .graperankAllSteps(f.observer(), Constants.DEFAULT_PARAMS, f.designated());

        List<Integer> inOrder = new ArrayList<>(finished);
        Collections.sort(inOrder);
        assertNotEquals(inOrder, finished, "batches should have finished out of order");
        assertEquals(MAPPER.writeValueAsString(expected.getScorecards()),
                MAPPER.writeValueAsString(actual.getScorecards()));
        assertEquals(expected.getRounds(), actual.getRounds());
        assertEquals(expected.getChangedScorePubkeys(), actual.getChangedScorePubkeys());
        assertEquals(expected.getDroppedBelowCutoffPubkeys(), actual.getDroppedBelowCutoffPubkeys());
    }

    @Test
    void aFailedFetchFailsTheRun() {
        Fixture f = fixture(6, 3000, 5, true);
        IRelationshipsCache failing = new DelegatingCache(f.cache()) {
            @Override
            public List<RelationshipInfo> getIncomingFollowsBulk(List<String> batch) {
                throw new IllegalStateException("redis down");
            }
        };

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                new GrapeRankAlgorithm(f.graph(), failing).graperankAllSteps(f.observer()));
        assertEquals("redis down", e.getMessage());
    }

    /** An Error on a fetch thread (e.g. OutOfMemoryError) must reach the caller unwrapped. */
    @Test
    void anErrorOnAFetchThreadIsNotWrapped() {
        Fixture f = fixture(7, 3000, 5, true);
        IRelationshipsCache failing = new DelegatingCache(f.cache()) {
            @Override
            public List<RelationshipInfo> getIncomingMutesBulk(List<String> batch) {
                throw new FetchError();
            }
        };

        assertThrows(FetchError.class, () ->
                new GrapeRankAlgorithm(f.graph(), failing).graperankAllSteps(f.observer()));
    }

    private static final class FetchError extends Error {}

    private static class DelegatingCache implements IRelationshipsCache {
        private final IRelationshipsCache delegate;

        DelegatingCache(IRelationshipsCache delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<RelationshipInfo> getIncomingFollowsBulk(List<String> batch) {
            return delegate.getIncomingFollowsBulk(batch);
        }

        @Override
        public List<RelationshipInfo> getIncomingMutesBulk(List<String> batch) {
            return delegate.getIncomingMutesBulk(batch);
        }

        @Override
        public List<RelationshipInfo> getIncomingReportsBulk(List<String> batch) {
            return delegate.getIncomingReportsBulk(batch);
        }
    }

    // JSON prints doubles shortest-round-trip, so it is exact already; this also covers -0.0 vs 0.0.
    private static void assertSameDoubles(ScoreCard expected, ScoreCard actual) {
        double[] e = {expected.getHops(), expected.getAverageScore(), expected.getInput(), expected.getConfidence(),
                expected.getInfluence(), expected.getTrustedFollowers(), expected.getTrustedReporters(),
                expected.getTrustedMuters()};
        double[] a = {actual.getHops(), actual.getAverageScore(), actual.getInput(), actual.getConfidence(),
                actual.getInfluence(), actual.getTrustedFollowers(), actual.getTrustedReporters(),
                actual.getTrustedMuters()};
        for (int i = 0; i < e.length; i++) {
            assertEquals(Double.doubleToRawLongBits(e[i]), Double.doubleToRawLongBits(a[i]), expected.getObservee());
        }
    }

    private static GrapeRankParams customParams() {
        return new GrapeRankParams(0.3, 0.7, 1.0, 0.05, -0.2, 0.4, -0.3, 0.6, 0.8, 0.05, 0.2, 0.03);
    }
}
