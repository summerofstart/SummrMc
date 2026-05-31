package com.mcopt;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.logging.Logger;

/**
 * Pure-JVM low-level tuner — calls shell commands for sysctl/cgroup
 * when the native library is unavailable. Falls back gracefully.
 */
public final class LowLevelTuner {

    private final Logger log;

    public LowLevelTuner(Logger log) {
        this.log = log;
    }

    /** Write to /proc/sys via shell */
    public boolean setSysctl(String key, String value) {
        return exec("sysctl -w " + key + "=" + value);
    }

    /** Write to /sys/fs/cgroup */
    public boolean setCgroupValue(String path, String value) {
        return exec("echo " + value + " > " + path);
    }

    /** Set I/O scheduler for block devices */
    public boolean setIOScheduler(String device, String scheduler) {
        return exec("echo " + scheduler + " > /sys/block/" + device + "/queue/scheduler");
    }

    /** Drop caches */
    public boolean dropCaches() {
        return exec("echo 3 > /proc/sys/vm/drop_caches");
    }

    /** Check if running in Docker */
    public boolean isDocker() {
        try {
            Process p = Runtime.getRuntime().exec("cat /proc/1/cgroup");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                return r.lines().anyMatch(l -> l.contains("docker"));
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** Get total memory in MB */
    public long getTotalMemoryMB() {
        try {
            Process p = Runtime.getRuntime().exec("grep MemTotal /proc/meminfo");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                if (line != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]) / 1024;
                    }
                }
            }
        } catch (Exception ignored) {}
        return 16384; // default fallback
    }

    /** Apply safe defaults */
    public void applySafeDefaults() {
        log.info("[McOpt] LowLevelTuner — applying safe defaults via shell");
        setSysctl("vm.swappiness", "10");
        setSysctl("vm.dirty_ratio", "5");
        setSysctl("vm.dirty_background_ratio", "2");
        setSysctl("vm.vfs_cache_pressure", "50");
        setSysctl("kernel.sched_min_granularity_ns", "3000000");
        setSysctl("kernel.sched_wakeup_granularity_ns", "2000000");
        log.info("[McOpt] LowLevelTuner — safe defaults applied");
    }

    private boolean exec(String command) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", command});
            int rc = p.waitFor();
            return rc == 0;
        } catch (Exception e) {
            log.fine("[McOpt] Command failed: " + command + " — " + e.getMessage());
            return false;
        }
    }
}