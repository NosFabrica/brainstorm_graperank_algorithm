package com.nosfabrica.graperank.db;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class RedisRelationshipsHelper implements IRelationshipsCache {

    private static final String FOLLOWED_BY_KEY_PREFIX = "followed_by:";
    private static final String MUTED_BY_KEY_PREFIX = "muted_by:";
    private static final String REPORTED_BY_KEY_PREFIX = "reported_by:";

    private final String host;
    private final int port;

    public RedisRelationshipsHelper(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public List<RelationshipInfo> getIncomingFollowsBulk(List<String> pubkeys) {
        return getIncomingBulk(pubkeys, FOLLOWED_BY_KEY_PREFIX, "FOLLOWS");
    }

    @Override
    public List<RelationshipInfo> getIncomingMutesBulk(List<String> pubkeys) {
        return getIncomingBulk(pubkeys, MUTED_BY_KEY_PREFIX, "MUTES");
    }

    @Override
    public List<RelationshipInfo> getIncomingReportsBulk(List<String> pubkeys) {
        return getIncomingBulk(pubkeys, REPORTED_BY_KEY_PREFIX, "REPORTS");
    }

    private List<RelationshipInfo> getIncomingBulk(List<String> pubkeys, String keyPrefix, String relationshipType) {
        List<RelationshipInfo> out = new ArrayList<>();
        if (pubkeys == null || pubkeys.isEmpty()) {
            return out;
        }

        try (Jedis jedis = new Jedis(host, port)) {
            Pipeline pipeline = jedis.pipelined();
            List<Response<Set<String>>> responses = new ArrayList<>(pubkeys.size());
            for (String pk : pubkeys) {
                responses.add(pipeline.smembers(keyPrefix + pk));
            }
            pipeline.sync();

            for (int i = 0; i < pubkeys.size(); i++) {
                String target = pubkeys.get(i);
                Set<String> sources = responses.get(i).get();
                if (sources == null || sources.isEmpty()) {
                    continue;
                }
                for (String source : sources) {
                    out.add(new RelationshipInfo(source, relationshipType, target));
                }
            }
        }

        return out;
    }
}
