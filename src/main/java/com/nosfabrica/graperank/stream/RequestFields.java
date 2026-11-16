package com.nosfabrica.graperank.stream;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Optional fields of a calc request, read without failing the run. */
final class RequestFields {

    private RequestFields() {}

    /** An optional pubkey list on the request: `designated_pubkeys` (the Observer's kind-10040)
     * or `compromised_pubkeys` (keys held at Influence 0). Absent means none. Malformed also
     * means none, never a failed run, but is logged: for the compromised list it turns the
     * zeroing off, so a server/worker contract break must show. */
    static List<String> pubkeys(JsonNode request, String field) {
        List<String> pubkeys = new ArrayList<>();
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) return pubkeys;
        if (!node.isArray()) {
            System.err.println("Ignoring malformed " + field + " (not an array): " + node.getNodeType());
            return pubkeys;
        }
        int skipped = 0;
        for (JsonNode item : node) {
            if (item.isTextual()) {
                pubkeys.add(item.asText());
            } else {
                skipped++;
            }
        }
        if (skipped > 0) {
            System.err.println("Ignoring " + skipped + " non-text entries in " + field);
        }
        return pubkeys;
    }

    /** True when the Observer's own key is on the compromised list: the run is refused. */
    static boolean observerIsCompromised(String observer, List<String> compromisedPubkeys) {
        return compromisedPubkeys.contains(observer);
    }
}
