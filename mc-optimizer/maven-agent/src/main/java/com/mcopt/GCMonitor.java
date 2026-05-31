package com.mcopt;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;

import javax.management.Notification;
import javax.management.NotificationListener;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.lang.management.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * GC telemetry — listens for GC notifications and logs pause times.
 * Used to measure the impact of optimizations.
 */
public final class GCMonitor implements NotificationListener {

    private final Logger log;
    private final AtomicLong totalPauseMs = new AtomicLong(0);
    private final AtomicLong gcCount = new AtomicLong(0);
    private volatile long lastLogTime = System.currentTimeMillis();
    private volatile double maxPauseMs = 0;

    public GCMonitor(Logger log) {
        this.log = log;

        // Register as GC notification listener
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc instanceof NotificationEmitter emitter) {
                emitter.addNotificationListener(this, null, gc.getName());
                log.info("[McOpt] GC monitor attached to: " + gc.getName());
            }
        }
    }

    @Override
    public void handleNotification(Notification notification, Object handback) {
        if (!notification.getType().equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {
            return;
        }

        try {
            CompositeData cd = (CompositeData) notification.getUserData();
            GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from(cd);
            GcInfo gcInfo = info.getGcInfo();

            long duration = gcInfo.getDuration();
            String gcName = info.getGcName();
            String gcAction = info.getGcAction();

            // ZGC: only warn on actual STW pauses (not concurrent cycle completions)
            boolean isActualPause = gcAction != null
                && (gcAction.contains("pause") || gcAction.contains("Pause"));

            // Count only actual pauses (not concurrent cycles)
            if (isActualPause || duration >= 0) {
                long count = gcCount.incrementAndGet();
                long total = totalPauseMs.addAndGet(duration);
                if (duration > maxPauseMs) {
                    maxPauseMs = duration;
                }

                // Warn on actual STW pauses > 50ms OR concurrent cycles > 500ms
                if ((isActualPause && duration > 50) || (!isActualPause && duration > 500)) {
                    log.warning(String.format(
                        "[McOpt] GC: %s — %d ms (total: %d ms / %d GCs)",
                        gcName, duration, total, count));
                }
            }

            // Periodic summary every 1000 actual pauses
            if (gcCount.get() % 1000 == 0 && gcCount.get() > 0) {
                logStats();
            }
        } catch (Exception e) {
            // Silently ignore parsing errors
        }
    }

    /** Log current GC statistics */
    public void logStats() {
        long count = gcCount.get();
        long total = totalPauseMs.get();
        double avg = count > 0 ? (double) total / count : 0;
        long elapsed = (System.currentTimeMillis() - lastLogTime) / 1000;
        log.info(String.format(
            "[McOpt] GC stats: %d pauses, avg=%.1fms, max=%.0fms, total=%.1fs (elapsed=%ds)",
            count, avg, maxPauseMs, total / 1000.0, elapsed));
        lastLogTime = System.currentTimeMillis();
    }

    public double getMaxPauseMs() { return maxPauseMs; }
    public long getGcCount() { return gcCount.get(); }
    public long getTotalPauseMs() { return totalPauseMs.get(); }
}
