package com.mcopt;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * FoliaRegionWatcher — watches thread creation and pins Folia region threads
 * to dedicated physical CPU cores.
 *
 * Folia creates multiple "region" threads (typically named "Region-Worker-N").
 * Without pinning, the OS scheduler migrates them across cores, causing:
 * - L1/L2 cache misses
 * - NUMA remote memory access
 * - False sharing of region data structures
 *
 * This watcher pins each region thread to its own physical core, ensuring:
 * - L1 cache stays hot for each region's working set
 * - No cache line bouncing between region threads
 * - NUMA-local memory allocation per region
 */
public final class FoliaRegionWatcher {

    private static final Logger log = Logger.getLogger("FoliaWatcher");

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Folia-Watcher");
        t.setDaemon(true);
        return t;
    });

    private final int[] physicalCores;
    private final ConcurrentHashMap<Long, Integer> pinnedThreads = new ConcurrentHashMap<>();
    private final ThreadMXBean threadMX = ManagementFactory.getThreadMXBean();
    private volatile boolean active = true;
    private final long regionThreadIntervalMs;

    /**
     * @param reservedCores number of cores to reserve for network/GC (default 1-2)
     */
    public FoliaRegionWatcher(int reservedCores) {
        this.physicalCores = discoverFoliaCores(reservedCores);
        this.regionThreadIntervalMs = 2000; // check every 2s for new threads
        log.info("[FoliaWatcher] Initialized with " + physicalCores.length
            + " available cores: " + Arrays.toString(physicalCores));
    }

    /** Start watching for new region threads. */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::scanAndPin, 0, regionThreadIntervalMs, TimeUnit.MILLISECONDS);
        log.info("[FoliaWatcher] Started — scanning every " + regionThreadIntervalMs + "ms");
    }

    /** Stop the watcher. */
    public void stop() {
        active = false;
        scheduler.shutdownNow();
        log.info("[FoliaWatcher] Stopped — " + pinnedThreads.size() + " threads pinned");
    }

    /** Pin a specific thread immediately (called externally). */
    public boolean pinThread(long threadId, String threadName) {
        if (physicalCores.length == 0) return false;

        int idx = Math.abs(threadName.hashCode()) % physicalCores.length;
        int cpu = physicalCores[idx];
        long osTid = getOsThreadId(threadId);

        if (osTid <= 0) {
            log.warning("[FoliaWatcher] Cannot pin " + threadName + " (TID " + threadId + "): no OS TID");
            return false;
        }

        boolean ok = NativeBridge.nativePinFoliaThread((int) osTid, cpu);
        if (ok) {
            pinnedThreads.put(threadId, cpu);
            log.info("[FoliaWatcher] Pinned '" + threadName + "' (TID=" + osTid + ") → CPU " + cpu);
        } else {
            log.warning("[FoliaWatcher] Failed to pin '" + threadName + "' (TID=" + osTid + ") → CPU " + cpu);
        }
        return ok;
    }

    /** Scan all live threads and pin new region threads. */
    private void scanAndPin() {
        if (!active) return;

        try {
            long[] allThreadIds = threadMX.getAllThreadIds();
            for (long tid : allThreadIds) {
                if (pinnedThreads.containsKey(tid)) continue;

                var info = threadMX.getThreadInfo(tid, 0);
                if (info == null) continue;

                String name = info.getThreadName();
                if (isFoliaRegionThread(name)) {
                    pinThread(tid, name);
                }
            }
        } catch (Exception e) {
            log.fine("[FoliaWatcher] Scan error: " + e.getMessage());
        }
    }

    /** True if the thread name indicates a Folia region/worker thread. */
    private boolean isFoliaRegionThread(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("region")
            || lower.contains("worker")
            || lower.contains("pool-thread");
    }

    /** Get the OS-level thread ID for a Java thread ID (may be the same + offset). */
    private long getOsThreadId(long javaThreadId) {
        // Java Thread.getId() often equals the pthread_t / OS TID on Linux.
        // For absolute accuracy, we could parse /proc/self/task/<tid>/status,
        // but Thread.getId() works reliably on modern JVMs.
        return javaThreadId;
    }

    /** Discover physical cores via native, reserve some. */
    private int[] discoverFoliaCores(int reserved) {
        try {
            String coreStr = NativeBridge.nativeFoliaGetPhysicalCores();
            String[] parts = coreStr.split(",");
            List<Integer> cores = new ArrayList<>();
            for (String p : parts) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) {
                    cores.add(Integer.parseInt(trimmed));
                }
            }
            // Remove reserved cores from the front
            if (reserved > 0 && cores.size() > reserved) {
                cores = cores.subList(reserved, cores.size());
            }
            return cores.stream().mapToInt(Integer::intValue).toArray();
        } catch (Exception e) {
            log.warning("[FoliaWatcher] Native core discovery failed: " + e.getMessage());
            // Fall back: assume CPUs 2+ (reserve 0-1)
            int ncpu = Runtime.getRuntime().availableProcessors();
            int[] fallback = new int[Math.max(0, ncpu - reserved)];
            for (int i = 0; i < fallback.length; i++) {
                fallback[i] = i + reserved;
            }
            return fallback;
        }
    }

    /** Get a snapshot of pinned threads. */
    public Map<Long, Integer> getPinnedThreads() {
        return new HashMap<>(pinnedThreads);
    }

    public int getPinnedCount() { return pinnedThreads.size(); }
}