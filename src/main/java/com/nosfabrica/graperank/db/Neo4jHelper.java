package com.nosfabrica.graperank.db;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.neo4j.driver.types.TypeSystem;

public class Neo4jHelper implements IGraphDB, AutoCloseable {

    private final Driver driver;

    public Neo4jHelper(String uri, String username, String passwd) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(username, passwd));
    }

    @Override
    public void close() {
        driver.close();
    }

    @Override
    public Map<String, ReachableUser> getReachableUsers(String observer) {
        if (!observer.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("observer pubkey must be 64 hex chars");
        }

        // One pruning BFS for distance, reach and the stored scores. Group on the node, not on
        // its properties: grouping on `other.influence_*` returns wrong values on Neo4j 5.26.
        String query = "MATCH p = (user:NostrUser {pubkey: $pubkey})-[:FOLLOWS*1..]->(other:NostrUser) " +
                "WHERE other <> user " +
                "WITH other, min(length(p)) AS hops " +
                "RETURN other.pubkey AS pubkey, hops, " + STORED_PROPS;

        Map<String, ReachableUser> result = new HashMap<>();

        try (Session session = driver.session()) {
            List<Record> records = session.readTransaction(tx -> {
                Result statementResult = tx.run(query, storedKeys(observer));
                return statementResult.list();
            });

            if (records != null && !records.isEmpty()) {
                result.put(observer, getObserver(observer));

                for (Record record : records) {
                    result.put(record.get("pubkey").asString(), reachableUser(record.get("hops").asInt(), record));
                }
            }
        }

        return result;
    }

    @Override
    public Map<String, ReachableUser> getUnreachedUsers(String observer, Collection<String> pubkeys) {
        Map<String, ReachableUser> result = new HashMap<>();
        if (pubkeys == null || pubkeys.isEmpty()) return result;
        String query = "UNWIND $pubkeys AS pk MATCH (other:NostrUser {pubkey: pk}) " +
                "WHERE other[$influenceKey] IS NOT NULL OR other[$hopsKey] IS NOT NULL " +
                "OR other[$trustedFollowersKey] IS NOT NULL OR other.nip05_status IN $penalised " +
                "RETURN other.pubkey AS pubkey, " + STORED_PROPS;
        Map<String, Object> params = new HashMap<>(storedKeys(observer).asMap());
        params.put("pubkeys", List.copyOf(pubkeys));
        params.put("penalised", List.copyOf(com.nosfabrica.graperank.grape.Constants.PENALISED_NIP05_STATUSES));
        int unreachable = (int) com.nosfabrica.graperank.grape.Constants.UNREACHABLE_HOPS;
        try (Session session = driver.session()) {
            List<Record> records = session.readTransaction(tx -> tx.run(query, params).list());
            for (Record record : records) {
                result.put(record.get("pubkey").asString(), reachableUser(unreachable, record));
            }
        }
        return result;
    }

    // ----- graph-only reads, for stored scores kept in the Scorecard database (ScorecardGraphDB) -----
    //
    // `user_id` is cheap to read: it was added to each node after the score properties, so it
    // sits at the head of the property chain. `pubkey` is the oldest property and costs a walk
    // of the whole chain, and so does `nip05_status` while the score properties are still on
    // the nodes: the walk reads neither. A node without an id (it should not exist) is
    // returned by pubkey.

    private static final String ID_OR_PUBKEY =
            "other.user_id AS id, CASE WHEN other.user_id IS NULL THEN other.pubkey END AS pubkey";

    private static final List<String> PENALISED =
            List.copyOf(com.nosfabrica.graperank.grape.Constants.PENALISED_NIP05_STATUSES);

    /** A reached user by User id, or by pubkey when the node has no id. */
    public record Reached(Integer id, String pubkey, int hops) {
    }

    /** A user with a penalised NIP-05 status, by User id or by pubkey when the node has no id. */
    public record Penalised(Integer id, String pubkey, String status) {
    }

    /** The follow walk of `getReachableUsers`: ids and distances only. */
    public List<Reached> walkByUserId(String observer) {
        String query = "MATCH p = (user:NostrUser {pubkey: $pubkey})-[:FOLLOWS*1..]->(other:NostrUser) " +
                "WHERE other <> user " +
                "WITH other, min(length(p)) AS hops " +
                "RETURN " + ID_OR_PUBKEY + ", hops";
        try (Session session = driver.session()) {
            return session.readTransaction(tx -> tx.run(query, Values.parameters("pubkey", observer))
                    .list(record -> new Reached(
                            record.get("id").isNull() ? null : record.get("id").asInt(),
                            record.get("pubkey").isNull() ? null : record.get("pubkey").asString(),
                            record.get("hops").asInt())));
        }
    }

    /**
     * Everyone with a penalised NIP-05 status, through the `nostr_user_nip05_status` index the
     * server creates. Only a penalised status changes a score and few users have one, so this
     * stands in for one property read per reached user. Temporary: once the score properties
     * are off the nodes the walk can read the status itself and the index can go.
     */
    public List<Penalised> penalisedNip05() {
        String query = "MATCH (other:NostrUser) WHERE other.nip05_status IN $penalised " +
                "RETURN " + ID_OR_PUBKEY + ", other.nip05_status AS nip05Status";
        try (Session session = driver.session()) {
            return session.readTransaction(tx -> tx.run(query, Values.parameters("penalised", PENALISED))
                    .list(record -> new Penalised(
                            record.get("id").isNull() ? null : record.get("id").asInt(),
                            record.get("pubkey").isNull() ? null : record.get("pubkey").asString(),
                            record.get("nip05Status").asString())));
        }
    }

    /** The NIP-05 status of each of [pubkeys] that has one. */
    public Map<String, String> nip05StatusesOf(Collection<String> pubkeys) {
        String query = "UNWIND $pubkeys AS pk MATCH (other:NostrUser {pubkey: pk}) " +
                "WHERE other.nip05_status IS NOT NULL " +
                "RETURN pk AS pubkey, other.nip05_status AS nip05Status";
        Map<String, String> statuses = new HashMap<>();
        try (Session session = driver.session()) {
            for (Record record : session.readTransaction(tx -> tx.run(query,
                    Values.parameters("pubkeys", List.copyOf(pubkeys))).list())) {
                statuses.put(record.get("pubkey").asString(), record.get("nip05Status").asString());
            }
        }
        return statuses;
    }

    private ReachableUser getObserver(String observer) {
        String query = "MATCH (other:NostrUser {pubkey: $pubkey}) RETURN " + STORED_PROPS + " LIMIT 1";
        try (Session session = driver.session()) {
            Record record = session.readTransaction(tx -> {
                Result statementResult = tx.run(query, storedKeys(observer));
                return statementResult.single();
            });
            return reachableUser(0, record);
        }
    }

    // Keys as parameters, not in the query text: one cached plan for every observer.
    private static final String STORED_PROPS = "other[$influenceKey] AS influence, " +
            "other[$hopsKey] AS storedHops, other[$trustedFollowersKey] AS storedTrustedFollowers, " +
            "other.nip05_status AS nip05Status";

    private static Value storedKeys(String observer) {
        return Values.parameters(
                "pubkey", observer,
                "influenceKey", "influence_" + observer,
                "hopsKey", "hops_" + observer,
                "trustedFollowersKey", "trusted_followers_" + observer);
    }

    private static ReachableUser reachableUser(int hops, Record record) {
        Value influence = record.get("influence");
        Value nip05 = record.get("nip05Status");
        return new ReachableUser(
                hops,
                influence.isNull() ? null : influence.asDouble(),
                storedInteger(record.get("storedHops")),
                storedInteger(record.get("storedTrustedFollowers")),
                nip05.isNull() ? null : nip05.asString());
    }

    // A float-typed value (pre-cutover workers sent counts as floats) reads as absent, so it is rewritten as an integer.
    private static Long storedInteger(Value value) {
        return value.hasType(TypeSystem.getDefault().INTEGER()) ? value.asLong() : null;
    }

    public String getNodeIdByPubkey(String pubkey) {
        String query = "MATCH (u:NostrUser {pubkey: $pubkey}) " +
                "RETURN elementId(u) AS node_id LIMIT 1";

        try (Session session = driver.session()) {
            Record result = session.readTransaction(tx -> {
                Result statementResult = tx.run(query, Values.parameters("pubkey", pubkey));
                return statementResult.single();
            });

            if (result != null) {
                return result.get("node_id").asString();
            } else {
                return null;
            }
        }
    }

}