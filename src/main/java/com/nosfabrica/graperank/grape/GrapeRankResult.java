package com.nosfabrica.graperank.grape;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nosfabrica.graperank.rank.ScoreCard;

import java.util.Map;

public class GrapeRankResult {
    private Map<String, ScoreCard> scorecards;
    private Integer rounds;
    @JsonProperty("duration_seconds")
    private double duration_seconds;
    private boolean success = false;

    public GrapeRankResult(Map<String, ScoreCard> scorecards, Integer rounds, double durationSeconds, boolean success) {
        this.scorecards = scorecards;
        this.rounds = rounds;
        this.duration_seconds = durationSeconds;
        this.success = success;
    }

    public Map<String, ScoreCard> getScorecards() {
        return scorecards;
    }

    public void setScorecards(Map<String, ScoreCard> scorecards) {
        this.scorecards = scorecards;
    }

    public Integer getRounds() {
        return rounds;
    }

    public void setRounds(Integer rounds) {
        this.rounds = rounds;
    }

    public double getDurationSeconds() {
        return duration_seconds;
    }

    public void setDurationSeconds(double durationSeconds) {
        this.duration_seconds = durationSeconds;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }
}
