package com.nosfabrica.graperank.grape;

import com.nosfabrica.graperank.db.IGraphDB;
import com.nosfabrica.graperank.db.IRelationshipsCache;
import com.nosfabrica.graperank.db.ReachableUser;
import com.nosfabrica.graperank.db.RelationshipInfo;
import com.nosfabrica.graperank.rank.ScoreCard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A key reached only by designation is diffed against what Neo4j holds for it, like a followed one:
 * not rewritten or republished when unchanged, cleared and dropped when it falls to zero.
 *
 * OBSERVER follows five FRIENDS; each designates PROVIDER, which no follow reaches.
 */
class DesignationOnlyStoredScoresTest {

    private static final String OBSERVER = "observer";
    private static final List<String> FRIENDS = List.of("f1", "f2", "f3", "f4", "f5");
    private static final String PROVIDER = "provider";

    private static GrapeRankResult run(ReachableUser providerStored) {
        IGraphDB graph = new IGraphDB() {
            @Override
            public Map<String, ReachableUser> getReachableUsers(String observer) {
                Map<String, ReachableUser> reachable = new HashMap<>();
                reachable.put(OBSERVER, new ReachableUser(0, 1.0));
                for (String friend : FRIENDS) reachable.put(friend, new ReachableUser(1, null));
                return reachable;
            }

            @Override
            public Map<String, ReachableUser> getUnreachedUsers(String observer, Collection<String> pubkeys) {
                Map<String, ReachableUser> out = new HashMap<>();
                if (providerStored != null && pubkeys.contains(PROVIDER)) out.put(PROVIDER, providerStored);
                return out;
            }
        };
        IRelationshipsCache cache = new IRelationshipsCache() {
            @Override
            public List<RelationshipInfo> getIncomingFollowsBulk(List<String> pubkeys) {
                List<RelationshipInfo> out = new ArrayList<>();
                for (String friend : FRIENDS) {
                    if (pubkeys.contains(friend)) out.add(new RelationshipInfo(OBSERVER, "FOLLOWS", friend));
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

            @Override
            public Map<String, Set<String>> getDesignationsBulk(List<String> designators) {
                Map<String, Set<String>> out = new HashMap<>();
                for (String d : designators) if (FRIENDS.contains(d)) out.put(d, Set.of(PROVIDER));
                return out;
            }
        };
        return new GrapeRankAlgorithm(graph, cache).graperankAllSteps(OBSERVER, Constants.DEFAULT_PARAMS, List.of());
    }

    @Test
    void anUnchangedKeyIsNeitherRewrittenNorRepublished() {
        ScoreCard first = run(null).getScorecards().get(PROVIDER);
        assertTrue(Math.round(first.getInfluence() * 100.0) / 100.0 >= 0.02, "above the publish cutoff");

        GrapeRankResult again = run(new ReachableUser(
                (int) Constants.UNREACHABLE_HOPS, first.getInfluence(),
                (long) first.getHops(), (long) first.getTrustedFollowers()));

        assertFalse(again.getNeo4jWritePubkeys().contains(PROVIDER));
        assertFalse(again.getChangedScorePubkeys().contains(PROVIDER));
    }

    @Test
    void aZeroedKeyIsClearedAndDropped() {
        GrapeRankResult result = run(new ReachableUser(
                (int) Constants.UNREACHABLE_HOPS, 0.3, (long) Constants.UNREACHABLE_HOPS, 0L, "IMPERSONATION"));

        assertEquals(0.0, result.getScorecards().get(PROVIDER).getInfluence());
        assertTrue(result.getNeo4jClearPubkeys().contains(PROVIDER));
        assertTrue(result.getDroppedBelowCutoffPubkeys().contains(PROVIDER));
    }

    @Test
    void aKeyNeo4jHoldsNothingForIsWrittenAndPublished() {
        GrapeRankResult result = run(null);

        assertTrue(result.getNeo4jWritePubkeys().contains(PROVIDER));
        assertTrue(result.getChangedScorePubkeys().contains(PROVIDER));
    }
}
