package com.nosfabrica.graperank.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Needs a throwaway Redis (it flushes the db): set REDIS_TEST_HOST. Skipped otherwise. */
class JobLeaseTest {

    private static final String HOST = System.getenv("REDIS_TEST_HOST");
    private static final String LANE = "sched:house";
    private static final long T0 = 1_000_000_000L;

    private Jedis redis;

    @BeforeEach
    void connect() {
        assumeTrue(HOST != null && !HOST.isBlank(), "REDIS_TEST_HOST not set");
        redis = new Jedis(HOST, 6379);
        redis.flushDB();
    }

    @AfterEach
    void close() {
        if (redis != null) redis.close();
    }

    @Test
    void aReleasedJobLeavesNothingBehind() throws Exception {
        assertEquals(1, JobLease.claim(redis, 7, LANE, "{\"private_id\":7}", T0));
        JobLease.release(redis, 7);

        assertFalse(redis.hexists(JobLease.CLAIMS, "7"));
        assertEquals(0, redis.zcard(JobLease.HEARTBEATS));
        assertFalse(redis.exists(JobLease.attemptsKey(7)));
    }

    @Test
    void aStaleClaimGoesBackToTheFrontOfItsLane() throws Exception {
        redis.rpush(LANE, "{\"private_id\":8}");
        JobLease.claim(redis, 7, LANE, "{\"private_id\":7}", T0);

        assertEquals(List.of("7"), JobLease.requeueStale(redis, T0 + JobLease.LEASE_MS + 1));
        assertEquals("{\"private_id\":7}", redis.lindex(LANE, 0));
        assertFalse(redis.hexists(JobLease.CLAIMS, "7"));
    }

    @Test
    void aClaimWithinItsLeaseStays() throws Exception {
        JobLease.claim(redis, 7, LANE, "{\"private_id\":7}", T0);

        assertEquals(List.of(), JobLease.requeueStale(redis, T0 + JobLease.LEASE_MS - 1));
        assertTrue(redis.hexists(JobLease.CLAIMS, "7"));
    }

    @Test
    void aHeartbeatKeepsALongRunAlive() throws Exception {
        JobLease.claim(redis, 7, LANE, "{\"private_id\":7}", T0);
        JobLease.heartbeat(redis, 7, T0 + JobLease.LEASE_MS);

        assertEquals(List.of(), JobLease.requeueStale(redis, T0 + JobLease.LEASE_MS + 1));
    }

    @Test
    void aHeartbeatAfterReleaseDoesNotRecreateTheClaim() throws Exception {
        JobLease.claim(redis, 7, LANE, "{\"private_id\":7}", T0);
        JobLease.release(redis, 7);
        JobLease.heartbeat(redis, 7, T0 + 1);

        assertEquals(0, redis.zcard(JobLease.HEARTBEATS));
    }

    @Test
    void twoReapersRequeueAStaleJobOnce() throws Exception {
        JobLease.claim(redis, 7, LANE, "{\"private_id\":7}", T0);
        long later = T0 + JobLease.LEASE_MS + 1;

        JobLease.requeueStale(redis, later);
        JobLease.requeueStale(redis, later);

        assertEquals(1, redis.llen(LANE));
    }

    @Test
    void attemptsCountEveryPickupUntilReleased() throws Exception {
        assertEquals(1, JobLease.claim(redis, 7, LANE, "{\"private_id\":7}", T0));
        JobLease.requeueStale(redis, T0 + JobLease.LEASE_MS + 1);
        assertEquals(2, JobLease.claim(redis, 7, LANE, "{\"private_id\":7}", T0 + JobLease.LEASE_MS + 2));
    }
}
