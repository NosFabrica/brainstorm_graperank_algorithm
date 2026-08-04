package com.nosfabrica.graperank.db;

import java.util.List;

/** The reverse-set caches the algorithm reads (`followed_by:` / `muted_by:` /
 * `reported_by:`). Redis-backed in production; fakeable in tests. */
public interface IRelationshipsCache {
    List<RelationshipInfo> getIncomingFollowsBulk(List<String> pubkeys);

    List<RelationshipInfo> getIncomingMutesBulk(List<String> pubkeys);

    List<RelationshipInfo> getIncomingReportsBulk(List<String> pubkeys);
}
