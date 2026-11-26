package com.nosfabrica.graperank.grape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nosfabrica.graperank.db.RunIds;
import com.nosfabrica.graperank.rank.ScoreCard;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdGrapeRankResultTest {

    private static final String OBSERVER = "a".repeat(64);
    private static final String ALICE = "b".repeat(64);
    private static final String BOB = "c".repeat(64);

    private static GrapeRankResult result() {
        Map<String, ScoreCard> cards = new LinkedHashMap<>();
        cards.put(OBSERVER, new ScoreCard(OBSERVER, OBSERVER, 0.0));
        ScoreCard alice = new ScoreCard(OBSERVER, ALICE, 1.0);
        alice.setInfluence(0.5);
        cards.put(ALICE, alice);
        GrapeRankResult result = new GrapeRankResult(cards, 7, 1.5, true, List.of(ALICE), List.of());
        result.setNeo4jWritePubkeys(List.of(ALICE));
        result.setNeo4jClearPubkeys(List.of());
        result.setNeo4jPersistFloor(0.0001);
        return result;
    }

    private static RunIds ids(Map<Integer, String> known) {
        RunIds ids = new RunIds(null);
        ids.learn(known);
        return ids;
    }

    @Test
    void usersAreNamedByIdAndNoPubkeyIsRepeated() throws Exception {
        GrapeRankResult named = new IdGrapeRankResult(OBSERVER, result(), ids(Map.of(1, OBSERVER, 2, ALICE)));
        String json = new ObjectMapper().writeValueAsString(named);
        JsonNode node = new ObjectMapper().readTree(json);

        assertEquals(OBSERVER, node.get("observer").asText());
        assertEquals(0.5, node.get("scorecardsById").get("2").get("influence").asDouble());
        assertEquals(1, node.get("scorecardsById").get("2").get("hops").asInt());
        for (String gone : new String[]{"observer", "observee", "context"}) {
            assertFalse(node.get("scorecardsById").get("2").has(gone), gone);
        }
        assertEquals(2, node.get("changedScoreIds").get(0).asInt());
        assertEquals(2, node.get("neo4jWriteIds").get(0).asInt());
        assertEquals(0, node.get("neo4jClearIds").size());
        assertEquals(7, node.get("rounds").asInt());
        assertTrue(node.get("success").asBoolean());
        assertEquals(0.0001, node.get("neo4jPersistFloor").asDouble());
        // The Observer's pubkey appears once; no Observee pubkey at all.
        assertEquals(json.indexOf(OBSERVER), json.lastIndexOf(OBSERVER));
        assertFalse(json.contains(ALICE));
        assertTrue(node.get("scorecards").isNull());
    }

    @Test
    void aWriteListThatWasNotComputedStaysAbsent() throws Exception {
        GrapeRankResult result = result();
        result.setNeo4jWritePubkeys(null);
        GrapeRankResult named = new IdGrapeRankResult(OBSERVER, result, ids(Map.of(1, OBSERVER, 2, ALICE)));
        assertTrue(new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(named)).get("neo4jWriteIds").isNull());
    }

    @Test
    void aScoredUserWithoutAnIdFailsTheRun() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new IdGrapeRankResult(OBSERVER, result(), ids(Map.of(1, OBSERVER))));
        assertTrue(error.getMessage().contains("1 scored users have no User id"));
    }

    @Test
    void thePubkeyFormIsUnchanged() throws Exception {
        JsonNode card = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(result()))
                .get("scorecards").get(ALICE);
        assertEquals(OBSERVER, card.get("observer").asText());
        assertEquals(ALICE, card.get("observee").asText());
        assertEquals("not a bot", card.get("context").asText());
    }
}
