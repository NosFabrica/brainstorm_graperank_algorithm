package com.nosfabrica.graperank.db;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `SCORES_IN_POSTGRES`: off unless set, and on only with every Scorecard database setting. */
class PostgresHelperSettingsTest {

    private static Map<String, String> complete() {
        Map<String, String> env = new HashMap<>();
        env.put("SCORES_IN_POSTGRES", "true");
        env.put("SCORECARD_DB_HOST", "db");
        env.put("SCORECARD_DB_PORT", "5432");
        env.put("SCORECARD_DB_NAME", "scorecard");
        env.put("SCORECARD_DB_USER", "reader");
        env.put("SCORECARD_DB_PASSWORD", "secret");
        return env;
    }

    @Test
    void offByDefaultAndWhenFalse() {
        assertNull(PostgresHelper.fromEnv(Map.of()));
        assertNull(PostgresHelper.fromEnv(Map.of("SCORES_IN_POSTGRES", "false")));
        // Database settings alone (the chart passes the host before the cutover) change nothing.
        Map<String, String> env = complete();
        env.remove("SCORES_IN_POSTGRES");
        assertNull(PostgresHelper.fromEnv(env));
    }

    @Test
    void onWithEverySetting() {
        assertNotNull(PostgresHelper.fromEnv(complete()));
    }

    @Test
    void onWithoutCredentialsStopsTheWorker() {
        Map<String, String> env = complete();
        env.remove("SCORECARD_DB_PASSWORD");
        env.put("SCORECARD_DB_USER", " ");
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> PostgresHelper.fromEnv(env));
        assertTrue(error.getMessage().contains("SCORECARD_DB_USER"));
        assertTrue(error.getMessage().contains("SCORECARD_DB_PASSWORD"));
    }

    @Test
    void anythingElseIsNotASwitch() {
        Map<String, String> env = complete();
        env.put("SCORES_IN_POSTGRES", "yes please");
        assertThrows(IllegalStateException.class, () -> PostgresHelper.fromEnv(env));
    }

    @Test
    void connectionsCarryTimeouts() {
        Properties properties = PostgresHelper.connectionProperties("reader", "secret");
        assertEquals("reader", properties.getProperty("user"));
        assertEquals("secret", properties.getProperty("password"));
        assertEquals("-c statement_timeout=120000", properties.getProperty("options"));
        assertEquals("300", properties.getProperty("socketTimeout"));
    }
}
