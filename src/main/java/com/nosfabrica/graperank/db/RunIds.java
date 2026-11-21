package com.nosfabrica.graperank.db;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The User ids one run has met, both ways. The follow walk fills it; the Redis sets, which name
 * users by id once scores are in Postgres, are read through it. A pubkey or id it has not met is
 * looked up in the Scorecard database on request and remembered for the rest of the run.
 * Used from the gather's fetch threads.
 */
public class RunIds {

    private final PostgresHelper postgres;
    private final Map<String, Integer> idByPubkey = new ConcurrentHashMap<>();
    private final Map<Integer, String> pubkeyById = new ConcurrentHashMap<>();

    public RunIds(PostgresHelper postgres) {
        this.postgres = postgres;
    }

    public void learn(Map<Integer, String> pubkeysById) {
        pubkeysById.forEach((id, pubkey) -> {
            pubkeyById.put(id, pubkey);
            idByPubkey.put(pubkey, id);
        });
    }

    /** The id of a pubkey already met; null otherwise. */
    public Integer idOf(String pubkey) {
        return idByPubkey.get(pubkey);
    }

    /** The pubkey of an id already met; null otherwise. */
    public String pubkeyOf(int id) {
        return pubkeyById.get(id);
    }

    /** Look up the ids of the pubkeys not met yet. One without an id stays unknown. */
    public void resolvePubkeys(Collection<String> pubkeys) {
        List<String> unknown = new ArrayList<>();
        for (String pubkey : pubkeys) if (!idByPubkey.containsKey(pubkey)) unknown.add(pubkey);
        if (unknown.isEmpty()) return;
        postgres.idsOf(unknown).forEach((pubkey, id) -> {
            idByPubkey.put(pubkey, id);
            pubkeyById.put(id, pubkey);
        });
    }

    /** Look up the pubkeys of the ids not met yet. */
    public void resolveIds(Collection<Integer> ids) {
        List<Integer> unknown = new ArrayList<>();
        for (Integer id : ids) if (!pubkeyById.containsKey(id)) unknown.add(id);
        if (unknown.isEmpty()) return;
        learn(postgres.pubkeysOf(unknown));
    }
}
