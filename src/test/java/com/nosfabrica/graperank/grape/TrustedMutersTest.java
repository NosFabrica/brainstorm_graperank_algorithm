package com.nosfabrica.graperank.grape;

import com.nosfabrica.graperank.rank.ScoreCard;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.nosfabrica.graperank.grape.ObserverMuterSubjectFixture.MUTER;
import static com.nosfabrica.graperank.grape.ObserverMuterSubjectFixture.SUBJECT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verified-muters count a Publish run carries into the Trusted Assertion:
 * how many of a subject's muters clear the Observer's muter cutoff.
 */
class TrustedMutersTest {

    private static Map<String, ScoreCard> run(double verifiedMutersInfluenceCutoff) {
        return ObserverMuterSubjectFixture.run(
                Constants.DEFAULT_CUTOFF_OF_VALID_USER,
                Constants.DEFAULT_CUTOFF_OF_TRUSTED_REPORTER,
                verifiedMutersInfluenceCutoff);
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
