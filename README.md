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