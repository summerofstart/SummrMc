#!/bin/bash
# ============================================================================
# setup-host.sh — One-shot host system tuning for Minecraft Server
# Folia-aware version — includes NUMA, multi-core scheduling optimizations.
# Runs outside Docker to prepare the host OS.
# Requires root (sudo).
# ============================================================================

set -e

echo "╔═══════════════════════════════════════════════╗"
echo "║  🔧 McOpt Host Setup (Folia-aware)            ║"
echo "║  Ultra-low-layer kernel tuning for Minecraft  ║"
echo "╚═══════════════════════════════════════════════╝"
echo ""

# Check root
if [ "$EUID" -ne 0 ]; then
    echo "❌ Please run as root: sudo $0"
    exit 1
fi

echo "🖥  System: $(uname -r)"
echo "🧠 CPU: $(grep 'model name' /proc/cpuinfo | head -1 | cut -d: -f2 | xargs)"
echo "💾 RAM: $(free -h | grep Mem | awk '{print $2}')"
echo ""

# Auto-detect Folia by checking if user passed --folia
FOLIA=0
if [ "$1" = "--folia" ]; then
    FOLIA=1
    echo "✓ Folia profile selected — multi-core scheduling optimizations enabled"
fi

echo ""

# ===== 1. Kernel parameters =====
echo "=== 1. Kernel parameters ==="

sysctl -w vm.swappiness=10
sysctl -w vm.dirty_ratio=5
sysctl -w vm.dirty_background_ratio=2
sysctl -w vm.vfs_cache_pressure=50
sysctl -w vm.stat_interval=10
sysctl -w vm.page-cluster=0
sysctl -w vm.compaction_proactiveness=20
sysctl -w vm.min_free_kbytes=65536
sysctl -w vm.max_map_count=262144

if [ "$FOLIA" = "1" ]; then
    # Folia: finer-grained scheduler, NUMA-aware
    sysctl -w kernel.sched_min_granularity_ns=1500000
    sysctl -w kernel.sched_latency_ns=12000000
    sysctl -w kernel.sched_wakeup_granularity_ns=1200000
    sysctl -w kernel.sched_migration_cost_ns=250000
    sysctl -w kernel.sched_nr_migrate=16
    sysctl -w kernel.sched_autogroup_enabled=1
    sysctl -w kernel.numa_balancing=1
    sysctl -w kernel.sched_energy_aware=0
    echo "✓ Folia kernel scheduler parameters optimized"
else
    # Paper: latency-focused single-thread
    sysctl -w kernel.sched_min_granularity_ns=3000000
    sysctl -w kernel.sched_latency_ns=18000000
    sysctl -w kernel.sched_wakeup_granularity_ns=2000000
    sysctl -w kernel.sched_migration_cost_ns=500000
    sysctl -w kernel.sched_nr_migrate=32
    sysctl -w kernel.sched_autogroup_enabled=0
    sysctl -w kernel.numa_balancing=0
    echo "✓ Paper kernel scheduler parameters optimized"
fi

# Network — BBR congestion control
modprobe tcp_bbr 2>/dev/null || true
sysctl -w net.core.default_qdisc=fq
sysctl -w net.ipv4.tcp_congestion_control=bbr
sysctl -w net.core.somaxconn=2048
sysctl -w net.core.netdev_budget=600
sysctl -w net.core.dev_weight=64
sysctl -w net.ipv4.tcp_fastopen=3
sysctl -w net.ipv4.tcp_slow_start_after_idle=0
sysctl -w net.ipv4.tcp_notsent_lowat=16384

echo "✓ Network parameters optimized"
echo ""

# ===== 2. HugePages =====
echo "=== 2. HugePages ==="

TOTAL_MB=$(grep MemTotal /proc/meminfo | awk '{print int($2/1024)}')

if [ "$FOLIA" = "1" ]; then
    # Folia: more hugepages (memory divided across more regions)
    HUGEPAGES_MB=$(( (TOTAL_MB - 2048) / 3 ))  # 33% of RAM
else
    HUGEPAGES_MB=$(( (TOTAL_MB - 4096) / 4 ))  # 25% of RAM
fi

HUGEPAGES_COUNT=$(( HUGEPAGES_MB * 1024 / 2048 ))

if [ $HUGEPAGES_COUNT -gt 0 ]; then
    echo "Allocating ${HUGEPAGES_COUNT} hugepages (2MB each = ${HUGEPAGES_MB}MB)..."
    echo $HUGEPAGES_COUNT > /proc/sys/vm/nr_hugepages 2>/dev/null || echo "⚠  Could not allocate (not enough contiguous memory)"
    ACTUAL=$(cat /proc/sys/vm/nr_hugepages)
    echo "  Requested: $HUGEPAGES_COUNT, Actual: $ACTUAL"
fi

echo madvise > /sys/kernel/mm/transparent_hugepage/enabled 2>/dev/null || true
echo madvise > /sys/kernel/mm/transparent_hugepage/defrag 2>/dev/null || true
echo "✓ THP set to madvise mode"
echo ""

# ===== 3. CPU governor =====
echo "=== 3. CPU Governor ==="

