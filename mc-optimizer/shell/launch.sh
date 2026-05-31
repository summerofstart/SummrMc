#!/bin/bash
# ============================================================================
# launch.sh — Launch Minecraft Server with McOpt optimizer (Folia-aware)
# ============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# --- Config ---
SERVER_JAR="${SERVER_JAR:-$PROJECT_DIR/../server.jar}"
AGENT_JAR="${AGENT_JAR:-$PROJECT_DIR/maven-agent/target/mc-opt-agent.jar}"
MEM_MB="${MEM_MB:-$(grep MemTotal /proc/meminfo | awk '{print int($2/1024)}')}"

# Detect Folia
FOLIA=false
if jar -tf "$SERVER_JAR" 2>/dev/null | grep -q "io/papermc/paper/threadedregions/RegionizedServer"; then
    FOLIA=true
    echo "  ✓ Folia detected — using Folia profile"
fi
if [ "${FOLIA_FORCE:-0}" = "1" ]; then
    FOLIA=true
fi

if [ ! -f "$SERVER_JAR" ]; then
    echo "❌ Server JAR not found: $SERVER_JAR"
    echo "   Set SERVER_JAR env or place at $SERVER_JAR"
    exit 1
fi

if [ ! -f "$AGENT_JAR" ]; then
    echo "⚠  Agent JAR not found at $AGENT_JAR"
    echo "   Run 'make agent' to build it, or set AGENT_JAR"
    echo "   Continuing without agent..."
    AGENT_JAR=""
fi

# --- Heap sizing ---
if [ "$FOLIA" = true ]; then
    OS_RESERVE=$(( 4096 + ($(nproc) / 4) * 1024 ))
    HEAP_MB=$(( MEM_MB - OS_RESERVE ))
    if [ $HEAP_MB -le 0 ]; then
        HEAP_MB=$(( MEM_MB / 2 ))
    fi
else
    HEAP_MB=$(( MEM_MB * 75 / 100 ))
fi

# --- JVM Flags ---
JVM_FLAGS="-Xms${HEAP_MB}m -Xmx${HEAP_MB}m"

if [ "$FOLIA" = true ]; then
    JVM_FLAGS="$JVM_FLAGS -XX:+UseZGC -XX:+ZGenerational"
    JVM_FLAGS="$JVM_FLAGS -XX:ZCollectionInterval=0.05"
    JVM_FLAGS="$JVM_FLAGS -XX:ZFragmentationLimit=25"
    JVM_FLAGS="$JVM_FLAGS -XX:+ZUncommit -XX:ZUncommitDelay=30"
    JVM_FLAGS="$JVM_FLAGS -XX:ZAllocationSpikeTolerance=2.0"
    JVM_FLAGS="$JVM_FLAGS -XX:+ZNUMA"
    JVM_FLAGS="$JVM_FLAGS -XX:+AlwaysPreTouch"
    JVM_FLAGS="$JVM_FLAGS -XX:+DisableExplicitGC"
    JVM_FLAGS="$JVM_FLAGS -XX:+ParallelRefProcEnabled"
    JVM_FLAGS="$JVM_FLAGS -XX:+UseStringDeduplication"
    JVM_FLAGS="$JVM_FLAGS -XX:StringDeduplicationAgeThreshold=2"
    JVM_FLAGS="$JVM_FLAGS -XX:+OptimizeStringConcat"
    JVM_FLAGS="$JVM_FLAGS -XX:+UseCompressedOops"
    JVM_FLAGS="$JVM_FLAGS -XX:+UseCompressedClassPointers"
    JVM_FLAGS="$JVM_FLAGS -XX:+ExitOnOutOfMemoryError"
    JVM_FLAGS="$JVM_FLAGS -Xss512k"
else
    JVM_FLAGS="$JVM_FLAGS -XX:+UseZGC -XX:+ZGenerational"
    JVM_FLAGS="$JVM_FLAGS -XX:ZCollectionInterval=0.1"
    JVM_FLAGS="$JVM_FLAGS -XX:ZFragmentationLimit=25"
    JVM_FLAGS="$JVM_FLAGS -XX:+ZUncommit -XX:ZUncommitDelay=30"
    JVM_FLAGS="$JVM_FLAGS -XX:+AlwaysPreTouch"
    JVM_FLAGS="$JVM_FLAGS -XX:+DisableExplicitGC"
    JVM_FLAGS="$JVM_FLAGS -XX:+ParallelRefProcEnabled"
    JVM_FLAGS="$JVM_FLAGS -XX:+UseStringDeduplication"
    JVM_FLAGS="$JVM_FLAGS -XX:StringDeduplicationAgeThreshold=3"
    JVM_FLAGS="$JVM_FLAGS -XX:+OptimizeStringConcat"
    JVM_FLAGS="$JVM_FLAGS -XX:+UseCompressedOops"
    JVM_FLAGS="$JVM_FLAGS -XX:+UseCompressedClassPointers"
    JVM_FLAGS="$JVM_FLAGS -XX:+ExitOnOutOfMemoryError"
fi

JVM_FLAGS="$JVM_FLAGS -XX:+UseLargePages"
JVM_FLAGS="$JVM_FLAGS -XX:+UseTransparentHugePages"

# Agent
if [ -n "$AGENT_JAR" ]; then
    JVM_FLAGS="$JVM_FLAGS -javaagent:$AGENT_JAR"
fi

# Additional flags from env
if [ -n "$JVM_EXTRA" ]; then
    JVM_FLAGS="$JVM_FLAGS $JVM_EXTRA"
fi

# --- CPU pinning ---
CPU_MASK="${CPU_MASK:-0x3F}"
PREFIX=""

if command -v taskset &>/dev/null && [ -n "$CPU_MASK" ]; then
    PREFIX="taskset $CPU_MASK"
    echo "  CPU mask: $CPU_MASK"
fi

# --- Launch ---
echo ""
echo "╔═══════════════════════════════════════════════╗"
echo "║     🚀 Launching Minecraft Server (McOpt)     ║"
echo "╚═══════════════════════════════════════════════╝"
echo ""
echo "  Server:  $SERVER_JAR"
echo "  Heap:    ${HEAP_MB}MB"
echo "  Mode:    $([ "$FOLIA" = true ] && echo 'Folia' || echo 'Paper')"
echo "  Agent:   ${AGENT_JAR:-none}"
echo "  Prefix:  ${PREFIX:-none}"
echo ""

exec $PREFIX java $JVM_FLAGS -jar "$SERVER_JAR" --nogui
