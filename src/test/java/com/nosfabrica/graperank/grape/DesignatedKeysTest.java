package com.nosfabrica.graperank.grape;

import com.nosfabrica.graperank.db.IGraphDB;
import com.nosfabrica.graperank.db.IRelationshipsCache;
import com.nosfabrica.graperank.db.RelationshipInfo;
import com.nosfabrica.graperank.rank.ScoreCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.nosfabrica.graperank.grape.ObserverMuterSubjectFixture.MUTER;
import static com.nosfabrica.graperank.grape.ObserverMuterSubjectFixture.OBSERVER;
import static com.nosfabrica.graperank.grape.ObserverMuterSubjectFixture.SUBJECT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keys the Observer listed in their kind-10040 are scored at a fixed 95,
 * whatever the graph says about them — reached, rated, or not reached at all.
 */
class DesignatedKeysTest {

    private static final String UNREACHED = "designated-but-unreached";

    private static Map<String, ScoreCard> run(List<String> designated) {
        return ObserverMuterSubjectFixture.run(
                Constants.DEFAULT_CUTOFF_OF_VALID_USER,
                Constants.DEFAULT_CUTOFF_OF_TRUSTED_REPORTER,
                Constants.DEFAULT_CUTOFF_OF_VERIFIED_MUTER,
                designated);
    }

    @Test
    void overridesTheComputedInfluenceOfAReachedKey() {
        assertTrue(run(List.of()).get(MUTER).getInfluence() < Constants.DESIGNATED_KEY_INFLUENCE);
        assertEquals(Constants.DESIGNATED_KEY_INFLUENCE, run(List.of(MUTER)).get(MUTER).getInfluence());
    }

    @Test
    void overridesTheComputedInfluenceOfAMutedKey() {
        assertEquals(Constants.DESIGNATED_KEY_INFLUENCE, run(List.of(SUBJECT)).get(SUBJECT).getInfluence());
    }

    @Test
    void scoresAKeyTheGraphNeverReaches() {
        ScoreCard scorecard = run(List.of(UNREACHED)).get(UNREACHED);
        assertEquals(Constants.DESIGNATED_KEY_INFLUENCE, scorecard.getInfluence());
        assertEquals(Constants.UNREACHABLE_HOPS, scorecard.getHops());
        assertTrue(scorecard.getVerified());
    }

    @Test
    void leavesTheObserverAtFullInfluence() {
        assertEquals(1.0, run(List.of(OBSERVER)).get(OBSERVER).getInfluence());
    }

    /** An Observer with no graph is still NO_ELIGIBLE_USERS: designated keys are
     * added regardless of the graph and must not turn that into a success. */
    @Test
    void doesNotMakeAnUnconnectedObserverSucceed() {
        IGraphDB emptyGraph = new IGraphDB() {
            @Override
            public List<String> getUsersConnectedToObserver(String observer, Integer hopsLimit) {
                return List.of();
            }

            @Override
            public Map<String, Double> getUsersConnectedToObserverWithPreviousInfluence(String observer) {
                return Map.of(OBSERVER, 1.0);
            }
        };
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

        GrapeRankResult result = new GrapeRankAlgorithm(emptyGraph, noEdges)
                .graperankAllSteps(OBSERVER, Constants.DEFAULT_PARAMS, List.of(UNREACHED));

        assertFalse(result.isSuccess());
    }
}
