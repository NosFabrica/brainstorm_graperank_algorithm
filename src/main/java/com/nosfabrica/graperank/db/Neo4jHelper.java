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
    public Map<String, String> getPenalisedNip05Statuses(Collection<String> pubkeys) {
        Map<String, String> result = new HashMap<>();
        if (pubkeys == null || pubkeys.isEmpty()) return result;
        String query = "UNWIND $pubkeys AS pk MATCH (u:NostrUser {pubkey: pk}) " +
                "WHERE u.nip05_status IN $penalised RETURN u.pubkey AS pubkey, u.nip05_status AS status";
        try (Session session = driver.session()) {
            List<Record> records = session.readTransaction(tx -> tx.run(query, Values.parameters(
                    "pubkeys", List.copyOf(pubkeys),
                    "penalised", List.copyOf(com.nosfabrica.graperank.grape.Constants.PENALISED_NIP05_STATUSES))).list());
            for (Record record : records) result.put(record.get("pubkey").asString(), record.get("status").asString());
        }
        return result;
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