#!/bin/bash
# ============================================================================
# entrypoint.sh — McOpt Docker entrypoint (Folia-aware)
# Applies kernel-level tuning in Docker (with appropriate capabilities),
# then launches the Minecraft server with McOpt JVM agent.
# Auto-detects Folia fork vs Paper.
# ============================================================================
set -e

echo ""
echo "╔═══════════════════════════════════════════════╗"
echo "║     🚀 McOpt Minecraft Optimizer Agent        ║"
echo "║     Folia-aware version 2.0.0                 ║"
echo "╚═══════════════════════════════════════════════╝"
echo ""

# --- Detect environment ---
MEM_MB=$(grep MemTotal /proc/meminfo | awk '{print int($2/1024)}')
CPU_COUNT=$(nproc)
echo "  CPUs:         $CPU_COUNT"
echo "  Memory:       ${MEM_MB}MB"
echo "  Java:         $(java -version 2>&1 | head -1)"
echo "  McOpt Agent:  /opt/mc-opt-agent.jar"
echo ""

# --- Detect Folia vs Paper ---
FOLIA=false
if [ -n "$SERVER_JAR" ] && [ -f "$SERVER_JAR" ]; then
    if jar -tf "$SERVER_JAR" 2>/dev/null | grep -q "io/papermc/paper/threadedregions/RegionizedServer"; then
        FOLIA=true
        echo "  ✓ Folia detected — enabling region-aware optimizations"
    fi
fi
# Allow env override
if [ "${FOLIA_FORCE:-0}" = "1" ]; then
    FOLIA=true
fi
echo "  Server mode:  $([ "$FOLIA" = true ] && echo 'Folia' || echo 'Paper/Spigot')"
echo ""

# --- Apply kernel tuning (if we have CAP_SYS_ADMIN) ---
if [ -w /proc/sys/vm/swappiness ] 2>/dev/null; then
    echo "  ⚙  Applying kernel tuning (CAP_SYS_ADMIN detected)..."

    if [ "$FOLIA" = true ]; then
        # Folia profile — finer-grained scheduling, NUMA-aware
        echo 10 > /proc/sys/vm/swappiness 2>/dev/null || true
        echo 5 > /proc/sys/vm/dirty_ratio 2>/dev/null || true
        echo 2 > /proc/sys/vm/dirty_background_ratio 2>/dev/null || true
        echo 50 > /proc/sys/vm/vfs_cache_pressure 2>/dev/null || true
        echo 1500000 > /proc/sys/kernel/sched_min_granularity_ns 2>/dev/null || true
        echo 12000000 > /proc/sys/kernel/sched_latency_ns 2>/dev/null || true
        echo 1200000 > /proc/sys/kernel/sched_wakeup_granularity_ns 2>/dev/null || true
        echo 1 > /proc/sys/kernel/sched_autogroup_enabled 2>/dev/null || true
        echo 1 > /proc/sys/kernel/numa_balancing 2>/dev/null || true
        echo "  ✓ Folia kernel tuning applied"
    else
        # Paper profile — latency-focused single-thread
        echo 10 > /proc/sys/vm/swappiness 2>/dev/null || true
        echo 5 > /proc/sys/vm/dirty_ratio 2>/dev/null || true
        echo 2 > /proc/sys/vm/dirty_background_ratio 2>/dev/null || true
        echo 50 > /proc/sys/vm/vfs_cache_pressure 2>/dev/null || true
        echo 3000000 > /proc/sys/kernel/sched_min_granularity_ns 2>/dev/null || true
        echo 18000000 > /proc/sys/kernel/sched_latency_ns 2>/dev/null || true
        echo 2000000 > /proc/sys/kernel/sched_wakeup_granularity_ns 2>/dev/null || true
        echo 0 > /proc/sys/kernel/sched_autogroup_enabled 2>/dev/null || true
        echo 0 > /proc/sys/kernel/numa_balancing 2>/dev/null || true
        echo "  ✓ Paper kernel tuning applied"
    fi

    # Network (common)
    echo "bbr" > /proc/sys/net/ipv4/tcp_congestion_control 2>/dev/null || true
    echo 0 > /proc/sys/net/ipv4/tcp_slow_start_after_idle 2>/dev/null || true
else
    echo "  ℹ  No CAP_SYS_ADMIN — kernel tuning skipped (add --cap-add=SYS_ADMIN for full effect)"
