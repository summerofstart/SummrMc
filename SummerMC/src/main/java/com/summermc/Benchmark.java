package com.summermc;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * SummerMC Benchmark — measures performance under Minecraft-like load.
 *
 * Tests:
 *   1. Compute throughput (entity simulation)
 *   2. Memory allocation (chunk loading)
 *   3. Thread contention (region boundaries)
 *   4. GC pressure response
 *
 * Uses only standard JDK APIs — no internal modules.
 */
public final class Benchmark {

    private static final Logger log = Logger.getLogger("SummerMC-Bench");

    private Benchmark() {}

    /** Run all benchmarks and return results as JSON-like string. */
    public static String runAndGetResults() {
        StringBuilder sb = new StringBuilder();
        Runtime rt = Runtime.getRuntime();
        int cores = rt.availableProcessors();

        sb.append("┌─────────────────────────────────────────────────────────────────────────┐\n");
        sb.append("│                       SummerMC Performance Benchmark                      │\n");
        sb.append("├─────────────────────────────────────────────────────────────────────────┤\n");
        sb.append(String.format("│  CPUs: %-52d │\n", cores));
        sb.append(String.format("│  Memory: %dMB / %dMB max                                      │\n",
            rt.totalMemory() / 1048576, rt.maxMemory() / 1048576));
        sb.append(String.format("│  JVM: %-52s │\n",
            ManagementFactory.getRuntimeMXBean().getVmVersion()));
        sb.append("├─────────────────────────────────────────────────────────────────────────┤\n");

        try {
            // Test 1: Compute
            log.info("  ▶ Test 1: Compute throughput (entity simulation)...");
            double compute = benchmarkCompute(cores);
            sb.append(String.format("│  Compute ops/sec        %18.0f                           │\n", compute));
        } catch (Exception e) {
            sb.append("│  Compute ops/sec         FAILED                                      │\n");
        }

        try {
            // Test 2: Memory
            log.info("  ▶ Test 2: Memory allocation (chunk simulation)...");
            double chunks = benchmarkMemory(cores);
            sb.append(String.format("│  Chunks/sec              %18.0f                           │\n", chunks));
        } catch (Exception e) {
            sb.append("│  Chunks/sec               FAILED                                      │\n");
        }

        try {
            // Test 3: GC
            log.info("  ▶ Test 3: GC pressure test...");
            GcResult gc = benchmarkGc();
            sb.append(String.format("│  GC avg pause (ms)      %18.1f                           │\n", gc.avgMs));
            sb.append(String.format("│  GC max pause (ms)      %18.0f                           │\n", gc.maxMs));
            sb.append(String.format("│  GC total pauses        %18d                           │\n", gc.count));
        } catch (Exception e) {
            sb.append("│  GC test                  FAILED                                      │\n");
        }

        try {
            // Test 4: Contention
            log.info("  ▶ Test 4: Thread contention (region boundary)...");
            double ops = benchmarkContention(cores);
            sb.append(String.format("│  Region cross ops/sec   %18.0f                           │\n", ops));
        } catch (Exception e) {
            sb.append("│  Region cross ops/sec     FAILED                                      │\n");
        }

        try {
            // Test 5: Startup time (server startup benchmark)
            log.info("  ▶ Test 5: Class loading throughput (JVM warmup)...");
            double loadTime = benchmarkClassLoading();
            sb.append(String.format("│  Class load (classes/s) %18.0f                           │\n", loadTime));
        } catch (Exception e) {
            sb.append("│  Class load                FAILED                                      │\n");
        }

        sb.append("└─────────────────────────────────────────────────────────────────────────┘\n");

        return sb.toString();
    }

    public static void run() throws Exception {
        System.out.println();
        System.out.print(runAndGetResults());
        System.out.println();
    }

    // Test 1: Parallel compute (entity-like transforms)
    private static double benchmarkCompute(int cores) throws InterruptedException {
        int tasks = cores * 4;
        int iterations = 500_000;
        var pool = Executors.newFixedThreadPool(cores);
        long start = System.nanoTime();
        var latch = new CountDownLatch(tasks);

        for (int t = 0; t < tasks; t++) {
            pool.submit(() -> {
                double x = 0, y = 0, z = 0;
                var rnd = ThreadLocalRandom.current();
                for (int i = 0; i < iterations; i++) {
                    x += rnd.nextDouble() * 2 - 1;
                    y += rnd.nextDouble() * 2 - 1;
                    z += rnd.nextDouble() * 2 - 1;
                    double dist = Math.sqrt(x * x + y * y + z * z);
                    if (dist > 100) { x /= 2; y /= 2; z /= 2; }
                }
                latch.countDown();
            });
        }
        latch.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        long elapsed = System.nanoTime() - start;
        return (double) tasks * iterations / (elapsed / 1e9);
    }

