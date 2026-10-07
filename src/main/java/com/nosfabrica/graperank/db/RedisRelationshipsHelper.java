package com.nosfabrica.graperank.db;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RedisRelationshipsHelper implements IRelationshipsCache, AutoCloseable {

    private static final String FOLLOWED_BY_KEY_PREFIX = "followed_by:";
    private static final String MUTED_BY_KEY_PREFIX = "muted_by:";
    private static final String REPORTED_BY_KEY_PREFIX = "reported_by:";

    private final JedisPool pool;

    /** `connections`: the most batches fetched at once; size it to the gather's parallelism. */
    public RedisRelationshipsHelper(String host, int port, int connections) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(connections);
        config.setMaxIdle(connections);
        // A pooled connection can outlive a Redis restart; PING it before use instead of failing a run.
        config.setTestOnBorrow(true);
        config.setMaxWait(Duration.ofSeconds(30));
        this.pool = new JedisPool(config, host, port);
    }

    @Override
    public void close() {
        pool.close();
    }

    @Override
    public List<RelationshipInfo> getIncomingFollowsBulk(List<String> pubkeys) {
        return fetch(pubkeys, FOLLOWED_BY_KEY_PREFIX).get(0);
    }

    @Override
    public List<RelationshipInfo> getIncomingMutesBulk(List<String> pubkeys) {
        return fetch(pubkeys, MUTED_BY_KEY_PREFIX).get(0);
    }

    @Override
    public List<RelationshipInfo> getIncomingReportsBulk(List<String> pubkeys) {
        return fetch(pubkeys, REPORTED_BY_KEY_PREFIX).get(0);
    }

    /** One pipelined round trip for all three reverse sets of the batch. */
    @Override
    public IncomingRelationships getIncomingBulk(List<String> pubkeys) {
        List<List<RelationshipInfo>> sets =
                fetch(pubkeys, FOLLOWED_BY_KEY_PREFIX, MUTED_BY_KEY_PREFIX, REPORTED_BY_KEY_PREFIX);
        return new IncomingRelationships(sets.get(0), sets.get(1), sets.get(2));
    }

    /** `SMEMBERS <prefix><pubkey>` for every pubkey and prefix, in one pipeline on a pooled
     * connection. One list per prefix, in pubkey order. */
    private List<List<RelationshipInfo>> fetch(List<String> pubkeys, String... prefixes) {
        if (pubkeys == null) pubkeys = List.of();
        List<List<Response<Set<String>>>> responses = new ArrayList<>();
        for (int k = 0; k < prefixes.length; k++) responses.add(new ArrayList<>(pubkeys.size()));
        if (!pubkeys.isEmpty()) {
            try (Jedis jedis = pool.getResource()) {
                Pipeline pipeline = jedis.pipelined();
                for (String pk : pubkeys) {
                    for (int k = 0; k < prefixes.length; k++) {
                        responses.get(k).add(pipeline.smembers(prefixes[k] + pk));
                    }
                }
                pipeline.sync();
            }
        }
        List<List<RelationshipInfo>> out = new ArrayList<>();
        for (int k = 0; k < prefixes.length; k++) {
            out.add(toRelationships(pubkeys, responses.get(k), RELATIONSHIP_OF.get(prefixes[k])));
        }
        return out;
    }

    private static final Map<String, String> RELATIONSHIP_OF = Map.of(
            FOLLOWED_BY_KEY_PREFIX, "FOLLOWS",
            MUTED_BY_KEY_PREFIX, "MUTES",
            REPORTED_BY_KEY_PREFIX, "REPORTS");

    private static List<RelationshipInfo> toRelationships(
            List<String> pubkeys, List<Response<Set<String>>> responses, String relationshipType) {
        List<RelationshipInfo> out = new ArrayList<>();
        for (int i = 0; i < pubkeys.size(); i++) {
            Set<String> sources = responses.get(i).get();
            if (sources == null) continue;
            for (String source : sources) {
                out.add(new RelationshipInfo(source, relationshipType, pubkeys.get(i)));
            }
        }
        return out;
    }
}
