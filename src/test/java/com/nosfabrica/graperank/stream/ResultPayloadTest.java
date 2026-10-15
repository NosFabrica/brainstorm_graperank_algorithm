package com.nosfabrica.graperank.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResultPayloadTest {

    @Test
    void keysAreUniquePerPublishOfTheSameRun() {
        String a = ResultPayload.newKey(42);
        String b = ResultPayload.newKey(42);
        assertTrue(a.startsWith("graperank:result:42:"));
        assertNotEquals(a, b);
    }

    @Test
    void envelopeCarriesOnlyTheRunIdAndKey() throws Exception {
        JsonNode env = new ObjectMapper().readTree(ResultPayload.envelope(42, "graperank:result:42:x"));
        assertEquals(42, env.get("private_id").asInt());
        assertEquals("graperank:result:42:x", env.get("result_key").asText());
        assertEquals(2, env.size());
    }
}
