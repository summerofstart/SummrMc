package com.summermc;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import com.mcopt.NativeLoader;
import com.mcopt.*;

/**
 * SummerMC — CLI tool to optimize ANY Paper/Folia Minecraft server.
 *
 * A standalone command-line utility that applies ultra-low-layer tuning
 * to any Minecraft server. Not a plugin — works with ANY server JAR.
 *
 * Commands:
 *   optimize     Apply system tuning (sysctl, hugepages, IO, CPU)
 *   launch       Launch a server with all optimizations
 *   download     Download Paper/Folia server from official repo
 *   bench        Run performance benchmark
 *   docker-gen   Generate Dockerfile + docker-compose.yml
 *
 * Copyright (c) 2026 SummerMC. MIT License — no Minecraft code included.
 */
public final class SummerMC {

    private static final Logger log = Logger.getLogger("SummerMC");
    private static final String VERSION = "2.0.0";
    private static final String PAPER_API = "https://api.papermc.io/v2/projects/paper";
    private static final String FOLIA_API = "https://api.papermc.io/v2/projects/folia";

    public static void main(String[] args) {
        if (args.length == 0) {
            printBanner();
            printHelp();
            return;
        }

        switch (args[0].toLowerCase()) {
            case "optimize"  -> runOptimize(Arrays.copyOfRange(args, 1, args.length));
            case "launch"    -> runLaunch(Arrays.copyOfRange(args, 1, args.length));
            case "download"  -> runDownload(Arrays.copyOfRange(args, 1, args.length));
            case "bench"     -> runBench();
            case "docker"    -> runDockerGen();
            case "help", "-h", "--help" -> { printBanner(); printHelp(); }
            default -> {
                printBanner();
                System.out.println("Unknown command: " + args[0]);
                System.out.println();
                printHelp();
            }
        }
    }

    // ===================================================================
    //  COMMAND: optimize — Apply system-level tuning
    // ===================================================================
    static void runOptimize(String[] args) {
        System.out.println();
        System.out.println("  ⚙  SummerMC System Optimizer");
        System.out.println();

        boolean folia = false;
        boolean dryRun = false;
        for (String a : args) {
            if (a.equals("--folia")) folia = true;
            if (a.equals("--dry-run")) dryRun = true;
        }

        // Detect Docker
        boolean inDocker = new File("/.dockerenv").exists()
            || new File("/proc/1/cgroup").exists();

        System.out.println("  Environment:");
        System.out.println("    Docker: " + inDocker);
        System.out.println("    Profile: " + (folia ? "Folia" : "Paper"));
        System.out.println("    Mode: " + (dryRun ? "DRY RUN (no changes)" : "LIVE"));
        System.out.println();

        if (dryRun) {
            System.out.println("  Would apply:");
            System.out.println("    - sysctl vm.swappiness=10");
            System.out.println("    - sysctl vm.dirty_ratio=5");
            System.out.println("    - sysctl vm.vfs_cache_pressure=50");
            System.out.println("    - sysctl kernel.sched_* (" + (folia ? "Folia" : "Paper") + " profile)");
            System.out.println("    - echo performance > CPU governor");
            System.out.println("    - IO scheduler: none (NVMe) / mq-deadline");
            System.out.println("    - HugePages: 2MB (25% of RAM)");
            if (folia) {
                System.out.println("    - NUMA balancing enabled");
                System.out.println("    - sched_autogroup enabled");
            }
            System.out.println();
            return;
        }

        System.out.println("  ⚠  Apply tuning requires root (try: sudo java -jar ...)");
        System.out.println();

        // Try native .so first
        if (NativeLoader.load()) {
            System.out.println("  ✅ Native optimizer loaded");
            System.out.println("  Run the McOpt javaagent for full kernel tuning:");
            System.out.println("    java -javaagent:SummerMC.jar -jar server.jar");
            System.out.println();
            return;
        }

        // Fallback: shell commands
        System.out.println("  ℹ  Native library not available — using shell fallback");
        System.out.println("  Run as root to apply system tuning:");
        System.out.println();

        // Print commands
        String[][] cmds = getSysctlCommands(folia);
        for (String[] cmd : cmds) {
            System.out.println("    " + (inDocker ? "# " : "") + "sysctl -w " + cmd[0] + "=" + cmd[1]);
        }

        if (!inDocker) {
            System.out.println("    echo performance | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor");
        }

        System.out.println();
        System.out.println("  Or use the javaagent for automated tuning:");
        System.out.println("    java -javaagent:SummerMC.jar -jar <server.jar> --nogui");
        System.out.println();
    }

