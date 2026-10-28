package com.nosfabrica.graperank.db;

/** A user the Observer reaches by follows: the shortest follow distance (0 for the
 * Observer), the Influence the Observer last gave them (null if never scored), and their
 * NIP-05 status as brainstorm_profile_resolver last recorded it (null if none). */
public record ReachableUser(int hops, Double previousInfluence, String nip05Status) {
    public ReachableUser(int hops, Double previousInfluence) {
        this(hops, previousInfluence, null);
    }
}
