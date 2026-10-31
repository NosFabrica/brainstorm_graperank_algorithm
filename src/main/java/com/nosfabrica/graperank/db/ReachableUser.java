package com.nosfabrica.graperank.db;

/** A user the Observer reaches by follows: the shortest follow distance (0 for the
 * Observer), the scores Neo4j holds from the Observer's last write (null if absent), and their
 * NIP-05 status as brainstorm_profile_resolver last recorded it (null if none). */
public record ReachableUser(int hops, Double storedInfluence, Long storedHops, Long storedTrustedFollowers,
                            String nip05Status) {
    public ReachableUser(int hops, Double storedInfluence, Long storedHops, Long storedTrustedFollowers) {
        this(hops, storedInfluence, storedHops, storedTrustedFollowers, null);
    }

    public ReachableUser(int hops, Double storedInfluence, String nip05Status) {
        this(hops, storedInfluence, null, null, nip05Status);
    }

    public ReachableUser(int hops, Double storedInfluence) {
        this(hops, storedInfluence, null, null, null);
    }
}