fi

# --- CPU governor hint ---
if [ -d /sys/devices/system/cpu ]; then
    echo "  ℹ  CPU governor: $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo 'N/A')"
fi

# --- Default JVM flags for McOpt ---
JVM_FLAGS="${JVM_FLAGS:-}"

# Auto-tune memory if not explicitly set
if ! echo "$JVM_FLAGS" | grep -q '\-Xmx'; then
    if [ "$FOLIA" = true ]; then
        # Folia: reserve more for OS (extra threads, NUMA)
        OS_RESERVE=$(( 4096 + (CPU_COUNT / 4) * 1024 ))
        HEAP_MB=$(( MEM_MB - OS_RESERVE ))
        if [ $HEAP_MB -le 0 ]; then
            HEAP_MB=$(( MEM_MB / 2 ))
        fi
    else
        # Paper: 75% of available memory
        HEAP_MB=$(( MEM_MB * 75 / 100 ))
    fi

    # Cap at 32GB for ZGC
    if [ $HEAP_MB -gt 32768 ]; then
        HEAP_MB=32768
    fi
    JVM_FLAGS="$JVM_FLAGS -Xms${HEAP_MB}m -Xmx${HEAP_MB}m"
    echo "  ℹ  Auto-tuned heap: ${HEAP_MB}MB"
fi

# --- Folia-optimized JVM flags ---
if [ "$FOLIA" = true ]; then
    if ! echo "$JVM_FLAGS" | grep -q 'UseZGC'; then
        JVM_FLAGS="$JVM_FLAGS -XX:+UseZGC -XX:+ZGenerational"
        JVM_FLAGS="$JVM_FLAGS -XX:ZCollectionInterval=0.05"
        JVM_FLAGS="$JVM_FLAGS -XX:ZFragmentationLimit=25"
        JVM_FLAGS="$JVM_FLAGS -XX:+ZUncommit -XX:ZUncommitDelay=30"
        JVM_FLAGS="$JVM_FLAGS -XX:ZAllocationSpikeTolerance=2.0"
        JVM_FLAGS="$JVM_FLAGS -XX:+ZNUMA"
    fi

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
    # Paper-optimized JVM flags
    if ! echo "$JVM_FLAGS" | grep -q 'UseZGC'; then
        JVM_FLAGS="$JVM_FLAGS -XX:+UseZGC -XX:+ZGenerational"
        JVM_FLAGS="$JVM_FLAGS -XX:ZCollectionInterval=0.1"
        JVM_FLAGS="$JVM_FLAGS -XX:ZFragmentationLimit=25"
        JVM_FLAGS="$JVM_FLAGS -XX:+ZUncommit -XX:ZUncommitDelay=30"
    fi

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

# Large pages
JVM_FLAGS="$JVM_FLAGS -XX:+UseLargePages -XX:+UseTransparentHugePages"

# McOpt javaagent
JVM_FLAGS="$JVM_FLAGS -javaagent:/opt/mc-opt-agent.jar"

# Debug flags
if [ "${DEBUG:-0}" = "1" ]; then
    JVM_FLAGS="$JVM_FLAGS -XX:+PrintGCDetails -XX:+PrintGCDateStamps"
    JVM_FLAGS="$JVM_FLAGS -Xlog:gc*:file=/data/gc.log:time,uptime,level,tags"
fi

echo ""
echo "  JVM flags: $JVM_FLAGS"
echo ""

# --- Determine server JAR ---
if [ -z "$SERVER_JAR" ]; then
    SERVER_JAR=$(ls /data/*.jar 2>/dev/null | head -1)
    if [ -z "$SERVER_JAR" ]; then
        echo "  ❌ No server JAR found in /data/"
        echo "  ℹ  Mount your server.jar at /data/ or set SERVER_JAR env"
        exit 1
    fi
fi
echo "  Server JAR: $SERVER_JAR"

# --- EULA check ---
if [ ! -f /data/eula.txt ]; then
    echo "eula=true" > /data/eula.txt
    echo "  ℹ  Auto-accepted EULA (eula.txt created)"
fi

# --- Launch ---
echo ""
echo "╔═══════════════════════════════════════════════╗"
echo "║     🎮 Starting Minecraft Server...           ║"
echo "╚═══════════════════════════════════════════════╝"
echo ""

exec java $JVM_FLAGS -jar "$SERVER_JAR" --nogui
