package com.nosfabrica.graperank.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nosfabrica.graperank.db.Neo4jHelper;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.nosfabrica.graperank.grape.GrapeRankAlgorithm;
import com.nosfabrica.graperank.grape.GrapeRankParams;
import com.nosfabrica.graperank.grape.GrapeRankPresets;
import com.nosfabrica.graperank.grape.GrapeRankResult;

public class Main {

    private static final String QUEUE_NAME = "message_queue";
    private static final String JOB_STARTED_QUEUE_NAME = "job_started_queue";
    private static final String RESULTS_QUEUE_NAME = "results_message_queue";
    private static final String UPLOAD_NOSTR_RESULTS_QUEUE_NAME = "nostr_results_message_queue";
    private static final String WRITE_NEO4J_RESULTS_QUEUE_NAME = "write_neo4j_message_queue";

    private static final String REDIS_HOST = System.getenv("REDIS_HOST");
    private static final int REDIS_PORT = Integer.parseInt(System.getenv("REDIS_PORT"));

    private static final String NEO4J_URL = System.getenv("NEO4J_URL");
    private static final String NEO4J_USERNAME = System.getenv("NEO4J_USERNAME");
    private static final String NEO4J_PASSWORD = System.getenv("NEO4J_PASSWORD");

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);

    public static void main(String[] args) {
        while (true) { // reconnect loop
            try (Jedis redis = new Jedis(REDIS_HOST, REDIS_PORT)) {
                System.out.println("Connected to Redis. Waiting for messages on '" + QUEUE_NAME + "'...");

                while (true) { // consume loop
                    try {
                        // timeout = 30 seconds instead of 0
                        List<String> result = redis.blpop(30, QUEUE_NAME);

                        if (result != null && result.size() == 2) {
                            String message = result.get(1);
                            processMessage(message);
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

    private static GrapeRankParams resolveParams(JsonNode node) {
        if (node == null || node.isNull()) {
            return GrapeRankPresets.DEFAULT;
        }
        try {
            JsonNode templateNode = node.get("template");
            String raw = templateNode != null ? templateNode.asText() : null;
            GrapeRankPresets.Template template = GrapeRankPresets.parseTemplate(raw);

            if (template != GrapeRankPresets.Template.CUSTOM) {
                return GrapeRankPresets.forTemplate(template);
            }

            return new GrapeRankParams(
                    node.get("rigor").asDouble(),
                    node.get("attenuation_factor").asDouble(),
                    node.get("follow_rating").asDouble(),
                    node.get("follow_confidence").asDouble(),
                    node.get("mute_rating").asDouble(),
                    node.get("mute_confidence").asDouble(),
                    node.get("report_rating").asDouble(),
                    node.get("report_confidence").asDouble(),
                    node.get("follow_confidence_of_observer").asDouble(),
                    node.get("verified_followers_influence_cutoff").asDouble(),
                    node.get("verified_reporters_influence_cutoff").asDouble(),
                    node.get("verified_muters_influence_cutoff").asDouble()
            );
        } catch (Exception e) {
            System.err.println("Failed to parse graperank_params, falling back to DEFAULT: " + e.getMessage());
            return GrapeRankPresets.DEFAULT;
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

    private static void processMessage(String message) {
        try (Jedis redis = new Jedis(REDIS_HOST, REDIS_PORT)) {
            JsonNode parsed = mapper.readTree(message);
            int privateId = parsed.get("private_id").asInt();
            String observer = parsed.get("parameters").asText();

            GrapeRankParams params = resolveParams(parsed.get("graperank_params"));

            System.out.println("Processing message: " + privateId);

            processJobStarted(privateId);

            GrapeRankAlgorithm helper = new GrapeRankAlgorithm(new Neo4jHelper(NEO4J_URL, NEO4J_USERNAME, NEO4J_PASSWORD));
            GrapeRankResult result = helper.graperankAllSteps(observer, params);

            MessageQueueReturnValue finalMessage = new MessageQueueReturnValue(result, privateId);
            String finalJson = mapper.writeValueAsString(finalMessage);

            redis.rpush(RESULTS_QUEUE_NAME, finalJson);
            redis.rpush(UPLOAD_NOSTR_RESULTS_QUEUE_NAME, finalJson);
            redis.rpush(WRITE_NEO4J_RESULTS_QUEUE_NAME, finalJson);

            System.out.println("Finished processing: " + privateId);

        } catch (JedisConnectionException e) {
            System.err.println("Redis connection lost during message processing:");
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Error processing message:");
            e.printStackTrace();
        }
    }
}