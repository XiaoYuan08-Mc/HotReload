package cn.mcfhm.hotreload;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class HotReloadPlugin extends JavaPlugin {

    private ReloadService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.service = new ReloadService(this);

        PluginCommand command = getCommand("hotreload");
        if (command == null) {
            getLogger().severe("plugin.yml 里没有 hotreload 命令");
            return;
        }
        ReloadCommand executor = new ReloadCommand(this, service);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getLogger().info("HotReload 已就绪" + (service.agentReady()
                ? "，类重定义可用（patch 不卸载）"
                : "，类重定义未启用（没有 -javaagent）"));
    }

    public ReloadService service() {
        return service;
    }
}
