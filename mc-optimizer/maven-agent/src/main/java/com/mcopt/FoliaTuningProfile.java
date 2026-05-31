package com.mcopt;

import java.util.logging.Logger;

/**
 * Folia-specific tuning profile.
 *
 * Folia differs from Paper fundamentally:
 * - Region-based multithreading (not a single tick loop)
 * - Each region has its own thread with local entity/chunk data
 * - Requires NUMA-aware memory allocation
 * - Needs CPU pinning for cache locality across regions
 * - Uses more threads → needs different GC tuning (parallel ZGC)
 * - String deduplication has less impact per thread but more total
 *
 * This profile overrides JVM flags for Folia's multi-threaded architecture.
 */
public final class FoliaTuningProfile {

    private final Logger log;

    public FoliaTuningProfile(Logger log) {
        this.log = log;
    }

    /**
     * Folia-optimized JVM flags.
     * Key differences from Paper:
     * - NUMA-aware allocation enabled (Folia threads touch different regions)
     * - ZGC concurrent threads = number of region threads, not all CPUs
     * - No biased locking (deprecated in JDK 21 anyway)
     * - Pre-touch is MORE important (large heap split across cores)
     * - String deduplication threshold lower (more strings created per tick)
     */
    public static final String[] FOLIA_FLAGS = {
        // ----- GC: Generational ZGC with concurrent parallelism -----
        "-XX:+UseZGC",
        "-XX:+ZGenerational",
        "-XX:ZCollectionInterval=0.05",      // GC every 50ms (faster than Paper)
        "-XX:ZFragmentationLimit=25",
        "-XX:ZUncommit",
        "-XX:ZUncommitDelay=30",
        "-XX:ZAllocationSpikeTolerance=2.0",  // Handle Folia's allocation spikes
        "-XX:+ZNUMA",                         // NUMA-aware ZGC (Folia spans NUMA)

        // ----- Memory -----
        "-XX:+UseLargePages",
        "-XX:+UseTransparentHugePages",
        "-XX:+AlwaysPreTouch",
        "-XX:+UseCompressedOops",
        "-XX:+UseCompressedClassPointers",

        // ----- GC Tuning -----
        "-XX:+DisableExplicitGC",
        "-XX:+ParallelRefProcEnabled",

        // ----- String optimization (more strings in Folia per tick) -----
        "-XX:+UseStringDeduplication",
        "-XX:StringDeduplicationAgeThreshold=2",  // Earlier dedup for Folia

        // ----- JIT -----
        "-XX:+OptimizeStringConcat",
        "-XX:+AlwaysActAsServerClassMachine",
        "-XX:-UseBiasedLocking",                 // Deprecated JDK 21

        // ----- Resilience -----
        "-XX:+ExitOnOutOfMemoryError",

        // ----- Thread stack (smaller for many region threads) -----
        "-Xss512k",

        // ----- Tiered compilation for faster warmup -----
        "-XX:+TieredCompilation",
        "-XX:TieredStopAtLevel=4",
        "-XX:TieredCompileTaskTimeout=5000",
        "-XX:TieredMinInvocationThreshold=100",

        // ----- Safepoint tuning for Folia's many threads -----
        "-XX:+SafepointTimeout",
        "-XX:SafepointTimeoutDelay=500",
        "-XX:GuaranteedSafepointInterval=500",  // Every 500ms
    };

    /**
     * Recommended heap ratios for Folia.
     * Returns [minHeapMB, maxHeapMB, youngRatio%].
     * Folia benefits from larger young gen due to per-tick allocation.
     */
    public static long[] computeFoliaHeap(long totalMemMB, int regionCount) {
        // Reserve 4GB for OS + 1GB per 4 region threads
        long osReserveMB = 4096 + (regionCount / 4) * 1024L;
        long heapMB = totalMemMB > osReserveMB ? totalMemMB - osReserveMB : totalMemMB / 2;

        // Cap at reasonable max (ZGC works best with large heaps)
        if (heapMB > 65536) heapMB = 65536;

        // Young gen: 40% for Folia (more allocation pressure)
        // default is 25%
        long youngMB = (long) (heapMB * 0.40);

        return new long[]{ heapMB / 2, heapMB, 40 };
    }

    /** Print the Folia JVM flags to log. */
    public void logRecommendedFlags() {
        log.info("[FoliaTuning] Recommended JVM flags:");
        for (String flag : FOLIA_FLAGS) {
            log.info("  " + flag);
        }
    }
}
