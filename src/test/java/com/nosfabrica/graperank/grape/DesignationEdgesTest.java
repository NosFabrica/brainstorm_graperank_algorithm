package com.nosfabrica.graperank.grape;

import com.nosfabrica.graperank.db.IGraphDB;
import com.nosfabrica.graperank.db.IRelationshipsCache;
import com.nosfabrica.graperank.db.ReachableUser;
import com.nosfabrica.graperank.db.RelationshipInfo;
import com.nosfabrica.graperank.rank.ScoreCard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everyone's kind-10040 designations (the `designates:` sets), not only the
 * Observer's: a key someone the Observer reaches designated is rated by them at
 * {@link Constants#DESIGNATION_RATING}, like a follow at that rating; the
 * Observer's own designations are pinned at {@link Constants#DESIGNATED_KEY_INFLUENCE}.
 */
class DesignationEdgesTest {

    private static final String OBSERVER = "observer";
    private static final String FRIEND = "friend";
    private static final String PROVIDER = "provider";
    private static final String OWN_PROVIDER = "own-provider";

    /** OBSERVER follows FRIEND. `followsOfFriend` are FRIEND's follows, reached at 2 hops. */
    private static Map<String, ScoreCard> run(
            Map<String, Set<String>> designations, List<String> followsOfFriend) {
        IGraphDB graph = observer -> {
            Map<String, ReachableUser> reachable = new HashMap<>();
            reachable.put(OBSERVER, new ReachableUser(0, 1.0));
            reachable.put(FRIEND, new ReachableUser(1, null));
            for (String followed : followsOfFriend) reachable.put(followed, new ReachableUser(2, null));
            return reachable;
        };
        IRelationshipsCache cache = new IRelationshipsCache() {
            @Override
            public List<RelationshipInfo> getIncomingFollowsBulk(List<String> pubkeys) {
                List<RelationshipInfo> out = new ArrayList<>();
                if (pubkeys.contains(FRIEND)) out.add(new RelationshipInfo(OBSERVER, "FOLLOWS", FRIEND));
                for (String followed : followsOfFriend) {
                    if (pubkeys.contains(followed)) out.add(new RelationshipInfo(FRIEND, "FOLLOWS", followed));
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
                for (String designator : designators) {
                    if (designations.containsKey(designator)) out.put(designator, designations.get(designator));
                }
                return out;
            }
        };
        GrapeRankResult result = new GrapeRankAlgorithm(graph, cache)
                .graperankAllSteps(OBSERVER, Constants.DEFAULT_PARAMS, List.of());
        assertTrue(result.isSuccess(), "expected a successful run");
        return result.getScorecards();
    }

    @Test
    void aKeySomeoneReachedDesignatedJoinsTheRunUnreachedByFollows() {
        ScoreCard provider = run(Map.of(FRIEND, Set.of(PROVIDER)), List.of()).get(PROVIDER);

        assertTrue(provider.getInfluence() > 0);
        assertEquals(Constants.DESIGNATION_RATING, provider.getAverageScore(), 1e-12);
        assertEquals(Constants.UNREACHABLE_HOPS, provider.getHops());
    }

    @Test
    void aDesignationRatesLikeAFollowAtTheDesignationRating() {
        double designated = run(Map.of(FRIEND, Set.of(PROVIDER)), List.of()).get(PROVIDER).getInfluence();
        double followed = run(Map.of(), List.of(PROVIDER)).get(PROVIDER).getInfluence();

        assertEquals(Constants.DESIGNATION_RATING * followed, designated, 1e-12);
    }

    @Test
    void theObserversOwnDesignationsArePinned() {
        ScoreCard own = run(Map.of(OBSERVER, Set.of(OWN_PROVIDER)), List.of()).get(OWN_PROVIDER);

        assertEquals(Constants.DESIGNATED_KEY_INFLUENCE, own.getInfluence());
    }

    @Test
    void aDesignationIsNotATrustedFollow() {
        ScoreCard provider = run(Map.of(FRIEND, Set.of(PROVIDER)), List.of()).get(PROVIDER);

        assertEquals(0.0, provider.getTrustedFollowers());
        assertEquals(0.0, provider.getTrustedReporters());
        assertEquals(0.0, provider.getTrustedMuters());
    }

    @Test
    void noDesignationsLeaveTheRunAsItWas() {
        assertFalse(run(Map.of(), List.of()).containsKey(PROVIDER));
    }
}
