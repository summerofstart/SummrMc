// ============================================================================
// MC-NATIVE-OPT — Ultra-low-layer Minecraft Server Optimizer
// Architecture: x86_64-linux | cgroup v2 | hugepages 2M/1G
// Hooks: sysctl, cgroup2, cpufreq, sched_setaffinity, ioprio_set, mbind
// Folia: region-thread pinning, cache topology, NUMA-aware dispatch
// ============================================================================

#![allow(non_camel_case_types, dead_code)]

use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::Path;
use std::ptr;
use std::sync::LazyLock;
use std::sync::Mutex;
use libc::{cpu_set_t, sched_setaffinity};

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------
const PROC_MEMINFO: &str = "/proc/meminfo";
const PROC_SYS: &str = "/proc/sys";
const CGROUP2_BASE: &str = "/sys/fs/cgroup";
const SYS_BLOCK: &str = "/sys/block";
const SYS_CPU: &str = "/sys/devices/system/cpu";

/// Tune profiles (Docker-aware)
#[derive(Debug, Clone, Copy, PartialEq)]
enum Profile {
    Default,    // safe defaults
    Aggressive,  // max single-core throughput
    Balanced,    // even distribution across cores
    LowMemory,   // < 4GB RAM
    Folia,       // multicore throughput / region-thread friendly
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
fn read_sys(path: &str) -> Option<String> {
    fs::read_to_string(path).ok().map(|s| s.trim().to_string())
}

fn write_sys(path: &str, value: &str) -> Result<(), String> {
    fs::write(path, value).map_err(|e| format!("{}: {}", path, e))
}

fn sysfs_block_devices() -> Vec<String> {
    let mut devs = Vec::new();
    if let Ok(entries) = fs::read_dir(SYS_BLOCK) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if !name.starts_with("loop") && !name.starts_with("dm-")
                && !name.starts_with("ram") && !name.starts_with("zram")
            {
                devs.push(name);
            }
        }
    }
    devs
}

fn is_running_in_docker() -> bool {
    Path::new("/.dockerenv").exists() || fs::read_to_string("/proc/1/cgroup")
        .ok().map_or(false, |c| c.contains("docker"))
}

fn total_memory_mb() -> Option<usize> {
    let content = fs::read_to_string(PROC_MEMINFO).ok()?;
    for line in content.lines() {
        if line.starts_with("MemTotal:") {
            let kb: usize = line.split_whitespace()
                .nth(1).and_then(|s| s.parse().ok())?;
            return Some(kb / 1024);
        }
    }
    None
}

// ---------------------------------------------------------------------------
// 1. Kernel sysctl tuning
// ---------------------------------------------------------------------------
fn tune_sysctl(net_iface: Option<&str>) -> Vec<String> {
    let mut applied = Vec::new();

    let params: Vec<(&str, &str)> = vec![
        ("vm/swappiness", "10"),
        ("vm/dirty_ratio", "5"),
        ("vm/dirty_background_ratio", "2"),
        ("vm/vfs_cache_pressure", "50"),
        ("vm/stat_interval", "10"),
        ("vm/page-cluster", "0"),
        ("vm/compaction_proactiveness", "20"),
        ("vm/min_free_kbytes", "65536"),
        ("net/core/somaxconn", "2048"),
        ("net/core/netdev_budget", "600"),
        ("net/core/dev_weight", "64"),
        ("net/ipv4/tcp_fastopen", "3"),
        ("net/ipv4/tcp_slow_start_after_idle", "0"),
        ("net/ipv4/tcp_congestion_control", "bbr"),
        ("net/ipv4/tcp_notsent_lowat", "16384"),
        ("net/ipv4/udp_mem", "65536 131072 262144"),
        ("kernel/sched_min_granularity_ns", "3000000"),
        ("kernel/sched_latency_ns", "18000000"),
        ("kernel/sched_wakeup_granularity_ns", "2000000"),
        ("kernel/sched_migration_cost_ns", "500000"),
        ("kernel/sched_nr_migrate", "32"),
        ("kernel/sched_autogroup_enabled", "0"),
        ("kernel/numa_balancing", "0"),
        ("kernel/sched_energy_aware", "0"),
    ];

    for (key, value) in &params {
        let path = format!("{}/{}", PROC_SYS, key);
        if write_sys(&path, value).is_ok() {
            applied.push(format!("sysctl: {} = {}", key, value));
        }
    }

    let _ = write_sys("/proc/sys/net/ipv4/tcp_congestion_control", "bbr");

    if let Some(iface) = net_iface {
        let path = format!(
            "{}/{}/queues/rx-0/rps_cpus",
            SYS_BLOCK.replace("block", "class/net"), iface
        );
        match num_cpus() {
            1..=4 => { let _ = write_sys(&path, "f"); }
            5..=8 => { let _ = write_sys(&path, "ff"); }
            _ => { let _ = write_sys(&path, "fff"); }
        }
    }

    applied
}

