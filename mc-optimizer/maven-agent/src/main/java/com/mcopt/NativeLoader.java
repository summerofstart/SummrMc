package com.mcopt;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Loads libmc_native_opt.so from inside the JAR.
 * Extracts to a temp dir on first use, then loads via System.load().
 * Works in Docker, bare-metal, and restricted environments.
 */
public final class NativeLoader {

    private static final Logger log = Logger.getLogger("NativeLoader");
    private static boolean loaded = false;

    /** Path inside the JAR where the .so is stored */
    private static final String SO_RESOURCE = "/native/linux-x86-64/libmc_native_opt.so";

    private NativeLoader() {}

    /**
     * Load the native library, extracting from JAR if needed.
     * Safe to call multiple times.
     * @return true if native library was successfully loaded
     */
    public static synchronized boolean load() {
        if (loaded) return true;

        // 1. Try direct System.loadLibrary first (library installed at system level)
        try {
            System.loadLibrary("mc_native_opt");
            loaded = true;
            log.info("[NativeLoader] Loaded libmc_native_opt.so from system library path");
            return true;
        } catch (UnsatisfiedLinkError e) {
            // Fall through to extraction
        }

        // 2. Try to extract from JAR
        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("mcopt-native-");
            File soFile = tmpDir.resolve("libmc_native_opt.so").toFile();
            soFile.deleteOnExit();

            try (InputStream in = NativeLoader.class.getResourceAsStream(SO_RESOURCE)) {
                if (in == null) {
                    log.warning("[NativeLoader] " + SO_RESOURCE + " not found in JAR");
                    return false;
                }
                try (FileOutputStream out = new FileOutputStream(soFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                }
            }

            System.load(soFile.getAbsolutePath());
            loaded = true;
            log.info("[NativeLoader] Extracted and loaded libmc_native_opt.so from JAR (" + soFile.length() + " bytes)");
            return true;
        } catch (Exception e) {
            log.warning("[NativeLoader] Failed to load native library: " + e.getMessage());
            return false;
        } finally {
            // Schedule temp dir cleanup (async, after library is loaded)
            if (tmpDir != null) {
                final Path dir = tmpDir;
                new Thread(() -> {
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                    try {
                        java.nio.file.Files.walk(dir)
                            .sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
                    } catch (Exception ignored) {}
                }, "mcopt-native-cleanup").start();
            }
        }
    }

    public static boolean isLoaded() { return loaded; }
}