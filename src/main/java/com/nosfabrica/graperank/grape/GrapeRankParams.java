package com.nosfabrica.graperank.grape;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GrapeRankParams(
        @JsonProperty(required = true) double rigor,
        @JsonProperty(required = true) double attenuationFactor,
        @JsonProperty(required = true) double followRating,
        @JsonProperty(required = true) double followConfidence,
        @JsonProperty(required = true) double muteRating,
        @JsonProperty(required = true) double muteConfidence,
        @JsonProperty(required = true) double reportRating,
        @JsonProperty(required = true) double reportConfidence,
        @JsonProperty(required = true) double followConfidenceOfObserver,
        @JsonProperty(required = true) double verifiedFollowersInfluenceCutoff,
        @JsonProperty(required = true) double verifiedReportersInfluenceCutoff,
        @JsonProperty(required = true) double verifiedMutersInfluenceCutoff
) {}
