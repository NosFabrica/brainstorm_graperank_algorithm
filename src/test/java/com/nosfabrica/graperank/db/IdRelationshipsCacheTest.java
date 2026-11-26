package com.nosfabrica.graperank.db;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import redis.clients.jedis.Jedis;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relationship sets read by User id give the algorithm what the pubkey-named sets give it.
 * Needs a Redis and a Postgres (WORKER_IT=1, WORKER_IT_REDIS_HOST / _PORT, WORKER_IT_PG_*).
 */
@EnabledIfEnvironmentVariable(named = "WORKER_IT", matches = "1")
class IdRelationshipsCacheTest {

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final String REDIS_HOST = env("WORKER_IT_REDIS_HOST", "127.0.0.1");
    private static final int REDIS_PORT = Integer.parseInt(env("WORKER_IT_REDIS_PORT", "6379"));
    private static final String PG_HOST = env("WORKER_IT_PG_HOST", "127.0.0.1");
    private static final String PG_PORT = env("WORKER_IT_PG_PORT", "5432");
    private static final String PG_PASSWORD = env("WORKER_IT_PG_PASSWORD", "test");

    private static String key(String c) {
        return c.repeat(64);
    }

    // The run scores OBSERVER, A, B and (reached only by designation) SIGNER. OUTSIDER rates
    // them but is not scored. NO_ID has no User id.
    private static final String OBSERVER = key("2");
    private static final String A = key("3");
    private static final String B = key("4");
    private static final String SIGNER = key("5");
    private static final String OUTSIDER = key("6");
    private static final String NO_ID = key("7");

    private static final Map<String, Integer> IDS = new HashMap<>();
    private static RedisRelationshipsHelper byPubkey;
    private static PostgresHelper postgres;

    @BeforeAll
    static void seed() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://" + PG_HOST + ":" + PG_PORT + "/scorecard", "postgres", PG_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SEQUENCE IF NOT EXISTS nostr_user_id_seq AS integer");
            statement.execute("CREATE TABLE IF NOT EXISTS nostr_user (id integer PRIMARY KEY DEFAULT nextval('nostr_user_id_seq'), pubkey text NOT NULL UNIQUE)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO nostr_user (pubkey) VALUES (?) ON CONFLICT (pubkey) DO UPDATE SET pubkey = EXCLUDED.pubkey RETURNING id")) {
                for (String pubkey : List.of(OBSERVER, A, B, SIGNER, OUTSIDER)) {
                    insert.setString(1, pubkey);
                    try (var rows = insert.executeQuery()) {
                        rows.next();
                        IDS.put(pubkey, rows.getInt(1));
                    }
                }
            }
        }
        String[][] edges = {
                {"followed_by:", A, OBSERVER}, {"followed_by:", A, B}, {"followed_by:", A, OUTSIDER},
                {"followed_by:", B, A}, {"followed_by:", B, NO_ID},
                {"muted_by:", B, OBSERVER}, {"reported_by:", A, B}, {"reported_by:", A, OUTSIDER},
                {"designates:", A, SIGNER}, {"zaps_via:", B, SIGNER}, {"zaps_via:", OBSERVER, A}};
        try (Jedis jedis = new Jedis(REDIS_HOST, REDIS_PORT)) {
            for (String[] edge : edges) {
                jedis.sadd(edge[0] + edge[1], edge[2]);
                // The same edge named by id; a user without an id is in no id set.
                if (IDS.containsKey(edge[1]) && IDS.containsKey(edge[2])) {
                    jedis.sadd(edge[0] + IDS.get(edge[1]), String.valueOf(IDS.get(edge[2])));
                }
            }
        }
        byPubkey = new RedisRelationshipsHelper(REDIS_HOST, REDIS_PORT);
        postgres = new PostgresHelper(PG_HOST, PG_PORT, "scorecard", "postgres", PG_PASSWORD);
    }

    /** A fresh run that has walked the graph and reached A and B. */
    private static IdRelationshipsCache run() {
        RunIds ids = new RunIds(postgres);
        ids.learn(Map.of(IDS.get(A), A, IDS.get(B), B));
        return new IdRelationshipsCache(byPubkey, ids);
    }

    private static Set<String> edges(List<RelationshipInfo> relationships, Set<String> scored) {
        Set<String> out = new HashSet<>();
        for (RelationshipInfo r : relationships) {
            // The algorithm ignores a rater it does not score; the id reader drops them early.
            if (scored.contains(r.getSource())) out.add(r.getSource() + ">" + r.getRelationship() + ">" + r.getTarget());
        }
        return out;
    }

    @Test
    void theReverseSetsGiveTheSameRatingsBetweenScoredUsers() {
        IdRelationshipsCache byId = run();
        List<String> relevant = List.of(OBSERVER, A, B, SIGNER);
        Set<String> scored = Set.copyOf(relevant);
        byId.getDesignationsBulk(List.of(OBSERVER, A, B));
        byId.prepare(relevant);

        IncomingRelationships fromPubkeys = byPubkey.getIncomingBulk(relevant);
        IncomingRelationships fromIds = byId.getIncomingBulk(relevant);
        assertEquals(edges(fromPubkeys.follows(), scored), edges(fromIds.follows(), scored));
        assertEquals(edges(fromPubkeys.mutes(), scored), edges(fromIds.mutes(), scored));
        assertEquals(edges(fromPubkeys.reports(), scored), edges(fromIds.reports(), scored));

        assertEquals(Set.of(OBSERVER + ">FOLLOWS>" + A, B + ">FOLLOWS>" + A, A + ">FOLLOWS>" + B),
                edges(fromIds.follows(), scored));
        // The outsider's ratings never leave the reader: nobody had to look them up.
        assertTrue(fromIds.follows().stream().noneMatch(r -> r.getSource().equals(OUTSIDER)));
        assertEquals(edges(byPubkey.getIncomingFollowsBulk(relevant), scored), edges(byId.getIncomingFollowsBulk(relevant), scored));
    }

    @Test
    void designationsNameKeysTheWalkDidNotReach() {
        List<String> designators = List.of(OBSERVER, A, B);
        assertEquals(byPubkey.getDesignationsBulk(designators), run().getDesignationsBulk(designators));
        assertEquals(Map.of(OBSERVER, Set.of(A), A, Set.of(SIGNER), B, Set.of(SIGNER)),
                run().getDesignationsBulk(designators));
    }

    @Test
    void aUserWithoutAnIdHasNoSets() {
        IdRelationshipsCache byId = run();
        byId.prepare(List.of(NO_ID));
        assertTrue(byId.getIncomingBulk(List.of(NO_ID)).follows().isEmpty());
        assertTrue(byId.getDesignationsBulk(List.of(NO_ID)).isEmpty());
    }
}
