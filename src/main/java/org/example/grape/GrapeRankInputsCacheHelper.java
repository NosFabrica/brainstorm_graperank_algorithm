package org.example.grape;

import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.Jedis;

public class GrapeRankInputsCacheHelper {

    private static final String CACHE_KEY = "graperank:inputs";
    private static final int CACHE_TTL_SECONDS = 30 * 60; // 30 minutes

    private final String redisHost;
    private final int redisPort;
    private final ObjectMapper mapper = new ObjectMapper();

    public GrapeRankInputsCacheHelper() {
        this.redisHost = System.getenv("REDIS_HOST");
        this.redisPort = Integer.parseInt(System.getenv("REDIS_PORT"));
    }

    public GrapeRankInputsCache getFromCache() {
        try (Jedis jedis = new Jedis(redisHost, redisPort)) {
            String json = jedis.get(CACHE_KEY);
            if (json == null) {
                return null;
            }
            return mapper.readValue(json, GrapeRankInputsCache.class);
        } catch (Exception e) {
            System.err.println("Failed to read graperank inputs from cache: " + e.getMessage());
            return null;
        }
    }

    public void saveToCache(GrapeRankInputsCache cache) {
        try (Jedis jedis = new Jedis(redisHost, redisPort)) {
            String json = mapper.writeValueAsString(cache);
            jedis.setex(CACHE_KEY, CACHE_TTL_SECONDS, json);
            System.out.println("Saved graperank inputs to cache ("
                    + cache.getInputs().size() + " ratees, "
                    + cache.getRaters().size() + " raters)");
        } catch (Exception e) {
            System.err.println("Failed to save graperank inputs to cache: " + e.getMessage());
        }
    }
}
