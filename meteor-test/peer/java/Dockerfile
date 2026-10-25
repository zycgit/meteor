ARG JAVA_IMAGE=eclipse-temurin:17-jdk-noble
ARG DOCKER_CLI_IMAGE=docker:29.5.3-cli
FROM debian:bookworm-slim AS process-builder
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev && rm -rf /var/lib/apt/lists/*
COPY meteor-test/src/main/c/peer-process.c /tmp/peer-process.c
COPY meteor-test/src/test/c/peer-process-race.c /tmp/peer_process_race.c
RUN gcc -std=c11 -O2 -Wall -Wextra -Werror -static /tmp/peer-process.c -o /peer-process \
    && gcc -std=c11 -O2 -Wall -Wextra -Werror -static -I/tmp /tmp/peer_process_race.c -o /peer-process-race
FROM ${DOCKER_CLI_IMAGE} AS docker-cli
FROM ${JAVA_IMAGE} AS java
RUN apt-get update && apt-get install -y --no-install-recommends \
    bash ca-certificates curl git openssl unzip && rm -rf /var/lib/apt/lists/*
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=process-builder /peer-process /opt/meteor-test/peer-process
COPY --from=process-builder /peer-process-race /opt/meteor-test/peer-process-race
WORKDIR /workspace/meteor
ENTRYPOINT []
CMD ["sleep", "infinity"]
