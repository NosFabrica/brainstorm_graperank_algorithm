package com.nosfabrica.graperank.db;

import com.nosfabrica.graperank.grape.Constants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the worker learns about an Observer's reach is the same whether the stored scores are
 * Neo4j properties or rows in the Scorecard database. Needs a Neo4j and a Postgres:
 *
 *   WORKER_IT=1 WORKER_IT_NEO4J=bolt://127.0.0.1:7699 WORKER_IT_NEO4J_PASSWORD=...
 *   WORKER_IT_PG_HOST=127.0.0.1 WORKER_IT_PG_PORT=54339 WORKER_IT_PG_PASSWORD=...
 *
 * The tables are created here as the server's migrations create them.
 */
@EnabledIfEnvironmentVariable(named = "WORKER_IT", matches = "1")
class StoredScoresEquivalenceTest {

    private static final String NEO4J = env("WORKER_IT_NEO4J", "bolt://127.0.0.1:7687");
    private static final String NEO4J_PASSWORD = env("WORKER_IT_NEO4J_PASSWORD", "testpassword");
    private static final String PG_HOST = env("WORKER_IT_PG_HOST", "127.0.0.1");
    private static final String PG_PORT = env("WORKER_IT_PG_PORT", "5432");
    private static final String PG_DB = env("WORKER_IT_PG_DB", "scorecard");
    private static final String PG_USER = env("WORKER_IT_PG_USER", "postgres");
    private static final String PG_PASSWORD = env("WORKER_IT_PG_PASSWORD", "test");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String key(char c) {
        return String.valueOf(c).repeat(64);
    }

    // observer -> a -> c -> observer (a cycle), observer -> b, a -> noId. d is no longer
    // reached but keeps a stored score; e is reached by nobody and is penalised.
    private static final String OBSERVER = key('0');
    private static final String A = key('a');
    private static final String B = key('b');
    private static final String C = key('c');
    private static final String D = key('d');
    private static final String E = key('e');
    private static final String NO_ID = key('f');
    private static final String NEW_OBSERVER = key('1');

    private static Driver driver;
    private static Neo4jHelper properties;
    private static ScorecardGraphDB scorecards;

