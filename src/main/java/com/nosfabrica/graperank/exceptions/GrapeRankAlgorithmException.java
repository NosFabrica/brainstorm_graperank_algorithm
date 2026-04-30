package com.nosfabrica.graperank.exceptions;

import com.nosfabrica.graperank.grape.GrapeRankError;

import org.neo4j.driver.exceptions.Neo4jException;

public class GrapeRankAlgorithmException extends RuntimeException {
    private final ErrorCode code;

    public GrapeRankAlgorithmException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public GrapeRankAlgorithmException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }

    public GrapeRankError toError() {
        return new GrapeRankError(code, getMessage());
    }

    public static GrapeRankAlgorithmException fromThrowable(Throwable t) {
        if (t instanceof GrapeRankAlgorithmException g) return g;
        if (t instanceof Neo4jException) {
            return new GrapeRankAlgorithmException(ErrorCode.NEO4J_ERROR, t.getMessage(), t);
        }
        return new GrapeRankAlgorithmException(ErrorCode.ALGORITHM_EXCEPTION,
                t.getClass().getSimpleName() + ": " + t.getMessage(), t);
    }
}
