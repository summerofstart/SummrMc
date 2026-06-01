#!/bin/bash
set -e

echo ""
echo "╔═══════════════════════════════════════════════╗"
echo "║     McOpt unified Minecraft launcher          ║"
echo "╚═══════════════════════════════════════════════╝"
echo ""

if [ -z "$SERVER_JAR" ]; then
    SERVER_JAR=$(find /data -maxdepth 1 -type f -name "*.jar" \
        ! -name "server.jar" \
        ! -name "mc-opt-agent.jar" \
        | sort | head -1)
fi

if [ -z "$SERVER_JAR" ] || [ ! -f "$SERVER_JAR" ]; then
    echo "No real Minecraft server jar found in /data."
    echo "Mount one as /data/server.paperclip.jar or set SERVER_JAR=/data/<name>.jar."
    exit 1
fi

if [ ! -f /data/eula.txt ]; then
    echo "eula=true" > /data/eula.txt
    echo "Created /data/eula.txt"
fi

export MC_REAL_SERVER_JAR="$SERVER_JAR"

echo "Server JAR: $MC_REAL_SERVER_JAR"
echo "Java: $(java -version 2>&1 | head -1)"
echo ""

exec java -jar /opt/server.jar "$@"
