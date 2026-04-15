package com.nosfabrica.graperank.rank;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ScoreCard {
    private String observer;
    private String observee;
    private String context = "not a bot";
    private double hops;
    private double averageScore = 0;
    private double input = 0;
    private double confidence = 0;
    private double influence = 0;
    private Boolean verified = null;

    @JsonProperty("trusted_followers")
    private double trustedFollowers = 0;

    @JsonProperty("trusted_reporters")
    private double trustedReporters = 0;

    public ScoreCard(String observer, String observee, double hops) {
        this.observer = observer;
        this.observee = observee;
        this.hops = hops;
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

    public double getHops() {
        return hops;
    }

    public void setHops(double hops) {
        this.hops = hops;
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

    public double getTrustedFollowers() {
        return trustedFollowers;
    }

    public void setTrustedFollowers(double trustedFollowers) {
        this.trustedFollowers = trustedFollowers;
    }

    public double getTrustedReporters() {
        return trustedReporters;
    }

    public void setTrustedReporters(double trustedReporters) {
        this.trustedReporters = trustedReporters;
    }
}
