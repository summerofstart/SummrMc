.PHONY: all rust agent launcher install-launcher jar clean docker docker-run docker-light docker-folia quick

# ============================================================================
# McOpt v2.0 — All-in-one Folia-aware Minecraft Optimizer
#   make jar   → builds mc-opt-agent.jar (native .so embedded inside)
#   make       → alias for jar
#   make docker-run → Docker with full native support
# ============================================================================

ROOT_DIR := $(shell dirname $(realpath $(firstword $(MAKEFILE_LIST))))
AGENT_JAR := maven-agent/target/mc-opt-agent.jar
CARGO_TARGET := /tmp/mcopt-target
LAUNCHER_CLASSES := /tmp/mcopt-launcher-classes
LAUNCHER_JAR := /tmp/server-launcher.jar

# ===========================================================================
# All-in-one JAR — builds Rust .so, copies into resources, then Maven shade
# ===========================================================================
all: jar

jar: rust copy-so agent
	@echo ""
	@echo "╔═══════════════════════════════════════════════╗"
	@echo "║  ✅ mc-opt-agent.jar built                    ║"
	@echo "║  $(ROOT_DIR)/$(AGENT_JAR)            ║"
	@echo "╚═══════════════════════════════════════════════╝"
	@ls -lh "$(ROOT_DIR)/$(AGENT_JAR)"

# Quick rebuild (skip Rust if .so exists)
quick: copy-so agent
	@echo "⚡ Quick rebuild (using existing .so)"
	@ls -lh "$(ROOT_DIR)/$(AGENT_JAR)"

# ===========================================================================
# server.jar launcher — runs the real server jar with sudo-less JVM tuning
# ===========================================================================
launcher: agent
	@echo "☕ Building server.jar launcher..."
	rm -rf "$(LAUNCHER_CLASSES)"
	mkdir -p "$(LAUNCHER_CLASSES)"
	javac --release 21 -d "$(LAUNCHER_CLASSES)" "$(ROOT_DIR)/launcher/src/com/mcopt/ServerLauncher.java"
	cp "$(ROOT_DIR)/$(AGENT_JAR)" "$(LAUNCHER_CLASSES)/mc-opt-agent.jar"
	jar cfe "$(LAUNCHER_JAR)" com.mcopt.ServerLauncher -C "$(LAUNCHER_CLASSES)" .
	@echo "✓ Launcher: $(LAUNCHER_JAR)"

install-launcher: launcher
	@if [ ! -f "$(ROOT_DIR)/../server.paperclip.jar" ]; then \
	    mv "$(ROOT_DIR)/../server.jar" "$(ROOT_DIR)/../server.paperclip.jar"; \
	fi
	cp "$(LAUNCHER_JAR)" "$(ROOT_DIR)/../server.jar"
	@echo "✓ Installed launcher as $(ROOT_DIR)/../server.jar"
	@echo "  Real server jar: $(ROOT_DIR)/../server.paperclip.jar"

# ===========================================================================
# Rust native library (cdylib = .so)
# ===========================================================================
rust:
	@echo "🔧 Building native optimizer (Rust)..."
	cd "$(ROOT_DIR)" && cargo build --release --target-dir $(CARGO_TARGET)
	@echo "✓ libmc_native_opt.so built"
	@ls -lh $(CARGO_TARGET)/release/libmc_native_opt.so

copy-so:
	@echo "📦 Copying .so into JAR resources..."
	cp $(CARGO_TARGET)/release/libmc_native_opt.so \
	    "$(ROOT_DIR)/maven-agent/src/main/resources/native/linux-x86-64/libmc_native_opt.so"
	@echo "✓ .so copied"

# ===========================================================================
# Java agent (Maven shade → fat JAR)
# ===========================================================================
agent:
	@echo "☕ Building all-in-one JAR (Maven shade)..."
	cd "$(ROOT_DIR)/maven-agent" && mvn package -DskipTests -q
	@echo "✓ JAR: $(AGENT_JAR)"

# ===========================================================================
# Docker
# ===========================================================================
docker:
	@echo "🐳 Building Docker image (multi-stage, all-in-one)..."
	cd "$(ROOT_DIR)" && docker build -t mc-opt-agent:latest -f docker/Dockerfile .
	@echo "✓ Docker image: mc-opt-agent:latest"

docker-run: docker
	@echo "🐳 Starting Docker container..."
	sudo mkdir -p "$(ROOT_DIR)/docker/mc-data"
	cp "$(ROOT_DIR)/../server.jar" "$(ROOT_DIR)/docker/mc-data/" 2>/dev/null || true
	cd "$(ROOT_DIR)/docker" && docker compose up -d
	@echo "✓ Container started: mc-optimized"

docker-stop:
	cd "$(ROOT_DIR)/docker" && docker compose down

docker-logs:
	cd "$(ROOT_DIR)/docker" && docker compose logs -f

# ===========================================================================
# Host system tuning
# ===========================================================================
tune-host:
	@echo "🔧 Tuning host system (requires sudo)..."
	sudo bash "$(ROOT_DIR)/shell/setup-host.sh"

tune-folia:
	@echo "🔧 Tuning host system for Folia (requires sudo)..."
	sudo bash "$(ROOT_DIR)/shell/setup-host.sh --folia"

# ===========================================================================
# Clean
# ===========================================================================
clean:
	cd "$(ROOT_DIR)" && cargo clean --target-dir $(CARGO_TARGET) 2>/dev/null || true
	cd "$(ROOT_DIR)/maven-agent" && mvn clean
	rm -f "$(ROOT_DIR)/maven-agent/src/main/resources/native/linux-x86-64/libmc_native_opt.so"
	rm -f "$(ROOT_DIR)/docker/mc-opt-agent.jar"
	rm -rf "$(LAUNCHER_CLASSES)" "$(LAUNCHER_JAR)"
	@echo "✓ Clean complete"

# ===========================================================================
# Info
# ===========================================================================
info:
	@echo "McOpt v2.0 — All-in-one Folia-aware Minecraft Optimizer"
	@echo ""
	@echo "   Build:     make jar"
	@echo "   Launcher:  make install-launcher"
	@echo "   Docker:    make docker-run"
	@echo "   Tune host: sudo make tune-host (Paper)"
	@echo "              sudo make tune-folia (Folia)"
	@echo ""
	@echo "   Usage: add -javaagent:/path/to/mc-opt-agent.jar to your JVM flags"
	@echo "   Drop the same JAR into plugins/ (works as Bukkit plugin too)"
