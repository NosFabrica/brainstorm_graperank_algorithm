package org.example.grape;

import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RedisHelper {

    private static final int CACHE_TTL_SECONDS = 30 * 60; // 30 minutes
    private static final String KEY_PREFIX = "graperank:rels:";

    private final JedisPool pool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedisHelper() {
        String redisUrl = System.getenv("REDIS_URL");
        if (redisUrl == null || redisUrl.isEmpty()) {
            redisUrl = "redis://localhost:6379";
        }
        try {
            this.pool = new JedisPool(new URI(redisUrl));
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to Redis at " + redisUrl, e);
        }
    }

    public static class CachedUserRelationships {
        public List<Neo4jHelper.RelationshipInfo> outgoing;
        public List<Neo4jHelper.RelationshipInfo> incomingFollow;
        public List<Neo4jHelper.RelationshipInfo> incomingReport;

        public CachedUserRelationships() {}

        public CachedUserRelationships(
                List<Neo4jHelper.RelationshipInfo> outgoing,
                List<Neo4jHelper.RelationshipInfo> incomingFollow,
                List<Neo4jHelper.RelationshipInfo> incomingReport) {
            this.outgoing = outgoing;
            this.incomingFollow = incomingFollow;
            this.incomingReport = incomingReport;
        }
    }

    /**
     * Fetches cached relationships for a batch of pubkeys in a single MGET round trip.
     * Returns a map of pubkey -> cached data; pubkeys that were not in cache are absent from the map.
     */
    public Map<String, CachedUserRelationships> getBulk(List<String> pubkeys) {
        Map<String, CachedUserRelationships> result = new HashMap<>();
        if (pubkeys.isEmpty()) return result;

        String[] keys = pubkeys.stream()
                .map(p -> KEY_PREFIX + p)
                .toArray(String[]::new);

        try (Jedis jedis = pool.getResource()) {
            List<String> values = jedis.mget(keys);
            for (int i = 0; i < pubkeys.size(); i++) {
                String json = values.get(i);
                if (json != null) {
                    try {
                        result.put(pubkeys.get(i), objectMapper.readValue(json, CachedUserRelationships.class));
                    } catch (Exception e) {
                        System.err.println("Redis deserialize failed for " + pubkeys.get(i) + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Redis MGET failed: " + e.getMessage());
        }
        return result;
    }

    /**
     * Writes a batch of pubkey -> relationships entries in a single pipelined round trip.
     */
    public void setBulk(Map<String, CachedUserRelationships> entries) {
        if (entries.isEmpty()) return;

        try (Jedis jedis = pool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            for (Map.Entry<String, CachedUserRelationships> entry : entries.entrySet()) {
                try {
                    String json = objectMapper.writeValueAsString(entry.getValue());
                    pipeline.setex(KEY_PREFIX + entry.getKey(), CACHE_TTL_SECONDS, json);
                } catch (Exception e) {
                    System.err.println("Redis serialize failed for " + entry.getKey() + ": " + e.getMessage());
                }
            }
            pipeline.sync();
        } catch (Exception e) {
            System.err.println("Redis pipeline SET failed: " + e.getMessage());
        }
    }

    public void close() {
        pool.close();
    }
}
