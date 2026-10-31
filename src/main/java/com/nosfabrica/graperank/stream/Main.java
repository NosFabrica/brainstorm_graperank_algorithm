package com.nosfabrica.graperank.stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nosfabrica.graperank.db.Neo4jHelper;
import com.nosfabrica.graperank.db.RedisRelationshipsHelper;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.nosfabrica.graperank.exceptions.ErrorCode;
import com.nosfabrica.graperank.exceptions.GrapeRankAlgorithmException;
import com.nosfabrica.graperank.grape.GrapeRankAlgorithm;
import com.nosfabrica.graperank.grape.GrapeRankError;
import com.nosfabrica.graperank.grape.GrapeRankParams;
import com.nosfabrica.graperank.grape.GrapeRankResult;

public class Main {

    private static final String[] QUEUE_NAMES = Scheduler.resolve();
    private static final String JOB_STARTED_QUEUE_NAME = "job_started_queue";
    private static final String RESULTS_QUEUE_NAME = "results_message_queue";

    private static final String REDIS_HOST = System.getenv("REDIS_HOST");
    private static final int REDIS_PORT = Integer.parseInt(System.getenv("REDIS_PORT"));
    // Jedis defaults to 2s; a busy Redis took longer than that to ack a large result write.
    private static final int RESULT_WRITE_TIMEOUT_MS = 60_000;

    private static final String NEO4J_URL = System.getenv("NEO4J_URL");
    private static final String NEO4J_USERNAME = System.getenv("NEO4J_USERNAME");
    private static final String NEO4J_PASSWORD = System.getenv("NEO4J_PASSWORD");

