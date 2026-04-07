package org.example.grape;

import java.util.ArrayList;
import java.util.List;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.neo4j.driver.util.Pair;

public class Neo4jHelper {

    private final Driver driver;

    public Neo4jHelper() {
        String uri = System.getenv("NEO4J_URL");
        // Authentication credentials
        String username =System.getenv("NEO4J_USERNAME");
        String password = System.getenv("NEO4J_PASSWORD");

        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }

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

    public List<RelationshipInfo> getIncomingFollowRelationshipsBulk(List<String> pubkeys) {
        String query =
                "UNWIND $pubkeys AS pubkey " +
                "MATCH (u:NostrUser {pubkey: pubkey}) " +
                "MATCH (source:NostrUser)-[r:FOLLOWS]->(u) " +
                "RETURN source.pubkey AS source, " +
                "       type(r) AS relationship, " +
                "       u.pubkey AS target";

        List<RelationshipInfo> resultList = new ArrayList<>();

        try (Session session = driver.session()) {
            session.executeRead(tx -> {
                Result result = tx.run(query, Values.parameters("pubkeys", pubkeys));

                while (result.hasNext()) {
                    Record record = result.next();
                    resultList.add(new RelationshipInfo(
                            record.get("source").asString(),
                            record.get("relationship").asString(),
                            record.get("target").asString()
                    ));
                }
                return null;
            });
        }

        return resultList;
    }

    public List<RelationshipInfo> getIncomingReportRelationshipsBulk(List<String> pubkeys) {
        String query =
                "UNWIND $pubkeys AS pubkey " +
                "MATCH (u:NostrUser {pubkey: pubkey}) " +
                "MATCH (source:NostrUser)-[r:REPORTS]->(u) " +
                "RETURN source.pubkey AS source, " +
                "       type(r) AS relationship, " +
                "       u.pubkey AS target";

        List<RelationshipInfo> resultList = new ArrayList<>();

        try (Session session = driver.session()) {
            session.executeRead(tx -> {
                Result result = tx.run(query, Values.parameters("pubkeys", pubkeys));

                while (result.hasNext()) {
                    Record record = result.next();
                    resultList.add(new RelationshipInfo(
                            record.get("source").asString(),
                            record.get("relationship").asString(),
                            record.get("target").asString()
                    ));
                }
                return null;
            });
        }

        return resultList;
    }


    public List<RelationshipInfo> getOutgoingRelationshipsBulk(List<String> pubkeys) {
        String query =
                "UNWIND $pubkeys AS pubkey " +
                        "MATCH (u:NostrUser {pubkey: pubkey}) " +
                        "MATCH (u)-[r:FOLLOWS|REPORTS|MUTES]->(target:NostrUser) " +
                        "RETURN u.pubkey AS source, " +
                        "       type(r) AS relationship, " +
                        "       target.pubkey AS target";

        List<RelationshipInfo> resultList = new ArrayList<>();

        try (Session session = driver.session()) {
            session.executeRead(tx -> {
                Result result = tx.run(query, Values.parameters("pubkeys", pubkeys));

                while (result.hasNext()) {
                    Record record = result.next();
                    resultList.add(new RelationshipInfo(
                            record.get("source").asString(),
                            record.get("relationship").asString(),
                            record.get("target").asString()
                    ));
                }
                return null;
            });
        }

        return resultList;
    }

    public class DistanceInfo {
        private String sourcePubkey;
        private String targetPubkey;
        private double distance;

        public DistanceInfo(String sourcePubkey, String targetPubkey, double distance) {
            this.sourcePubkey = sourcePubkey;
            this.targetPubkey = targetPubkey;
            this.distance = distance;
        }


        public String getSourcePubkey() { return sourcePubkey; }
        public String getTargetPubkey() { return targetPubkey; }
        public double getDistance() { return distance; }

        @Override
        public String toString() {
            return "DistanceInfo{" +
                    "source='" + sourcePubkey + '\'' +
                    ", target='" + targetPubkey + '\'' +
                    ", distance='" + distance + '\'' +
                    '}';
        }
    }

    public static class RelationshipInfo {
        private String source;
        private String relationship;
        private String target;

        public RelationshipInfo() {}

        public RelationshipInfo(String source, String relationship, String target) {
            this.source = source;
            this.relationship = relationship;
            this.target = target;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getRelationship() {
            return relationship;
        }

        public void setRelationship(String relationship) {
            this.relationship = relationship;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        @Override
        public String toString() {
            return "RelationshipInfo{" +
                    "source='" + source + '\'' +
                    ", relationship='" + relationship + '\'' +
                    ", target='" + target + '\'' +
                    '}';
        }
    }
}