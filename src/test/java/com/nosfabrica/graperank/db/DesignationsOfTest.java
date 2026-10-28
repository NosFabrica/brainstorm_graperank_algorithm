package com.nosfabrica.graperank.db;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A user's zap signer (`zaps_via:`) counts as one of their designations (`designates:`). */
class DesignationsOfTest {

    @Test
    void mergesTheZapSignerIntoTheDesignations() {
        Map<String, Set<String>> out = RedisRelationshipsHelper.designationsOf(
                List.of("alice", "bob", "carol", "dave"),
                Arrays.asList(Set.of("provider"), Set.of(), null, Set.of("provider")),
                Arrays.asList(Set.of("wos"), Set.of("wos"), Set.of(), Set.of("provider")));

        assertEquals(Map.of(
                "alice", Set.of("provider", "wos"),
                "bob", Set.of("wos"),
                "dave", Set.of("provider")), out);
    }
}
