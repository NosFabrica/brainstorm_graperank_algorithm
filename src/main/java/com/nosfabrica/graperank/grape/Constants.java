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

    // Follow distances beyond this are published as UNREACHABLE_HOPS.
    public static final int MAX_HOPS = 8;
    // "No follow path from the observer within the hop limit". Published as a
    // scorecard `hops` value; the server omits the TA's `hops` tag at it.
    public static final double UNREACHABLE_HOPS = 999;

    // Fixed Influence (a published rank of 95) for every key the Observer listed
    // in their kind-10040 — the keys they designated to publish assertions.
    public static final double DESIGNATED_KEY_INFLUENCE = 0.95;

    // Rating carried by anyone else's kind-10040 designation: it rates the key like a
    // follow does (same confidence, weighted by the designator's Influence), at 0.95.
    public static final double DESIGNATION_RATING = 0.95;

    // NIP-05 penalties (brainstorm_server ADR 0006), applied to a user's Influence inside the
    // iteration so what they pass on shrinks with it. brainstorm_profile_resolver records the
    // statuses; anything else — none, VERIFIED, SUSPECTED, UNKNOWN, PENDING — costs nothing.
    // The domain gives this name to another key, confirmed on two checks: someone else's name.
    public static final double NIP05_IMPERSONATION_FACTOR = 0.0;
    // The domain answered without backing the claim (name missing, or a 404).
    public static final double NIP05_NOT_LISTED_FACTOR = 0.5;

    public static final java.util.Set<String> PENALISED_NIP05_STATUSES = java.util.Set.of("IMPERSONATION", "NOT_LISTED");

    /** The multiplier a NIP-05 status puts on Influence. */
    public static double nip05Factor(String status) {
        if ("IMPERSONATION".equals(status)) return NIP05_IMPERSONATION_FACTOR;
        if ("NOT_LISTED".equals(status)) return NIP05_NOT_LISTED_FACTOR;
        return 1.0;
    }

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
