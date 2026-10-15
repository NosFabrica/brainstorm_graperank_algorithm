package com.nosfabrica.graperank.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/**
 * Stores a run's result once and queues a small reference to it on every result queue.
 * The server deletes the payload once every queue named in {@code <key>:pending} has released it;
 * the TTL only catches leaks.
 */
public final class ResultPayload {

    static final String[] QUEUES = {
            "results_message_queue",
            "nostr_results_message_queue",
            "write_neo4j_message_queue",
    };

    // Starts at write time, so it must outlast the longest plausible consumer outage.
    static final long TTL_SECONDS = 7L * 24 * 60 * 60;

    // One SET would make Redis hold the whole command buffer next to the stored value.
    static final int WRITE_CHUNK_BYTES = 8 * 1024 * 1024;

    private static final ObjectMapper mapper = new ObjectMapper();

    private ResultPayload() {}

    // UUID: a re-run of the same privateId must not reset the pending set of a key still queued.
    static String newKey(int privateId) {
        return "graperank:result:" + privateId + ":" + UUID.randomUUID();
    }

    static String envelope(int privateId, String key) throws JsonProcessingException {
        return mapper.writeValueAsString(Map.of("private_id", privateId, "result_key", key));
    }

    static void publish(Jedis redis, int privateId, String resultJson) throws JsonProcessingException {
        String key = newKey(privateId);
        String envelope = envelope(privateId, key);
        String pending = key + ":pending";
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

        // Written before any envelope points at the key, so a partial payload is never read.
        byte[] payload = resultJson.getBytes(StandardCharsets.UTF_8);
        for (int start = 0; start < payload.length; start += WRITE_CHUNK_BYTES) {
            redis.append(keyBytes, Arrays.copyOfRange(payload, start, Math.min(payload.length, start + WRITE_CHUNK_BYTES)));
            if (start == 0) {
                redis.expire(keyBytes, TTL_SECONDS); // a worker dying mid-write leaves only an expiring key
            }
        }

        Transaction tx = redis.multi();
        tx.sadd(pending, QUEUES);
        tx.expire(pending, TTL_SECONDS);
        for (String queue : QUEUES) {
            tx.rpush(queue, envelope);
        }
        tx.exec();
    }
}
