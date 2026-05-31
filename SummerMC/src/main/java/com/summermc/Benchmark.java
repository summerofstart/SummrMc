package com.summermc;

import java.io.*;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * SummerMC Benchmark — measures server performance under load.
 *
 * Tests:
 *   1. CPU compute (vector math, entity sim)
 *   2. Memory allocation / GC pressure
 *   3. Thread scheduling / context switching
 *   4. World-like chunk access patterns
 *
 * Run: java -jar SummerMC.jar --bench
 */
public final class Benchmark {

    private static final Logger log = Logger.getLogger("SummerMC-Bench");

    private Benchmark() {}

    public static void run() throws Exception {
        log.info("");
        log.info("╔═══════════════════════════════════════════════╗");
        log.info("║     📊 SummerMC Performance Benchmark        ║");
        log.info("╚═══════════════════════════════════════════════╝");
        log.info("");

        Runtime rt = Runtime.getRuntime();
        int cores = rt.availableProcessors();
        log.info("System:");
        log.info("  CPUs: " + cores);
        log.info("  Memory: " + (rt.totalMemory() / 1048576) + "MB / " + (rt.maxMemory() / 1048576) + "MB max");
        log.info("  JVM: " + ManagementFactory.getRuntimeMXBean().getVmVersion());
        log.info("");

        Map<String, Double> results = new LinkedHashMap<>();

        // ---------------------------------------------------------------
        // 1. Compute throughput (simulating entity/region tick)
        // ---------------------------------------------------------------
        log.info("▶ Test 1: Compute throughput (entity simulation)...");
        results.put("compute_ops_per_sec", benchmarkCompute(cores));
        log.info("   Result: " + String.format("%.0f", results.get("compute_ops_per_sec")) + " ops/sec");

        // ---------------------------------------------------------------
        // 2. Memory allocation pressure (simulating chunk load)
        // ---------------------------------------------------------------
        log.info("▶ Test 2: Memory allocation (chunk simulation)...");
        results.put("chunks_per_sec", benchmarkMemory(cores));
        log.info("   Result: " + String.format("%.0f", results.get("chunks_per_sec")) + " chunks/sec");

        // ---------------------------------------------------------------
        // 3. GC pause measurement
        // ---------------------------------------------------------------
        log.info("▶ Test 3: GC pause impact...");
        double gcMs = benchmarkGc();
        results.put("gc_avg_pause_ms", gcMs);
        log.info("   Result: " + String.format("%.1f", gcMs) + " ms avg pause");

        // ---------------------------------------------------------------
        // 4. Thread contention (region boundary sim)
        // ---------------------------------------------------------------
        log.info("▶ Test 4: Thread contention (region boundary)...");
        double ops = benchmarkContention(cores);
        results.put("region_cross_ops_per_sec", ops);
        log.info("   Result: " + String.format("%.0f", ops) + " ops/sec");

        // ---------------------------------------------------------------
        // Summary
        // ---------------------------------------------------------------
        log.info("");
        log.info("╔═══════════════════════════════════════════════╗");
        log.info("║     📊 Benchmark Results                     ║");
        log.info("╠═══════════════════════════════════════════════╣");
        for (Map.Entry<String, Double> e : results.entrySet()) {
            log.info(String.format("║ %-30s %12.0f ║", e.getKey(), e.getValue()));
        }
        log.info("╚═══════════════════════════════════════════════╝");
        log.info("");
    }

    /** Compute: parallel vector math (entity-like transformations). */
    private static double benchmarkCompute(int cores) throws InterruptedException {
        int tasks = cores * 4;
        int iterations = 500_000;
        ExecutorService pool = Executors.newFixedThreadPool(cores);
        long start = System.nanoTime();

        CountDownLatch latch = new CountDownLatch(tasks);
        for (int t = 0; t < tasks; t++) {
            pool.submit(() -> {
                double x = 0, y = 0, z = 0;
                Random rnd = ThreadLocalRandom.current();
                for (int i = 0; i < iterations; i++) {
                    // Simulate entity position update
                    x += rnd.nextDouble() * 2 - 1;
                    y += rnd.nextDouble() * 2 - 1;
                    z += rnd.nextDouble() * 2 - 1;
                    // Euler distance check
                    double dist = Math.sqrt(x * x + y * y + z * z);
                    if (dist > 100) { x /= 2; y /= 2; z /= 2; }
                }
                latch.countDown();
            });
        }
        latch.await();
        pool.shutdown();

        long elapsed = System.nanoTime() - start;
        double ops = (double) tasks * iterations / (elapsed / 1e9);
        return ops;
    }

