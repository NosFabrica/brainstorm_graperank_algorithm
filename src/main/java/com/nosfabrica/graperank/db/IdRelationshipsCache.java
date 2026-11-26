package com.nosfabrica.graperank.db;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The relationship sets when they name users by User id (`followed_by:<id>` holding ids), which
 * they do once scores are in Postgres. One per run: it answers in pubkeys, as the algorithm
 * expects, through that run's [RunIds].
 *
 * A member whose id the run has not met is someone the run does not score, so their rating
 * would be ignored anyway: it is dropped here without a lookup. That is only true once
 * [prepare] has been told every user the run scores.
 */
public class IdRelationshipsCache implements IRelationshipsCache {

    private static final String FOLLOWED_BY = "followed_by:";
    private static final String MUTED_BY = "muted_by:";
    private static final String REPORTED_BY = "reported_by:";
    private static final String DESIGNATES = "designates:";
    private static final String ZAPS_VIA = "zaps_via:";

    private final RedisRelationshipsHelper redis;
    private final RunIds ids;

    public IdRelationshipsCache(RedisRelationshipsHelper redis, RunIds ids) {
        this.redis = redis;
        this.ids = ids;
    }

    @Override
    public int maxConcurrentFetches() {
        return redis.maxConcurrentFetches();
    }

    @Override
    public void prepare(List<String> relevantUsers) {
        ids.resolvePubkeys(relevantUsers);
    }

    @Override
    public List<RelationshipInfo> getIncomingFollowsBulk(List<String> pubkeys) {
        return incoming(pubkeys, FOLLOWED_BY, "FOLLOWS");
    }

    @Override
    public List<RelationshipInfo> getIncomingMutesBulk(List<String> pubkeys) {
        return incoming(pubkeys, MUTED_BY, "MUTES");
    }

    @Override
    public List<RelationshipInfo> getIncomingReportsBulk(List<String> pubkeys) {
        return incoming(pubkeys, REPORTED_BY, "REPORTS");
    }

    /** One pipelined round trip for all three reverse sets of the batch. */
    @Override
    public IncomingRelationships getIncomingBulk(List<String> pubkeys) {
        List<String> targets = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (String pubkey : pubkeys) {
            Integer id = ids.idOf(pubkey);
            if (id == null) continue;
            targets.add(pubkey);
            keys.add(FOLLOWED_BY + id);
            keys.add(MUTED_BY + id);
            keys.add(REPORTED_BY + id);
        }
        List<Set<String>> sets = redis.members(keys);
        List<RelationshipInfo> follows = new ArrayList<>();
        List<RelationshipInfo> mutes = new ArrayList<>();
        List<RelationshipInfo> reports = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            addRaters(follows, sets.get(3 * i), "FOLLOWS", targets.get(i));
            addRaters(mutes, sets.get(3 * i + 1), "MUTES", targets.get(i));
            addRaters(reports, sets.get(3 * i + 2), "REPORTS", targets.get(i));
        }
        return new IncomingRelationships(follows, mutes, reports);
    }

    private List<RelationshipInfo> incoming(List<String> pubkeys, String prefix, String relationship) {
        List<String> targets = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (String pubkey : pubkeys) {
            Integer id = ids.idOf(pubkey);
            if (id == null) continue;
            targets.add(pubkey);
            keys.add(prefix + id);
        }
        List<Set<String>> sets = redis.members(keys);
        List<RelationshipInfo> out = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) addRaters(out, sets.get(i), relationship, targets.get(i));
        return out;
    }

    private void addRaters(List<RelationshipInfo> out, Set<String> members, String relationship, String target) {
        if (members == null) return;
        for (String member : members) {
            String source = ids.pubkeyOf(Integer.parseInt(member));
            if (source != null) out.add(new RelationshipInfo(source, relationship, target));
        }
    }

    /** Designated keys can be users the run has not met (reached only by designation), so
     * their ids are looked up. */
    @Override
    public Map<String, Set<String>> getDesignationsBulk(List<String> designators) {
        if (designators == null || designators.isEmpty()) return new HashMap<>();
        ids.resolvePubkeys(designators);
        List<String> known = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (String pubkey : designators) {
            Integer id = ids.idOf(pubkey);
            if (id == null) continue;
            known.add(pubkey);
            keys.add(DESIGNATES + id);
            keys.add(ZAPS_VIA + id);
        }
        List<Set<String>> sets = redis.members(keys);
        Set<Integer> named = new HashSet<>();
        for (Set<String> set : sets) if (set != null) for (String member : set) named.add(Integer.parseInt(member));
        ids.resolveIds(named);

        List<Set<String>> designated = new ArrayList<>(known.size());
        List<Set<String>> signers = new ArrayList<>(known.size());
        for (int i = 0; i < known.size(); i++) {
            designated.add(pubkeysOf(sets.get(2 * i)));
            signers.add(pubkeysOf(sets.get(2 * i + 1)));
        }
        return RedisRelationshipsHelper.designationsOf(known, designated, signers);
    }

    private Set<String> pubkeysOf(Set<String> members) {
        Set<String> out = new HashSet<>();
        if (members == null) return out;
        for (String member : members) {
            String pubkey = ids.pubkeyOf(Integer.parseInt(member));
            if (pubkey != null) out.add(pubkey);
        }
        return out;
    }
}
