package com.nosfabrica.graperank.grape;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nosfabrica.graperank.rank.ScoreCard;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class GrapeRankResult {
    private Map<String, ScoreCard> scorecards;
    private Integer rounds;
    @JsonProperty("duration_seconds")
    private double duration_seconds;
    private boolean success = false;
    private List<String> changedScorePubkeys = Collections.emptyList();
    private List<String> droppedBelowCutoffPubkeys = Collections.emptyList();

    public GrapeRankResult(Map<String, ScoreCard> scorecards, Integer rounds, double durationSeconds, boolean success) {
        this.scorecards = scorecards;
        this.rounds = rounds;
        this.duration_seconds = durationSeconds;
        this.success = success;
    }

    public GrapeRankResult(Map<String, ScoreCard> scorecards, Integer rounds, double durationSeconds, boolean success,
                           List<String> changedScorePubkeys, List<String> droppedBelowCutoffPubkeys) {
        this.scorecards = scorecards;
        this.rounds = rounds;
        this.duration_seconds = durationSeconds;
        this.success = success;
        this.changedScorePubkeys = changedScorePubkeys;
        this.droppedBelowCutoffPubkeys = droppedBelowCutoffPubkeys;
    }

    public List<String> getChangedScorePubkeys() {
        return changedScorePubkeys;
    }

    public void setChangedScorePubkeys(List<String> changedScorePubkeys) {
        this.changedScorePubkeys = changedScorePubkeys;
    }

    public List<String> getDroppedBelowCutoffPubkeys() {
        return droppedBelowCutoffPubkeys;
    }

    public void setDroppedBelowCutoffPubkeys(List<String> droppedBelowCutoffPubkeys) {
        this.droppedBelowCutoffPubkeys = droppedBelowCutoffPubkeys;
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
