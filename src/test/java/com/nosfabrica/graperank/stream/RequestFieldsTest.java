package com.nosfabrica.graperank.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The request fields brainstorm_server sends beside `graperank_params`: the names are a
 * cross-repo contract (brainstorm_server `scheduler_lanes.enqueue_calc_request`). */
class RequestFieldsTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static JsonNode request(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void readsBothListsAsTheServerSendsThem() throws Exception {
        JsonNode r = request("{\"designated_pubkeys\": [\"p1\"], \"compromised_pubkeys\": [\"c1\", \"c2\"]}");
        assertEquals(List.of("p1"), RequestFields.pubkeys(r, "designated_pubkeys"));
        assertEquals(List.of("c1", "c2"), RequestFields.pubkeys(r, "compromised_pubkeys"));
    }

    @Test
    void absentNullOrMalformedMeansNone() throws Exception {
        assertEquals(List.of(), RequestFields.pubkeys(request("{}"), "compromised_pubkeys"));
        assertEquals(List.of(), RequestFields.pubkeys(request("{\"compromised_pubkeys\": null}"), "compromised_pubkeys"));
        assertEquals(List.of(), RequestFields.pubkeys(request("{\"compromised_pubkeys\": \"c1\"}"), "compromised_pubkeys"));
        assertEquals(List.of("c1"),
                RequestFields.pubkeys(request("{\"compromised_pubkeys\": [\"c1\", 7, {}]}"), "compromised_pubkeys"));
    }

    @Test
    void aCompromisedObserversRunIsRefused() {
        assertTrue(RequestFields.observerIsCompromised("obs", List.of("x", "obs")));
        assertFalse(RequestFields.observerIsCompromised("obs", List.of("x")));
        assertFalse(RequestFields.observerIsCompromised("obs", List.of()));
    }
}
