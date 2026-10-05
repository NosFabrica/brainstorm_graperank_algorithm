package com.nosfabrica.graperank.db;

import java.util.Map;

public interface IGraphDB {
    /** Everyone the Observer reaches by follows, plus the Observer; empty if none. */
    Map<String, ReachableUser> getReachableUsers(String observer);
}