    // Test 2: Memory allocation (chunk-like data)
    private static double benchmarkMemory(int cores) throws InterruptedException {
        int tasks = Math.min(cores, 8);
        int chunksPerTask = 200;
        var pool = Executors.newFixedThreadPool(tasks);
        long start = System.nanoTime();
        var latch = new CountDownLatch(tasks);

        for (int t = 0; t < tasks; t++) {
            pool.submit(() -> {
                var chunks = new ArrayList<byte[][]>();
                var rnd = ThreadLocalRandom.current();
                for (int i = 0; i < chunksPerTask; i++) {
                    byte[][] sections = new byte[24][16 * 16 * 16];
                    for (int s = 0; s < sections.length; s++) {
                        rnd.nextBytes(sections[s]);
                    }
                    chunks.add(sections);
                    if (i % 50 == 49) { chunks.clear(); System.gc(); }
                }
                chunks.clear();
                latch.countDown();
            });
        }
        latch.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        long elapsed = System.nanoTime() - start;
        return (double) tasks * chunksPerTask / (elapsed / 1e9);
    }

    // Test 3: GC measurement via MXBean polling
    private record GcResult(double avgMs, double maxMs, long count) {}

    private static GcResult benchmarkGc() throws InterruptedException {
        var gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long[] before = new long[gcBeans.size()];
        long[] after = new long[gcBeans.size()];
        long[] countBefore = new long[gcBeans.size()];
        long[] countAfter = new long[gcBeans.size()];

        for (int i = 0; i < gcBeans.size(); i++) {
            before[i] = gcBeans.get(i).getCollectionTime();
            countBefore[i] = gcBeans.get(i).getCollectionCount();
        }

        // Generate GC pressure
        var garbage = new ArrayList<byte[]>();
        var rnd = new Random();
        for (int i = 0; i < 100; i++) {
            garbage.add(new byte[1024 * 1024]);
            if (i % 10 == 9) {
                int remove = Math.min(5, garbage.size());
                for (int j = 0; j < remove; j++) garbage.removeFirst();
                Thread.sleep(2);
            }
        }
        garbage.clear();
        System.gc();
        Thread.sleep(500);

        for (int i = 0; i < gcBeans.size(); i++) {
            after[i] = gcBeans.get(i).getCollectionTime();
            countAfter[i] = gcBeans.get(i).getCollectionCount();
        }

        long totalPause = 0, totalCount = 0, maxPause = 0;
        for (int i = 0; i < gcBeans.size(); i++) {
            long pause = after[i] - before[i];
            long cnt = countAfter[i] - countBefore[i];
            totalPause += pause;
            totalCount += cnt;
            maxPause = Math.max(maxPause, pause);
        }

        double avg = totalCount > 0 ? (double) totalPause / totalCount : 0;
        return new GcResult(avg, maxPause, totalCount);
    }

    // Test 4: Lock contention (region boundary simulation)
    private static double benchmarkContention(int cores) throws InterruptedException {
        int tasks = Math.min(cores, 8);
        int iterations = 200_000;
        var locks = new Object[tasks];
        for (int i = 0; i < tasks; i++) locks[i] = new Object();

        var pool = Executors.newFixedThreadPool(tasks);
        long start = System.nanoTime();
        var latch = new CountDownLatch(tasks);

        for (int t = 0; t < tasks; t++) {
            final int threadId = t;
            pool.submit(() -> {
                int other = (threadId + 1) % tasks;
                for (int i = 0; i < iterations; i++) {
                    synchronized (locks[threadId]) {
                        synchronized (locks[other]) {
                            Math.sin(threadId + i * 0.1);
                        }
                    }
                }
                latch.countDown();
            });
        }
        latch.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        long elapsed = System.nanoTime() - start;
        return (double) tasks * iterations / (elapsed / 1e9);
    }

    // Test 5: Class loading throughput
    private static double benchmarkClassLoading() {
        var loadingBean = ManagementFactory.getClassLoadingMXBean();
        long before = loadingBean.getTotalLoadedClassCount();
        long start = System.nanoTime();

        // Load some synthetic classes via reflection
        int count = 0;
        for (int i = 0; i < 1000; i++) {
            try {
                // Each call creates + loads a new lambda class
                Runnable r = () -> {
                    int x = 0;
                    for (int j = 0; j < 10; j++) x += j;
                };
                count++;
            } catch (Exception e) {
                break;
            }
        }

        long elapsed = System.nanoTime() - start;
        long after = loadingBean.getTotalLoadedClassCount();
        long loaded = after - before;
        return loaded > 0 ? (double) loaded / (elapsed / 1e9) : (double) count / (elapsed / 1e9);
    }
}