for cpu in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
    echo performance > "$cpu" 2>/dev/null || true
done
echo "✓ CPU governor set to performance"
echo ""

# ===== 4. I/O scheduler =====
echo "=== 4. I/O Scheduler ==="

for dev in /sys/block/*/queue/scheduler; do
    case "$dev" in
        */loop*|*/dm*|*/zram*) continue ;;
    esac
    echo none > "$dev" 2>/dev/null || \
    echo mq-deadline > "$dev" 2>/dev/null || true
    echo 2 > "$(dirname "$dev")/nomerges" 2>/dev/null || true
    echo 128 > "$(dirname "$dev")/nr_requests" 2>/dev/null || true
    echo 128 > "$(dirname "$dev")/read_ahead_kb" 2>/dev/null || true
done
echo "✓ I/O scheduler optimized"
echo ""

# ===== 5. IRQ affinity =====
echo "=== 5. IRQ Tuning ==="

for irq_dir in /proc/irq/*/; do
    irq=$(basename "$irq_dir")
    if grep -q "eth\|enp\|nvme\|sd\|virtio" "$irq_dir/affinity_hint" 2>/dev/null; then
        echo 0f > "$irq_dir/smp_affinity" 2>/dev/null || true
    fi
done
echo "✓ IRQ affinity set (NIC/block → CPUs 0-3)"
echo ""

# ===== 6. Preserve settings =====
echo "=== 6. Preserve across reboots ==="

if [ ! -f /etc/sysctl.d/99-mcopt.conf ]; then
    cat > /etc/sysctl.d/99-mcopt.conf << 'EOF'
# McOpt — Minecraft Server Kernel Tuning
vm.swappiness=10
vm.dirty_ratio=5
vm.dirty_background_ratio=2
vm.vfs_cache_pressure=50
vm.stat_interval=10
vm.page-cluster=0
vm.compaction_proactiveness=20
vm.min_free_kbytes=65536
vm.max_map_count=262144
kernel.sched_min_granularity_ns=3000000
kernel.sched_latency_ns=18000000
kernel.sched_wakeup_granularity_ns=2000000
kernel.sched_migration_cost_ns=500000
kernel.sched_nr_migrate=32
kernel.sched_autogroup_enabled=0
kernel.numa_balancing=0
net.core.default_qdisc=fq
net.ipv4.tcp_congestion_control=bbr
net.core.somaxconn=2048
net.core.netdev_budget=600
net.core.dev_weight=64
net.ipv4.tcp_fastopen=3
net.ipv4.tcp_slow_start_after_idle=0
net.ipv4.tcp_notsent_lowat=16384
EOF
    echo "✓ /etc/sysctl.d/99-mcopt.conf created"
fi

# Also create Folia variant
if [ ! -f /etc/sysctl.d/99-mcopt-folia.conf ]; then
    cat > /etc/sysctl.d/99-mcopt-folia.conf << 'EOF'
# McOpt — Minecraft Server Kernel Tuning (Folia variant)
vm.swappiness=10
vm.dirty_ratio=5
vm.dirty_background_ratio=2
vm.vfs_cache_pressure=50
vm.stat_interval=10
vm.page-cluster=0
vm.compaction_proactiveness=20
vm.min_free_kbytes=65536
vm.max_map_count=262144
kernel.sched_min_granularity_ns=1500000
kernel.sched_latency_ns=12000000
kernel.sched_wakeup_granularity_ns=1200000
kernel.sched_migration_cost_ns=250000
kernel.sched_nr_migrate=16
kernel.sched_autogroup_enabled=1
kernel.numa_balancing=1
net.core.default_qdisc=fq
net.ipv4.tcp_congestion_control=bbr
net.core.somaxconn=2048
net.core.netdev_budget=600
net.core.dev_weight=64
net.ipv4.tcp_fastopen=3
net.ipv4.tcp_slow_start_after_idle=0
net.ipv4.tcp_notsent_lowat=16384
EOF
    echo "✓ /etc/sysctl.d/99-mcopt-folia.conf created"
fi

# HugePages service
if [ ! -f /etc/systemd/system/mcopt-hugepages.service ]; then
    cat > /etc/systemd/system/mcopt-hugepages.service << 'EOF'
[Unit]
Description=McOpt HugePages Allocator
After=network.target

[Service]
Type=oneshot
ExecStart=/bin/sh -c 'echo $(awk "/MemTotal/{print int((($2-4194304)/4)/2048)}" /proc/meminfo) > /proc/sys/vm/nr_hugepages'
RemainAfterExit=true

[Install]
WantedBy=multi-user.target
EOF
    systemctl daemon-reload 2>/dev/null || true
    echo "✓ /etc/systemd/system/mcopt-hugepages.service created"
fi

echo ""
echo "╔═══════════════════════════════════════════════╗"
echo "║  ✅ Host tuning complete!                     ║"
echo "║                                               ║"
echo "║  For Folia:  sudo ./setup-host.sh --folia     ║"
echo "║  For Paper:  sudo ./setup-host.sh             ║"
echo "║                                               ║"
echo "║  Next: make all && make run (or folia-run)    ║"
echo "╚═══════════════════════════════════════════════╝"