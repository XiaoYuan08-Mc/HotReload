package cn.mcfhm.hotreload;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.Recipe;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

// 卸载和加载的唯一入口，全部在主线程跑。宁可卡一下，也不留半清状态
public final class ReloadService {

    private final HotReloadPlugin plugin;
    private final Set<String> protectedNames = new HashSet<>();
    private final boolean verifyClassloader;
    private final boolean explicitGcDisabled;
    private final boolean idleOnly;
    private final int idleTimeoutSeconds;
    private final Patcher patcher;

    private final Object server;
    private final Object simpleManager;
    private final Object instanceManager;
    private final Object commandMap;

    private final Map<String, Boolean> verdicts = new ConcurrentHashMap<>();
    private final Map<String, WeakReference<ClassLoader>> probes = new ConcurrentHashMap<>();

    public ReloadService(HotReloadPlugin plugin) {
        this.plugin = plugin;
        for (String name : plugin.getConfig().getStringList("protected-plugins")) {
            if (name != null && !name.isBlank()) {
                this.protectedNames.add(name.toLowerCase(Locale.ROOT));
            }
        }
        this.protectedNames.add(plugin.getName().toLowerCase(Locale.ROOT));
        this.verifyClassloader = plugin.getConfig().getBoolean("verify-classloader", true);
        this.idleOnly = plugin.getConfig().getBoolean("idle-only", false);
        this.idleTimeoutSeconds = Math.max(5, plugin.getConfig().getInt("idle-timeout", 1800));
        this.explicitGcDisabled = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .anyMatch(argument -> argument.contains("DisableExplicitGC"));
        this.patcher = new Patcher(resolveInstrumentation());

        Object srv = Bukkit.getServer();
        this.server = srv;
        this.simpleManager = Reflect.get(srv, "pluginManager");
        this.instanceManager = Reflect.get(Reflect.get(srv, "paperPluginManager"), "instanceManager");
        this.commandMap = Reflect.get(srv, "commandMap");
    }

    public synchronized ReloadReport unload(Plugin target, boolean force) {
        ReloadReport report = new ReloadReport();
        if (target == null) {
            report.fatal("插件不存在");
            return report;
        }
        if (target == plugin) {
            report.fatal("不能卸载自己");
            return report;
        }

        String key = target.getName().toLowerCase(Locale.ROOT);
        if (!force && protectedNames.contains(key)) {
            report.fatal(target.getName() + " 在 protected-plugins 名单里");
            return report;
        }
        if (!force) {
            List<String> dependents = enabledDependents(target);
            if (!dependents.isEmpty()) {
                report.fatal("这些插件还硬依赖它：" + String.join(", ", dependents) + "（加 -f 强制）");
                return report;
            }
        }

        WeakReference<ClassLoader> probe = new WeakReference<>(target.getClass().getClassLoader());
        invokeHook(target, "onHotReloadUnload");
        long start = System.nanoTime();

        try {
            Bukkit.getPluginManager().disablePlugin(target);
            report.ok("disable", millis(start), target.isEnabled() ? "仍处于启用状态" : "onDisable 已执行");
        } catch (Throwable t) {
            report.fail("disable", millis(start), t.toString());
        }

        start = System.nanoTime();
        int cancelled = 0;
        for (org.bukkit.scheduler.BukkitTask task : Bukkit.getScheduler().getPendingTasks()) {
            if (task.getOwner() == target) {
                cancelled++;
            }
        }
        Bukkit.getScheduler().cancelTasks(target);
        report.ok("tasks", millis(start), "取消 " + cancelled + " 个排队任务");

        start = System.nanoTime();
        try {
            Bukkit.getMessenger().unregisterIncomingPluginChannel(target);
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(target);
            report.ok("channels", millis(start), "已反注册插件消息通道");
        } catch (Throwable t) {
            report.fail("channels", millis(start), t.toString());
        }

        start = System.nanoTime();
        int registrations = 0;
        for (HandlerList handlerList : HandlerList.getHandlerLists()) {
            registrations += handlerList.getRegisteredListeners().length;
            handlerList.unregisterAll(target);
        }
        report.ok("listeners", millis(start), "扫描 " + registrations + " 个注册项");

        start = System.nanoTime();
        try {
            Bukkit.getServicesManager().unregisterAll(target);
            report.ok("services", millis(start), "");
        } catch (Throwable t) {
            report.fail("services", millis(start), t.toString());
        }

        start = System.nanoTime();
        report.ok("commands", millis(start), "移除 " + clearCommands(target) + " 个");

        start = System.nanoTime();
        report.ok("permissions", millis(start), "移除 " + clearPermissions(target) + " 个");

        start = System.nanoTime();
        report.ok("recipes", millis(start), "移除 " + clearRecipes(target) + " 个");

        start = System.nanoTime();
        int nodes = clearBrigadier(target);
        report.ok("brigadier", millis(start), nodes < 0 ? "跳过（非 Paper 命令树）" : "移除 " + nodes + " 个节点");

        start = System.nanoTime();
        removeFromLists(target);
        report.ok("registry", millis(start), "");

        start = System.nanoTime();
        closeLoader(probe);
        report.ok("classloader", millis(start), "已关闭");

        start = System.nanoTime();
        invokeNoArg(server, "syncCommands");
        report.ok("sync", millis(start), "");

        probes.put(key, probe);
        if (verifyClassloader) {
            start = System.nanoTime();
            scheduleVerify(key, probe);
            report.ok("verify", millis(start), "已转到后台复查，用 /hr status 随时看");
        }
        return report;
    }

