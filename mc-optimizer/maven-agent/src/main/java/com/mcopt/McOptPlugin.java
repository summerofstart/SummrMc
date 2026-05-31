package com.mcopt;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * McOpt — Bukkit/Paper plugin entry point (legacy, use FoliaPlugin for new installs).
 * Bridges kernel-level tuning (via JNI/NativeBridge), JVM agent calls,
 * and provides runtime telemetry. Auto-detects Folia.
 */
public final class McOptPlugin extends JavaPlugin {

    private static McOptPlugin instance;
    private JVMOptimizer jvmOpt;
    private GCMonitor gcMonitor;
    private LowLevelTuner lowTuner;
    private boolean nativeLoaded = false;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getLogger().info("§a[McOpt] Initializing ultra-low-layer optimizer...");

        // 1. Load native library (from JAR or system)
        if (NativeLoader.load()) {
            nativeLoaded = true;
            getLogger().info("[McOpt] Native library loaded");
        } else {
            getLogger().warning("[McOpt] Native library not available — pure-JVM mode");
        }

        // 2. Initialize tuners
        jvmOpt = new JVMOptimizer(getLogger());
        gcMonitor = new GCMonitor(getLogger());
        lowTuner = new LowLevelTuner(getLogger());

        // 3. Apply low-level tuning
        if (nativeLoaded) {
            Profile profile = loadProfileFromConfig();
            String result = NativeBridge.nativeTuneAll(profile.ordinal());
            getLogger().info("[McOpt] Native tuning report:\n" + result);
        }

        // 4. Apply JVM tuning
        jvmOpt.applyAll();

        // 5. Register GC monitor (periodic telemetry)
        int gcInterval = getConfig().getInt("gc-monitor-interval-seconds", 300);
        new BukkitRunnable() {
            @Override
            public void run() {
                gcMonitor.logStats();
            }
        }.runTaskTimerAsynchronously(this, 20L * 60, 20L * gcInterval);

        // 6. CPU affinity (from config)
        String cpuMask = getConfig().getString("cpu-affinity-mask", "");
        if (!cpuMask.isEmpty() && nativeLoaded) {
            try {
                long mask = Long.parseLong(cpuMask, 16);
                NativeBridge.nativePinToCpus(mask);
                getLogger().info("[McOpt] Pinned to CPU mask: 0x" + Long.toHexString(mask));
            } catch (NumberFormatException e) {
                getLogger().warning("[McOpt] Invalid cpu-affinity-mask: " + cpuMask);
            }
        }

        // 7. Register command
        getCommand("mcopt").setExecutor(new McOptCommand(gcMonitor, jvmOpt, () -> this.nativeLoaded));

        getLogger().info("§a[McOpt] §f✔ Optimizer active");
        getLogger().info("§a[McOpt] §7Profile: " + loadProfileFromConfig());
    }

    @Override
    public void onDisable() {
        getLogger().info("[McOpt] Shutting down...");
    }

    private Profile loadProfileFromConfig() {
        String name = getConfig().getString("tuning-profile", "aggressive");
        try {
            return Profile.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Profile.AGGRESSIVE;
        }
    }

    public static McOptPlugin getInstance() { return instance; }
    public JVMOptimizer getJvmOptimizer() { return jvmOpt; }
    public GCMonitor getGcMonitor() { return gcMonitor; }
    public boolean isNativeLoaded() { return nativeLoaded; }
}