// ---------------------------------------------------------------------------
// 2. CPU pinning with sched_setaffinity
// ---------------------------------------------------------------------------
fn num_cpus() -> usize {
    unsafe { libc::sysconf(libc::_SC_NPROCESSORS_ONLN) as usize }
}

fn pin_to_cpus(cpu_mask: &[usize]) -> Result<(), String> {
    unsafe {
        let mut set: cpu_set_t = std::mem::zeroed();
        libc::CPU_ZERO(&mut set);
        for &cpu in cpu_mask {
            libc::CPU_SET(cpu as _, &mut set);
        }
        let ret = sched_setaffinity(0, std::mem::size_of::<cpu_set_t>(), &set);
        if ret != 0 {
            return Err(format!("sched_setaffinity: {}", std::io::Error::last_os_error()));
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// 3. I/O scheduler & priority
// ---------------------------------------------------------------------------
fn tune_io(profile: Profile) -> Vec<String> {
    let mut applied = Vec::new();
    let sched = match profile {
        Profile::Default | Profile::LowMemory => "mq-deadline",
        Profile::Aggressive | Profile::Balanced | Profile::Folia => "none",
    };

    for dev in sysfs_block_devices() {
        let path = format!("{}/{}/queue/scheduler", SYS_BLOCK, dev);
        if write_sys(&path, sched).is_ok() {
            applied.push(format!("IO: {} scheduler={}", dev, sched));
        }
        let _ = write_sys(&format!("{}/{}/queue/nr_requests", SYS_BLOCK, dev), "128");
        let _ = write_sys(&format!("{}/{}/queue/read_ahead_kb", SYS_BLOCK, dev), "128");
        let _ = write_sys(&format!("{}/{}/queue/nomerges", SYS_BLOCK, dev), "2");
    }

    unsafe {
        let which = 1i32; // IOPRIO_WHO_PROCESS = 1
        let who = 0u32; // current process
        let ioprio_class = 1u32; // IOPRIO_CLASS_RT
        let ioprio_data = 0u32;
        let prio = (ioprio_class << 13) | ioprio_data;
        let ret = libc::syscall(libc::SYS_ioprio_set, which, who, prio);
        if ret == 0 {
            applied.push("IO: ioprio_set CLASS_RT highest".into());
        }
    }

    applied
}

// ---------------------------------------------------------------------------
// 4. HugePages (2MB) allocation
// ---------------------------------------------------------------------------
fn setup_hugepages(target_mb: usize) -> Result<Vec<String>, String> {
    let mut applied = Vec::new();
    let page_size_kb = 2048;
    let nr_pages = target_mb * 1024 / page_size_kb;

    let _ = write_sys("/proc/sys/vm/nr_overcommit_hugepages", &nr_pages.to_string());

    if nr_pages > 0 {
        write_sys("/proc/sys/vm/nr_hugepages", &nr_pages.to_string())
            .map_err(|e| format!("HugePages allocation failed: {}", e))?;
    }

    let actual = read_sys("/proc/sys/vm/nr_hugepages").unwrap_or_default();
    applied.push(format!("HugePages 2MB: requested={}, actual={}", nr_pages, actual));

    let _ = write_sys("/sys/kernel/mm/transparent_hugepage/enabled", "madvise");
    let _ = write_sys("/sys/kernel/mm/transparent_hugepage/defrag", "madvise");
    applied.push("THP: madvise mode".into());

    Ok(applied)
}

// ---------------------------------------------------------------------------
// 5. Memory policy — bind to local NUMA node
// ---------------------------------------------------------------------------
fn bind_memory_to_node(node: i32) -> Result<String, String> {
    unsafe {
        // MPOL_BIND = 2, MPOL_MF_STRICT = 1
        let nodemask: libc::c_ulong = 1u64 << node;
        // SYS_mbind = 235 on x86_64
        // mbind(void *addr, unsigned long len, int mode,
        //        const unsigned long *nodemask, unsigned long maxnode, unsigned flags)
        let ret = libc::syscall(
            235i64,                                 // SYS_mbind
            ptr::null::<libc::c_void>(),             // addr = NULL = all pages
            0usize,                                  // len = 0
            2i64,                                    // MPOL_BIND
            &nodemask as *const libc::c_ulong as i64, // nodemask
            64i64,                                   // maxnode (bits)
            1i64,                                    // MPOL_MF_STRICT (1)
        );
        if ret != 0 {
            return Err(format!("mbind: {}", std::io::Error::last_os_error()));
        }
    }
    Ok(format!("Memory bound to NUMA node {}", node))
}

// ---------------------------------------------------------------------------
// 6. CPU governor
// ---------------------------------------------------------------------------
fn set_cpu_governor(governor: &str) -> Vec<String> {
    let mut applied = Vec::new();
    for cpu in 0..num_cpus() {
        let path = format!("{}/cpu{}/cpufreq/scaling_governor", SYS_CPU, cpu);
        if write_sys(&path, governor).is_ok() {
            if applied.is_empty() {
                applied.push(format!("CPU governor: {} (applied)", governor));
            }
        }
    }
    if applied.is_empty() {
        applied.push(format!("CPU governor: {} (no cpufreq — Docker/virt)", governor));
    }
    applied
}

// ---------------------------------------------------------------------------
// 7. Cgroup v2 resource limits (Docker-friendly)
// ---------------------------------------------------------------------------
fn setup_cgroup_v2(cpu_quota: Option<usize>, mem_limit_mb: Option<usize>) -> Vec<String> {
    let mut applied = Vec::new();

    let cgroup_path = || -> Option<String> {
        let f = fs::read_to_string("/proc/self/cgroup").ok()?;
        for line in f.lines() {
            // cgroup v2 line looks like: 0::/path or 0::/docker/xxx
            if let Some(cgroup_part) = line.split(':').nth(2) {
                let p = cgroup_part.trim();
                if !p.is_empty() && p != "/" {
                    return Some(format!("{}{}", CGROUP2_BASE, p));
                }
            }
        }
        Some(CGROUP2_BASE.to_string())
    };

    let Some(cg_root) = cgroup_path() else { return applied; };

    if let Some(quota) = cpu_quota {
        let cpu_max_path = format!("{}/cpu.max", cg_root);
        if write_sys(&cpu_max_path, &format!("{} 100000", quota * 100_000)).is_ok() {
            applied.push(format!("cgroup: cpu.max = {} cores", quota));
        }
    }

    if let Some(limit) = mem_limit_mb {
        let mem_path = format!("{}/memory.max", cg_root);
        if write_sys(&mem_path, &format!("{}", limit * 1024 * 1024)).is_ok() {
            applied.push(format!("cgroup: memory.max = {} MB", limit));
        }
    }

    let swappiness_path = format!("{}/memory.swappiness", cg_root);
    let _ = write_sys(&swappiness_path, "10");

    applied
}

// ---------------------------------------------------------------------------
// 8. Clear page cache before server start
// ---------------------------------------------------------------------------
fn drop_caches() -> Result<String, String> {
    write_sys("/proc/sys/vm/drop_caches", "3")?;
    Ok("Page cache dropped".into())
}

// ---------------------------------------------------------------------------
// 9. Folia-specific sysctl tuning (multicore throughput)
// ---------------------------------------------------------------------------
fn tune_folia_sysctl() -> Vec<String> {
    let mut applied = Vec::new();

    let params: Vec<(&str, &str)> = vec![
        ("kernel/sched_min_granularity_ns", "1500000"),
        ("kernel/sched_latency_ns", "12000000"),
        ("kernel/sched_wakeup_granularity_ns", "1200000"),
        ("kernel/sched_migration_cost_ns", "250000"),
        ("kernel/sched_nr_migrate", "16"),
        ("kernel/sched_autogroup_enabled", "1"),
        ("kernel/numa_balancing", "1"),
        ("kernel/sched_energy_aware", "0"),
        ("kernel/sched_cfs_bandwidth_slice_us", "3000"),
    ];

    for (key, value) in &params {
        let path = format!("{}/{}", PROC_SYS, key);
        if write_sys(&path, value).is_ok() {
            applied.push(format!("Folia sysctl: {} = {}", key, value));
        }
    }

    applied
}

// ---------------------------------------------------------------------------
// 10. CPU topology discovery for Folia region-thread pinning
// ---------------------------------------------------------------------------
#[derive(Debug, Clone)]
struct CpuTopology {
    logical_id: usize,
    physical_package_id: usize,
    core_id: usize,
}

fn discover_cpu_topology() -> Vec<CpuTopology> {
    let ncpus = num_cpus();
    let mut topology = Vec::new();

    for cpu in 0..ncpus {
        let cpu_dir = format!("{}/cpu{}", SYS_CPU, cpu);
        if !Path::new(&cpu_dir).exists() {
            continue;
        }

        let phys_pkg = read_sys(&format!("{}/topology/physical_package_id", cpu_dir))
            .and_then(|s| s.parse::<usize>().ok())
            .unwrap_or(0);

        let core_id = read_sys(&format!("{}/topology/core_id", cpu_dir))
            .and_then(|s| s.parse::<usize>().ok())
            .unwrap_or(cpu);

        topology.push(CpuTopology {
            logical_id: cpu,
            physical_package_id: phys_pkg,
            core_id,
        });
    }

    topology
}

fn folia_physical_cores() -> Vec<usize> {
    let topo = discover_cpu_topology();
    let mut seen_cores = HashSet::new();
    let mut result = Vec::new();

    let mut sorted = topo.clone();
    sorted.sort_by_key(|c| (c.physical_package_id, c.core_id, c.logical_id));

    for cpu in &sorted {
        let key = (cpu.physical_package_id, cpu.core_id);
        if seen_cores.insert(key) {
            result.push(cpu.logical_id);
        }
    }

    result
}

// ---------------------------------------------------------------------------
// 11. Folia region-thread pinning — pin each region thread to a unique core
// ---------------------------------------------------------------------------
static FOLIA_THREAD_MAP: LazyLock<Mutex<HashMap<String, isize>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

fn pin_tid_to_cpu(tid: i32, cpu: usize) -> Result<(), String> {
    unsafe {
        let mut set: cpu_set_t = std::mem::zeroed();
        libc::CPU_ZERO(&mut set);
        libc::CPU_SET(cpu, &mut set);
        let ret = sched_setaffinity(tid as _, std::mem::size_of::<cpu_set_t>(), &set);
        if ret != 0 {
            return Err(format!("sched_setaffinity(tid={}, cpu={}): {}", tid, cpu, std::io::Error::last_os_error()));
        }
    }
    Ok(())
}

fn pin_folia_region_threads(_region_count: usize) -> Vec<String> {
    let mut applied = Vec::new();
    let physical_cores = folia_physical_cores();

    if physical_cores.is_empty() {
        applied.push("FOLIA: No physical cores detected".into());
        return applied;
    }

    let reserved = if physical_cores.len() >= 8 { 2 } else { 1 };
    let available_cores: Vec<usize> = physical_cores.iter().copied().skip(reserved).collect();

    if available_cores.is_empty() {
        applied.push("FOLIA: Not enough cores for region pinning".into());
        return applied;
    }

    let task_dir = Path::new("/proc/self/task");
    if let Ok(entries) = fs::read_dir(task_dir) {
        let mut region_idx = 0usize;
        let mut tid_list: Vec<(i32, String)> = Vec::new();

        for entry in entries.flatten() {
            let tid_str = entry.file_name();
            let tid: i32 = match tid_str.to_string_lossy().parse() {
                Ok(v) => v,
                Err(_) => continue,
            };
            let comm_path = format!("/proc/self/task/{}/comm", tid);
            if let Ok(comm) = fs::read_to_string(&comm_path) {
                tid_list.push((tid, comm.trim().to_string()));
            }
        }

        tid_list.sort_by_key(|(tid, _)| *tid);

        for (tid, name) in &tid_list {
            let lower = name.to_lowercase();
            let is_region = lower.contains("region");
            let is_worker = lower.contains("worker") || lower.contains("pool");

            if is_region || is_worker {
                let cpu_idx = region_idx % available_cores.len();
                let target_cpu = available_cores[cpu_idx];

                match pin_tid_to_cpu(*tid, target_cpu) {
                    Ok(()) => {
                        applied.push(format!("FOLIA: TID {} '{}' → CPU {}", tid, name, target_cpu));
                        let mut map = FOLIA_THREAD_MAP.lock().unwrap();
                        map.insert(name.clone(), target_cpu as isize);
                        region_idx += 1;
                    }
                    Err(e) => {
                        applied.push(format!("FOLIA: TID {} '{}' — {}", tid, name, e));
                    }
                }
            }
        }
    }

    if applied.is_empty() {
        applied.push("FOLIA: No region threads found (is Folia running?)".into());
    }

    applied
}

// ---------------------------------------------------------------------------
// JNI exports
// ---------------------------------------------------------------------------
#[no_mangle]
pub extern "system" fn Java_com_mcopt_NativeBridge_nativeTuneAll(
    env: jni::JNIEnv, _class: jni::objects::JClass, profile_int: jni::sys::jint,
) -> jni::sys::jstring {
    let profile = match profile_int {
        0 => Profile::Default,
        1 => Profile::Aggressive,
        2 => Profile::Balanced,
        3 => Profile::LowMemory,
        4 => Profile::Folia,
        _ => Profile::Default,
    };

    let mut report: Vec<String> = Vec::new();
    let docker = is_running_in_docker();
    report.push(format!(
        "env: docker={}, cpus={}, mem_total_mb={:?}",
        docker, num_cpus(), total_memory_mb()
    ));

    report.extend(tune_sysctl(None));
    report.extend(set_cpu_governor("performance"));
    report.extend(tune_io(profile));

    if profile == Profile::Folia {
        report.extend(tune_folia_sysctl());
    }

    if let Some(mem) = total_memory_mb() {
        let huge_mb = mem.saturating_sub(4096) / 4;
        if huge_mb > 0 {
            if let Ok(huge) = setup_hugepages(huge_mb) {
                report.extend(huge);
            }
        }
    }

    let _ = bind_memory_to_node(0).ok().map(|s| report.push(s));
    let _ = drop_caches().ok().map(|s| report.push(s));

    if profile == Profile::Folia {
        let physical = folia_physical_cores();
        report.push(format!("FOLIA: {} physical cores for regions: {:?}", physical.len(), physical));
        report.extend(pin_folia_region_threads(physical.len().saturating_sub(2)));
    }

    let result = report.join("\n");
    let output = env.new_string(&result).expect("JNI string creation failed");
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_mcopt_NativeBridge_nativePinToCpus(
    _env: jni::JNIEnv, _class: jni::objects::JClass, mask: jni::sys::jlong,
) -> jni::sys::jboolean {
    let mut cpus = Vec::new();
    for i in 0..64 {
        if (mask >> i) & 1 == 1 {
            cpus.push(i as usize);
        }
    }
    pin_to_cpus(&cpus).is_ok() as jni::sys::jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_mcopt_NativeBridge_nativeSetCgroupLimits(
    _env: jni::JNIEnv, _class: jni::objects::JClass,
    cpu_quota: jni::sys::jint, mem_limit_mb: jni::sys::jint,
) -> jni::sys::jboolean {
    let cpu = if cpu_quota > 0 { Some(cpu_quota as usize) } else { None };
    let mem = if mem_limit_mb > 0 { Some(mem_limit_mb as usize) } else { None };
    let result = setup_cgroup_v2(cpu, mem);
    !result.is_empty() as jni::sys::jboolean
}

/// JNI: Folia — pin a specific thread by TID to a physical core
#[no_mangle]
pub extern "system" fn Java_com_mcopt_NativeBridge_nativePinFoliaThread(
    _env: jni::JNIEnv, _class: jni::objects::JClass,
    tid: jni::sys::jint, cpu: jni::sys::jint,
) -> jni::sys::jboolean {
    pin_tid_to_cpu(tid, cpu as usize).is_ok() as jni::sys::jboolean
}

/// JNI: Folia — discover available physical cores for region threads
#[no_mangle]
pub extern "system" fn Java_com_mcopt_NativeBridge_nativeFoliaGetPhysicalCores(
    env: jni::JNIEnv, _class: jni::objects::JClass,
) -> jni::sys::jstring {
    let cores: Vec<String> = folia_physical_cores().iter().map(|c| c.to_string()).collect();
    let result = cores.join(",");
    let output = env.new_string(&result).expect("JNI string creation failed");
    output.into_raw()
}

// Use libc::CPU_ZERO / libc::CPU_SET from libc crate instead
// of custom implementations

// ============================================================================
// Standalone entry — called from main.rs binary
// ============================================================================
pub fn standalone_main() {
    let args: Vec<String> = std::env::args().collect();
    let profile = if args.len() > 1 {
        match args[1].as_str() {
            "aggressive" => Profile::Aggressive,
            "balanced" => Profile::Balanced,
            "lowmem" => Profile::LowMemory,
            "folia" => Profile::Folia,
            _ => Profile::Default,
        }
    } else {
        Profile::Aggressive
    };

    println!("=== MC-NATIVE-OPT (Folia-aware) ===");
    println!("Docker: {}", is_running_in_docker());
    println!("CPUs: {}", num_cpus());
    println!("Profile: {:?}", profile);

    println!("\n--- sysctl tuning ---");
    for line in tune_sysctl(None) {
        println!("  {}", line);
    }

    println!("\n--- CPU governor ---");
    for line in set_cpu_governor("performance") {
        println!("  {}", line);
    }

    println!("\n--- I/O tuning ---");
    for line in tune_io(profile) {
        println!("  {}", line);
    }

    if profile == Profile::Folia {
        println!("\n--- Folia sysctl tuning ---");
        for line in tune_folia_sysctl() {
            println!("  {}", line);
        }

        println!("\n--- CPU topology for Folia ---");
        let physical = folia_physical_cores();
        println!("  Physical cores: {:?}", physical);

        println!("\n--- Folia region thread pinning ---");
        for line in pin_folia_region_threads(8) {
            println!("  {}", line);
        }
    }

    if !is_running_in_docker() {
        if let Some(mem) = total_memory_mb() {
            let huge_mb = mem.saturating_sub(4096) / 4;
            if huge_mb > 0 {
                println!("\n--- HugePages ---");
                match setup_hugepages(huge_mb) {
                    Ok(lines) => for l in lines { println!("  {}", l); }
                    Err(e) => println!("  ERROR: {}", e),
                }
            }
        }

        println!("\n--- Memory binding ---");
        match bind_memory_to_node(0) {
            Ok(s) => println!("  {}", s),
            Err(e) => println!("  {}", e),
        }

        println!("\n--- Cache drop ---");
        match drop_caches() {
            Ok(s) => println!("  {}", s),
            Err(e) => println!("  ERROR: {}", e),
        }
    }

    println!("\n--- Cgroup setup ---");
    if !is_running_in_docker() {
        for line in setup_cgroup_v2(Some(num_cpus()), total_memory_mb().map(|m| m - 1024)) {
            println!("  {}", line);
        }
    }

    println!("\n✓ Done. Run the Java agent for JVM-level optimization.");
}
