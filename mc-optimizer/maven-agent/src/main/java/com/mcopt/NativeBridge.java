package com.mcopt;

/**
 * JNI bridge to libmc_native_opt.so (Rust native agent).
 */
public final class NativeBridge {

    static {
        // lib loaded via System.loadLibrary in McOptPlugin
    }

    /**
     * Apply ALL kernel-level tunings via native code.
     * @param profile 0=Default, 1=Aggressive, 2=Balanced, 3=LowMemory, 4=Folia
     * @return multiline report string
     */
    public static native String nativeTuneAll(int profile);

    /**
     * Pin the JVM process to specific CPUs via sched_setaffinity.
     * @param mask bitmask where bit N = CPU N (e.g., 0x3F = CPUs 0-5)
     * @return true on success
     */
    public static native boolean nativePinToCpus(long mask);

    /**
     * Set cgroup v2 resource limits for the current process.
     * @param cpuQuota max CPU cores (0 = no limit)
     * @param memLimitMb max memory in MB (0 = no limit)
     * @return true on success
     */
    public static native boolean nativeSetCgroupLimits(int cpuQuota, int memLimitMb);

    // =======================================================================
    // Folia-specific JNI methods
    // =======================================================================

    /**
     * Pin a specific thread (by OS TID) to a physical CPU core.
     * Used by FoliaRegionWatcher to pin region threads.
     * @param tid OS thread ID (from Thread.getId() or ManagementFactory)
     * @param cpu physical CPU core index
     * @return true on success
     */
    public static native boolean nativePinFoliaThread(int tid, int cpu);

    /**
     * Get comma-separated list of physical core indices.
     * Skips hyperthread siblings; returns one core per physical unit.
     * First 1-2 cores are reserved for network/GC.
     * @return comma-separated core list, e.g. "2,4,6,8"
     */
    public static native String nativeFoliaGetPhysicalCores();

    private NativeBridge() {}
}
