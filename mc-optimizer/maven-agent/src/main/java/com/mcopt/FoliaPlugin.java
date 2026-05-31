package com.mcopt;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * McOpt for Folia — ultra-low-layer optimizer plugin.
 *
 * Key differences from the base McOpt plugin:
 * 1. Registers FoliaRegionWatcher to pin region threads to physical cores
 * 2. Uses Folia-optimized sysctl/kernel profile
 * 3. NUMA-aware memory allocation
 * 4. Faster GC monitoring interval (Folia allocates more per tick)
 * 5. Region-cache aware chunk pre-generation
 * 6. Auto-detects Folia at runtime
 */
public final class FoliaPlugin extends JavaPlugin {

    private static FoliaPlugin instance;
    private JVMOptimizer jvmOpt;
    private GCMonitor gcMonitor;
    private FoliaRegionWatcher regionWatcher;
    private FoliaTuningProfile foliaProfile;
    private boolean nativeLoaded = false;
    private boolean isFolia = false;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getLogger().info("§a[McOpt-Folia] Initializing Folia-aware optimizer...");

        // Detect Folia
        detectFolia();

        // Load native library (from JAR or system)
        if (NativeLoader.load()) {
            nativeLoaded = true;
            getLogger().info("[McOpt-Folia] Native library loaded");
        } else {
            getLogger().warning("[McOpt-Folia] Native library not available — pure-JVM mode (region pinning unavailable)");
        }

        // Initialize components
        jvmOpt = new JVMOptimizer(getLogger());
        gcMonitor = new GCMonitor(getLogger());
        foliaProfile = new FoliaTuningProfile(getLogger());

        // Only start region watcher if native is loaded
        if (nativeLoaded) {
            regionWatcher = new FoliaRegionWatcher(getConfig().getInt("reserved-cores", 2));
        } else {
            regionWatcher = null;
        }

        // Apply kernel-level tuning
        if (nativeLoaded) {
            int profileOrdinal = loadProfileOrdinal();
            String result = NativeBridge.nativeTuneAll(profileOrdinal);
            getLogger().info("[McOpt-Folia] Native tuning report:\n" + result);
        } else {
            getLogger().info("[McOpt-Folia] Applying safe defaults via shell...");
            new LowLevelTuner(getLogger()).applySafeDefaults();
        }

        // Apply JVM tuning info
        jvmOpt.applyAll();

        if (isFolia) {
            foliaProfile.logRecommendedFlags();
        }

        // Start Folia region thread watcher (pins new region threads to cores)
        if (isFolia && nativeLoaded) {
            regionWatcher.start();
            getLogger().info("§a[McOpt-Folia] Region thread watcher started");
        }

        // GC monitoring — faster interval for Folia
        int gcInterval = getConfig().getInt("gc-monitor-interval-seconds", isFolia ? 60 : 300);
        new BukkitRunnable() {
            @Override
            public void run() {
                gcMonitor.logStats();
            }
        }.runTaskTimerAsynchronously(this, 20L * 60, 20L * gcInterval);

        // Register command
        getCommand("mcopt").setExecutor(new McOptCommand(gcMonitor, jvmOpt, () -> this.nativeLoaded));

        getLogger().info("§a[McOpt-Folia] §f✔ Optimizer active" + (isFolia ? " (Folia mode)" : " (Paper mode)"));
    }

    @Override
    public void onDisable() {
        if (regionWatcher != null) {
            regionWatcher.stop();
        }
        getLogger().info("[McOpt-Folia] Shutting down...");
    }

    /** Detect if we're running on Folia (vs Paper) by checking for Folia API classes. */
    private void detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFolia = true;
            getLogger().info("§a[McOpt-Folia] Folia detected — applying region-aware optimizations");
        } catch (ClassNotFoundException e) {
            isFolia = false;
            getLogger().info("[McOpt-Folia] Standard Paper/Spigot detected");
        }
    }

    private int loadProfileOrdinal() {
        String name = getConfig().getString("tuning-profile", "folia");
        if (isFolia && (name.equals("folia") || name.equals("aggressive"))) {
            return 4; // Folia
        }
        try {
            return Profile.valueOf(name.toUpperCase()).ordinal();
        } catch (IllegalArgumentException e) {
            return isFolia ? 4 : 1; // folia or aggressive
        }
    }

    public static FoliaPlugin getInstance() { return instance; }
    public JVMOptimizer getJvmOptimizer() { return jvmOpt; }
    public GCMonitor getGcMonitor() { return gcMonitor; }
    public FoliaRegionWatcher getRegionWatcher() { return regionWatcher; }
    public boolean isNativeLoaded() { return nativeLoaded; }
    public boolean isFolia() { return isFolia; }
}
