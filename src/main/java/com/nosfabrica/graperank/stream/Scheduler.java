package com.nosfabrica.graperank.stream;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered Redis calc lanes the worker drains, highest priority first (BLPOP
 * serves the leftmost non-empty). Lane names must match the server's
 * scheduler_lanes.py.
 */
public final class Scheduler {

    private Scheduler() {}

    static final String DEFAULT_LANE = "message_queue";

    // SCHEDULER_LANE_MAX_PRIORITY: 0/unset/non-numeric -> [message_queue];
    // N>=1 -> sched:admin, sched:house, message_queue, sched:N..0.
    public static String[] resolve() {
        int max = maxPriority();
        if (max <= 0) {
            return new String[] {DEFAULT_LANE};
        }
        List<String> lanes = new ArrayList<>();
        lanes.add("sched:admin");
        lanes.add("sched:house");
        lanes.add(DEFAULT_LANE);
        for (int p = max; p >= 0; p--) {
            lanes.add("sched:" + p);
        }
        return lanes.toArray(new String[0]);
    }

    static int maxPriority() {
        String v = System.getenv("SCHEDULER_LANE_MAX_PRIORITY");
        if (v == null || v.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
