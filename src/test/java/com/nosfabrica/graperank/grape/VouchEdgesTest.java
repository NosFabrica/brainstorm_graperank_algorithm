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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vouches (kind 30003, d=\"vouches\"): a deliberate endorsement, carried at
 * {@code vouchRating} / {@code vouchConfidence} instead of the follow's ambient
 * rating and confidence. A vouch moves a user's Influence but never extends the
 * candidate set, and counts as none of the trusted-rater counts — like a
 * designation, unlike a follow.
 */
class VouchEdgesTest {

    private static final String OBSERVER = "observer";
    private static final String FRIEND = "friend";
    private static final String TARGET = "target";
    private static final String STRANGER = "stranger";

    /**
     * OBSERVER follows FRIEND, and TARGET when {@code observerFollowsTarget}. FRIEND
     * follows TARGET when {@code friendFollowsTarget}. {@code vouches} holds
     * voucher → vouched for every vouch in the run.
     */
    private static Map<String, ScoreCard> run(
            Map<String, List<String>> vouches, boolean friendFollowsTarget, GrapeRankParams params) {
        IGraphDB graph = observer -> {
            Map<String, ReachableUser> reachable = new HashMap<>();
            reachable.put(OBSERVER, new ReachableUser(0, 1.0));
            reachable.put(FRIEND, new ReachableUser(1, null));
            if (friendFollowsTarget) reachable.put(TARGET, new ReachableUser(2, null));
            return reachable;
        };
        IRelationshipsCache cache = new IRelationshipsCache() {
            @Override
            public List<RelationshipInfo> getIncomingFollowsBulk(List<String> pubkeys) {
                List<RelationshipInfo> out = new ArrayList<>();
                if (pubkeys.contains(FRIEND)) out.add(new RelationshipInfo(OBSERVER, "FOLLOWS", FRIEND));
                if (friendFollowsTarget && pubkeys.contains(TARGET)) {
                    out.add(new RelationshipInfo(FRIEND, "FOLLOWS", TARGET));
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
            public List<RelationshipInfo> getIncomingVouchesBulk(List<String> pubkeys) {
                List<RelationshipInfo> out = new ArrayList<>();
                for (Map.Entry<String, List<String>> entry : vouches.entrySet()) {
                    for (String vouched : entry.getValue()) {
                        if (pubkeys.contains(vouched)) {
                            out.add(new RelationshipInfo(entry.getKey(), "VOUCHES", vouched));
                        }
                    }
                }
                return out;
            }
        };
        GrapeRankResult result = new GrapeRankAlgorithm(graph, cache)
                .graperankAllSteps(OBSERVER, params, List.of());
        assertTrue(result.isSuccess(), "expected a successful run");
        return result.getScorecards();
    }

    private static Map<String, ScoreCard> run(Map<String, List<String>> vouches, boolean friendFollowsTarget) {
        return run(vouches, friendFollowsTarget, Constants.DEFAULT_PARAMS);
    }

    @Test
    void aVouchRaisesTheVouchedUsersInfluence() {
        double plain = run(Map.of(), true).get(TARGET).getInfluence();
        double vouched = run(Map.of(FRIEND, List.of(TARGET)), true).get(TARGET).getInfluence();

        assertTrue(vouched > plain, "vouched " + vouched + " should beat plain " + plain);
    }

    @Test
    void aVouchIsWorthMoreThanABareFollowFromTheSameRater() {
        // TARGET reached at 2 hops either way; the only difference is FRIEND's edge kind.
        double followedOnly = run(Map.of(), true).get(TARGET).getInfluence();
        // FRIEND vouches TARGET but does not follow them: OBSERVER follows TARGET directly
        // so the candidate set reaches TARGET, and the only FRIEND→TARGET edge is the vouch.
        IGraphDB graph = observer -> {
            Map<String, ReachableUser> reachable = new HashMap<>();
            reachable.put(OBSERVER, new ReachableUser(0, 1.0));
            reachable.put(FRIEND, new ReachableUser(1, null));
            reachable.put(TARGET, new ReachableUser(1, null));
            return reachable;
        };
        IRelationshipsCache cache = new IRelationshipsCache() {
            @Override
            public List<RelationshipInfo> getIncomingFollowsBulk(List<String> pubkeys) {
                List<RelationshipInfo> out = new ArrayList<>();
                if (pubkeys.contains(FRIEND)) out.add(new RelationshipInfo(OBSERVER, "FOLLOWS", FRIEND));
                if (pubkeys.contains(TARGET)) out.add(new RelationshipInfo(OBSERVER, "FOLLOWS", TARGET));
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
            public List<RelationshipInfo> getIncomingVouchesBulk(List<String> pubkeys) {
                return pubkeys.contains(TARGET)
                        ? List.of(new RelationshipInfo(FRIEND, "VOUCHES", TARGET))
                        : List.of();
            }
        };
        double vouchedOnly = new GrapeRankAlgorithm(graph, cache)
                .graperankAllSteps(OBSERVER, Constants.DEFAULT_PARAMS, List.of())
                .getScorecards().get(TARGET).getInfluence();

        assertTrue(vouchedOnly > followedOnly, "vouched " + vouchedOnly + " should beat followed " + followedOnly);
    }

    @Test
    void aVouchAloneDoesNotExtendTheCandidateSet() {
        // FRIEND vouches STRANGER, whom no follow reaches: no scorecard for STRANGER.
        Map<String, ScoreCard> cards = run(Map.of(FRIEND, List.of(STRANGER)), false);

        assertFalse(cards.containsKey(STRANGER));
        assertFalse(cards.containsKey(TARGET));
    }

    @Test
    void aVouchCountsAsNoTrustedRater() {
        ScoreCard plain = run(Map.of(), true).get(TARGET);
        ScoreCard vouched = run(Map.of(FRIEND, List.of(TARGET)), true).get(TARGET);

        assertEquals(plain.getTrustedFollowers(), vouched.getTrustedFollowers());
        assertEquals(plain.getTrustedMuters(), vouched.getTrustedMuters());
        assertEquals(plain.getTrustedReporters(), vouched.getTrustedReporters());
    }

    @Test
    void aZeroConfidenceVouchChangesNothing() {
        GrapeRankParams noVouch = new GrapeRankParams(
                Constants.GLOBAL_RIGOR,
                Constants.GLOBAL_ATTENUATION_FACTOR,
                Constants.DEFAULT_RATING_FOR_FOLLOW,
                Constants.DEFAULT_CONFIDENCE_FOR_FOLLOW,
                Constants.DEFAULT_RATING_FOR_MUTE,
                Constants.DEFAULT_CONFIDENCE_FOR_MUTE,
                Constants.DEFAULT_RATING_FOR_REPORT,
                Constants.DEFAULT_CONFIDENCE_FOR_REPORT,
                Constants.DEFAULT_CONFIDENCE_FOR_FOLLOW_FROM_OBSERVER,
                Constants.DEFAULT_CUTOFF_OF_VALID_USER,
                Constants.DEFAULT_CUTOFF_OF_TRUSTED_REPORTER,
                Constants.DEFAULT_CUTOFF_OF_VERIFIED_MUTER,
                Constants.DEFAULT_RATING_FOR_VOUCH,
                0.0);
        double plain = run(Map.of(), true).get(TARGET).getInfluence();
        double zeroed = run(Map.of(FRIEND, List.of(TARGET)), true, noVouch).get(TARGET).getInfluence();

        assertEquals(plain, zeroed, 1e-12);
    }

    @Test
    void noVouchesLeaveTheRunAsItWas() {
        Map<String, ScoreCard> cards = run(Map.of(), true);

        assertTrue(cards.containsKey(TARGET));
        assertFalse(cards.containsKey(STRANGER));
    }
}
