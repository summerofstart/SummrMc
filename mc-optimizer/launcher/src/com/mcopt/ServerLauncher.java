package com.mcopt;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.jar.JarFile;

public final class ServerLauncher {
    private static final String REAL_SERVER = "server.paperclip.jar";
    private static final String AGENT = "mc-optimizer/maven-agent/target/mc-opt-agent.jar";
    private static final String EMBEDDED_AGENT = "/mc-opt-agent.jar";

    private ServerLauncher() {
    }

    public static void main(String[] args) throws Exception {
        Path root = locateRoot();
        Path server = realServerJar(root);
        if (!Files.isRegularFile(server)) {
            server = downloadServerJar(server);
        }

        boolean folia = isFolia(server) || "1".equals(System.getenv("FOLIA_FORCE"));
        int heapMb = heapMb(folia);

        List<String> command = new ArrayList<>();
        command.add(javaBinary());
        command.add("-Xms" + heapMb + "m");
        command.add("-Xmx" + heapMb + "m");
        command.addAll(jvmFlags(folia));

        Path agent = agentJar(root);
        if (agent != null && Files.isRegularFile(agent) && !"1".equals(System.getenv("MC_SKIP_AGENT"))) {
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
        System.out.println("[McOpt] Agent: " + (agent != null && Files.isRegularFile(agent) ? agent : "none"));

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

    private static Path realServerJar(Path root) {
        String explicit = System.getenv("MC_REAL_SERVER_JAR");
        if (explicit == null || explicit.isBlank()) {
            explicit = System.getenv("REAL_SERVER_JAR");
        }
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit).toAbsolutePath();
        }

        Path preferred = root.resolve(REAL_SERVER);
        if (Files.isRegularFile(preferred)) {
            return preferred;
        }

        try (Stream<Path> jars = Files.list(root)) {
            Optional<Path> detected = jars
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .filter(path -> !path.getFileName().toString().equals("server.jar"))
                .filter(path -> !path.getFileName().toString().equals("mc-opt-agent.jar"))
                .filter(path -> !path.getFileName().toString().startsWith("server-launcher"))
                .sorted()
                .findFirst();
            if (detected.isPresent()) {
                return detected.get();
            }
        } catch (IOException ignored) {
        }

        return preferred;
    }

    private static Path downloadServerJar(Path target) throws Exception {
        String directUrl = env("MC_SERVER_DOWNLOAD_URL", "SERVER_DOWNLOAD_URL");
        String project = env("PAPER_PROJECT", "MC_SERVER_PROJECT");
        String version = env("PAPER_VERSION", "MC_SERVER_VERSION", "MINECRAFT_VERSION", "VANILLA_VERSION");
        String build = env("PAPER_BUILD", "MC_SERVER_BUILD");

        if (project == null || project.isBlank()) {
            project = "paper";
        }
        if (version == null || version.isBlank()) {
            version = "1.21.8";
        }

        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

        URI uri;
        if (directUrl != null && !directUrl.isBlank()) {
            uri = URI.create(directUrl);
        } else {
            if (build == null || build.isBlank() || build.equalsIgnoreCase("latest")) {
                build = latestBuild(client, project, version);
            }
            String name = project + "-" + version + "-" + build + ".jar";
            uri = URI.create("https://api.papermc.io/v2/projects/" + project
                + "/versions/" + version
                + "/builds/" + build
                + "/downloads/" + name);
        }

        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".download");
        System.out.println("[McOpt] Real server jar not found. Downloading " + uri);

        HttpRequest request = HttpRequest.newBuilder(uri)
            .header("User-Agent", "McOpt-ServerLauncher")
            .build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(tmp);
            throw new IOException("Failed to download server jar: HTTP " + response.statusCode() + " from " + uri);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static String latestBuild(HttpClient client, String project, String version) throws Exception {
        URI uri = URI.create("https://api.papermc.io/v2/projects/" + project + "/versions/" + version + "/builds");
        HttpRequest request = HttpRequest.newBuilder(uri)
            .header("User-Agent", "McOpt-ServerLauncher")
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to query latest PaperMC build: HTTP " + response.statusCode() + " from " + uri);
        }

        Matcher matcher = Pattern.compile("\"build\"\\s*:\\s*(\\d+)").matcher(response.body());
        String latest = null;
        while (matcher.find()) {
            latest = matcher.group(1);
        }
        if (latest == null) {
            throw new IOException("No builds found for " + project + " " + version);
        }
        return latest;
    }

    private static String env(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Path agentJar(Path root) {
        Path external = root.resolve(AGENT);
        if (Files.isRegularFile(external)) {
            return external;
        }

        try (InputStream in = ServerLauncher.class.getResourceAsStream(EMBEDDED_AGENT)) {
            if (in == null) {
                return null;
            }
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "mcopt");
            Files.createDirectories(dir);
            Path embedded = dir.resolve("mc-opt-agent.jar");
            Files.copy(in, embedded, StandardCopyOption.REPLACE_EXISTING);
            embedded.toFile().deleteOnExit();
            return embedded;
        } catch (IOException e) {
            System.err.println("[McOpt] Embedded agent unavailable: " + e.getMessage());
            return null;
        }
    }

    private static int heapMb(boolean folia) {
        String override = System.getenv("MC_HEAP_MB");
        if (override != null && !override.isBlank()) {
            return Integer.parseInt(override.trim());
        }

        String panelMemory = System.getenv("SERVER_MEMORY");
        if (panelMemory != null && !panelMemory.isBlank()) {
            long memoryMb = Long.parseLong(panelMemory.trim());
            if (memoryMb > 0) {
                long heap = Math.max(1024L, memoryMb * 90L / 100L);
                return (int) Math.min(heap, Integer.MAX_VALUE);
            }
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
