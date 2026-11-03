package com.nosfabrica.graperank.db;

import java.util.Collection;
import java.util.Map;

public interface IGraphDB {
    /** Everyone the Observer reaches by follows, plus the Observer; empty if none. */
    Map<String, ReachableUser> getReachableUsers(String observer);

    /** For [pubkeys] the follow walk does not reach (keys reached only by designation): what Neo4j
     * holds of the Observer's scores and a penalised NIP-05 status, at hops `UNREACHABLE_HOPS`.
     * Users with neither are absent. */
    default Map<String, ReachableUser> getUnreachedUsers(String observer, Collection<String> pubkeys) {
        return Map.of();
    }
}
