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

/**
 * NIP-05 penalties (brainstorm_server ADR 0006): a confirmed impersonation zeroes a user's
 * Influence and a claim the domain does not back halves it, whatever the web of trust says.
 * The penalty applies inside the sweep, so a penalised user's follows carry it too.
 *
 * OBSERVER follows SUSPECT and BYSTANDER; SUSPECT follows SYBIL, which nobody else follows.
 */
class Nip05PenaltyTest {

    private static final String OBSERVER = "observer";
    private static final String SUSPECT = "suspect";
    private static final String BYSTANDER = "bystander";
    private static final String SYBIL = "sybil";
    private static final String DESIGNATED_ONLY = "designated-only";

    private static Map<String, ScoreCard> run(String suspectStatus) {
        return run(suspectStatus, Map.of(), List.of(), Map.of());
    }

    private static Map<String, ScoreCard> run(
            String suspectStatus,
            Map<String, Set<String>> designations,
            List<String> designatedByObserver,
            Map<String, String> unreachedStatuses) {
        IGraphDB graph = new IGraphDB() {
            @Override
            public Map<String, ReachableUser> getReachableUsers(String observer) {
                Map<String, ReachableUser> reachable = new HashMap<>();
                reachable.put(OBSERVER, new ReachableUser(0, 1.0));
                reachable.put(SUSPECT, new ReachableUser(1, null, suspectStatus));
                reachable.put(BYSTANDER, new ReachableUser(1, null));
                reachable.put(SYBIL, new ReachableUser(2, null));
                return reachable;
            }

            @Override
            public Map<String, String> getPenalisedNip05Statuses(Collection<String> pubkeys) {
                Map<String, String> out = new HashMap<>();
                for (String pubkey : pubkeys) if (unreachedStatuses.containsKey(pubkey)) out.put(pubkey, unreachedStatuses.get(pubkey));
                return out;
            }
        };
        IRelationshipsCache cache = new IRelationshipsCache() {
            @Override
            public List<RelationshipInfo> getIncomingFollowsBulk(List<String> pubkeys) {
                List<RelationshipInfo> out = new ArrayList<>();
                if (pubkeys.contains(SUSPECT)) out.add(new RelationshipInfo(OBSERVER, "FOLLOWS", SUSPECT));
                if (pubkeys.contains(BYSTANDER)) out.add(new RelationshipInfo(OBSERVER, "FOLLOWS", BYSTANDER));
                if (pubkeys.contains(SYBIL)) out.add(new RelationshipInfo(SUSPECT, "FOLLOWS", SYBIL));
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
                for (String d : designators) if (designations.containsKey(d)) out.put(d, designations.get(d));
                return out;
            }
        };
        return new GrapeRankAlgorithm(graph, cache)
                .graperankAllSteps(OBSERVER, Constants.DEFAULT_PARAMS, designatedByObserver)
                .getScorecards();
    }

    @Test
    void anImpersonatorIsZeroedEvenWhenTheObserverFollowsThem() {
        Map<String, ScoreCard> baseline = run(null);
        Map<String, ScoreCard> scored = run("IMPERSONATION");

        assertEquals(0.0, scored.get(SUSPECT).getInfluence());
        assertEquals(0.0, scored.get(SYBIL).getInfluence(), "an impersonator's follows pass nothing on");
        assertEquals(baseline.get(BYSTANDER).getInfluence(), scored.get(BYSTANDER).getInfluence());
    }

    @Test
    void anUnbackedClaimHalvesInfluence() {
        Map<String, ScoreCard> baseline = run(null);
        Map<String, ScoreCard> scored = run("NOT_LISTED");

        assertEquals(0.5 * baseline.get(SUSPECT).getInfluence(), scored.get(SUSPECT).getInfluence(), 1e-12);
        assertEquals(true, scored.get(SYBIL).getInfluence() < baseline.get(SYBIL).getInfluence(),
                "what a halved user passes on is halved too");
    }

    @Test
    void otherStatusesCostNothing() {
        double baseline = run(null).get(SUSPECT).getInfluence();
        for (String status : List.of("VERIFIED", "SUSPECTED", "UNKNOWN", "PENDING")) {
            assertEquals(baseline, run(status).get(SUSPECT).getInfluence(), status);
        }
    }

    @Test
    void aKeyTheObserverDesignatedIsNotPenalised() {
        assertEquals(Constants.DESIGNATED_KEY_INFLUENCE,
                run("IMPERSONATION", Map.of(), List.of(SUSPECT), Map.of()).get(SUSPECT).getInfluence());
    }

    @Test
    void aKeyReachedOnlyByDesignationIsLookedUp() {
        Map<String, Set<String>> designations = Map.of(BYSTANDER, Set.of(DESIGNATED_ONLY));
        double clean = run(null, designations, List.of(), Map.of()).get(DESIGNATED_ONLY).getInfluence();
        double impersonator = run(null, designations, List.of(), Map.of(DESIGNATED_ONLY, "IMPERSONATION"))
                .get(DESIGNATED_ONLY).getInfluence();

        assertEquals(true, clean > 0);
        assertEquals(0.0, impersonator);
    }
}