    /** Memory: simulate chunk data allocation. */
    private static double benchmarkMemory(int cores) throws InterruptedException {
        int tasks = cores;
        int chunksPerTask = 200;
        ExecutorService pool = Executors.newFixedThreadPool(cores);
        long start = System.nanoTime();

        CountDownLatch latch = new CountDownLatch(tasks);
        for (int t = 0; t < tasks; t++) {
            pool.submit(() -> {
                List<byte[][]> chunks = new ArrayList<>();
                Random rnd = ThreadLocalRandom.current();
                for (int i = 0; i < chunksPerTask; i++) {
                    // Simulate a chunk: 16x16x16 block storage
                    byte[][] sections = new byte[24][16 * 16 * 16];
                    for (int s = 0; s < sections.length; s++) {
                        rnd.nextBytes(sections[s]);
                    }
                    chunks.add(sections);
                    // Periodically clear to simulate GC
                    if (i % 50 == 49) {
                        chunks.clear();
                        System.gc(); // hint for GC to run
                    }
                }
                chunks.clear();
                latch.countDown();
            });
        }
        latch.await();
        pool.shutdown();

        long elapsed = System.nanoTime() - start;
        return (double) tasks * chunksPerTask / (elapsed / 1e9);
    }

    /** GC: measure pause times under allocation pressure. */
    private static double benchmarkGc() throws Exception {
        List<Long> pauses = new ArrayList<>();
        GcMonitor listener = new GcMonitor(pauses);
        ManagementFactory.getGarbageCollectorMXBeans().stream()
            .filter(gc -> gc instanceof NotificationEmitter)
            .forEach(gc -> ((NotificationEmitter) gc).addNotificationListener(listener, null, null));

        // Generate GC pressure
        List<byte[]> garbage = new ArrayList<>();
        Random rnd = new Random();
        for (int i = 0; i < 50; i++) {
            garbage.add(new byte[1024 * 1024]); // 1MB
            if (i % 10 == 9) {
                garbage.subList(0, 5).clear();
                Thread.sleep(5);
            }
        }
        garbage.clear();

        // Wait for pending GC notifications
        Thread.sleep(200);

        double sum = pauses.stream().mapToLong(Long::longValue).sum();
        return pauses.isEmpty() ? 0 : sum / pauses.size();
    }

    /** Contention: simulate region boundary operations with locks. */
    private static double benchmarkContention(int cores) throws InterruptedException {
        int tasks = Math.min(cores, 8);
        int iterations = 200_000;
        Object[] locks = new Object[tasks];
        for (int i = 0; i < tasks; i++) locks[i] = new Object();

        ExecutorService pool = Executors.newFixedThreadPool(tasks);
        long start = System.nanoTime();

        CountDownLatch latch = new CountDownLatch(tasks);
        for (int t = 0; t < tasks; t++) {
            final int threadId = t;
            pool.submit(() -> {
                int other = (threadId + 1) % tasks;
                for (int i = 0; i < iterations; i++) {
                    // Simulate cross-region access: lock own region, then adjacent
                    synchronized (locks[threadId]) {
                        synchronized (locks[other]) {
                            // Critical section: chunk data transfer
                            Math.sin(threadId + i * 0.1);
                        }
                    }
                }
                latch.countDown();
            });
        }
        latch.await();
        pool.shutdown();

        long elapsed = System.nanoTime() - start;
        return (double) tasks * iterations / (elapsed / 1e9);
    }

    /** GC notification listener for pause measurement. */
    private record GcMonitor(List<Long> pauses) implements javax.management.NotificationListener {
        @Override
        public void handleNotification(javax.management.Notification n, Object h) {
            if (n.getType().equals(
                    com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {
                var info = com.sun.management.GarbageCollectionNotificationInfo.from(
                    (javax.management.openmbean.CompositeData) n.getUserData());
                if (!info.getGcAction().toLowerCase().contains("end of gc cycle")) {
                    pauses.add(info.getGcInfo().getDuration());
                }
            }
        }
    }

    // javax.management.NotificationEmitter
    private interface NotificationEmitter {}
}
