package com.nosfabrica.graperank.db;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only access to the Scorecard database: User ids (`nostr_user`) and stored Scorecards
 * (`scorecard`). The graph answers who by User id; this gives pubkeys and stored scores.
 * One short-lived connection per call: a run makes a handful of them.
 */
public class PostgresHelper {

    /** Ids per `= ANY(?)`: one array parameter, kept to a few hundred kilobytes. */
    static final int ID_BATCH = 50_000;
    private static final int FETCH_SIZE = 10_000;

    private final String url;
    private final String user;
    private final String password;

    public PostgresHelper(String host, String port, String database, String user, String password) {
        this.url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        this.user = user;
        this.password = password;
    }

    /** From the environment when `SCORES_IN_POSTGRES` is on, else null. A switch that is on
     * without every setting stops the worker. */
    public static PostgresHelper fromEnv(Map<String, String> env) {
        String flag = env.getOrDefault("SCORES_IN_POSTGRES", "false").trim().toLowerCase();
        if (flag.equals("false") || flag.equals("0") || flag.isEmpty()) return null;
        if (!flag.equals("true") && !flag.equals("1")) {
            throw new IllegalStateException("SCORES_IN_POSTGRES must be true or false, got: " + flag);
        }
        List<String> missing = new ArrayList<>();
        for (String name : List.of("SCORECARD_DB_HOST", "SCORECARD_DB_PORT", "SCORECARD_DB_NAME",
                "SCORECARD_DB_USER", "SCORECARD_DB_PASSWORD")) {
            String value = env.get(name);
            if (value == null || value.isBlank()) missing.add(name);
        }
        if (!missing.isEmpty()) throw new IllegalStateException("SCORES_IN_POSTGRES needs " + missing);
        return new PostgresHelper(env.get("SCORECARD_DB_HOST"), env.get("SCORECARD_DB_PORT"),
                env.get("SCORECARD_DB_NAME"), env.get("SCORECARD_DB_USER"), env.get("SCORECARD_DB_PASSWORD"));
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(url, user, password);
        connection.setReadOnly(true);
        // Off, so a fetch size streams rows instead of buffering a whole Observer.
        connection.setAutoCommit(false);
        return connection;
    }

    /** What a run needs once the graph has said who is reached. */
    public record Stored(Map<Integer, String> pubkeys, Map<Integer, StoredScore> scores) {
    }

    /**
     * The pubkey of every id in [userIds], and every Scorecard the Observer has stored (by
     * Observee id; an Observer with none is a first-time Observer). An id the table does not
     * know means the graph and the table disagree: it throws.
     */
    public Stored load(String observer, Collection<Integer> userIds) {
        try (Connection connection = connect()) {
            Map<Integer, String> pubkeys = pubkeysOf(connection, userIds);
            if (pubkeys.size() != userIds.stream().distinct().count()) {
                long missing = userIds.stream().distinct().filter(id -> !pubkeys.containsKey(id)).count();
                throw new IllegalStateException(missing + " user ids from the graph are not in nostr_user");
            }
            return new Stored(pubkeys, scoresOf(connection, observer));
        } catch (SQLException e) {
            throw new IllegalStateException("Scorecard database read failed: " + e.getMessage(), e);
        }
    }

    private static Map<Integer, String> pubkeysOf(Connection connection, Collection<Integer> userIds)
            throws SQLException {
        Map<Integer, String> pubkeys = new HashMap<>(userIds.size() * 2);
        List<Integer> ids = new ArrayList<>(userIds);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, pubkey FROM nostr_user WHERE id = ANY(?)")) {
            statement.setFetchSize(FETCH_SIZE);
            for (int from = 0; from < ids.size(); from += ID_BATCH) {
                List<Integer> batch = ids.subList(from, Math.min(from + ID_BATCH, ids.size()));
                Array array = connection.createArrayOf("integer", batch.toArray());
                statement.setArray(1, array);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) pubkeys.put(rows.getInt(1), rows.getString(2));
                }
                array.free();
            }
        }
        return pubkeys;
    }

    /** One indexed range: the Observer's id is looked up first, so the scan has no join. */
    private static Map<Integer, StoredScore> scoresOf(Connection connection, String observer) throws SQLException {
        Map<Integer, StoredScore> scores = new HashMap<>();
        Integer observerId = null;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM nostr_user WHERE pubkey = ?")) {
            statement.setString(1, observer);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) observerId = rows.getInt(1);
            }
        }
        if (observerId == null) return scores;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT observee_id, influence, hops, verified_followers FROM scorecard WHERE observer_id = ?")) {
            statement.setFetchSize(FETCH_SIZE);
            statement.setInt(1, observerId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) scores.put(rows.getInt(1), storedScore(rows, 2));
            }
        }
        return scores;
    }

    /** The Observer's stored Scorecards for [pubkeys], by pubkey; those with none are absent. */
    public Map<String, StoredScore> scoresFor(String observer, Collection<String> pubkeys) {
        Map<String, StoredScore> scores = new HashMap<>();
        if (pubkeys.isEmpty()) return scores;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT u.pubkey, s.influence, s.hops, s.verified_followers "
                             + "FROM nostr_user o "
                             + "JOIN scorecard s ON s.observer_id = o.id "
                             + "JOIN nostr_user u ON u.id = s.observee_id "
                             + "WHERE o.pubkey = ? AND u.pubkey = ANY(?)")) {
            Array array = connection.createArrayOf("text", pubkeys.toArray());
            statement.setString(1, observer);
            statement.setArray(2, array);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) scores.put(rows.getString(1), storedScore(rows, 2));
            }
            array.free();
        } catch (SQLException e) {
            throw new IllegalStateException("Scorecard database read failed: " + e.getMessage(), e);
        }
        return scores;
    }

    private static StoredScore storedScore(ResultSet rows, int first) throws SQLException {
        Double influence = rows.getObject(first, Double.class);
        Number hops = (Number) rows.getObject(first + 1);
        Number followers = (Number) rows.getObject(first + 2);
        return new StoredScore(influence, hops == null ? null : hops.longValue(),
                followers == null ? null : followers.longValue());
    }
}
