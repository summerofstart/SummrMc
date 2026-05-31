package com.mcopt;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.logging.Logger;

/**
 * JVM Agent — applied at startup via -javaagent:/path/to/mc-opt-agent.jar.
 * Injects class transformers and applies early JVM flags/monitoring.
 * GC monitoring is handled by the Bukkit plugin (FoliaPlugin/McOptPlugin)
 * to avoid duplicate registration — the agent only applies kernel tuning + flags.
 */
public final class McOptJavaAgent {

    private static final Logger log = Logger.getLogger("McOptAgent");

    public static void premain(String args, Instrumentation inst) {
        log.info("[McOpt-Agent] === Ultra-low-layer optimizer agent ===");

        // Log JVM flags
        String jvmArgs = System.getProperty("sun.java.command", "?");
        log.info("[McOpt-Agent] Main class: " + jvmArgs);

        // Show recommended flags that should be present
        boolean hasZGC = ManagementFactory.getRuntimeMXBean().getInputArguments()
            .stream().anyMatch(arg -> arg.contains("+UseZGC"));
        boolean hasHugePages = ManagementFactory.getRuntimeMXBean().getInputArguments()
            .stream().anyMatch(arg -> arg.contains("+UseLargePages") || arg.contains("+UseTransparentHugePages"));

        if (!hasZGC) {
            log.warning("[McOpt-Agent] ⚠ ZGC not detected. Add -XX:+UseZGC -XX:+ZGenerational for best performance");
        }
        if (!hasHugePages) {
            log.warning("[McOpt-Agent] ⚠ Huge pages not detected. Add -XX:+UseTransparentHugePages for sudo-less memory tuning");
        }

        // Try to load native (from JAR or system) and apply kernel tuning at agent level
        try {
            if (!NativeLoader.load()) {
                log.info("[McOpt-Agent] Native library not available — continuing without kernel tuning");
            } else {
                log.info("[McOpt-Agent] Native library loaded, applying kernel tuning...");

                // Detect Folia
                boolean isFolia = false;
                try {
                    Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                    isFolia = true;
                    log.info("[McOpt-Agent] Folia detected — using Folia profile");
                } catch (ClassNotFoundException e) {
                    log.info("[McOpt-Agent] Standard Paper/Spigot detected");
                }

                int profileId = isFolia ? Profile.FOLIA.ordinal() : Profile.AGGRESSIVE.ordinal();
                String report = NativeBridge.nativeTuneAll(profileId);
                log.info("[McOpt-Agent] Native tune report:\n" + report);
            }
        } catch (Exception e) {
            log.info("[McOpt-Agent] Native library: " + e.getMessage());
        }

        log.info("[McOpt-Agent] ✓ Agent initialized");
    }

    /**
     * Agent re-attachment point (Java 9+).
     */
    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }
}