    public synchronized ReloadReport load(String name, boolean force) {
        ReloadReport report = new ReloadReport();
        if (name == null || name.isBlank()) {
            report.fatal("插件名不能为空");
            return report;
        }
        if (Bukkit.getPluginManager().getPlugin(name) != null) {
            report.fatal(name + " 还在运行，先 unload");
            return report;
        }

        File jar = findJar(name);
        if (jar == null) {
            report.fatal("plugins 目录下找不到 " + name + ".jar");
            return report;
        }

        long start = System.nanoTime();
        Plugin loaded;
        try {
            loaded = Bukkit.getPluginManager().loadPlugin(jar);
        } catch (Throwable t) {
            report.fail("loadPlugin", millis(start), t.toString());
            return report;
        }
        if (loaded == null) {
            report.fail("loadPlugin", millis(start), "返回 null");
            return report;
        }
        report.ok("loadPlugin", millis(start), loaded.getName() + " " + loaded.getDescription().getVersion());

        // Paper 的加载流程内部已经调用过 onLoad，这里不再重复触发

        start = System.nanoTime();
        try {
            Bukkit.getPluginManager().enablePlugin(loaded);
        } catch (Throwable t) {
            report.fail("enable", millis(start), t.toString());
            return report;
        }
        if (!loaded.isEnabled()) {
            report.fail("enable", millis(start), "插件没有进入启用状态");
            return report;
        }
        report.ok("enable", millis(start), "");
        invokeHook(loaded, "onHotReloadLoad");
        invokeNoArg(server, "syncCommands");
        return report;
    }

    /**
     * 不卸载、不重建 ClassLoader，直接把新 jar 里的方法体盖到已经加载的类上。
     * 世界里已有的对象、注册进去的监听、开着的连接全都继续指向同一个类。
     */
    public synchronized ReloadReport patch(String name, String sourcePath) {
        ReloadReport report = new ReloadReport();
        if (name == null || name.isBlank()) {
            report.fatal("插件名不能为空");
            return report;
        }
        Plugin target = Bukkit.getPluginManager().getPlugin(name);
        if (target == null) {
            report.fatal("找不到插件 " + name);
            return report;
        }
        if (target == plugin) {
            report.fatal("不能给自己打补丁");
            return report;
        }
        if (!patcher.available()) {
            report.fatal("没有挂上 agent，启动参数加 -javaagent:hotreload-agent.jar 才能用");
            return report;
        }

        File jar;
        if (sourcePath != null && !sourcePath.isBlank()) {
            jar = new File(sourcePath);
            if (!jar.isAbsolute()) {
                jar = new File(pluginsDir(), sourcePath);
            }
            if (!jar.isFile()) {
                report.fatal("找不到指定的 jar: " + jar.getAbsolutePath());
                return report;
            }
        } else {
            jar = findJar(target.getName());
        }
        if (jar == null) {
            report.fatal("plugins 目录下找不到 " + target.getName() + ".jar");
            return report;
        }

        Patcher.Result result = patcher.patch(target.getClass().getClassLoader(), jar);
        if (!result.applied()) {
            String reason = result.failures().isEmpty() ? "没有可替换的类" : result.failures().get(0);
            report.fail("patch", result.millis(), reason);
            return report;
        }

        report.ok("patch", result.millis(),
                "扫描 " + result.scanned() + " 个类，替换 " + result.changed() + " 个");
        if (result.failed() > 0) {
            report.fail("structure", 0L, result.failed() + " 个类结构变了，只能走 reload");
            for (String failure : result.failures().subList(0, Math.min(5, result.failures().size()))) {
                plugin.getLogger().warning("patch 未生效: " + failure);
            }
        }
        return report;
    }

