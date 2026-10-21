package com.nosfabrica.graperank.db;

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

        String stored = storedPropsReturn("other", observer);

        // One pruning BFS for distance, reach and the stored scores. Group on the node, not on
        // its properties: grouping on `other.influence_*` returns wrong values on Neo4j 5.26.
        String query = "MATCH p = (user:NostrUser {pubkey: $pubkey})-[:FOLLOWS*1..]->(other:NostrUser) " +
                "WHERE other <> user " +
                "WITH other, min(length(p)) AS hops " +
                "RETURN other.pubkey AS pubkey, hops, " + stored;

        Map<String, ReachableUser> result = new HashMap<>();

        try (Session session = driver.session()) {
            List<Record> records = session.readTransaction(tx -> {
                Result statementResult = tx.run(query, Values.parameters("pubkey", observer));
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

    private ReachableUser getObserver(String observer) {
        String query = "MATCH (other:NostrUser {pubkey: $pubkey}) RETURN " + storedPropsReturn("other", observer) + " LIMIT 1";
        try (Session session = driver.session()) {
            Record record = session.readTransaction(tx -> {
                Result statementResult = tx.run(query, Values.parameters("pubkey", observer));
                return statementResult.single();
            });
            return reachableUser(0, record);
        }
    }

    // Property names interpolated: `observer` is validated hex.
    private static String storedPropsReturn(String node, String observer) {
        return node + ".influence_" + observer + " AS influence, " +
                node + ".hops_" + observer + " AS storedHops, " +
                node + ".trusted_followers_" + observer + " AS storedTrustedFollowers";
    }

    private static ReachableUser reachableUser(int hops, Record record) {
        Value influence = record.get("influence");
        return new ReachableUser(
                hops,
                influence.isNull() ? null : influence.asDouble(),
                storedInteger(record.get("storedHops")),
                storedInteger(record.get("storedTrustedFollowers")));
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