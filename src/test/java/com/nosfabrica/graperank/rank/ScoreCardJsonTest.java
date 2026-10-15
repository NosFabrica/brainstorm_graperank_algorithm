package com.nosfabrica.graperank.rank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreCardJsonTest {

    @Test
    void countsAndHopsSerializeAsIntegers() throws Exception {
        ScoreCard card = new ScoreCard("observer", "observee", 3.0);
        card.setTrustedFollowers(412.0);
        card.setTrustedReporters(1.0);
        card.setTrustedMuters(2.0);

        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(card));

        for (String field : new String[]{"hops", "trusted_followers", "trusted_reporters", "trusted_muters"}) {
            assertTrue(json.get(field).isInt(), field + " should be an int, was " + json.get(field));
        }
    }
}
