package com.nosfabrica.graperank.exceptions;

public class UnknownRelationshipException extends GrapeRankAlgorithmException {
    public UnknownRelationshipException(String relationship) {
        super(ErrorCode.UNKNOWN_RELATIONSHIP, "Unknown relationship type: " + relationship);
    }
}
