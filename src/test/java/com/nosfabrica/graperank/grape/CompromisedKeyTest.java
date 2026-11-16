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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compromised keys (brainstorm_server `compromised_pubkey`): whoever holds the key now must not
 * spend the old owner's reputation. The key is held at Influence 0 inside the sweep, so its
 * follows, mutes, reports and designations carry nothing — even when the Observer follows or
 * designated it.
 *
 * OBSERVER follows STOLEN and BYSTANDER (and FRIEND, who follows BYSTANDER too). STOLEN follows
 * SYBIL, which nobody else follows; optionally STOLEN reports BYSTANDER, and designates PROVIDER.
 */
class CompromisedKeyTest {

    private static final String OBSERVER = "observer";
    private static final String STOLEN = "stolen";
    private static final String BYSTANDER = "bystander";
    private static final String FRIEND = "friend";
    private static final String SYBIL = "sybil";
    private static final String PROVIDER = "provider";

    private static Map<String, ScoreCard> run(
            Collection<String> compromised, boolean stolenReportsBystander, List<String> designatedByObserver) {
        IGraphDB graph = new IGraphDB() {
            @Override
            public Map<String, ReachableUser> getReachableUsers(String observer) {
                Map<String, ReachableUser> reachable = new HashMap<>();
                reachable.put(OBSERVER, new ReachableUser(0, 1.0));
                reachable.put(STOLEN, new ReachableUser(1, null));
                reachable.put(BYSTANDER, new ReachableUser(1, null));
                reachable.put(FRIEND, new ReachableUser(1, null));
                reachable.put(SYBIL, new ReachableUser(2, null));
                return reachable;
            }

            @Override
            public Map<String, ReachableUser> getUnreachedUsers(String observer, Collection<String> pubkeys) {
                return Map.of();
            }
        };
        IRelationshipsCache cache = new IRelationshipsCache() {
            @Override
            public List<RelationshipInfo> getIncomingFollowsBulk(List<String> pubkeys) {
                List<RelationshipInfo> out = new ArrayList<>();
                if (pubkeys.contains(STOLEN)) out.add(new RelationshipInfo(OBSERVER, "FOLLOWS", STOLEN));
                if (pubkeys.contains(BYSTANDER)) {
                    out.add(new RelationshipInfo(OBSERVER, "FOLLOWS", BYSTANDER));
                    out.add(new RelationshipInfo(FRIEND, "FOLLOWS", BYSTANDER));
                }
                if (pubkeys.contains(FRIEND)) out.add(new RelationshipInfo(OBSERVER, "FOLLOWS", FRIEND));
                if (pubkeys.contains(SYBIL)) out.add(new RelationshipInfo(STOLEN, "FOLLOWS", SYBIL));
                return out;
            }

            @Override
            public List<RelationshipInfo> getIncomingMutesBulk(List<String> pubkeys) {
                return List.of();
            }

            @Override
            public List<RelationshipInfo> getIncomingReportsBulk(List<String> pubkeys) {
                if (stolenReportsBystander && pubkeys.contains(BYSTANDER)) {
                    return List.of(new RelationshipInfo(STOLEN, "REPORTS", BYSTANDER));
                }
                return List.of();
            }

            @Override
            public Map<String, Set<String>> getDesignationsBulk(List<String> designators) {
                Map<String, Set<String>> out = new HashMap<>();
                if (designators.contains(STOLEN)) out.put(STOLEN, Set.of(PROVIDER));
                return out;
            }
        };
        return new GrapeRankAlgorithm(graph, cache)
                .graperankAllSteps(OBSERVER, Constants.DEFAULT_PARAMS, designatedByObserver, compromised)
                .getScorecards();
    }

    private static Map<String, ScoreCard> run(Collection<String> compromised) {
        return run(compromised, false, List.of());
    }

    @Test
    void aCompromisedKeyIsZeroedEvenWhenTheObserverFollowsIt() {
        Map<String, ScoreCard> baseline = run(Set.of());
        Map<String, ScoreCard> scored = run(Set.of(STOLEN));

        assertTrue(baseline.get(STOLEN).getInfluence() > 0, "the fixture gives the key real Influence");
        assertTrue(baseline.get(SYBIL).getInfluence() > 0, "...which it passes on when not compromised");
        assertEquals(0.0, scored.get(STOLEN).getInfluence());
        assertEquals(0.0, scored.get(SYBIL).getInfluence(), "what it follows gains nothing from it");
        assertEquals(baseline.get(BYSTANDER).getInfluence(), scored.get(BYSTANDER).getInfluence());
    }

    @Test
    void itsReportsCostNothing() {
        Map<String, ScoreCard> clean = run(Set.of(STOLEN), false, List.of());
        Map<String, ScoreCard> reported = run(Set.of(STOLEN), true, List.of());
        Map<String, ScoreCard> reportedByLiveKey = run(Set.of(), true, List.of());

        assertTrue(reportedByLiveKey.get(BYSTANDER).getInfluence()
                        < run(Set.of(), false, List.of()).get(BYSTANDER).getInfluence(),
                "the fixture's report costs something from a live key");
        assertEquals(clean.get(BYSTANDER).getInfluence(), reported.get(BYSTANDER).getInfluence(), 1e-12);
    }

    @Test
    void itsDesignationsCountForNothing() {
        Map<String, ScoreCard> baseline = run(Set.of());
        Map<String, ScoreCard> scored = run(Set.of(STOLEN));

        assertTrue(baseline.get(PROVIDER).getInfluence() > 0, "a live key's designation rates the provider");
        assertEquals(0.0, scored.get(PROVIDER).getInfluence());
    }

    @Test
    void itOverridesTheObserversDesignationPin() {
        Map<String, ScoreCard> pinned = run(Set.of(), false, List.of(STOLEN));
        Map<String, ScoreCard> scored = run(Set.of(STOLEN), false, List.of(STOLEN));

        assertEquals(Constants.DESIGNATED_KEY_INFLUENCE, pinned.get(STOLEN).getInfluence());
        assertEquals(0.0, scored.get(STOLEN).getInfluence());
        assertEquals(0.0, scored.get(SYBIL).getInfluence());
    }

    @Test
    void theObserverIsNeverZeroed() {
        Map<String, ScoreCard> baseline = run(Set.of());
        Map<String, ScoreCard> scored = run(Set.of(OBSERVER));

        assertEquals(1.0, scored.get(OBSERVER).getInfluence());
        for (String user : List.of(STOLEN, BYSTANDER, FRIEND, SYBIL)) {
            assertEquals(baseline.get(user).getInfluence(), scored.get(user).getInfluence(), user);
        }
    }

    @Test
    void aKeyTheRunDoesNotHoldChangesNothing() {
        Map<String, ScoreCard> baseline = run(Set.of());
        Map<String, ScoreCard> scored = run(Set.of("someone-else"));

        for (String user : baseline.keySet()) {
            assertEquals(baseline.get(user).getInfluence(), scored.get(user).getInfluence(), user);
        }
    }
}
