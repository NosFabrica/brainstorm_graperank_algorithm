package com.nosfabrica.graperank.db;

import java.util.Collection;
import java.util.Map;

public interface IGraphDB {
    /** Everyone the Observer reaches by follows, plus the Observer; empty if none. */
    Map<String, ReachableUser> getReachableUsers(String observer);

    /** The NIP-05 status of each of [pubkeys] that has a penalised one, for users the
     * follow walk does not reach (keys reached only by designation). */
    default Map<String, String> getPenalisedNip05Statuses(Collection<String> pubkeys) {
        return Map.of();
    }
}
