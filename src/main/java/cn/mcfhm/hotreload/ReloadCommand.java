package cn.mcfhm.hotreload;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ReloadCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ACTIONS =
            List.of("reload", "patch", "unload", "load", "status", "list", "info", "help");

    private final HotReloadPlugin plugin;
    private final ReloadService service;

    public ReloadCommand(HotReloadPlugin plugin, ReloadService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            help(sender, label);
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        boolean force = Arrays.stream(args).anyMatch("-f"::equalsIgnoreCase);
        boolean idle = Arrays.stream(args).anyMatch("--idle"::equalsIgnoreCase) || service.idleOnly();
        switch (action) {
            case "reload", "unload", "load" -> {
                if (args.length < 2) {
                    line(sender, "用法: /" + label + " " + action + " <插件> [-f] [--idle]", NamedTextColor.RED);
                    return true;
                }
                switch (action) {
                    case "unload" -> {
                        Plugin target = Bukkit.getPluginManager().getPlugin(args[1]);
                        if (target == null) {
                            line(sender, "找不到插件 " + args[1], NamedTextColor.RED);
                            return true;
                        }
                        print(sender, "unload " + target.getName(), service.unload(target, force));
                    }
                    case "load" -> print(sender, "load " + args[1], service.load(args[1], force));
                    default -> reload(sender, args[1], force, idle);
                }
            }
            case "patch" -> {
                if (args.length < 2) {
                    line(sender, "用法: /" + label + " patch <插件> [新jar]", NamedTextColor.RED);
                    return true;
                }
                String source = args.length >= 3 && !args[2].startsWith("-") ? args[2] : null;
                print(sender, "patch " + args[1], service.patch(args[1], source));
            }
            case "status" -> status(sender, args);
            case "list" -> list(sender);
            case "info" -> info(sender);
            default -> help(sender, label);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(ACTIONS, args[0]);
        }
        if (args.length == 2 && !"list".equalsIgnoreCase(args[0])
                && !"help".equalsIgnoreCase(args[0]) && !"info".equalsIgnoreCase(args[0])) {
            List<String> names = new ArrayList<>();
            for (Plugin loaded : Bukkit.getPluginManager().getPlugins()) {
                names.add(loaded.getName());
            }
            if ("load".equalsIgnoreCase(args[0])) {
                File dir = plugin.getDataFolder().getParentFile();
                File[] jars = dir == null ? null : dir.listFiles((ignored, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
                if (jars != null) {
                    for (File jar : jars) {
                        String fileName = jar.getName();
                        names.add(fileName.substring(0, fileName.length() - 4));
                    }
                }
            }
            return filter(names, args[1]);
        }
        if (args.length == 3) {
            return filter(List.of("-f", "--idle"), args[2]);
        }
        return List.of();
    }

    private void reload(CommandSender sender, String name, boolean force, boolean idle) {
        Plugin target = Bukkit.getPluginManager().getPlugin(name);
        if (target == null) {
            line(sender, "找不到插件 " + name, NamedTextColor.RED);
            return;
        }
        if (idle && !service.idle()) {
            line(sender, "现在有 " + Bukkit.getOnlinePlayers().size() + " 人在线，已排队等空服，最多 "
                    + service.idleTimeoutSeconds() + " 秒", NamedTextColor.YELLOW);
            service.whenIdle(() -> runReload(sender, target, force));
            return;
        }
        runReload(sender, target, force);
    }

    private void runReload(CommandSender sender, Plugin target, boolean force) {
        broadcast("[HotReload] 正在重载 " + target.getName() + "，可能卡顿一下");
        ReloadReport unload = service.unload(target, force);
        print(sender, "unload " + target.getName(), unload);
        if (!unload.success()) {
            return;
        }
        print(sender, "load " + target.getName(), service.load(target.getName(), force));
        broadcast("[HotReload] " + target.getName() + " 已重载");
    }

    private void broadcast(String text) {
        if (!plugin.getConfig().getBoolean("announce", true) || Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }
        Bukkit.broadcast(Component.text(text, NamedTextColor.YELLOW));
    }

    private void info(CommandSender sender) {
        line(sender, "HotReload 运行环境", NamedTextColor.AQUA);
        line(sender, "  在线人数    " + Bukkit.getOnlinePlayers().size(), NamedTextColor.GRAY);
        line(sender, "  类重定义    " + (service.agentReady()
                ? "可用，patch 能直接换方法体，不卸载"
                : "不可用，启动参数没有 -javaagent"),
                service.agentReady() ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
        line(sender, "  空闲策略    " + (service.idleOnly()
                ? "只等空服执行，超时 " + service.idleTimeoutSeconds() + " 秒"
                : "立即执行，可用 --idle 临时排队"),
                NamedTextColor.GRAY);
        line(sender, "  显式 GC     " + (service.explicitGcDisabled()
                ? "被 -XX:+DisableExplicitGC 关掉了，verify 只能等自然回收"
                : "可用，verify 会主动催一次"),
                service.explicitGcDisabled() ? NamedTextColor.YELLOW : NamedTextColor.GRAY);
    }

    private void status(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            java.lang.ref.WeakReference<ClassLoader> probe = service.probe(args[1]);
            if (probe == null) {
                line(sender, "还没有 " + args[1] + " 的记录，先跑一次 unload", NamedTextColor.YELLOW);
            } else if (probe.get() == null) {
                line(sender, args[1] + " 的旧 ClassLoader 已回收", NamedTextColor.GREEN);
            } else {
                line(sender, args[1] + " 的旧 ClassLoader 还在内存里", NamedTextColor.YELLOW);
            }
            return;
        }

        Map<String, java.lang.ref.WeakReference<ClassLoader>> all = service.probes();
        if (all.isEmpty()) {
            line(sender, "还没有任何记录", NamedTextColor.YELLOW);
            return;
        }
        for (Map.Entry<String, java.lang.ref.WeakReference<ClassLoader>> entry : all.entrySet()) {
            boolean alive = entry.getValue().get() != null;
            line(sender, entry.getKey() + "  " + (alive ? "还在内存" : "已回收"),
                    alive ? NamedTextColor.YELLOW : NamedTextColor.GREEN);
        }
    }

    private void list(CommandSender sender) {
        line(sender, "已加载插件:", NamedTextColor.GRAY);
        for (Plugin loaded : Bukkit.getPluginManager().getPlugins()) {
            line(sender, "  " + loaded.getName() + " " + loaded.getDescription().getVersion()
                    + (loaded.isEnabled() ? "" : " (已禁用)"),
                    loaded.isEnabled() ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY);
        }
    }

    private void help(CommandSender sender, String label) {
        line(sender, "HotReload 命令:", NamedTextColor.AQUA);
        line(sender, "  /" + label + " patch <插件> [新jar]  不卸载，直接把新 jar 里的方法体盖上去", NamedTextColor.GRAY);
        line(sender, "  /" + label + " reload <插件> [-f] [--idle]  卸载后重新加载", NamedTextColor.GRAY);
        line(sender, "  /" + label + " unload <插件> [-f]  只卸载", NamedTextColor.GRAY);
        line(sender, "  /" + label + " load <插件> [-f]    从 plugins 目录加载", NamedTextColor.GRAY);
        line(sender, "  /" + label + " status [插件]       查看旧类加载器是否被回收", NamedTextColor.GRAY);
        line(sender, "  /" + label + " list                列出已加载插件", NamedTextColor.GRAY);
        line(sender, "  /" + label + " info                查看 agent、GC、空闲策略", NamedTextColor.GRAY);
        line(sender, "  -f 跳过保护名单和依赖检查", NamedTextColor.DARK_GRAY);
        line(sender, "  --idle 改成等空服再执行，默认有人也直接执行", NamedTextColor.DARK_GRAY);
    }

    private void print(CommandSender sender, String title, ReloadReport report) {
        line(sender, title, report.success() ? NamedTextColor.AQUA : NamedTextColor.RED);
        for (ReloadReport.Step step : report.steps()) {
            String text = "  " + (step.ok() ? "✓" : "✗") + " " + step.name() + "  " + step.millis() + "ms";
            if (!step.detail().isBlank()) {
                text += "  " + step.detail();
            }
            line(sender, text, step.ok() ? NamedTextColor.GRAY : NamedTextColor.RED);
        }
        line(sender, report.success() ? "  完成，共 " + report.totalMillis() + "ms" : "  失败: " + report.failure(),
                report.success() ? NamedTextColor.DARK_GRAY : NamedTextColor.RED);
    }

    private void line(CommandSender sender, String text, NamedTextColor color) {
        sender.sendMessage(Component.text(text, color));
    }

    private List<String> filter(List<String> candidates, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(candidate);
            }
        }
        return out;
    }
}