    private static final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true);
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    // Heartbeats for the running job and the stale-claim reaper (JobLease).
    private static final ScheduledExecutorService leaseTimer = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "job-lease");
        t.setDaemon(true);
        return t;
    });

    // One driver per process: a driver per message leaked its pool and native buffers.
    private static final Neo4jHelper neo4jHelper = new Neo4jHelper(NEO4J_URL, NEO4J_USERNAME, NEO4J_PASSWORD);
    private static final RedisRelationshipsHelper relationshipsCache = new RedisRelationshipsHelper(REDIS_HOST, REDIS_PORT);

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(neo4jHelper::close));
        Runtime.getRuntime().addShutdownHook(new Thread(relationshipsCache::close));
        leaseTimer.scheduleAtFixedRate(Main::requeueStaleClaims, 0, JobLease.REAP_EVERY_MS, TimeUnit.MILLISECONDS);

        while (true) { // reconnect loop
            try (Jedis redis = new Jedis(REDIS_HOST, REDIS_PORT)) {
                System.out.println("Connected to Redis. Waiting for priority queues: " + Arrays.toString(QUEUE_NAMES));

                while (true) { // consume loop
                    try {
                        // timeout = 30 seconds instead of 0
                        List<String> result = redis.blpop(30, QUEUE_NAMES);

                        if (result != null && result.size() == 2) {
                            runClaimed(redis, result.get(0), result.get(1));
                        }

                    } catch (JedisConnectionException e) {
                        System.err.println("Redis connection lost, will reconnect: " + e.getMessage());
                        break; // exit inner loop to reconnect
                    } catch (Exception e) {
                        System.err.println("Error processing message:");
                        e.printStackTrace();
                    }
                }

            } catch (Exception e) {
                System.err.println("Failed to connect to Redis, retrying in 2s...");
                e.printStackTrace();
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    /** Runs a job under a claim (JobLease): released only once its result or failure is published,
     * so a worker that dies mid-run leaves the job for another worker to pick up again. */
    private static void runClaimed(Jedis redis, String lane, String message) throws Exception {
        int privateId;
        try {
            privateId = mapper.readTree(message).get("private_id").asInt();
        } catch (Exception e) {
            processMessage(message); // unparseable: nothing to claim it by
            return;
        }

        long attempts = JobLease.claim(redis, privateId, lane, message, System.currentTimeMillis());
        if (attempts > JobLease.MAX_ATTEMPTS) {
            System.err.println("Job " + privateId + " picked up " + attempts + " times without finishing, marking FAILED");
            GrapeRankError error = new GrapeRankError(ErrorCode.WORKER_LOST,
                    "the worker running this job was lost " + (attempts - 1) + " times");
            if (pushFailureResult(redis, privateId, error)) {
                JobLease.release(redis, privateId);
            }
            return;
        }

        ScheduledFuture<?> beat = leaseTimer.scheduleAtFixedRate(() -> heartbeat(privateId),
                JobLease.HEARTBEAT_MS, JobLease.HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        boolean done;
        try {
            done = processMessage(message);
        } finally {
            beat.cancel(false);
        }
        if (done) {
            JobLease.release(redis, privateId);
        }
    }

    private static void heartbeat(int privateId) {
        try (Jedis redis = new Jedis(REDIS_HOST, REDIS_PORT)) {
            JobLease.heartbeat(redis, privateId, System.currentTimeMillis());
        } catch (Exception e) {
            System.err.println("Heartbeat failed for job " + privateId + ": " + e.getMessage());
        }
    }

    private static void requeueStaleClaims() {
        try (Jedis redis = new Jedis(REDIS_HOST, REDIS_PORT)) {
            List<String> requeued = JobLease.requeueStale(redis, System.currentTimeMillis());
            if (!requeued.isEmpty()) {
                System.out.println("Re-queued jobs whose worker was lost: " + requeued);
            }
        } catch (Exception e) {
            System.err.println("Stale-claim reaper failed: " + e.getMessage());
        }
    }

    private static GrapeRankParams resolveParams(JsonNode paramsNode) throws Exception {
        if (paramsNode == null || paramsNode.isNull()) {
            throw new IllegalArgumentException("graperank_params missing from payload");
        }
        return mapper.treeToValue(paramsNode, GrapeRankParams.class);
    }

    /** Optional: the pubkeys in the Observer's kind-10040. Absent or malformed
     * means none — designation is an addition to the run, never a reason to fail it. */
    private static List<String> resolveDesignatedPubkeys(JsonNode node) {
        List<String> pubkeys = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    pubkeys.add(item.asText());
                }
            }
        }
        return pubkeys;
    }

    private static boolean pushFailureResult(Jedis redis, int privateId, GrapeRankError error) {
        try {
            GrapeRankResult failure = new GrapeRankResult(null, null, 0.0, false, error);
            MessageQueueReturnValue msg = new MessageQueueReturnValue(failure, privateId);
            redis.rpush(RESULTS_QUEUE_NAME, mapper.writeValueAsString(msg));
            System.err.println("Pushed failure result for privateId " + privateId + ": " + error.getCode() + " - " + error.getMessage());
            return true;
        } catch (Exception e) {
            System.err.println("Failed to push failure result for privateId " + privateId + ": " + e.getMessage());
            return false;
        }
    }

    private static void processJobStarted(int privateId) {
        try (Jedis redis = new Jedis(REDIS_HOST, REDIS_PORT)) {
            System.out.println("Setting job as ongoing: " + privateId);
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", privateId);

            String finalJson = mapper.writeValueAsString(payload);

            redis.rpush(JOB_STARTED_QUEUE_NAME, finalJson);
            System.out.println("Finished setting job as ongoing: " + privateId);
        } catch (JedisConnectionException e) {
            System.err.println("Redis connection lost during job started:");
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Error setting job as ongoing:");
            e.printStackTrace();
        }
    }

    /** True once the run's result or failure is published. */
    private static boolean processMessage(String message) {
        try (Jedis redis = new Jedis(REDIS_HOST, REDIS_PORT, RESULT_WRITE_TIMEOUT_MS)) {
            JsonNode parsed = mapper.readTree(message);
            int privateId = parsed.get("private_id").asInt();
            String observer = parsed.get("parameters").asText();

            GrapeRankParams params;
            try {
                params = resolveParams(parsed.get("graperank_params"));
            } catch (Exception e) {
                System.err.println("Malformed graperank_params for privateId " + privateId + ", marking FAILED: " + e.getMessage());
                return pushFailureResult(redis, privateId, new GrapeRankError(ErrorCode.MALFORMED_PARAMS, e.getMessage()));
            }

            List<String> designatedPubkeys = resolveDesignatedPubkeys(parsed.get("designated_pubkeys"));

            System.out.println("Processing message: " + privateId
                    + " (" + designatedPubkeys.size() + " designated keys)");

            processJobStarted(privateId);


            GrapeRankResult result;
            try {
                GrapeRankAlgorithm helper = new GrapeRankAlgorithm(neo4jHelper, relationshipsCache);
                result = helper.graperankAllSteps(observer, params, designatedPubkeys);
            } catch (Exception e) {
                System.err.println("Algorithm failed for privateId " + privateId + ", marking FAILED: " + e.getMessage());
                e.printStackTrace();
                return pushFailureResult(redis, privateId, GrapeRankAlgorithmException.fromThrowable(e).toError());
            }


            MessageQueueReturnValue finalMessage = new MessageQueueReturnValue(result, privateId);
            String finalJson = mapper.writeValueAsString(finalMessage);

            ResultPayload.publish(redis, privateId, finalJson);

            System.out.println("Finished processing: " + privateId);
            return true;

        } catch (JedisConnectionException e) {
            System.err.println("Redis connection lost during message processing:");
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Error processing message:");
            e.printStackTrace();
        }
        return false;
    }
}
