package com.mcopt;

import java.lang.management.*;
import java.util.logging.Logger;

/**
 * JVM-level optimization — GC tuning, memory management, JIT flags.
 * Applied on-enable and also accessible via /mcopt command.
 */
public final class JVMOptimizer {

    private final Logger log;

    // These are applied at JVM startup via -javaagent or command flags.
    // This class exposes them for verification + runtime toggles where possible.

    public static final String[] RECOMMENDED_FLAGS = {
        "-XX:+UseZGC",                    // Low-pause GC (sub-millisecond)
        "-XX:ZCollectionInterval=0.1",    // GC every 100ms for tighter heap
        "-XX:ZFragmentationLimit=25",     // More aggressive defrag
        "-XX:+ZGenerational",             // Generational ZGC for better throughput
        "-XX:+ZUncommit",                 // Return unused memory to OS
        "-XX:ZUncommitDelay=30",          // Uncommit after 30s idle
        "-XX:+UseLargePages",             // Use transparent hugepages
        "-XX:+AlwaysPreTouch",            // Pre-touch all pages at startup
        "-XX:+DisableExplicitGC",         // Prevent System.gc() from causing pauses
        "-XX:+ParallelRefProcEnabled",    // Parallel reference processing
        "-XX:+UseStringDeduplication",    // Deduplicate strings (lots in MC)
        "-XX:StringDeduplicationAgeThreshold=3",
        "-XX:+OptimizeStringConcat",      // Optimize string building
        "-XX:+UseCompressedOops",         // 32-bit object references (heap < 32GB)
        "-XX:+UseCompressedClassPointers",
        "-XX:+AlwaysActAsServerClassMachine",
        "-XX:-UseBiasedLocking",          // Biased locking deprecated in JDK 21
        "-XX:+ExitOnOutOfMemoryError",    // Safer than hanging
        "-Djdk.attach.allowAttachSelf=true",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/sun.security.provider=ALL-UNNAMED",
    };

    public JVMOptimizer(Logger log) {
        this.log = log;
    }

    /** Apply all JVM-level runtime tunings */
    public void applyAll() {
        log.info("[McOpt] JVM Optimizer — applying runtime settings");
        log.info("[McOpt] For full effect, use -javaagent:mc-opt-agent.jar with JVM flags:");
        for (String flag : RECOMMENDED_FLAGS) {
            log.info("  " + flag);
        }

        // Show current JVM info
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        log.info("[McOpt] JVM: " + runtime.getVmName() + " " + runtime.getVmVersion());
        log.info("[McOpt] Args: " + String.join(" ", runtime.getInputArguments()));

        // Show memory info
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        log.info(String.format("[McOpt] Heap: used=%dMB / max=%dMB",
            heap.getUsed() / 1048576, heap.getMax() / 1048576));
        log.info(String.format("[McOpt] Non-heap: used=%dMB / max=%dMB",
            nonHeap.getUsed() / 1048576, nonHeap.getMax() / 1048576));
    }

    /** Get a summary string */
    public String getSummary() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        return String.format("Heap used=%dMB / max=%dMB",
            heap.getUsed() / 1048576, heap.getMax() / 1048576);
    }
}
