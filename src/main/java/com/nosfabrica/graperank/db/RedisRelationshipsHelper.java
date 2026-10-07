package com.nosfabrica.graperank.db;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RedisRelationshipsHelper implements IRelationshipsCache, AutoCloseable {

    /** One pooled connection per concurrent batch. */
    private static final int CONNECTIONS = 4;

    private enum ReverseSet {
        FOLLOWED_BY("followed_by:", "FOLLOWS"),
        MUTED_BY("muted_by:", "MUTES"),
        REPORTED_BY("reported_by:", "REPORTS");

        final String keyPrefix;
        final String relationship;

        ReverseSet(String keyPrefix, String relationship) {
            this.keyPrefix = keyPrefix;
            this.relationship = relationship;
        }
    }

    private final JedisPool pool;

    public RedisRelationshipsHelper(String host, int port) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(CONNECTIONS);
        config.setMaxIdle(CONNECTIONS);
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
    public int maxConcurrentFetches() {
        return CONNECTIONS;
    }

    @Override
    public List<RelationshipInfo> getIncomingFollowsBulk(List<String> pubkeys) {
        return fetch(pubkeys, ReverseSet.FOLLOWED_BY).get(ReverseSet.FOLLOWED_BY);
    }

    @Override
    public List<RelationshipInfo> getIncomingMutesBulk(List<String> pubkeys) {
        return fetch(pubkeys, ReverseSet.MUTED_BY).get(ReverseSet.MUTED_BY);
    }

    @Override
    public List<RelationshipInfo> getIncomingReportsBulk(List<String> pubkeys) {
        return fetch(pubkeys, ReverseSet.REPORTED_BY).get(ReverseSet.REPORTED_BY);
    }

    /** One pipelined round trip for all three reverse sets of the batch. */
    @Override
    public IncomingRelationships getIncomingBulk(List<String> pubkeys) {
        Map<ReverseSet, List<RelationshipInfo>> sets =
                fetch(pubkeys, ReverseSet.FOLLOWED_BY, ReverseSet.MUTED_BY, ReverseSet.REPORTED_BY);
        return new IncomingRelationships(
                sets.get(ReverseSet.FOLLOWED_BY),
                sets.get(ReverseSet.MUTED_BY),
                sets.get(ReverseSet.REPORTED_BY));
    }

    /** `SMEMBERS` of each reverse set for every pubkey, in one pipeline on a pooled connection.
     * Lists keep pubkey order, then Redis member order. */
    private Map<ReverseSet, List<RelationshipInfo>> fetch(List<String> pubkeys, ReverseSet... sets) {
        List<String> targets = pubkeys == null ? List.of() : pubkeys;
        Map<ReverseSet, List<Response<Set<String>>>> responses = new EnumMap<>(ReverseSet.class);
        for (ReverseSet set : sets) responses.put(set, new ArrayList<>(targets.size()));
        if (!targets.isEmpty()) {
            try (Jedis jedis = pool.getResource()) {
                Pipeline pipeline = jedis.pipelined();
                for (String pk : targets) {
                    for (ReverseSet set : sets) {
                        responses.get(set).add(pipeline.smembers(set.keyPrefix + pk));
                    }
                }
                pipeline.sync();
            }
        }

        Map<ReverseSet, List<RelationshipInfo>> out = new EnumMap<>(ReverseSet.class);
        for (ReverseSet set : sets) {
            // Convert one set at a time and drop its raw replies, so they don't sit next to the result.
            out.put(set, toRelationships(targets, responses.remove(set), set.relationship));
        }
        return out;
    }

    private static List<RelationshipInfo> toRelationships(
            List<String> targets, List<Response<Set<String>>> responses, String relationship) {
        List<RelationshipInfo> out = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            Set<String> sources = responses.get(i).get();
            responses.set(i, null);
            if (sources == null) continue;
            for (String source : sources) {
                out.add(new RelationshipInfo(source, relationship, targets.get(i)));
            }
        }
        return out;
    }
}
