package com.nosfabrica.graperank.grape;

import com.nosfabrica.graperank.db.IGraphDB;
import com.nosfabrica.graperank.db.IRelationshipsCache;
import com.nosfabrica.graperank.db.RelationshipInfo;
import com.nosfabrica.graperank.rank.ScoreCard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The smallest graph that produces a rater with real Influence:
 * OBSERVER follows MUTER (so MUTER earns Influence from the Observer's own
 * follow), and MUTER mutes SUBJECT.
 *
 * Shared by the tests that pin the trusted-rater counts and the verified flag,
 * since both need a scorecard whose Influence is computed rather than seeded.
 */
final class ObserverMuterSubjectFixture {

    static final String OBSERVER = "observer";
    static final String MUTER = "muter";
    static final String SUBJECT = "subject";

    private ObserverMuterSubjectFixture() {}

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

    static Map<String, ScoreCard> run(
            double verifiedFollowersInfluenceCutoff,
            double verifiedReportersInfluenceCutoff,
            double verifiedMutersInfluenceCutoff) {
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
                verifiedFollowersInfluenceCutoff,
                verifiedReportersInfluenceCutoff,
                verifiedMutersInfluenceCutoff);

        GrapeRankResult result =
                new GrapeRankAlgorithm(new FakeGraphDB(), new FakeRelationshipsCache())
                        .graperankAllSteps(OBSERVER, params);

        assertTrue(result.isSuccess(), "expected a successful run");
        return result.getScorecards();
    }
}
