# Brainstorm Graperank Algorithm in Java

Build

```sh
docker build -t graperank .
```

Running

```sh
docker run -d -e REDIS_HOST=host.docker.internal   -e REDIS_PORT=6379  -e NEO4J_URL=neo4j://host.docker.internal:7687 -e  NEO4J_USERNAME=neo4j -e NEO4J_PASSWORD=password  graperank 
```

## Stored scores in the Scorecard database (`SCORES_IN_POSTGRES`)

Off by default: the worker reads an Observer's stored scores from Neo4j properties
(`Neo4jHelper`). With `SCORES_IN_POSTGRES=true` (and the five `SCORECARD_DB_*` settings, or the
worker refuses to start) it uses `ScorecardGraphDB` instead:

1. Neo4j walks the follows and returns each reached user's `user_id`, distance and NIP-05 status.
2. `PostgresHelper` gives the pubkey of every id (batches of 50,000 ids) and the Observer's stored
   Scorecards (`WHERE observer_id = ?`, one range, no join).

Job and result messages are unchanged (pubkeys). Flip it together with the server's
`SCORES_IN_POSTGRES`. `StoredScoresEquivalenceTest` (set `WORKER_IT=1`, see the class comment)
holds both forms to the same answer against a real Neo4j and Postgres.

## Adding a new GrapeRank param

Python (`brainstorm_server`) is the source of truth for preset values. Changes touch both repos — see the full checklist in [`brainstorm_server/README.md`](../brainstorm_server/README.md#adding--removing-a-graperank-preset-param).

On the Java side:
- `src/main/java/.../grape/GrapeRankParams.java` — add `@JsonProperty(required = true) double newField` (camelCase, matches Python).
- `src/main/java/.../grape/Constants.java` — add default + include it in `DEFAULT_PARAMS`.
- Wire into the algorithm.

Missing required fields in the Redis payload throw at `Main.resolveParams`; the request is pushed back with `success: false` and the Python server marks it `FAILED`.

## How a run is held in memory (`ScoreGraph`)

A run scores ~250k users over ~12M ratings, ~40 rounds. To keep that fast and small:

- **Int ids.** Each pubkey gets an index `0..n-1` once; everything after works on ints, not pubkey strings.
- **SoA (struct of arrays).** Instead of one `ScoreCard` object per user, each field is its own array:
  `influence[i]`, `confidence[i]`, `hops[i]`, … for user `i`. `ScoreCard`s are only built at the end for the JSON.
- **CSR (compressed sparse rows).** The incoming ratings of user `i` sit in `src[off[i] .. off[i+1])`
  (rater ids), with `ratingConfidence[]` / `rating[]` at the same positions. Followers, muters and
  reporters (for the trusted counts) use the same layout.
- **Pinned users** — the Observer, their designated (kind-10040) keys, and the key that signs their
  zap receipts (`zaps_via:<observer>`, read even when the follow walk returns nothing) — are seeded
  once and never recomputed (`pinned[i]`).
- **Designations** — everyone else's kind-10040 keys and NIP-57 zap signers, read from the forward
  `designates:<pubkey>` and `zaps_via:<pubkey>` sets (the latter written by brainstorm_profile_resolver)
  for every reachable user before the graph is built — are one more rating kind (`DESIGNATE`): rating
  `DESIGNATION_RATING` (0.95) at follow confidence, appended after each ratee's follows, mutes and
  reports. A designated key joins the run even when no follow reaches it (hops `UNREACHABLE_HOPS`).

- **NIP-05 penalties** (brainstorm_server ADR 0006) — `factor[i]` multiplies each computed Influence
  inside the sweep: 0 for a confirmed impersonation (the domain gives the user's claimed name to another
  key), 0.5 for a claim the domain does not back, 1 for everyone else. Inside the sweep, not on the
  published number, so what a penalised user passes to the people they follow shrinks with it. Statuses
  come from the follow walk's Neo4j query (`nip05_status`, written by brainstorm_profile_resolver), plus
  one lookup for keys reached only by designation. Pinned users are never penalised.

- **Compromised keys** — the calc request's `compromised_pubkeys` (brainstorm_server's
  `compromised_pubkey` table, read when the run is queued) are held at Influence 0 from the first
  sweep (`ScoreGraph.compromise`): factor 0, so nothing they rate — follows, mutes, reports,
  designations — carries weight, and they count toward nobody's trusted followers, muters or
  reporters. Unlike a NIP-05 penalty this overrides a designation pin: whoever holds the key now is
  not who the Observer designated. A run whose Observer is on the list is refused
  (`COMPROMISED_OBSERVER`): its perspective is whoever holds the key now. brainstorm_server also cuts
  the key's own follows, mutes, reports and designations from Neo4j and Redis when it is marked, so
  the follow walk stops at it. A request without the field (an older server) zeroes nothing; a
  malformed one zeroes nothing and is logged.

The loop updates Influence in place, so the order users are visited in, and the order of each user's
ratings (follows → mutes → reports), change the result. Keep both as they are, or
`GrapeRankEquivalenceTest` fails.
