package com.nosfabrica.graperank.grape;

public class GrapeRankPresets {

    public enum Template {
        DEFAULT,
        PERMISSIVE,
        RESTRICTIVE,
        CUSTOM
    }

    public static GrapeRankParams forTemplate(Template template) {
        switch (template) {
            case PERMISSIVE:
                return PERMISSIVE;
            case RESTRICTIVE:
                return RESTRICTIVE;
            case DEFAULT:
            default:
                return DEFAULT;
        }
    }

    public static Template parseTemplate(String raw) {
        if (raw == null) return Template.DEFAULT;
        try {
            return Template.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown preset template '" + raw + "', falling back to DEFAULT");
            return Template.DEFAULT;
        }
    }

    public static final GrapeRankParams DEFAULT = new GrapeRankParams(
            Constants.GLOBAL_RIGOR,
            Constants.GLOBAL_ATTENUATION_FACTOR,
            Constants.DEFAULT_RATING_FOR_FOLLOW,
            Constants.DEFAULT_CONFIDENCE_FOR_FOLLOW,
            Constants.DEFAULT_RATING_FOR_MUTE,
            Constants.DEFAULT_CONFIDENCE_FOR_MUTE,
            Constants.DEFAULT_RATING_FOR_REPORT,
            Constants.DEFAULT_CONFIDENCE_FOR_REPORT,
            Constants.DEFAULT_CONFIDENCE_FOR_FOLLOW_FROM_OBSERVER,
            Constants.DEFAULT_CUTOFF_OF_VALID_USER,
            Constants.DEFAULT_CUTOFF_OF_TRUSTED_REPORTER,
            0.01
    );

    public static final GrapeRankParams PERMISSIVE = new GrapeRankParams(
            0.3,
            0.95,
            1.0,
            0.1,
            0.0,
            0.1,
            0.0,
            0.1,
            0.1,
            0.002,
            0.002,
            0.002
    );

    public static final GrapeRankParams RESTRICTIVE = new GrapeRankParams(
            0.65,
            0.5,
            1.0,
            0.03,
            -0.9,
            0.9,
            -0.9,
            0.9,
            0.5,
            0.5,
            0.5,
            0.5
    );
}
