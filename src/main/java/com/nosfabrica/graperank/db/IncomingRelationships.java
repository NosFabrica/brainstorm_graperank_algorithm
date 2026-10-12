package com.nosfabrica.graperank.db;

import java.util.List;

/** A batch's incoming follows, mutes and reports, each in cache order. */
public record IncomingRelationships(
        List<RelationshipInfo> follows,
        List<RelationshipInfo> mutes,
        List<RelationshipInfo> reports) {
}
