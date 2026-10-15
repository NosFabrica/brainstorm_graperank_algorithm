package com.nosfabrica.graperank.rank;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ScoreCard {
    private String observer;
    private String observee;
    private String context = "not a bot";
    // Whole numbers on the wire (3, not 3.0): consumers bucket and tag by them as ints.
    private int hops;
    private double averageScore = 0;
    private double input = 0;
    private double confidence = 0;
    private double influence = 0;
    private Boolean verified = null;

    @JsonProperty("trusted_followers")
    private int trustedFollowers = 0;

    @JsonProperty("trusted_reporters")
    private int trustedReporters = 0;

    @JsonProperty("trusted_muters")
    private int trustedMuters = 0;

    public ScoreCard(String observer, String observee, double hops) {
        this.observer = observer;
        this.observee = observee;
        this.hops = (int) hops;
    }

    public ScoreCard(String observer, String observee, double averageScore, double input, double confidence,
            double influence) {
        this.observer = observer;
        this.observee = observee;
        this.averageScore = averageScore;
        this.input = input;
        this.confidence = confidence;
        this.influence = influence;
        this.hops = 0;
    }

    public String getObserver() {
        return observer;
    }

    public void setObserver(String observer) {
        this.observer = observer;
    }

    public String getObservee() {
        return observee;
    }

    public void setObservee(String observee) {
        this.observee = observee;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public int getHops() {
        return hops;
    }

    public void setHops(double hops) {
        this.hops = (int) hops;
    }


    public double getAverageScore() {
        return averageScore;
    }

    public void setAverageScore(double averageScore) {
        this.averageScore = averageScore;
    }

    public double getInput() {
        return input;
    }

    public void setInput(double input) {
        this.input = input;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public double getInfluence() {
        return influence;
    }

    public void setInfluence(double influence) {
        this.influence = influence;
    }

    public Boolean getVerified() {
        return verified;
    }

    public void setVerified(Boolean verified) {
        this.verified = verified;
    }

    public int getTrustedFollowers() {
        return trustedFollowers;
    }

    public void setTrustedFollowers(double trustedFollowers) {
        this.trustedFollowers = (int) trustedFollowers;
    }

    public int getTrustedReporters() {
        return trustedReporters;
    }

    public void setTrustedReporters(double trustedReporters) {
        this.trustedReporters = (int) trustedReporters;
    }

    public int getTrustedMuters() {
        return trustedMuters;
    }

    public void setTrustedMuters(double trustedMuters) {
        this.trustedMuters = (int) trustedMuters;
    }
}
