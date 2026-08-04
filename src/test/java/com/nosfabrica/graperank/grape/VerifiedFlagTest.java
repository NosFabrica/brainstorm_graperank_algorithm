package com.nosfabrica.graperank.grape;

import com.nosfabrica.graperank.rank.ScoreCard;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.nosfabrica.graperank.grape.ObserverMuterSubjectFixture.MUTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ScoreCard.verified — whether a subject's own Influence clears the Observer's
 * follower cutoff. It must use the same strict `>` as the trusted-rater counts:
 * the two disagreed on the boundary, so an account sitting exactly on the cutoff
 * was "verified" while not counting as a verified follower of anyone.
 */
class VerifiedFlagTest {

    private static Map<String, ScoreCard> run(double verifiedFollowersInfluenceCutoff) {
        return ObserverMuterSubjectFixture.run(
                verifiedFollowersInfluenceCutoff,
                Constants.DEFAULT_CUTOFF_OF_TRUSTED_REPORTER,
                Constants.DEFAULT_CUTOFF_OF_VERIFIED_MUTER);
    }

    private static double muterInfluence() {
        return run(Constants.DEFAULT_CUTOFF_OF_VALID_USER).get(MUTER).getInfluence();
    }

    @Test
    void marksAnAccountAboveTheCutoffVerified() {
        assertTrue(run(Constants.DEFAULT_CUTOFF_OF_VALID_USER).get(MUTER).getVerified());
    }

    @Test
    void marksAnAccountBelowTheCutoffUnverified() {
        assertFalse(run(0.99).get(MUTER).getVerified());
    }

    @Test
    void excludesAnAccountSittingExactlyOnTheCutoff() {
        // Strict `>`: put the cutoff exactly on the computed Influence.
        assertFalse(run(muterInfluence()).get(MUTER).getVerified());
    }

    @Test
    void agreesWithTheTrustedFollowerCountOnTheBoundary() {
        // The two definitions of "verified" must not disagree: at a cutoff equal
        // to MUTER's Influence, MUTER is neither verified nor a verified follower
        // of anyone.
        Map<String, ScoreCard> scorecards = run(muterInfluence());

        assertFalse(scorecards.get(MUTER).getVerified());
        assertEquals(0.0, scorecards.get(ObserverMuterSubjectFixture.SUBJECT).getTrustedFollowers());
    }
}
