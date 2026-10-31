package com.nosfabrica.graperank.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.params.ZAddParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A job taken off its lane stays claimed in Redis until its result is published, so a worker that
 * dies mid-run (deploy, crash, OOM) doesn't lose it: any worker puts a claim whose heartbeat went
 * stale back at the front of its lane. */
final class JobLease {

    static final String CLAIMS = "jobs:claims";         // private_id -> {"lane", "message"}
    static final String HEARTBEATS = "jobs:heartbeats"; // private_id -> last heartbeat, epoch ms
    static final long LEASE_MS = 180_000;
    static final long HEARTBEAT_MS = 30_000;
    static final long REAP_EVERY_MS = 60_000;
    // A job picked up this many times without finishing is failed instead of re-queued.
    static final int MAX_ATTEMPTS = 3;
    static final int ATTEMPTS_TTL_SECONDS = 24 * 60 * 60;

    private static final ObjectMapper mapper = new ObjectMapper();

    // Atomic so that of several workers reaping at once only one re-queues a claim.
    private static final String REQUEUE_IF_STALE =
            "local beat = redis.call('ZSCORE', KEYS[2], ARGV[1]) "
            + "if not beat or tonumber(beat) > tonumber(ARGV[2]) then return 0 end "
            + "local claim = redis.call('HGET', KEYS[1], ARGV[1]) "
            + "redis.call('HDEL', KEYS[1], ARGV[1]) "
            + "redis.call('ZREM', KEYS[2], ARGV[1]) "
            + "if claim then local c = cjson.decode(claim) redis.call('LPUSH', c.lane, c.message) end "
            + "return 1";

    private JobLease() {}

    static String attemptsKey(int privateId) {
        return "jobs:attempts:" + privateId;
    }

    /** Records the claim; returns how many times this job has now been picked up. */
    static long claim(Jedis redis, int privateId, String lane, String message, long now)
            throws JsonProcessingException {
        String id = String.valueOf(privateId);
        String claim = mapper.writeValueAsString(Map.of("lane", lane, "message", message));
        Transaction tx = redis.multi();
        tx.hset(CLAIMS, id, claim);
        tx.zadd(HEARTBEATS, now, id);
        Response<Long> attempts = tx.incr(attemptsKey(privateId));
        tx.expire(attemptsKey(privateId), ATTEMPTS_TTL_SECONDS);
        tx.exec();
        return attempts.get();
    }

    static void heartbeat(Jedis redis, int privateId, long now) {
        redis.zadd(HEARTBEATS, now, String.valueOf(privateId), ZAddParams.zAddParams().xx());
    }

    /** The job finished: its result or failure is published. */
    static void release(Jedis redis, int privateId) {
        String id = String.valueOf(privateId);
        Transaction tx = redis.multi();
        tx.hdel(CLAIMS, id);
        tx.zrem(HEARTBEATS, id);
        tx.del(attemptsKey(privateId));
        tx.exec();
    }

    /** Puts every claim whose heartbeat is older than the lease back on its lane; returns their ids. */
    static List<String> requeueStale(Jedis redis, long now) {
        long cutoff = now - LEASE_MS;
        List<String> requeued = new ArrayList<>();
        for (String id : redis.zrangeByScore(HEARTBEATS, 0, cutoff)) {
            Object done = redis.eval(REQUEUE_IF_STALE, List.of(CLAIMS, HEARTBEATS), List.of(id, String.valueOf(cutoff)));
            if (Long.valueOf(1L).equals(done)) {
                requeued.add(id);
            }
        }
        return requeued;
    }
}
