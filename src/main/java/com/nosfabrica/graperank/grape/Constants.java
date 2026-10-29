package com.nosfabrica.graperank.grape;

public class Constants {

    public static final double DEFAULT_RATING_FOR_FOLLOW = 1.0;
    public static final double DEFAULT_RATING_FOR_MUTE = -0.1;
    public static final double DEFAULT_RATING_FOR_REPORT = -0.1;

    public static final double DEFAULT_CONFIDENCE_FOR_FOLLOW = 0.03; // [0,1]
    public static final double DEFAULT_CONFIDENCE_FOR_FOLLOW_FROM_OBSERVER = 0.5;
    public static final double DEFAULT_CONFIDENCE_FOR_MUTE = 0.5; // [0,1]
    public static final double DEFAULT_CONFIDENCE_FOR_REPORT = 0.5; // [0,1]

    public static final double GLOBAL_ATTENUATION_FACTOR = 0.85; // [0,1]
    public static final double GLOBAL_RIGOR = 0.5; // [0,1]

    public static final double THRESHOLD_OF_LOOP_BREAK_GIVEN_MINIMUM_DELTA_INFLUENCE = 0.0001;

    // Neo4j stores an Observer's scores only above this Influence: below the loop-break delta a
    // value is indistinguishable from 0. Operator override: env INFLUENCE_PERSIST_FLOOR (0 = store
    // everything but exact zeros). Not a preset parameter.
    public static final double PERSIST_FLOOR = persistFloor(System.getenv("INFLUENCE_PERSIST_FLOOR"));

    // An Influence within this of its stored value keeps the stored value. 5x the loop-break
    // delta: smaller moves are convergence noise, and they flip trusted-rater counts at the cutoffs.
    // Operator override: env INFLUENCE_DEAD_ZONE (0 turns it off). Not a preset parameter.
    public static final double INFLUENCE_DEAD_ZONE = influenceDeadZone(System.getenv("INFLUENCE_DEAD_ZONE"));

    // Readers threshold stored Influence at preset cutoffs, the lowest in use 0.002 (PERMISSIVE).
    static double persistFloor(String env) {
        return operatorInfluence("INFLUENCE_PERSIST_FLOOR", env, 0.0001, 0.001);
    }

    // Past a rank step (0.01) the published rank would lag by more than it shows.
    static double influenceDeadZone(String env) {
        return operatorInfluence("INFLUENCE_DEAD_ZONE", env, 0.0005, 0.01);
    }

    /** An operator-set Influence amount: unset = `fallback`; anything outside [0, maxExclusive) stops the worker. */
    private static double operatorInfluence(String name, String env, double fallback, double maxExclusive) {
        if (env == null || env.isBlank()) return fallback;
        double v;
        try {
            v = Double.parseDouble(env.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(name + " is not a number: " + env);
        }
        if (!(v >= 0 && v < maxExclusive)) {
            throw new IllegalStateException(name + " must be in [0, " + maxExclusive + "): " + env);
        }
        return v;
    }

    // Follow distances beyond this are published as UNREACHABLE_HOPS.
    public static final int MAX_HOPS = 8;
    // "No follow path from the observer within the hop limit". Published as a
    // scorecard `hops` value; the server omits the TA's `hops` tag at it.
    public static final double UNREACHABLE_HOPS = 999;

    // Fixed Influence (a published rank of 95) for every key the Observer listed
    // in their kind-10040 — the keys they designated to publish assertions.
    public static final double DESIGNATED_KEY_INFLUENCE = 0.95;

    public static final double DEFAULT_CUTOFF_OF_VALID_USER = 0.02;

    public static final double DEFAULT_CUTOFF_OF_TRUSTED_REPORTER = 0.1;

    public static final double DEFAULT_CUTOFF_OF_VERIFIED_MUTER = 0.01;

    public static final GrapeRankParams DEFAULT_PARAMS = new GrapeRankParams(
            GLOBAL_RIGOR,
            GLOBAL_ATTENUATION_FACTOR,
            DEFAULT_RATING_FOR_FOLLOW,
            DEFAULT_CONFIDENCE_FOR_FOLLOW,
            DEFAULT_RATING_FOR_MUTE,
            DEFAULT_CONFIDENCE_FOR_MUTE,
            DEFAULT_RATING_FOR_REPORT,
            DEFAULT_CONFIDENCE_FOR_REPORT,
            DEFAULT_CONFIDENCE_FOR_FOLLOW_FROM_OBSERVER,
            DEFAULT_CUTOFF_OF_VALID_USER,
            DEFAULT_CUTOFF_OF_TRUSTED_REPORTER,
            DEFAULT_CUTOFF_OF_VERIFIED_MUTER
    );
}
