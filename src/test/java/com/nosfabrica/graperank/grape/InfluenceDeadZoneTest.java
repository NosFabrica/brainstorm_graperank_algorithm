package com.nosfabrica.graperank.grape;

import com.nosfabrica.graperank.db.IRelationshipsCache;
import com.nosfabrica.graperank.db.ReachableUser;
import com.nosfabrica.graperank.db.RelationshipInfo;
import com.nosfabrica.graperank.rank.ScoreCard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Influence that moved less than {@link Constants#INFLUENCE_DEAD_ZONE} from its stored value keeps
 * the stored value, so the cutoffs, counts and write set downstream don't flap on noise.
 *
 * Graph: the Observer follows RATER and SUBJECT; RATER follows SUBJECT. */
class InfluenceDeadZoneTest {

    private static final String OBSERVER = "observer";
    private static final String RATER = "rater";
    private static final String SUBJECT = "subject";

    private static GrapeRankParams params(double followersCutoff) {
        return new GrapeRankParams(
                Constants.GLOBAL_RIGOR, Constants.GLOBAL_ATTENUATION_FACTOR,
                Constants.DEFAULT_RATING_FOR_FOLLOW, Constants.DEFAULT_CONFIDENCE_FOR_FOLLOW,
                Constants.DEFAULT_RATING_FOR_MUTE, Constants.DEFAULT_CONFIDENCE_FOR_MUTE,
                Constants.DEFAULT_RATING_FOR_REPORT, Constants.DEFAULT_CONFIDENCE_FOR_REPORT,
                Constants.DEFAULT_CONFIDENCE_FOR_FOLLOW_FROM_OBSERVER,
                followersCutoff, 0.1, 0.01);
    }

    /** Stored scores as Neo4j would hold them after writing `cards`, with `shift` added to RATER and SUBJECT. */
    private static Map<String, ReachableUser> stored(Map<String, ScoreCard> cards, double shift) {
        Map<String, ReachableUser> out = new HashMap<>();
        out.put(OBSERVER, new ReachableUser(0, 1.0, 0L, 0L));
        for (String pk : List.of(RATER, SUBJECT)) {
            ScoreCard c = cards.get(pk);
            out.put(pk, new ReachableUser(1, c.getInfluence() + shift, 1L, (long) c.getTrustedFollowers()));
        }
        return out;
    }

    private static GrapeRankResult run(Map<String, ReachableUser> stored, double followersCutoff) {
        return new GrapeRankAlgorithm(observer -> stored, new Follows()).graperankAllSteps(OBSERVER, params(followersCutoff));
    }

    private static Map<String, ReachableUser> unscored() {
        return Map.of(OBSERVER, new ReachableUser(0, null, null, null), RATER, new ReachableUser(1, null, null, null),
                SUBJECT, new ReachableUser(1, null, null, null));
    }

    @Test
    void noiseKeepsTheStoredValueAndWritesNothing() {
        Map<String, ScoreCard> first = run(unscored(), 0.02).getScorecards();
        Map<String, ReachableUser> stored = stored(first, Constants.INFLUENCE_DEAD_ZONE / 10);

        GrapeRankResult again = run(stored, 0.02);

        for (String pk : List.of(RATER, SUBJECT)) {
            assertEquals(stored.get(pk).storedInfluence(), again.getScorecards().get(pk).getInfluence());
        }
        assertEquals(List.of(), again.getNeo4jWritePubkeys());
        assertEquals(List.of(), again.getChangedScorePubkeys());
    }

    @Test
    void aMovePastTheDeadZoneIsTakenAndWritten() {
        Map<String, ScoreCard> first = run(unscored(), 0.02).getScorecards();

        GrapeRankResult again = run(stored(first, 0.01), 0.02);

        for (String pk : List.of(RATER, SUBJECT)) {
            assertEquals(first.get(pk).getInfluence(), again.getScorecards().get(pk).getInfluence());
        }
        assertTrue(again.getNeo4jWritePubkeys().containsAll(List.of(RATER, SUBJECT)));
    }

    @Test
    void aRaterJitteringAcrossTheCutoffDoesNotFlipTheCount() {
        Map<String, ScoreCard> first = run(unscored(), 0.02).getScorecards();
        double raw = first.get(RATER).getInfluence();
        // Stored just below the cutoff, this run's raw value just above it: noise across the line.
        double cutoff = raw - Constants.INFLUENCE_DEAD_ZONE / 4;
        double storedBelow = raw - Constants.INFLUENCE_DEAD_ZONE / 2;
        Map<String, ReachableUser> stored = new HashMap<>(stored(first, 0));
        stored.put(RATER, new ReachableUser(1, storedBelow, 1L, 0L));

        GrapeRankResult again = run(stored, cutoff);

        assertEquals(storedBelow, again.getScorecards().get(RATER).getInfluence());
        // RATER stays below the cutoff, so SUBJECT counts only the Observer's follow.
        assertEquals(1, again.getScorecards().get(SUBJECT).getTrustedFollowers());
    }

    /** RATER's Influence after holding `computed` against `stored` (null = nothing stored). */
    private static double held(Double stored, double computed) {
        ScoreGraph graph = new ScoreGraph(OBSERVER, List.of(OBSERVER, RATER), Map.of());
        graph.influence[graph.idOf.get(RATER)] = computed;
        Map<String, Double> storedInfluence = new HashMap<>();
        storedInfluence.put(RATER, stored);
        graph.holdInfluence(storedInfluence, Constants.INFLUENCE_DEAD_ZONE, Constants.PERSIST_FLOOR);
        return graph.influence[graph.idOf.get(RATER)];
    }

    @Test
    void anUnstoredScoreIsHeldAtZeroUntilItReachesTheDeadZone() {
        double below = Constants.INFLUENCE_DEAD_ZONE * 0.8;
        assertEquals(0.0, held(null, below));
        assertEquals(Constants.INFLUENCE_DEAD_ZONE, held(null, Constants.INFLUENCE_DEAD_ZONE));
    }

    @Test
    void aStoredScoreFallingToTheFloorIsTakenNotHeld() {
        double stored = Constants.PERSIST_FLOOR * 3;
        double atFloor = Constants.PERSIST_FLOOR;
        assertEquals(atFloor, held(stored, atFloor));
        assertEquals(0.0, held(stored, 0.0));
        assertEquals(stored, held(stored, Constants.PERSIST_FLOOR * 2));
    }

    /** Observer → A → `raters` B's → TAIL. One B leaves TAIL at or below the floor; four put it
     * between the floor and the dead zone. `tailStored` is TAIL's stored Influence, or null. */
    private static GrapeRankResult tailRun(int raters, Double tailStored) {
        List<String> bs = new ArrayList<>();
        for (int i = 0; i < raters; i++) bs.add("b" + i);
        Map<String, ReachableUser> reach = new HashMap<>();
        reach.put(OBSERVER, new ReachableUser(0, null, null, null));
        reach.put("a", new ReachableUser(1, null, null, null));
        for (String b : bs) reach.put(b, new ReachableUser(2, null, null, null));
        reach.put("tail", new ReachableUser(3, tailStored, tailStored == null ? null : 3L,
                tailStored == null ? null : 0L));
        IRelationshipsCache chain = new IRelationshipsCache() {
            @Override
            public List<RelationshipInfo> getIncomingFollowsBulk(List<String> pubkeys) {
                List<RelationshipInfo> out = new ArrayList<>();
                if (pubkeys.contains("a")) out.add(new RelationshipInfo(OBSERVER, "FOLLOWS", "a"));
                for (String b : bs) {
                    if (pubkeys.contains(b)) out.add(new RelationshipInfo("a", "FOLLOWS", b));
                    if (pubkeys.contains("tail")) out.add(new RelationshipInfo(b, "FOLLOWS", "tail"));
                }
                return out;
            }

            @Override
            public List<RelationshipInfo> getIncomingMutesBulk(List<String> pubkeys) {
                return List.of();
            }

            @Override
            public List<RelationshipInfo> getIncomingReportsBulk(List<String> pubkeys) {
                return List.of();
            }
        };
        return new GrapeRankAlgorithm(observer -> reach, chain).graperankAllSteps(OBSERVER);
    }

    @Test
    void anUnstoredTailScoreBelowTheDeadZoneIsNotStored() {
        // Stored far off, so nothing is held: the raw computed value.
        double computed = tailRun(4, 0.5).getScorecards().get("tail").getInfluence();
        assertTrue(computed > Constants.PERSIST_FLOOR && computed < Constants.INFLUENCE_DEAD_ZONE);

        GrapeRankResult result = tailRun(4, null);

        assertEquals(0.0, result.getScorecards().get("tail").getInfluence());
        assertTrue(!result.getNeo4jWritePubkeys().contains("tail"));
        assertTrue(!result.getNeo4jClearPubkeys().contains("tail"));
    }

    @Test
    void aStoredTailScoreThatFallsToTheFloorIsCleared() {
        GrapeRankResult result = tailRun(1, 0.0003);

        assertTrue(result.getScorecards().get("tail").getInfluence() <= Constants.PERSIST_FLOOR);
        assertTrue(!result.getNeo4jWritePubkeys().contains("tail"));
        assertEquals(List.of("tail"), result.getNeo4jClearPubkeys());
    }

    @Test
    void operatorOverrideDefaultsAndRejectsNonsense() {
        assertEquals(0.0005, Constants.influenceDeadZone(null));
        assertEquals(0.0005, Constants.influenceDeadZone(" "));
        assertEquals(0.0, Constants.influenceDeadZone("0"));
        assertEquals(0.001, Constants.influenceDeadZone("0.001"));
        assertThrows(IllegalStateException.class, () -> Constants.influenceDeadZone("abc"));
        assertThrows(IllegalStateException.class, () -> Constants.influenceDeadZone("-0.1"));
        assertThrows(IllegalStateException.class, () -> Constants.influenceDeadZone("0.01"));
    }

    private static final class Follows implements IRelationshipsCache {
        @Override
        public List<RelationshipInfo> getIncomingFollowsBulk(List<String> pubkeys) {
            List<RelationshipInfo> out = new ArrayList<>();
            if (pubkeys.contains(RATER)) out.add(new RelationshipInfo(OBSERVER, "FOLLOWS", RATER));
            if (pubkeys.contains(SUBJECT)) {
                out.add(new RelationshipInfo(OBSERVER, "FOLLOWS", SUBJECT));
                out.add(new RelationshipInfo(RATER, "FOLLOWS", SUBJECT));
            }
            return out;
        }

        @Override
        public List<RelationshipInfo> getIncomingMutesBulk(List<String> pubkeys) {
            return List.of();
        }

        @Override
        public List<RelationshipInfo> getIncomingReportsBulk(List<String> pubkeys) {
            return List.of();
        }
    }
}