    private static Connection postgres() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://" + PG_HOST + ":" + PG_PORT + "/" + PG_DB, PG_USER, PG_PASSWORD);
    }

    @BeforeAll
    static void seed() throws Exception {
        driver = GraphDatabase.driver(NEO4J, AuthTokens.basic("neo4j", NEO4J_PASSWORD));
        properties = new Neo4jHelper(NEO4J, "neo4j", NEO4J_PASSWORD);
        scorecards = new ScorecardGraphDB(properties,
                new PostgresHelper(PG_HOST, PG_PORT, PG_DB, PG_USER, PG_PASSWORD));

        Map<String, Integer> ids = new HashMap<>();
        try (Connection connection = postgres(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SEQUENCE IF NOT EXISTS nostr_user_id_seq AS integer");
            statement.execute("CREATE TABLE IF NOT EXISTS nostr_user (id integer PRIMARY KEY DEFAULT nextval('nostr_user_id_seq'), pubkey text NOT NULL UNIQUE)");
            statement.execute("CREATE TABLE IF NOT EXISTS scorecard (observer_id integer NOT NULL, observee_id integer NOT NULL, influence double precision, hops smallint, verified_followers integer, PRIMARY KEY (observer_id, observee_id))");
            statement.execute("TRUNCATE scorecard, nostr_user");
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO nostr_user (pubkey) VALUES (?) RETURNING id")) {
                for (String pubkey : List.of(OBSERVER, A, B, C, D, E, NEW_OBSERVER)) {
                    insert.setString(1, pubkey);
                    try (var rows = insert.executeQuery()) {
                        rows.next();
                        ids.put(pubkey, rows.getInt(1));
                    }
                }
            }
            // Not exactly representable in single precision; a zero count; a null count.
            Object[][] scores = {
                    {OBSERVER, 1.0, 0, 3}, {A, 0.7512345678901234, 1, 12}, {B, 0.05, 1, 0},
                    {C, 0.3, 2, null}, {D, 0.2, 3, 1}};
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO scorecard VALUES (?, ?, ?, ?, ?)")) {
                for (Object[] score : scores) {
                    insert.setInt(1, ids.get(OBSERVER));
                    insert.setInt(2, ids.get((String) score[0]));
                    insert.setDouble(3, (Double) score[1]);
                    insert.setInt(4, (Integer) score[2]);
                    insert.setObject(5, score[3]);
                    insert.execute();
                }
            }
            try (Session session = driver.session()) {
                session.run("MATCH (n:NostrUser) WHERE n.pubkey IN $pubkeys DETACH DELETE n",
                        Values.parameters("pubkeys", List.of(OBSERVER, A, B, C, D, E, NO_ID, NEW_OBSERVER))).consume();
                for (Map.Entry<String, Integer> entry : ids.entrySet()) {
                    session.run("CREATE (:NostrUser {pubkey: $pubkey, user_id: $id})",
                            Values.parameters("pubkey", entry.getKey(), "id", entry.getValue())).consume();
                }
                session.run("CREATE INDEX nostr_user_nip05_status IF NOT EXISTS FOR (u:NostrUser) ON (u.nip05_status)").consume();
                session.run("CREATE (:NostrUser {pubkey: $pubkey})", Values.parameters("pubkey", NO_ID)).consume();
                for (Object[] score : scores) {
                    Map<String, Object> props = new HashMap<>();
                    props.put("influence_" + OBSERVER, score[1]);
                    props.put("hops_" + OBSERVER, score[2]);
                    if (score[3] != null) props.put("trusted_followers_" + OBSERVER, score[3]);
                    session.run("MATCH (n:NostrUser {pubkey: $pubkey}) SET n += $props",
                            Values.parameters("pubkey", score[0], "props", props)).consume();
                }
                String[][] follows = {{OBSERVER, A}, {OBSERVER, B}, {A, C}, {C, OBSERVER}, {A, NO_ID}, {NEW_OBSERVER, A}};
                for (String[] follow : follows) {
                    session.run("MATCH (x:NostrUser {pubkey: $from}), (y:NostrUser {pubkey: $to}) CREATE (x)-[:FOLLOWS]->(y)",
                            Values.parameters("from", follow[0], "to", follow[1])).consume();
                }
                String[][] statuses = {{A, "NOT_LISTED"}, {B, "VERIFIED"}, {E, "IMPERSONATION"}, {NO_ID, "NOT_LISTED"}};
                for (String[] status : statuses) {
                    session.run("MATCH (n:NostrUser {pubkey: $pubkey}) SET n.nip05_status = $status",
                            Values.parameters("pubkey", status[0], "status", status[1])).consume();
                }
            }
        }
    }

    @AfterAll
    static void close() {
        properties.close();
        driver.close();
    }

    /** A reached user's status that is not penalised reads as none from the Scorecard path. */
    private static Map<String, ReachableUser> penalisedOnly(Map<String, ReachableUser> users) {
        Map<String, ReachableUser> out = new HashMap<>();
        users.forEach((pubkey, user) -> out.put(pubkey, new ReachableUser(
                user.hops(), user.storedInfluence(), user.storedHops(), user.storedTrustedFollowers(),
                user.nip05Status() != null && Constants.PENALISED_NIP05_STATUSES.contains(user.nip05Status())
                        ? user.nip05Status() : null)));
        return out;
    }

    @Test
    void theFollowWalkAgrees() {
        Map<String, ReachableUser> fromProperties = properties.getReachableUsers(OBSERVER);
        Map<String, ReachableUser> fromScorecards = scorecards.getReachableUsers(OBSERVER);
        assertEquals(penalisedOnly(fromProperties), fromScorecards);

        assertEquals(java.util.Set.of(OBSERVER, A, B, C, NO_ID), fromScorecards.keySet());
        assertEquals(new ReachableUser(1, 0.7512345678901234, 1L, 12L, "NOT_LISTED"), fromScorecards.get(A));
        assertEquals(new ReachableUser(1, 0.05, 1L, 0L, null), fromScorecards.get(B));
        assertEquals(new ReachableUser(2, 0.3, 2L, null, null), fromScorecards.get(C));
        assertEquals(new ReachableUser(2, null, null, null, "NOT_LISTED"), fromScorecards.get(NO_ID));
        assertEquals(new ReachableUser(0, 1.0, 0L, 3L, null), fromScorecards.get(OBSERVER));
    }

    @Test
    void anObserverWithNoRowsIsAFirstTimeObserver() {
        Map<String, ReachableUser> fromProperties = properties.getReachableUsers(NEW_OBSERVER);
        Map<String, ReachableUser> fromScorecards = scorecards.getReachableUsers(NEW_OBSERVER);
        assertEquals(penalisedOnly(fromProperties), fromScorecards);
        assertNull(fromScorecards.get(A).storedInfluence());
        assertEquals(1, fromScorecards.get(A).hops());
    }

    @Test
    void anObserverWithoutANodeReachesNobody() {
        assertTrue(properties.getReachableUsers(key('9')).isEmpty());
        assertTrue(scorecards.getReachableUsers(key('9')).isEmpty());
    }

    @Test
    void theUnreachedLookupAgrees() {
        List<String> wanted = new ArrayList<>(List.of(D, E, B, key('9')));
        Map<String, ReachableUser> fromProperties = properties.getUnreachedUsers(OBSERVER, wanted);
        Map<String, ReachableUser> fromScorecards = scorecards.getUnreachedUsers(OBSERVER, wanted);
        assertEquals(fromProperties, fromScorecards);

        int unreachable = (int) Constants.UNREACHABLE_HOPS;
        assertEquals(new ReachableUser(unreachable, 0.2, 3L, 1L, null), fromScorecards.get(D));
        assertEquals(new ReachableUser(unreachable, null, null, null, "IMPERSONATION"), fromScorecards.get(E));
        assertEquals(java.util.Set.of(D, E, B), fromScorecards.keySet());
    }

    @Test
    void aGraphIdTheTableDoesNotKnowFailsTheRun() throws Exception {
        try (Session session = driver.session()) {
            session.run("MATCH (n:NostrUser {pubkey: $pubkey}) SET n.user_id = 2147483000",
                    Values.parameters("pubkey", C)).consume();
            assertThrows(IllegalStateException.class, () -> scorecards.getReachableUsers(OBSERVER));
        } finally {
            try (Connection connection = postgres(); Statement statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT id FROM nostr_user WHERE pubkey = '" + C + "'")) {
                rows.next();
                int id = rows.getInt(1);
                try (Session session = driver.session()) {
                    session.run("MATCH (n:NostrUser {pubkey: $pubkey}) SET n.user_id = $id",
                            Values.parameters("pubkey", C, "id", id)).consume();
                }
            }
        }
    }
}
