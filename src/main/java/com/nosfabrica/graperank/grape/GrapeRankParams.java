package com.nosfabrica.graperank.grape;

public record GrapeRankParams(
        double rigor,
        double attenuationFactor,
        double followRating,
        double followConfidence,
        double muteRating,
        double muteConfidence,
        double reportRating,
        double reportConfidence,
        double followConfidenceOfObserver,
        double verifiedFollowersInfluenceCutoff,
        double verifiedReportersInfluenceCutoff,
        double verifiedMutersInfluenceCutoff
) {}
