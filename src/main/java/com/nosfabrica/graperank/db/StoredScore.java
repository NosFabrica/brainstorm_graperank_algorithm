package com.nosfabrica.graperank.db;

/** What the Scorecard database holds for one Observee from the Observer's last write. */
public record StoredScore(Double influence, Long hops, Long trustedFollowers) {
}
