package com.nosfabrica.graperank.db;

import com.nosfabrica.graperank.grape.Constants;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An Observer's reach with the stored scores kept in the Scorecard database: Neo4j answers who
 * (by User id), how far and with which NIP-05 status; Postgres gives pubkeys and stored scores.
 * Same answers as [Neo4jHelper] reading score properties.
 */
public class ScorecardGraphDB implements IGraphDB {

    private final Neo4jHelper graph;
    private final PostgresHelper postgres;

    public ScorecardGraphDB(Neo4jHelper graph, PostgresHelper postgres) {
        this.graph = graph;
        this.postgres = postgres;
    }

    @Override
    public Map<String, ReachableUser> getReachableUsers(String observer) {
        if (!observer.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("observer pubkey must be 64 hex chars");
        }
        Map<String, ReachableUser> result = new HashMap<>();
        List<Neo4jHelper.Reached> reached = graph.walkByUserId(observer);
        if (reached.isEmpty()) return result;

        List<Integer> ids = new ArrayList<>(reached.size());
        for (Neo4jHelper.Reached user : reached) if (user.id() != null) ids.add(user.id());
        PostgresHelper.Stored stored = postgres.load(observer, ids);

        result.put(observer, reachableUser(0,
                postgres.scoresFor(observer, List.of(observer)).get(observer),
                graph.nip05StatusesOf(List.of(observer)).get(observer)));
        for (Neo4jHelper.Reached user : reached) {
            if (user.id() == null) {
                result.put(user.pubkey(), reachableUser(user.hops(), null, user.nip05Status()));
            } else {
                result.put(stored.pubkeys().get(user.id()), reachableUser(
                        user.hops(), stored.scores().get(user.id()), user.nip05Status()));
            }
        }
        return result;
    }

    @Override
    public Map<String, ReachableUser> getUnreachedUsers(String observer, Collection<String> pubkeys) {
        Map<String, ReachableUser> result = new HashMap<>();
        if (pubkeys == null || pubkeys.isEmpty()) return result;
        Map<String, StoredScore> stored = postgres.scoresFor(observer, pubkeys);
        Map<String, String> statuses = graph.nip05StatusesOf(pubkeys);
        int unreachable = (int) Constants.UNREACHABLE_HOPS;
        for (String pubkey : pubkeys) {
            // As the property path: a stored score, or a penalised status.
            if (stored.containsKey(pubkey) || Constants.PENALISED_NIP05_STATUSES.contains(statuses.getOrDefault(pubkey, ""))) {
                result.put(pubkey, reachableUser(unreachable, stored.get(pubkey), statuses.get(pubkey)));
            }
        }
        return result;
    }

    private static ReachableUser reachableUser(int hops, StoredScore score, String nip05Status) {
        if (score == null) return new ReachableUser(hops, null, null, null, nip05Status);
        return new ReachableUser(hops, score.influence(), score.hops(), score.trustedFollowers(), nip05Status);
    }
}
