package com.nosfabrica.graperank.grape;

import com.nosfabrica.graperank.rank.ScoreCard;

import java.util.Map;

public class GrapeRankAlgorithmResult {
    private final Map<String, ScoreCard> scorecards;
    private final int rounds;

    public GrapeRankAlgorithmResult(Map<String, ScoreCard> scorecards, int rounds) {
        this.scorecards = scorecards;
        this.rounds = rounds;
    }

    public Map<String, ScoreCard> getScorecards() {
        return scorecards;
    }

    public int getRounds() {
        return rounds;
    }
}