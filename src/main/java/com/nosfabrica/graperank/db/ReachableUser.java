package com.nosfabrica.graperank.db;

/** A user the Observer reaches by follows: the shortest follow distance (0 for the
 * Observer) and the Influence the Observer last gave them (null if never scored). */
public record ReachableUser(int hops, Double previousInfluence) {
}
