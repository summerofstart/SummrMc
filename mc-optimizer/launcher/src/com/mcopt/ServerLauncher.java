package com.mcopt;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;

public final class ServerLauncher {
    private static final String REAL_SERVER = "server.paperclip.jar";
    private static final String AGENT = "mc-optimizer/maven-agent/target/mc-opt-agent.jar";

    private ServerLauncher() {
    }

    public static void main(String[] args) throws Exception {
        Path root = locateRoot();
        Path server = root.resolve(REAL_SERVER);
        if (!Files.isRegularFile(server)) {
            System.err.println("Missing " + REAL_SERVER + " next to server.jar");
            System.err.println("Move the real Paper/Purpur/Folia jar to " + REAL_SERVER + ".");
            System.exit(2);
        }

        boolean folia = isFolia(server) || "1".equals(System.getenv("FOLIA_FORCE"));
        int heapMb = heapMb(folia);

        List<String> command = new ArrayList<>();
        command.add(javaBinary());
        command.add("-Xms" + heapMb + "m");
        command.add("-Xmx" + heapMb + "m");
        command.addAll(jvmFlags(folia));

        Path agent = root.resolve(AGENT);
        if (Files.isRegularFile(agent) && !"1".equals(System.getenv("MC_SKIP_AGENT"))) {
            command.add("-javaagent:" + agent.toAbsolutePath());
        }

        String extra = System.getenv("MC_JVM_EXTRA");
        if (extra != null && !extra.isBlank()) {
            command.addAll(splitArgs(extra));
        }

        command.add("-jar");
        command.add(server.toAbsolutePath().toString());
        command.add("--nogui");
        for (String arg : args) {
            command.add(arg);
        }

        System.out.println("[McOpt] Launching optimized Minecraft server");
        System.out.println("[McOpt] Mode: " + (folia ? "Folia" : "Paper/Purpur"));
        System.out.println("[McOpt] Heap: " + heapMb + " MB");
        System.out.println("[McOpt] Agent: " + (Files.isRegularFile(agent) ? agent : "none"));

        Process process = new ProcessBuilder(command)
            .directory(root.toFile())
            .inheritIO()
            .start();
        System.exit(process.waitFor());
    }

    private static Path locateRoot() throws IOException {
        String classPath = ServerLauncher.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        Path jar = Path.of(classPath).toAbsolutePath();
        Path parent = Files.isDirectory(jar) ? jar : jar.getParent();
        return parent == null ? Path.of(".").toAbsolutePath() : parent;
    }

    private static boolean isFolia(Path server) {
        try (JarFile jar = new JarFile(server.toFile())) {
            return jar.getEntry("io/papermc/paper/threadedregions/RegionizedServer.class") != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static int heapMb(boolean folia) {
        String override = System.getenv("MC_HEAP_MB");
        if (override != null && !override.isBlank()) {
            return Integer.parseInt(override.trim());
        }

        long totalMb = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
            .getTotalMemorySize() / 1024 / 1024;
        long reserveMb = folia ? 4096 + (Runtime.getRuntime().availableProcessors() / 4L) * 1024L : totalMb / 4L;
        long heap = Math.max(1024L, totalMb - reserveMb);
        return (int) Math.min(heap, Integer.MAX_VALUE);
    }

    private static List<String> jvmFlags(boolean folia) {
        List<String> flags = new ArrayList<>();
        flags.add("-XX:+UseZGC");
        flags.add("-XX:+ZGenerational");
        flags.add("-XX:ZCollectionInterval=" + (folia ? "0.05" : "0.1"));
        flags.add("-XX:ZFragmentationLimit=25");
        flags.add("-XX:+ZUncommit");
        flags.add("-XX:ZUncommitDelay=30");
        flags.add("-XX:+AlwaysPreTouch");
        flags.add("-XX:+DisableExplicitGC");
        flags.add("-XX:+ParallelRefProcEnabled");
        flags.add("-XX:+UseStringDeduplication");
        flags.add("-XX:StringDeduplicationAgeThreshold=" + (folia ? "2" : "3"));
        flags.add("-XX:+OptimizeStringConcat");
        flags.add("-XX:+UseCompressedOops");
        flags.add("-XX:+UseCompressedClassPointers");
        flags.add("-XX:+ExitOnOutOfMemoryError");
        flags.add("-XX:+UseTransparentHugePages");
        if (folia) {
            flags.add("-XX:ZAllocationSpikeTolerance=2.0");
            flags.add("-XX:+ZNUMA");
            flags.add("-Xss512k");
        }
        return flags;
    }

    private static String javaBinary() {
        String javaHome = System.getProperty("java.home");
        String exe = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        return Path.of(javaHome, "bin", exe).toString();
    }

    private static List<String> splitArgs(String text) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            args.add(current.toString());
        }
        return args;
    }
}
