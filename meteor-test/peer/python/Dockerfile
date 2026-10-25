ARG PYTHON_IMAGE=python:3.13-slim-bookworm
ARG PYTHON_LEGACY_IMAGE=python:3.12.16-slim-bookworm
FROM ${PYTHON_LEGACY_IMAGE} AS python-legacy
FROM debian:bookworm-slim AS process-builder
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev && rm -rf /var/lib/apt/lists/*
COPY meteor-test/src/main/c/peer-process.c /tmp/peer_process.c
RUN gcc -std=c11 -O2 -Wall -Wextra -Werror -static /tmp/peer_process.c -o /peer-process
FROM ${PYTHON_IMAGE} AS python
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources && \
    apt-get update && \
    apt-get install -y --no-install-recommends gcc g++ libc6-dev && \
    rm -rf /var/lib/apt/lists/*
COPY meteor-test/peer/python/grpc/requirements.txt /opt/meteor-toolchain/requirements/grpc.txt
COPY meteor-test/peer/python/thrift/requirements.txt /opt/meteor-toolchain/requirements/thrift.txt
COPY meteor-test/peer/python/soap/requirements.txt /opt/meteor-toolchain/requirements/soap.txt
COPY meteor-test/peer/python/connect/requirements.txt /opt/meteor-toolchain/requirements/connect.txt
COPY meteor-test/peer/python/hprose/requirements.txt /opt/meteor-toolchain/requirements/hprose.txt
COPY meteor-test/peer/python/hessian/requirements.txt /opt/meteor-toolchain/requirements/hessian.txt
COPY meteor-test/peer/python/oncrpc/requirements.txt /opt/meteor-toolchain/requirements/oncrpc.txt
COPY meteor-test/peer/python/triple/requirements.txt /opt/meteor-toolchain/requirements/triple.txt
RUN --mount=type=cache,target=/root/.cache/pip \
    for peer in grpc thrift soap connect hprose hessian oncrpc triple; do \
        python -m venv "/opt/meteor-toolchain/python-${peer}" && \
        "/opt/meteor-toolchain/python-${peer}/bin/python" -m pip install \
            --requirement "/opt/meteor-toolchain/requirements/${peer}.txt" && \
        cp "/opt/meteor-toolchain/requirements/${peer}.txt" \
            "/opt/meteor-toolchain/python-${peer}/requirements-installed.txt" || exit; \
    done
COPY --from=python-legacy /usr/local /opt/meteor-toolchain/python-legacy
RUN echo /opt/meteor-toolchain/python-legacy/lib > /etc/ld.so.conf.d/meteor-python-legacy.conf && ldconfig
COPY meteor-test/peer/python/connect/versions/ /opt/meteor-toolchain/requirements/connect/versions/
COPY meteor-test/peer/python/soap/versions/ /opt/meteor-toolchain/requirements/soap/versions/
COPY meteor-test/peer/python/thrift/versions/ /opt/meteor-toolchain/requirements/thrift/versions/
COPY meteor-test/peer/python/prepare-versions.py /opt/meteor-toolchain/prepare-python-versions.py
RUN --mount=type=cache,target=/root/.cache/pip \
    python /opt/meteor-toolchain/prepare-python-versions.py --install \
        --source /opt/meteor-toolchain/requirements
COPY --from=process-builder /peer-process /opt/meteor-test/peer-process
WORKDIR /workspace/meteor
ENTRYPOINT []
CMD ["python3", "/workspace/meteor/meteor-test/peer/python/build-runtime.py"]
