package com.nosfabrica.graperank.exceptions;

// Keep in sync with brainstorm_server/app/schemas/error_codes.py
public enum ErrorCode {
    /** Server's job payload was malformed — missing fields, bad types, or unparseable graperank_params. Fix: server side. */
    MALFORMED_PARAMS,

    /** Observer has no (or too few) eligible users in the graph; algorithm has nothing to score. Fix: ensure observer is connected to a non-trivial graph. */
    NO_ELIGIBLE_USERS,

    /** Algorithm hit a relationship type it doesn't handle (graph data anomaly). Fix: extend algorithm or sanitize graph. */
    UNKNOWN_RELATIONSHIP,

    /** Any Neo4j driver error — unavailable, auth, bad cypher, transient, etc. Fix: read message and stack trace. */
    NEO4J_ERROR,

    /** Unclassified RuntimeException from the algorithm path — likely a bug. Fix: read stack trace and investigate. */
    ALGORITHM_EXCEPTION;
}
