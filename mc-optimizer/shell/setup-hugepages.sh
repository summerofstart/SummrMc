#!/bin/bash
# ============================================================================
# setup-hugepages.sh — Allocate 2MB HugePages for Minecraft
# Usage: sudo ./setup-hugepages.sh [pages] [--folia]
# ============================================================================
set -e

if [ "$EUID" -ne 0 ]; then
    echo "Please run as root: sudo $0 [pages] [--folia]"
    exit 1
fi

FOLIA=0
PAGES=0

for arg in "$@"; do
    case "$arg" in
        --folia) FOLIA=1 ;;
        *) PAGES="$arg" ;;
    esac
done

if [ "$PAGES" -eq 0 ]; then
    MEM_MB=$(grep MemTotal /proc/meminfo | awk '{print int($2/1024)}')
    if [ "$FOLIA" = "1" ]; then
        # Folia: larger allocation — 33% of RAM, less OS reserve
        PAGES=$(( (MEM_MB - 2048) * 1024 / 2048 / 3 ))
    else
        # Paper: conservative — 25% of RAM, 4GB OS reserve
        PAGES=$(( (MEM_MB - 4096) * 1024 / 2048 / 4 ))
    fi
fi

echo "Allocating $PAGES hugepages (2MB each = $(( PAGES * 2 / 1024 ))MB)..."
echo "$PAGES" > /proc/sys/vm/nr_hugepages
echo "Actual: $(cat /proc/sys/vm/nr_hugepages)"

# Verify
grep -i huge /proc/meminfo
