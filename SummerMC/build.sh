#!/bin/bash
# ============================================================================
# SummerMC Build Script
# Builds SummerMC.jar — All-in-one Minecraft Server CLI Optimizer
#
# Requirements: Java 21+ (JDK), Rust toolchain (optional), curl
#
# Usage:
#   bash build.sh                 # Full build
#   bash build.sh --no-rust       # Skip Rust build
#   bash build.sh --bench         # Include benchmark module
# ============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

VERSION="2.0.0"
OUTPUT="$SCRIPT_DIR/SummerMC.jar"
BUILD_DIR="/tmp/summermc-build-$$"
STAGING="$BUILD_DIR/staging"
CLASSES="$BUILD_DIR/classes"
MCOPT_DIR="$ROOT_DIR/mc-optimizer"
AGENT_JAR="$MCOPT_DIR/maven-agent/target/mc-opt-agent.jar"

WITH_RUST=true
WITH_BENCH=false
for arg in "$@"; do
    case "$arg" in --no-rust) WITH_RUST=false ;; --bench) WITH_BENCH=true ;; esac
done

echo ""
echo "╔═══════════════════════════════════════════════╗"
echo "║     🔨 SummerMC v${VERSION} Build Script       ║"
echo "╚═══════════════════════════════════════════════╝"
echo ""

mkdir -p "$CLASSES/com/summermc" "$CLASSES/com/mcopt" "$STAGING"

# ------------------------------------------------------------------
# Step 1: Native .so
# ------------------------------------------------------------------
if [ "$WITH_RUST" = true ] && command -v cargo &>/dev/null; then
    echo "🔧 Step 1: Building native .so (Rust)..."
    cd "$MCOPT_DIR"
    cargo build --release --target-dir "$BUILD_DIR/cargo" 2>&1 | tail -1
    mkdir -p "$STAGING/native/linux-x86-64"
    cp "$BUILD_DIR/cargo/release/libmc_native_opt.so" "$STAGING/native/linux-x86-64/"
    echo "   ✅ Native .so: $(ls -lh $STAGING/native/linux-x86-64/libmc_native_opt.so | awk '{print $5}')"
elif [ -f "$AGENT_JAR" ]; then
    echo "🔧 Step 1: Extracting .so from agent JAR..."
    cd "$STAGING"
    jar xf "$AGENT_JAR" native/linux-x86-64/libmc_native_opt.so 2>/dev/null || true
    if [ -f "native/linux-x86-64/libmc_native_opt.so" ]; then
        echo "   ✅ Extracted"
    else
        echo "   ⚡ No .so in agent (pure-JVM)"
    fi
else
    echo "🔧 Step 1: No native .so — pure-JVM mode"
fi

# ------------------------------------------------------------------
# Step 2: Compile SummerMC.java using existing agent as classpath
# ------------------------------------------------------------------
echo ""
echo "☕ Step 2: Compiling SummerMC launcher..."

cp "$SCRIPT_DIR/src/main/java/com/summermc/SummerMC.java" "$CLASSES/com/summermc/"

if [ "$WITH_BENCH" = true ] && [ -f "$SCRIPT_DIR/src/main/java/com/summermc/Benchmark.java" ]; then
    cp "$SCRIPT_DIR/src/main/java/com/summermc/Benchmark.java" "$CLASSES/com/summermc/"
fi

# NativeLoader source (needed if agent classes not available)
if [ -f "$MCOPT_DIR/maven-agent/src/main/java/com/mcopt/NativeLoader.java" ]; then
    cp "$MCOPT_DIR/maven-agent/src/main/java/com/mcopt/NativeLoader.java" "$CLASSES/com/mcopt/"
fi

# Compilation strategy: use agent JAR as classpath if it exists
if [ -f "$AGENT_JAR" ]; then
    # Extract agent classes for compilation
    cd "$STAGING"
    jar xf "$AGENT_JAR"
    rm -rf META-INF/maven 2>/dev/null || true

    # Compile SummerMC (+ Benchmark if present) using agent classes as classpath
    ALL_SOURCES=$(find "$CLASSES" -name '*.java' | tr '\n' ' ')
    javac -d "$CLASSES" --release 21 \
        -cp "$STAGING" \
        $ALL_SOURCES 2>&1 | grep -v "^Note:" || {
        echo "   ⚠  Retrying without --release (for JMX access)..."
        javac -d "$CLASSES" -source 21 -target 21 \
            -cp "$STAGING" \
            $ALL_SOURCES 2>&1 | grep -v "^Note:" || {
            echo "   ⚠  Removing Benchmark.java and retrying..."
            rm -f "$CLASSES/com/summermc/Benchmark.java"
            javac -d "$CLASSES" --release 21 \
                -cp "$STAGING" \
                "$CLASSES/com/summermc/SummerMC.java" 2>&1 | grep -v "^Note:" || true
        }
    }
else
    # Standalone: compile both SummerMC and NativeLoader together
    javac -d "$CLASSES" --release 21 \
        "$CLASSES/com/summermc/SummerMC.java" \
        "$CLASSES/com/mcopt/NativeLoader.java" 2>&1 | grep -v "^Note:" || true
fi

CLASS_COUNT=$(find "$CLASSES" -name '*.class' | wc -l)
echo "   ✅ $CLASS_COUNT classes"

# ------------------------------------------------------------------
# Step 3: Assemble SummerMC.jar
# ------------------------------------------------------------------
echo ""
echo "📦 Step 3: Assembling SummerMC.jar..."

# Ensure native dir
mkdir -p "$STAGING/native/linux-x86-64"

# Manifest
cat > "$BUILD_DIR/MANIFEST.MF" << 'MANIFEST'
Manifest-Version: 1.0
Created-By: SummerMC Build Script
Main-Class: com.summermc.SummerMC
Premain-Class: com.mcopt.McOptJavaAgent
Agent-Class: com.mcopt.McOptJavaAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
Can-Set-Native-Method-Prefix: true

MANIFEST

# Assemble
jar cfm "$OUTPUT" "$BUILD_DIR/MANIFEST.MF" \
    -C "$CLASSES" . \
    -C "$STAGING" . 2>/dev/null

# Verify
TOTAL_CLASSES=$(jar tf "$OUTPUT" 2>/dev/null | grep -c '\.class' || echo 0)
HAS_NATIVE=$(jar tf "$OUTPUT" 2>/dev/null | grep -c '\.so' || echo 0)
JAR_SIZE=$(ls -lh "$OUTPUT" 2>/dev/null | awk '{print $5}')

echo ""
echo "╔═══════════════════════════════════════════════╗"
echo "║     ✅ SummerMC.jar built!                    ║"
echo "║                                               ║"
echo "║     File: $OUTPUT                     ║"
echo "║     Size: $JAR_SIZE                          ║"
echo "║     Classes: $TOTAL_CLASSES                  ║"
echo "║     Native:  $([ "$HAS_NATIVE" -gt 0 ] && echo '✅ embedded' || echo '⚠  not available')  ║"
echo "║                                               ║"
echo "║     Usage:                                    ║"
echo "║       java -jar SummerMC.jar help             ║"
echo "╚═══════════════════════════════════════════════╝"
echo ""

rm -rf "$BUILD_DIR" 2>/dev/null || true
