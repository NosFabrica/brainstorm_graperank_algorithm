package com.nosfabrica.graperank.db;

/** A user the Observer reaches by follows: the shortest follow distance (0 for the
 * Observer) and the scores Neo4j holds from the Observer's last write (null if absent). */
public record ReachableUser(int hops, Double storedInfluence, Long storedHops, Long storedTrustedFollowers) {
}
