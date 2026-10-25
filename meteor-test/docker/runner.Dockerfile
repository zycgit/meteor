ARG JAVA_IMAGE=eclipse-temurin:17-jdk-noble
ARG DOCKER_CLI_IMAGE=docker:29.5.3-cli
FROM ${DOCKER_CLI_IMAGE} AS docker-cli
FROM ${JAVA_IMAGE}
RUN apt-get update && apt-get install -y --no-install-recommends \
    bash ca-certificates curl git openssl unzip && rm -rf /var/lib/apt/lists/*
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
WORKDIR /workspace/meteor
ENTRYPOINT []
CMD ["sleep", "infinity"]