    public boolean idle() {
        return Bukkit.getOnlinePlayers().isEmpty();
    }

    public boolean idleOnly() {
        return idleOnly;
    }

    public int idleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public boolean agentReady() {
        return patcher.available();
    }

    public boolean explicitGcDisabled() {
        return explicitGcDisabled;
    }

    /**
     * 等到服务器没人的时候再执行。每 5 秒看一次，超过 idle-timeout 就放弃。
     */
    public void whenIdle(Runnable action) {
        new BukkitRunnable() {
            private int waited;

            @Override
            public void run() {
                if (idle()) {
                    cancel();
                    action.run();
                    return;
                }
                waited += 5;
                if (waited >= idleTimeoutSeconds) {
                    cancel();
                    plugin.getLogger().info("等空闲超过 " + waited + " 秒，这次重载放弃");
                }
            }
        }.runTaskTimer(plugin, 0L, 100L);
    }

    private void scheduleVerify(String key, WeakReference<ClassLoader> probe) {
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean reclaimed = verifyNow(probe);
                verdicts.put(key, reclaimed);
                if (reclaimed) {
                    plugin.getLogger().info(key + " 的旧 ClassLoader 已回收");
                }
            });
        } catch (Throwable ignored) {
            // 排不上就丢掉这次复查，status 仍然可以实时看
        }
    }

    private void invokeHook(Plugin target, String methodName) {
        try {
            Method hook = target.getClass().getMethod(methodName);
            if (hook.getParameterCount() != 0) {
                return;
            }
            hook.invoke(target);
        } catch (NoSuchMethodException ignored) {
            // 插件没实现这个钩子，正常
        } catch (Throwable error) {
            plugin.getLogger().warning(target.getName() + " 的 " + methodName + " 抛了异常: " + error);
        }
    }

    private static Instrumentation resolveInstrumentation() {
        try {
            Class<?> agent = Class.forName("cn.mcfhm.hotreload.agent.HotReloadAgent",
                    false, ClassLoader.getSystemClassLoader());
            return (Instrumentation) agent.getMethod("instrumentation").invoke(null);
        } catch (Throwable ignored) {
            // 没加 -javaagent 就是这条路
            return null;
        }
    }

    public Map<String, Boolean> verdicts() {
        return new HashMap<>(verdicts);
    }

    public Boolean verdict(String name) {
        return verdicts.get(name.toLowerCase(Locale.ROOT));
    }

    public WeakReference<ClassLoader> probe(String name) {
        return probes.get(name.toLowerCase(Locale.ROOT));
    }

    public Map<String, WeakReference<ClassLoader>> probes() {
        return new HashMap<>(probes);
    }

    // ------------------------------------------------------------------ 清理

    private int clearCommands(Plugin target) {
        Map<String, Object> known = Reflect.asMap(Reflect.get(commandMap, "knownCommands"));
        if (known == null) {
            return 0;
        }

        ClassLoader loader = target.getClass().getClassLoader();
        String prefix = target.getName().toLowerCase(Locale.ROOT) + ":";
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Object> entry : known.entrySet()) {
            Object handle = entry.getValue();
            if (!(handle instanceof Command command)) {
                continue;
            }
            boolean mine = entry.getKey().toLowerCase(Locale.ROOT).startsWith(prefix)
                    || command instanceof PluginCommand pluginCommand && pluginCommand.getPlugin() == target
                    || command.getClass().getClassLoader() == loader;
            if (mine) {
                keys.add(entry.getKey());
            }
        }

        for (String keyName : keys) {
            Object handle = known.remove(keyName);
            if (handle instanceof Command command && commandMap instanceof CommandMap map) {
                try {
                    command.unregister(map);
                } catch (Throwable ignored) {
                    // 已经不在 map 里，忽略
                }
            }
        }
        return keys.size();
    }

    private int clearPermissions(Plugin target) {
        Set<String> names = new LinkedHashSet<>();
        String prefix = target.getName().toLowerCase(Locale.ROOT);

        PluginDescriptionFile description = target.getDescription();
        for (Permission permission : description.getPermissions()) {
            names.add(permission.getName());
            names.addAll(permission.getChildren().keySet());
        }
        for (Permission permission : Bukkit.getPluginManager().getPermissions()) {
            String lower = permission.getName().toLowerCase(Locale.ROOT);
            if (lower.equals(prefix) || lower.startsWith(prefix + ".")) {
                names.add(permission.getName());
            }
        }

        int removed = 0;
        for (String name : names) {
            try {
                if (Bukkit.getPluginManager().getPermission(name) != null) {
                    Bukkit.getPluginManager().removePermission(name);
                    removed++;
                }
            } catch (Throwable ignored) {
                // 单条权限删不掉不影响主流程
            }
        }
        return removed;
    }

    private int clearRecipes(Plugin target) {
        String namespace = target.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        List<NamespacedKey> keys = new ArrayList<>();
        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe = iterator.next();
            if (recipe instanceof Keyed keyed && keyed.getKey().getNamespace().equalsIgnoreCase(namespace)) {
                keys.add(keyed.getKey());
            }
        }
        for (NamespacedKey key : keys) {
            Bukkit.removeRecipe(key);
        }
        if (!keys.isEmpty()) {
            invokeNoArg(server, "updateRecipes");
        }
        return keys.size();
    }

    private int clearBrigadier(Plugin target) {
        try {
            Class<?> paperCommandsClass = Class.forName("io.papermc.paper.command.brigadier.PaperCommands");
            Object paperCommands = paperCommandsClass.getField("INSTANCE").get(null);
            Object dispatcher = paperCommandsClass.getMethod("getDispatcherInternal").invoke(paperCommands);
            Object root = dispatcher.getClass().getMethod("getRoot").invoke(dispatcher);
            Map<String, Object> children = Reflect.asMap(Reflect.get(root, "children"));
            if (children == null) {
                return -1;
            }

            Set<String> names = new LinkedHashSet<>();
            for (Map.Entry<String, Map<String, Object>> entry : target.getDescription().getCommands().entrySet()) {
                names.add(entry.getKey());
                Object aliases = entry.getValue().get("aliases");
                if (aliases instanceof String alias) {
                    names.add(alias);
                } else if (aliases instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof String alias) {
                            names.add(alias);
                        }
                    }
                }
            }

            int removed = 0;
            for (String name : names) {
                if (children.remove(name) != null) {
                    removed++;
                }
            }
            return removed;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void removeFromLists(Plugin target) {
        List<Object> simplePlugins = Reflect.asList(Reflect.get(simpleManager, "plugins"));
        if (simplePlugins != null) {
            simplePlugins.removeIf(candidate -> candidate == target);
        }
        Map<String, Object> simpleLookup = Reflect.asMap(Reflect.get(simpleManager, "lookupNames"));
        if (simpleLookup != null) {
            simpleLookup.keySet().removeIf(name -> name.equalsIgnoreCase(target.getName()));
        }

        List<Object> paperPlugins = Reflect.asList(Reflect.get(instanceManager, "plugins"));
        if (paperPlugins != null) {
            paperPlugins.removeIf(candidate -> candidate == target);
        }
        Map<String, Object> paperLookup = Reflect.asMap(Reflect.get(instanceManager, "lookupNames"));
        if (paperLookup != null) {
            paperLookup.keySet().removeIf(name -> name.equalsIgnoreCase(target.getName()));
        }
    }

    private void closeLoader(WeakReference<ClassLoader> probe) {
        ClassLoader loader = probe.get();
        if (loader == null) {
            return;
        }

        detachFromGroup(loader);
        unregisterFromStorage(loader);

        // Paper 的 PaperPluginClassLoader 用 loadedJavaPlugin，老 Bukkit 用 plugin / pluginInit
        Reflect.set(loader, "loadedJavaPlugin", null);
        Reflect.set(loader, "plugin", null);
        Reflect.set(loader, "pluginInit", null);

        // PaperSimplePluginClassLoader 的 jar 是 final JarFile，不关掉会一直锁着 jar 文件
        Object jar = Reflect.get(loader, "jar");
        if (jar instanceof JarFile jarFile) {
            try {
                jarFile.close();
            } catch (IOException ignored) {
                // 已经关过
            }
        }

        if (loader instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // URLClassLoader.close 会重复关一次内部 jar，无害
            }
        }
    }

    // 动态加载出来的 loader 会被挂进 Group，不摘掉就一直被 Group 的列表持有
    private void detachFromGroup(ClassLoader loader) {
        Object group = Reflect.get(loader, "group");
        if (group == null) {
            return;
        }
        try {
            for (java.lang.reflect.Method method : group.getClass().getMethods()) {
                if (!method.getName().equals("remove") || method.getParameterCount() != 1) {
                    continue;
                }
                if (method.getParameterTypes()[0].isInstance(loader)) {
                    method.invoke(group, loader);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // 摘不掉不影响后续清理
        }
    }

    // Paper 有官方的注销入口，能拿到的调用它，拿不到靠 detachFromGroup 兜底
    private void unregisterFromStorage(ClassLoader loader) {
        try {
            Class<?> storageType = Class.forName("io.papermc.paper.plugin.provider.classloader.PaperClassLoaderStorage");
            Object storage = null;
            for (Object candidate : java.util.ServiceLoader.load(storageType, Bukkit.class.getClassLoader())) {
                storage = candidate;
                break;
            }
            if (storage == null) {
                return;
            }
            for (java.lang.reflect.Method method : storage.getClass().getMethods()) {
                if (!method.getName().equals("unregisterClassloader") || method.getParameterCount() != 1) {
                    continue;
                }
                if (method.getParameterTypes()[0].isInstance(loader)) {
                    method.invoke(storage, loader);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // 版本不同或没有这个服务都正常
        }
    }

    // ------------------------------------------------------------------ 验证

    // G1 下 System.gc() 是并发回收，调用后立刻检查只会得到假结果，必须给它落地的时间
    private boolean verifyNow(WeakReference<ClassLoader> probe) {
        if (probe.get() == null) {
            return true;
        }
        if (explicitGcDisabled) {
            return false;
        }
        System.gc();
        try {
            Thread.sleep(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return probe.get() == null;
    }

    // ------------------------------------------------------------------ 辅助

    private List<String> enabledDependents(Plugin target) {
        List<String> dependents = new ArrayList<>();
        for (Plugin other : Bukkit.getPluginManager().getPlugins()) {
            if (other == target || !other.isEnabled()) {
                continue;
            }
            if (other.getDescription().getDepend().contains(target.getName())) {
                dependents.add(other.getName());
            }
        }
        return dependents;
    }

    private File pluginsDir() {
        File parent = plugin.getDataFolder().getParentFile();
        return parent != null ? parent : new File("plugins");
    }

    private File findJar(String name) {
        File dir = pluginsDir();
        File direct = new File(dir, name + ".jar");
        if (direct.isFile()) {
            return direct;
        }

        File[] jars = dir.listFiles((ignored, fileName) -> fileName.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null) {
            return null;
        }
        for (File jar : jars) {
            if (jar.getName().equalsIgnoreCase(name + ".jar")) {
                return jar;
            }
        }
        // 常见的 Name-version.jar 命名，直接按前缀命中，避免依赖读 yml
        for (File jar : jars) {
            String fileName = jar.getName();
            if (fileName.length() > name.length() + 1
                    && fileName.regionMatches(true, 0, name, 0, name.length())
                    && fileName.charAt(name.length()) == '-') {
                return jar;
            }
        }
        for (File jar : jars) {
            String declared = readPluginName(jar);
            if (declared != null && declared.equalsIgnoreCase(name)) {
                return jar;
            }
        }
        return null;
    }

    // 直接按文本读 name，避开 YAML 解析对第三方库的依赖
    private String readPluginName(File file) {
        try (JarFile jar = new JarFile(file)) {
            for (String entryName : new String[]{"plugin.yml", "paper-plugin.yml"}) {
                JarEntry entry = jar.getJarEntry(entryName);
                if (entry == null) {
                    continue;
                }
                try (InputStream in = jar.getInputStream(entry);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (!trimmed.startsWith("name:")) {
                            continue;
                        }
                        String value = trimmed.substring(5).trim();
                        if (value.length() > 1
                                && ((value.startsWith("\"") && value.endsWith("\""))
                                || (value.startsWith("'") && value.endsWith("'")))) {
                            value = value.substring(1, value.length() - 1);
                        }
                        return value.isEmpty() ? null : value;
                    }
                }
            }
            return null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private void invokeNoArg(Object target, String method) {
        try {
            target.getClass().getMethod(method).invoke(target);
        } catch (Throwable ignored) {
            // 26.3 上这几个方法都在，失败也不影响主流程
        }
    }

    private long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
