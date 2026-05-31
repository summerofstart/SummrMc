# ============================================================================
# Dockerfile — MC Optimizer Docker Image (Folia-aware, all-in-one JAR)
# Builds a multi-stage image — the Rust native .so is embedded inside the JAR.
# Just drop mc-opt-agent.jar into /opt/ and you're done.
# ============================================================================

# ---- Stage 1: Build Rust native library + Java agent into one JAR ----
FROM rust:1.90-slim-bookworm AS builder-rust

RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc libc6-dev pkg-config && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY Cargo.toml Cargo.lock* ./
COPY src/ ./src/

RUN cargo build --release && \
    cp target/release/libmc_native_opt.so /libmc_native_opt.so

# ---- Stage 2: Build Java agent JAR with .so embedded ----
FROM maven:3.9-eclipse-temurin-21 AS builder-java

WORKDIR /build
COPY maven-agent/ ./maven-agent/
COPY --from=builder-rust /libmc_native_opt.so \
    ./maven-agent/src/main/resources/native/linux-x86-64/libmc_native_opt.so

RUN cd maven-agent && mvn package -DskipTests -q && \
    cp target/mc-opt-agent.jar /mc-opt-agent.jar

# ---- Stage 3: Runtime image — one JAR to rule them all ----
FROM eclipse-temurin:21-jdk-jammy AS runtime

LABEL maintainer="McOpt Team"
LABEL description="Minecraft Server with McOpt Folia-aware optimizer — all-in-one JAR"

# Minimal runtime tools
RUN apt-get update && apt-get install -y --no-install-recommends \
    numactl procps lsof && rm -rf /var/lib/apt/lists/*

# Single artifact: the all-in-one JAR
COPY --from=builder-java /mc-opt-agent.jar /opt/mc-opt-agent.jar

# Entrypoint (Folia-aware)
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

VOLUME ["/data"]
WORKDIR /data
EXPOSE 25565

HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
    CMD lsof -i :25565 || exit 1

ENTRYPOINT ["/entrypoint.sh"]