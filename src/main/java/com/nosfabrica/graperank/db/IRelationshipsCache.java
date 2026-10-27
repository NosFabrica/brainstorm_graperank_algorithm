package com.nosfabrica.graperank.db;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The caches the algorithm reads: the reverse sets (`followed_by:` / `muted_by:` /
 * `reported_by:`) and the forward `designates:` sets. Redis-backed in production;
 * fakeable in tests. */
public interface IRelationshipsCache {
    List<RelationshipInfo> getIncomingFollowsBulk(List<String> pubkeys);

    List<RelationshipInfo> getIncomingMutesBulk(List<String> pubkeys);

    List<RelationshipInfo> getIncomingReportsBulk(List<String> pubkeys);

    /** How many batches the gather may fetch at once. */
    default int maxConcurrentFetches() {
        return 4;
    }

    /** All three for one batch. Called from up to {@link #maxConcurrentFetches()} threads at once. */
    default IncomingRelationships getIncomingBulk(List<String> pubkeys) {
        return new IncomingRelationships(
                getIncomingFollowsBulk(pubkeys),
                getIncomingMutesBulk(pubkeys),
                getIncomingReportsBulk(pubkeys));
    }

    /** The keys each of `designators` chose: those named in their kind-10040 (`designates:<pubkey>`)
     * and the signer of their zap receipts (`zaps_via:<pubkey>`).
     * A designator with none is absent. Called from up to {@link #maxConcurrentFetches()}
     * threads at once. */
    default Map<String, Set<String>> getDesignationsBulk(List<String> designators) {
        return Map.of();
    }
}