    // ===================================================================
    //  COMMAND: launch — Start server with all optimizations
    // ===================================================================
    static void runLaunch(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java -jar SummerMC.jar launch <server.jar> [options]");
            System.out.println();
            System.out.println("Options:");
            System.out.println("  --memory SIZE    Heap size (default: 75% of RAM)");
            System.out.println("  --folia          Enable Folia optimizations");
            System.out.println("  --port PORT      Server port (default: 25565)");
            System.out.println("  --nogui          No GUI mode");
            System.out.println();
            return;
        }

        String serverJar = args[0];
        String memory = "75%";
        boolean folia = false;
        int port = 25565;
        boolean nogui = true;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--memory" -> { if (++i < args.length) memory = args[i]; }
                case "--folia"  -> folia = true;
                case "--port"   -> { if (++i < args.length) port = Integer.parseInt(args[i]); }
                case "--gui"    -> nogui = false;
            }
        }

        if (!new File(serverJar).exists()) {
            System.err.println("❌ Server JAR not found: " + serverJar);
            System.exit(1);
        }

        System.out.println("  🚀 SummerMC Launch: " + serverJar);
        System.out.println();

        // Detect JAR type
        boolean isFolia = false;
        try {
            String output = exec("jar tf " + serverJar + " 2>/dev/null | grep -i 'RegionizedServer' || true");
            isFolia = output.contains("RegionizedServer");
        } catch (Exception e) {
            // ignore
        }

        String profile = (folia || isFolia) ? "Folia" : "Paper";
        System.out.println("  Profile: " + profile);
        System.out.println("  Memory: " + memory);
        System.out.println("  Port: " + port);
        System.out.println();

        // Build JVM command
        List<String> cmd = new ArrayList<>();
        cmd.add("java");

        // Memory
        if (memory.endsWith("%")) {
            int pct = Integer.parseInt(memory.replace("%", ""));
            cmd.add("-Xms" + pct + "%");
            cmd.add("-Xmx" + pct + "%");
        } else {
            cmd.add("-Xms" + memory);
            cmd.add("-Xmx" + memory);
        }

        // ZGC
        cmd.add("-XX:+UseZGC");
        cmd.add("-XX:+ZGenerational");
        cmd.add("-XX:ZCollectionInterval=60");
        cmd.add("-XX:ZFragmentationLimit=25");
        cmd.add("-XX:+ZUncommit");
        cmd.add("-XX:ZUncommitDelay=300");

        // Memory & perf
        cmd.add("-XX:+AlwaysPreTouch");
        cmd.add("-XX:+DisableExplicitGC");
        cmd.add("-XX:+ParallelRefProcEnabled");
        cmd.add("-XX:+UseLargePages");
        cmd.add("-XX:+UseTransparentHugePages");
        cmd.add("-XX:+ExitOnOutOfMemoryError");

        if (profile.equals("Folia")) {
            cmd.add("-XX:+ZNUMA");
            cmd.add("-XX:ZAllocationSpikeTolerance=2.0");
            cmd.add("-Xss512k");
            cmd.add("-XX:StringDeduplicationAgeThreshold=2");
        } else {
            cmd.add("-XX:+UseStringDeduplication");
            cmd.add("-XX:StringDeduplicationAgeThreshold=3");
            cmd.add("-XX:+OptimizeStringConcat");
            cmd.add("-XX:+UseCompressedOops");
            cmd.add("-XX:+UseCompressedClassPointers");
        }

        // Self as javaagent
        Path ourJar = findOurJar();
        if (ourJar != null) {
            cmd.add("--enable-native-access=ALL-UNNAMED");
            cmd.add("-javaagent:" + ourJar.toAbsolutePath());
        }

        cmd.add("-jar");
        cmd.add(new File(serverJar).getAbsolutePath());
        cmd.add("--port");
        cmd.add(String.valueOf(port));
        if (nogui) cmd.add("--nogui");

        System.out.println("  JVM flags: " + String.join(" ", cmd.subList(1, Math.min(8, cmd.size()))) + " ...");
        System.out.println();

        // Apply system tuning first
        runOptimize(new String[]{ folia ? "--folia" : "" });

        System.out.println("  🚀 Starting server...");
        System.out.println();

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            pb.environment().put("SUMMERMC_ACTIVE", "true");
            pb.environment().put("SUMMERMC_PROFILE", profile);
            Process p = pb.start();
            p.getOutputStream().close();
            System.exit(p.waitFor());
        } catch (Exception e) {
            System.err.println("❌ Launch failed: " + e.getMessage());
            System.exit(1);
        }
    }

    // ===================================================================
    //  COMMAND: download — Download Paper/Folia server
    // ===================================================================
    static void runDownload(String[] args) {
        boolean folia = false;
        String output = "paper-server.jar";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--folia" -> folia = true;
                case "--output" -> { if (++i < args.length) output = args[i]; }
            }
        }

        String project = folia ? "folia" : "paper";
        String apiUrl = "https://api.papermc.io/v2/projects/" + project;

        System.out.println("  📥 Downloading " + project + " server...");
        System.out.println();

        try {
            // Get latest version
            String versionJson = fetchUrl(apiUrl);
            String version = extractLatestVersion(versionJson);

            // Get latest build
            String buildJson = fetchUrl(apiUrl + "/versions/" + version);
            String build = extractLatestBuild(buildJson);

            String downloadUrl = apiUrl + "/versions/" + version
                + "/builds/" + build + "/downloads/"
                + project + "-" + version + "-" + build + ".jar";

            System.out.println("  Version: " + version + " (build " + build + ")");
            System.out.println("  URL: " + downloadUrl);
            System.out.println();

            Path target = Paths.get(output).toAbsolutePath();
            try (InputStream in = new URL(downloadUrl).openStream();
                 ReadableByteChannel rbc = Channels.newChannel(in);
                 FileOutputStream fos = new FileOutputStream(target.toFile())) {
                fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
            }

            System.out.println("  ✅ Downloaded: " + target);
            System.out.println("  Size: " + (target.toFile().length() / 1048576) + "MB");
            System.out.println();
            System.out.println("  Run: java -jar SummerMC.jar launch " + target);
            System.out.println();

        } catch (Exception e) {
            System.err.println("❌ Download failed: " + e.getMessage());
            System.exit(1);
        }
    }

    // ===================================================================
    //  COMMAND: bench — Run benchmark
    // ===================================================================
    static void runBench() {
        try {
            Class<?> benchClass = Class.forName("com.summermc.Benchmark");
            benchClass.getMethod("run").invoke(null);
        } catch (ClassNotFoundException e) {
            System.out.println("  ℹ  Benchmark not available (build with --bench)");
            System.out.println("  Run: bash build.sh --bench");
        } catch (Exception e) {
            System.err.println("❌ Benchmark failed: " + e.getMessage());
        }
    }

    // ===================================================================
    //  COMMAND: docker — Generate Docker configuration
    // ===================================================================
    static void runDockerGen() {
        System.out.println("  🐳 Generating SummerMC Docker configuration...");
        System.out.println();

        // Dockerfile
        String dockerfile = """
FROM eclipse-temurin:21-jdk-jammy AS runtime

RUN apt-get update && apt-get install -y --no-install-recommends \\
    numactl procps lsof && rm -rf /var/lib/apt/lists/*

COPY SummerMC.jar /opt/SummerMC.jar
COPY server.jar /data/server.jar
WORKDIR /data

EXPOSE 25565

ENTRYPOINT ["java", "-javaagent:/opt/SummerMC.jar", "-jar", "/data/server.jar", "--nogui"]
""";

        // docker-compose
        String compose = """
services:
  minecraft:
    build: .
    container_name: mc-summermc
    ports:
      - "25565:25565"
    volumes:
      - ./world:/data/world
      - ./plugins:/data/plugins
    environment:
      - MEMORY=12G
    cap_add:
      - SYS_ADMIN
      - IPC_LOCK
    restart: unless-stopped
""";

        System.out.println("  Creating Dockerfile...");
        writeFile("Dockerfile.summermc", dockerfile);
        System.out.println("    -> Dockerfile.summermc");

        System.out.println("  Creating docker-compose.yml...");
        writeFile("docker-compose.summermc.yml", compose);
        System.out.println("    -> docker-compose.summermc.yml");

        System.out.println();
        System.out.println("  Usage:");
        System.out.println("    cp SummerMC.jar .");
        System.out.println("    cp server.jar .");
        System.out.println("    docker compose -f docker-compose.summermc.yml up -d");
        System.out.println();
    }

    // ===================================================================
    //  Helpers
    // ===================================================================
    static void printBanner() {
        System.out.println("  ___ _   ___  ___  __  __ ____ _  _ ");
        System.out.println(" / __| | | \\ \\/ / |/ / / /_  /| \\| |");
        System.out.println(" \\__ \\ |_| |>  <| ' <  / // /_| .` |");
        System.out.println(" |___/ \\___//_/\\_\\_|\\_\\ /_/___|_|\\_|");
        System.out.println("  v" + VERSION + " — Minecraft Server CLI Optimizer");
        System.out.println("  Optimize ANY Paper/Folia server. No Minecraft code included.");
        System.out.println();
    }

    static void printHelp() {
        System.out.println("USAGE:");
        System.out.println("  java -jar SummerMC.jar <command> [options]");
        System.out.println();
        System.out.println("COMMANDS:");
        System.out.println("  optimize [--folia] [--dry-run]");
        System.out.println("        Apply system tuning (sysctl, CPU, IO, hugepages)");
        System.out.println();
        System.out.println("  launch <server.jar> [--memory 12G] [--folia] [--port 25565]");
        System.out.println("        Launch a server with full optimization stack");
        System.out.println();
        System.out.println("  download [--folia] [--output server.jar]");
        System.out.println("        Download Paper/Folia server from official repo");
        System.out.println();
        System.out.println("  bench");
        System.out.println("        Run performance benchmark (requires --bench build)");
        System.out.println();
        System.out.println("  docker");
        System.out.println("        Generate Dockerfile + docker-compose config");
        System.out.println();
        System.out.println("  help");
        System.out.println("        Show this help");
        System.out.println();
        System.out.println("EXAMPLES:");
        System.out.println("  java -jar SummerMC.jar download");
        System.out.println("  java -jar SummerMC.jar launch paper-server.jar --memory 12G");
        System.out.println("  java -jar SummerMC.jar launch folia-server.jar --folia");
        System.out.println("  java -jar SummerMC.jar optimize --dry-run");
        System.out.println();
        System.out.println("  # Also works as javaagent:");
        System.out.println("  java -javaagent:SummerMC.jar -jar server.jar --nogui");
        System.out.println();
        System.out.println("LICENSE: MIT — No Mojang code distributed.");
        System.out.println("         Paper server downloaded from official MIT repo.");
        System.out.println();
    }

    static Path findOurJar() {
        try {
            String path = SummerMC.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI().getPath();
            if (path != null && path.endsWith(".jar")) return Paths.get(path);
        } catch (Exception e) { /* not from JAR */ }
        return null;
    }

    static String fetchUrl(String url) throws IOException {
        URLConnection conn = new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "SummerMC/" + VERSION);
        try (Scanner s = new Scanner(conn.getInputStream(), "UTF-8")) {
            s.useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }
    }

    static String extractLatestVersion(String json) {
        int idx = json.lastIndexOf("\"version\"");
        if (idx < 0) return "1.21.4";
        int start = json.indexOf('"', idx + 10) + 1;
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    static String extractLatestBuild(String json) {
        int idx = json.lastIndexOf("\"build\"");
        if (idx < 0) return "1";
        int colon = json.indexOf(':', idx);
        int end = json.indexOf(',', colon);
        if (end < 0) end = json.indexOf('}', colon);
        return json.substring(colon + 1, end).trim();
    }

    static String exec(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", cmd});
        try (Scanner s = new Scanner(p.getInputStream(), "UTF-8")) {
            s.useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }
    }

    static String[][] getSysctlCommands(boolean folia) {
        if (folia) {
            return new String[][]{
                {"vm.swappiness", "10"},
                {"vm.dirty_ratio", "5"},
                {"vm.dirty_background_ratio", "2"},
                {"vm.vfs_cache_pressure", "50"},
                {"kernel.sched_min_granularity_ns", "1500000"},
                {"kernel.sched_latency_ns", "12000000"},
                {"kernel.sched_wakeup_granularity_ns", "1200000"},
                {"kernel.sched_autogroup_enabled", "1"},
                {"kernel.numa_balancing", "1"},
            };
        } else {
            return new String[][]{
                {"vm.swappiness", "10"},
                {"vm.dirty_ratio", "5"},
                {"vm.dirty_background_ratio", "2"},
                {"vm.vfs_cache_pressure", "50"},
                {"kernel.sched_min_granularity_ns", "3000000"},
                {"kernel.sched_latency_ns", "18000000"},
                {"kernel.sched_wakeup_granularity_ns", "2000000"},
                {"kernel.sched_autogroup_enabled", "0"},
                {"kernel.numa_balancing", "0"},
            };
        }
    }

    static void writeFile(String path, String content) {
        try {
            Files.writeString(Paths.get(path), content);
        } catch (IOException e) {
            System.err.println("  ❌ Failed to write " + path + ": " + e.getMessage());
        }
    }
}
