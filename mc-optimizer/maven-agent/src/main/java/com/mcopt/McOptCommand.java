package com.mcopt;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * /mcopt command — query status, apply profiles, get recommendations.
 */
public final class McOptCommand implements CommandExecutor {

    private final GCMonitor gcMonitor;
    private final JVMOptimizer jvmOpt;
    private final BooleanSupplier nativeLoaded;

    public McOptCommand(GCMonitor gcMonitor, JVMOptimizer jvmOpt, BooleanSupplier nativeLoaded) {
        this.gcMonitor = gcMonitor;
        this.jvmOpt = jvmOpt;
        this.nativeLoaded = nativeLoaded;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendStatus(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status" -> sendStatus(sender);
            case "gc" -> gcMonitor.logStats();
            case "profile" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /mcopt profile <default|aggressive|balanced|low_memory>");
                    return true;
                }
                // Re-applying via native bridge
                try {
                    Profile p = Profile.valueOf(args[1].toUpperCase());
                    if (nativeLoaded.getAsBoolean()) {
                        String report = NativeBridge.nativeTuneAll(p.ordinal());
                        sender.sendMessage(ChatColor.GREEN + "[McOpt] Profile changed to " + args[1]);
                        for (String line : report.split("\n")) {
                            sender.sendMessage(ChatColor.GRAY + "  " + line);
                        }
                    } else {
                        sender.sendMessage(ChatColor.YELLOW + "[McOpt] Native library not loaded. Only JVM tuning active.");
                    }
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid profile. Use: default, aggressive, balanced, low_memory");
                }
            }
            case "flags" -> {
                sender.sendMessage(ChatColor.GOLD + "[McOpt] Recommended JVM flags:");
                for (String flag : JVMOptimizer.RECOMMENDED_FLAGS) {
                    sender.sendMessage(ChatColor.GRAY + "  " + flag);
                }
            }
            default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use: status, gc, profile, flags");
        }

        return true;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "===== [McOpt] Status =====");
        sender.sendMessage(ChatColor.AQUA + "Native lib: " + colorBool(nativeLoaded.getAsBoolean()));
        sender.sendMessage(ChatColor.AQUA + "JVM: " + jvmOpt.getSummary());
        sender.sendMessage(ChatColor.AQUA + "GC pauses: " + gcMonitor.getGcCount()
            + " (total " + gcMonitor.getTotalPauseMs() + "ms, max " + String.format("%.0f", gcMonitor.getMaxPauseMs()) + "ms)");
        sender.sendMessage(ChatColor.GOLD + "===========================");
    }

    private String colorBool(boolean val) {
        return val ? ChatColor.GREEN + "✓" : ChatColor.RED + "✗";
    }
}
