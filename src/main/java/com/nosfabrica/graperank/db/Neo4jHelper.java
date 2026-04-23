package com.nosfabrica.graperank.db;

import java.util.ArrayList;
import java.util.List;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;

public class Neo4jHelper implements IGraphDB {

    private final Driver driver;

    public Neo4jHelper(String uri, String username, String passwd) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(username, passwd));
    }

    @Override
    public List<String> getUsersConnectedToObserver(String observer, Integer hopsLimit) {
        String hopsLimitStr = (hopsLimit != null) ? hopsLimit.toString() : "";

        String query = "MATCH (user:NostrUser {pubkey: $pubkey})-[:FOLLOWS*1.." + hopsLimitStr +
                "]->(other:NostrUser) " +
                "WHERE other <> user " +
                "RETURN DISTINCT elementId(other) AS node_id, other.pubkey AS pubkey";

        List<String> resultList = new ArrayList<>();

        try (Session session = driver.session()) {

            List<Record> result = session.readTransaction(tx -> {
                Result statementResult = tx.run(query, Values.parameters("pubkey", observer));
                return statementResult.list();
            });

            if (result != null && !result.isEmpty()) {
                String observerNodeId = getNodeIdByPubkey(observer); // Get observer node ID

                if (observerNodeId != null) {
                    resultList.add(observer);

                    for (Record record : result) {
                        String pubkey = record.get("pubkey").asString();
                        resultList.add(pubkey);
                    }
                }
            }
        }

        return resultList;
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