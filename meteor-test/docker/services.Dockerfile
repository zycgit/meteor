ARG JAVA_IMAGE=eclipse-temurin:17-jre-noble
ARG ENVOY_IMAGE=envoyproxy/envoy@sha256:08acc53db6f4ac46e77e36b203c5254b4a2549869314bb54cf97aab81baa4566
FROM debian:bookworm-slim AS process-builder
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev && rm -rf /var/lib/apt/lists/*
COPY meteor-test/src/main/c/peer-process.c /tmp/peer_process.c
RUN gcc -std=c11 -O2 -Wall -Wextra -Werror -static /tmp/peer_process.c -o /peer-process
FROM ${JAVA_IMAGE} AS h2
ARG H2_VERSION=2.3.232
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && \
    rm -rf /var/lib/apt/lists/* && mkdir -p /opt/h2 && \
    curl --fail --silent --show-error --location \
    "https://repo.maven.apache.org/maven2/com/h2database/h2/${H2_VERSION}/h2-${H2_VERSION}.jar" \
    --output /opt/h2/h2.jar
CMD ["java", "-cp", "/opt/h2/h2.jar", "org.h2.tools.Server", "-tcp", "-tcpAllowOthers", "-ifNotExists", "-tcpPort", "9092"]
FROM ${ENVOY_IMAGE} AS envoy
USER root
COPY --from=process-builder /peer-process /opt/meteor-test/peer-process
WORKDIR /workspace/meteor
ENTRYPOINT []
CMD ["sleep", "infinity"]
