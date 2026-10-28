package com.nosfabrica.graperank.db;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;

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

        String influenceProp = "influence_" + observer;

        // One pruning BFS for distance, reach and previous Influence. Group on the node, not on
        // its properties: grouping on `other.influence_*` returns wrong values on Neo4j 5.26.
        String query = "MATCH p = (user:NostrUser {pubkey: $pubkey})-[:FOLLOWS*1..]->(other:NostrUser) " +
                "WHERE other <> user " +
                "WITH other, min(length(p)) AS hops " +
                "RETURN other.pubkey AS pubkey, hops, other." + influenceProp + " AS influence, " +
                "other.nip05_status AS nip05Status";

        Map<String, ReachableUser> result = new HashMap<>();

        try (Session session = driver.session()) {
            List<Record> records = session.readTransaction(tx -> {
                Result statementResult = tx.run(query, Values.parameters("pubkey", observer));
                return statementResult.list();
            });

            if (records != null && !records.isEmpty()) {
                result.put(observer, new ReachableUser(0, getInfluenceForPubkey(observer, influenceProp)));

                for (Record record : records) {
                    Value infValue = record.get("influence");
                    Double influence = infValue.isNull() ? null : infValue.asDouble();
                    Value nip05 = record.get("nip05Status");
                    result.put(record.get("pubkey").asString(), new ReachableUser(
                            record.get("hops").asInt(), influence, nip05.isNull() ? null : nip05.asString()));
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

    private Double getInfluenceForPubkey(String pubkey, String influenceProp) {
        String query = "MATCH (u:NostrUser {pubkey: $pubkey}) RETURN u." + influenceProp + " AS influence LIMIT 1";
        try (Session session = driver.session()) {
            Record record = session.readTransaction(tx -> {
                Result statementResult = tx.run(query, Values.parameters("pubkey", pubkey));
                return statementResult.single();
            });
            if (record == null) return null;
            Value v = record.get("influence");
            return v.isNull() ? null : v.asDouble();
        }
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