package com.nosfabrica.graperank.grape;

import com.nosfabrica.graperank.db.IGraphDB;
import com.nosfabrica.graperank.db.IRelationshipsCache;
import com.nosfabrica.graperank.db.ReachableUser;
import com.nosfabrica.graperank.db.RelationshipInfo;
import com.nosfabrica.graperank.rank.ScoreCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ScoreCard.hops — the follow distance up to {@link Constants#MAX_HOPS}, and
 * {@link Constants#UNREACHABLE_HOPS} beyond it. */
class HopsTest {

    private static final String OBSERVER = "observer";

    private static Map<String, ScoreCard> run() {
        IGraphDB graph = observer -> Map.of(
                OBSERVER, new ReachableUser(0, 1.0),
                "near", new ReachableUser(1, null),
                "edge", new ReachableUser(Constants.MAX_HOPS, null),
                "far", new ReachableUser(Constants.MAX_HOPS + 1, null));
        IRelationshipsCache noEdges = new IRelationshipsCache() {
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
        };
        return new GrapeRankAlgorithm(graph, noEdges).graperankAllSteps(OBSERVER).getScorecards();
    }

    @Test
    void keepsTheFollowDistanceWithinTheHopLimit() {
        assertEquals(1, run().get("near").getHops());
        assertEquals(Constants.MAX_HOPS, run().get("edge").getHops());
    }

    @Test
    void marksUsersBeyondTheHopLimitUnreachable() {
        assertEquals(Constants.UNREACHABLE_HOPS, run().get("far").getHops());
    }
}
