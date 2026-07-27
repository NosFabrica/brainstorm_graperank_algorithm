package com.nosfabrica.graperank.grape;

import com.nosfabrica.graperank.db.IGraphDB;
import com.nosfabrica.graperank.db.IRelationshipsCache;
import com.nosfabrica.graperank.db.RelationshipInfo;
import com.nosfabrica.graperank.rank.ScoreCard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verified-muters count a Publish run carries into the Trusted Assertion:
 * how many of a subject's muters clear the Observer's muter cutoff.
 *
 * The graph is the smallest one that produces a muter with real Influence:
 * OBSERVER follows MUTER (so MUTER earns Influence from the Observer's own
 * follow), and MUTER mutes SUBJECT.
 */
class TrustedMutersTest {

    private static final String OBSERVER = "observer";
    private static final String MUTER = "muter";
    private static final String SUBJECT = "subject";

    /** Reachable-user graph: the Observer follows MUTER (1 hop); SUBJECT is only
     * reached as a mute target, so it never appears at any hop. */
    private static final class FakeGraphDB implements IGraphDB {
        @Override
        public List<String> getUsersConnectedToObserver(String observer, Integer hopsLimit) {
            return List.of(MUTER);
        }

        @Override
        public Map<String, Double> getUsersConnectedToObserverWithPreviousInfluence(String observer) {
            Map<String, Double> previous = new HashMap<>();
            previous.put(OBSERVER, 1.0);
            previous.put(MUTER, 0.0);
            previous.put(SUBJECT, 0.0);
            return previous;
        }
    }

    /** Reverse-set cache: `followed_by:MUTER = {OBSERVER}`, `muted_by:SUBJECT = {MUTER}`. */
    private static final class FakeRelationshipsCache implements IRelationshipsCache {
        @Override
        public List<RelationshipInfo> getIncomingFollowsBulk(List<String> pubkeys) {
            return edgesFor(pubkeys, MUTER, "FOLLOWS", OBSERVER);
        }

        @Override
        public List<RelationshipInfo> getIncomingMutesBulk(List<String> pubkeys) {
            return edgesFor(pubkeys, SUBJECT, "MUTES", MUTER);
        }

        @Override
        public List<RelationshipInfo> getIncomingReportsBulk(List<String> pubkeys) {
            return List.of();
        }

        private static List<RelationshipInfo> edgesFor(
                List<String> pubkeys, String target, String relationship, String source) {
            List<RelationshipInfo> out = new ArrayList<>();
            if (pubkeys.contains(target)) {
                out.add(new RelationshipInfo(source, relationship, target));
            }
            return out;
        }
    }

    private static Map<String, ScoreCard> run(double verifiedMutersInfluenceCutoff) {
        GrapeRankParams params = new GrapeRankParams(
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
                verifiedMutersInfluenceCutoff);

        GrapeRankResult result =
                new GrapeRankAlgorithm(new FakeGraphDB(), new FakeRelationshipsCache())
                        .graperankAllSteps(OBSERVER, params);

        assertTrue(result.isSuccess(), "expected a successful run");
        return result.getScorecards();
    }

    @Test
    void countsAMuterWhoseInfluenceExceedsTheCutoff() {
        Map<String, ScoreCard> scorecards = run(Constants.DEFAULT_CUTOFF_OF_VERIFIED_MUTER);

        double muterInfluence = scorecards.get(MUTER).getInfluence();
        assertTrue(
                muterInfluence > Constants.DEFAULT_CUTOFF_OF_VERIFIED_MUTER,
                "fixture must put the muter above the cutoff, was " + muterInfluence);
        assertEquals(1.0, scorecards.get(SUBJECT).getTrustedMuters());
    }

    @Test
    void ignoresAMuterWhoseInfluenceIsBelowTheCutoff() {
        // Same graph, a cutoff the muter cannot clear: the count must react to the
        // param, which until now nothing read.
        Map<String, ScoreCard> scorecards = run(0.99);

        assertEquals(0.0, scorecards.get(SUBJECT).getTrustedMuters());
    }

    @Test
    void leavesAnUnmutedAccountAtZero() {
        Map<String, ScoreCard> scorecards = run(Constants.DEFAULT_CUTOFF_OF_VERIFIED_MUTER);

        assertEquals(0.0, scorecards.get(MUTER).getTrustedMuters());
    }

    @Test
    void countsTheThreeRelationshipsIndependently() {
        // The muter count shares its rule with the follower/reporter counts, so
        // pin all three: MUTER is followed by the Observer and reports nobody.
        Map<String, ScoreCard> scorecards = run(Constants.DEFAULT_CUTOFF_OF_VERIFIED_MUTER);

        ScoreCard muter = scorecards.get(MUTER);
        assertEquals(1.0, muter.getTrustedFollowers());
        assertEquals(0.0, muter.getTrustedReporters());
        assertEquals(0.0, scorecards.get(SUBJECT).getTrustedFollowers());
        assertEquals(0.0, scorecards.get(SUBJECT).getTrustedReporters());
    }
}
