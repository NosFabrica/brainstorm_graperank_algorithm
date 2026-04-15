package com.nosfabrica.graperank.grape;

import java.util.List;

public class GrapeRankInput {
    private String rater;
    private String ratee;
    private String context = "not a bot"; // Default value
    private double rating;
    private double confidence;

    public GrapeRankInput(String rater, String ratee, double rating, double confidence) {
        this.rater = rater;
        this.ratee = ratee;
        this.rating = rating;
        this.confidence = confidence;
    }

    public String getRater() {
        return rater;
    }

    public void setRater(String rater) {
        this.rater = rater;
    }

    public String getRatee() {
        return ratee;
    }

    public void setRatee(String ratee) {
        this.ratee = ratee;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public double getRating() {
        return rating;
    }

    public void setRating(double rating) {
        this.rating = rating;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }
}
