# Brainstorm Graperank Algorithm in Java

Build

```sh
docker build -t graperank .
```

Running

```sh
docker run -d -e REDIS_HOST=host.docker.internal   -e REDIS_PORT=6379  -e NEO4J_URL=neo4j://host.docker.internal:7687 -e  NEO4J_USERNAME=neo4j -e NEO4J_PASSWORD=password  graperank 
```

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
- **Pinned users** — the Observer and their designated (kind-10040) keys — are seeded once and never
  recomputed (`pinned[i]`).

The loop updates Influence in place, so the order users are visited in, and the order of each user's
ratings (follows → mutes → reports), change the result. Keep both as they are, or
`GrapeRankEquivalenceTest` fails.
