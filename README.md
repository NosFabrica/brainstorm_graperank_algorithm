# Brainstorm Graperank Algorithm in Java

Build

```sh
docker build -t graperank .
```

Running

```sh
docker run -d -e REDIS_HOST=host.docker.internal   -e REDIS_PORT=6379  -e NEO4J_URL=neo4j://host.docker.internal:7687 -e  NEO4J_USERNAME=neo4j -e NEO4J_PASSWORD=password  graperank 
```