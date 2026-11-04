package com.nosfabrica.graperank.db;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The caches the algorithm reads: the reverse sets (`followed_by:` / `muted_by:` /
 * `reported_by:` / `vouched_by:`) and the forward `designates:` sets. Redis-backed in
 * production; fakeable in tests. */
public interface IRelationshipsCache {
    List<RelationshipInfo> getIncomingFollowsBulk(List<String> pubkeys);

    List<RelationshipInfo> getIncomingMutesBulk(List<String> pubkeys);

    List<RelationshipInfo> getIncomingReportsBulk(List<String> pubkeys);

    /** The batch's incoming vouches (the `vouched_by:` reverse sets). Empty by default so
     * pre-vouch fakes and caches keep compiling and behaving as they did. */
    default List<RelationshipInfo> getIncomingVouchesBulk(List<String> pubkeys) {
        return List.of();
    }

    /** How many batches the gather may fetch at once. */
    default int maxConcurrentFetches() {
        return 4;
    }

    /** All four for one batch. Called from up to {@link #maxConcurrentFetches()} threads at once. */
    default IncomingRelationships getIncomingBulk(List<String> pubkeys) {
        return new IncomingRelationships(
                getIncomingFollowsBulk(pubkeys),
                getIncomingMutesBulk(pubkeys),
                getIncomingReportsBulk(pubkeys),
                getIncomingVouchesBulk(pubkeys));
    }

    /** The keys each of `designators` chose: those named in their kind-10040 (`designates:<pubkey>`)
     * and the signer of their zap receipts (`zaps_via:<pubkey>`).
     * A designator with none is absent. Called from up to {@link #maxConcurrentFetches()}
     * threads at once. */
    default Map<String, Set<String>> getDesignationsBulk(List<String> designators) {
        return Map.of();
    }
}
