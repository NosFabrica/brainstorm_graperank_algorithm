package com.nosfabrica.graperank.db;

import java.util.List;

/** A batch's incoming follows, mutes, reports and vouches, each in cache order. */
public record IncomingRelationships(
        List<RelationshipInfo> follows,
        List<RelationshipInfo> mutes,
        List<RelationshipInfo> reports,
        List<RelationshipInfo> vouches) {

    /** Pre-vouch payloads and test fakes: three lists, no vouches. */
    public IncomingRelationships(
            List<RelationshipInfo> follows,
            List<RelationshipInfo> mutes,
            List<RelationshipInfo> reports) {
        this(follows, mutes, reports, List.of());
    }
}
