package com.pixelknockoff;

import com.pixelknockoff.command.ReloadCommand;
import com.pixelknockoff.interceptor.KnockOffInterceptor;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PixelKnockOff 插件主类
 * 功能：拦截 Pixelmon 拍落技能，保护黑名单道具不被夺取
 *
 * 核心策略：不再使用 Forge 事件总线（Bukkit 插件注册 Forge 事件会因类加载器隔离导致崩溃），
 * 改为使用 Bukkit 定时任务定期扫描活跃战斗，通过反射直接保护目标宝可梦的携带物。
 */
public class PixelKnockOffPlugin extends JavaPlugin {

    private static PixelKnockOffPlugin instance;
    private ConfigManager configManager;
    private KnockOffInterceptor knockOffInterceptor;

    @Override
    public void onEnable() {
        instance = this;

        // 保存默认配置
        saveDefaultConfig();

        // 初始化配置管理器
        this.configManager = new ConfigManager(this);
        this.configManager.loadConfig();

        // 初始化拍落拦截器（内含定时扫描任务）
        this.knockOffInterceptor = new KnockOffInterceptor(this);
        this.knockOffInterceptor.start();

        // 注册指令
        ReloadCommand reloadCommand = new ReloadCommand(this);
        getCommand("pixelknockoff").setExecutor(reloadCommand);
        getCommand("pixelknockoff").setTabCompleter(reloadCommand);

        getLogger().info("PixelKnockOff 插件已启用 - 拍落技能拦截已激活");
        getLogger().info("黑名单道具数量: " + configManager.getBlockedCount());
    }

    @Override
    public void onDisable() {
        if (knockOffInterceptor != null) {
            knockOffInterceptor.stop();
        }
        getLogger().info("PixelKnockOff 插件已禁用");
        instance = null;
    }

    public void reloadPlugin() {
        reloadConfig();
        configManager.loadConfig();
    }

    public static PixelKnockOffPlugin getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public KnockOffInterceptor getKnockOffInterceptor() {
        return knockOffInterceptor;
    }
}
