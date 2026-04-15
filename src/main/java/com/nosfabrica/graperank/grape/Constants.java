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

    public static final double DEFAULT_CUTOFF_OF_VALID_USER = 0.02;

    public static final double DEFAULT_CUTOFF_OF_TRUSTED_REPORTER = 0.1;
}
