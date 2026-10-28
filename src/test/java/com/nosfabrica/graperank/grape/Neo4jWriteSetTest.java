package com.nosfabrica.graperank.grape;

import com.nosfabrica.graperank.db.IRelationshipsCache;
import com.nosfabrica.graperank.db.ReachableUser;
import com.nosfabrica.graperank.db.RelationshipInfo;
import com.nosfabrica.graperank.rank.ScoreCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which scorecards the server writes to Neo4j: any persisted field that differs from
 * what Neo4j holds, or that Neo4j holds nothing for. */
class Neo4jWriteSetTest {

    private static ScoreCard card(double influence, int hops, int trustedFollowers) {
        ScoreCard card = new ScoreCard("observer", "x", hops);
        card.setInfluence(influence);
        card.setTrustedFollowers(trustedFollowers);
        return card;
    }

    private static List<String> writeSet(ScoreCard card, ReachableUser stored) {
        return GrapeRankAlgorithm.neo4jWritePubkeys(
                Map.of("x", card), stored == null ? Map.of() : Map.of("x", stored));
    }

    @Test
    void skipsWhenEveryFieldMatches() {
        assertEquals(List.of(), writeSet(card(0.5, 2, 7), new ReachableUser(2, 0.5, 2L, 7L)));
    }

    @Test
    void skipsAnInfluenceChangeHiddenByRounding() {
        assertEquals(List.of(), writeSet(card(0.5012, 2, 7), new ReachableUser(2, 0.4991, 2L, 7L)));
    }

    @Test
    void writesARoundedInfluenceChange() {
        assertEquals(List.of("x"), writeSet(card(0.51, 2, 7), new ReachableUser(2, 0.5, 2L, 7L)));
    }

    @Test
    void writesAHopsChangeWithTheSameInfluence() {
        assertEquals(List.of("x"), writeSet(card(0.5, 3, 7), new ReachableUser(3, 0.5, 2L, 7L)));
    }

    @Test
    void writesATrustedFollowersChangeWithTheSameInfluence() {
        assertEquals(List.of("x"), writeSet(card(0.5, 2, 8), new ReachableUser(2, 0.5, 2L, 7L)));
    }

    @Test
    void writesANodeNeo4jHoldsNothingFor() {
        assertEquals(List.of("x"), writeSet(card(0.0, 2, 0), new ReachableUser(2, null, null, null)));
    }

    @Test
    void writesAMissingOrNonIntegerStoredCount() {
        assertEquals(List.of("x"), writeSet(card(0.5, 2, 7), new ReachableUser(2, 0.5, 2L, null)));
        assertEquals(List.of("x"), writeSet(card(0.5, 2, 7), new ReachableUser(2, 0.5, null, 7L)));
    }

    @Test
    void writesAScorecardOutsideTheReachableSet() {
        assertEquals(List.of("x"), writeSet(card(1.0, 999, 0), null));
    }

    @Test
    void aFirstRunWritesEveryScorecard() {
        GrapeRankResult result = new GrapeRankAlgorithm(
                observer -> Map.of("observer", new ReachableUser(0, null, null, null), "near", new ReachableUser(1, null, null, null)),
                new NoEdges()).graperankAllSteps("observer");
        assertEquals(result.getScorecards().size(), result.getNeo4jWritePubkeys().size());
    }

    private static final class NoEdges implements IRelationshipsCache {
        @Override
        public List<RelationshipInfo> getIncomingFollowsBulk(List<String> pubkeys) {
            return List.of();
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
