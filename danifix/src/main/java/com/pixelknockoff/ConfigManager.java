package com.pixelknockoff;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置管理器
 * 负责加载和读取 config.yml 中的黑名单配置
 */
public class ConfigManager {

    private final PixelKnockOffPlugin plugin;
    private FileConfiguration config;
    private List<String> blockedItems;
    private boolean debug;
    private String language;

    public ConfigManager(PixelKnockOffPlugin plugin) {
        this.plugin = plugin;
        this.blockedItems = new ArrayList<String>();
    }

    /**
     * 加载配置文件
     */
    public void loadConfig() {
        // 重新加载配置
        plugin.reloadConfig();
        this.config = plugin.getConfig();

        // 读取黑名单
        List<String> items = config.getStringList("blocked-items");
        if (items != null) {
            this.blockedItems = new ArrayList<String>(items);
        } else {
            this.blockedItems = new ArrayList<String>();
        }

        // 读取调试模式
        this.debug = config.getBoolean("debug", false);

        // 读取语言设置
        this.language = config.getString("language", "zh_CN");

        if (debug) {
            plugin.getLogger().info("已加载 " + blockedItems.size() + " 个黑名单道具");
        }
    }

    /**
     * 检查道具ID是否在黑名单中
     * 自动处理下划线变体（Pixelmon 内部物品名可能不带下划线，如 adamantcrystal）
     * @param itemId 道具ID (格式: pixelmon:xxx)
     * @return true如果在黑名单中
     */
    public boolean isBlocked(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        String lower = itemId.toLowerCase();
        String lowerNoUnderscore = lower.replace("_", "");
        for (String blocked : blockedItems) {
            String bLower = blocked.toLowerCase();
            String bLowerNoUnderscore = bLower.replace("_", "");
            if (bLowerNoUnderscore.equals(lowerNoUnderscore)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查道具ID是否在黑名单中 (支持多种格式匹配)
     * @param itemId 道具ID
     * @param alternateIds 备用ID列表
     * @return true如果在黑名单中
     */
    public boolean isBlocked(String itemId, String... alternateIds) {
        if (isBlocked(itemId)) {
            return true;
        }
        if (alternateIds != null) {
            for (String altId : alternateIds) {
                if (isBlocked(altId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 获取黑名单列表
     * @return 黑名单道具ID列表
     */
    public List<String> getBlockedItems() {
        return new ArrayList<String>(blockedItems);
    }

    /**
     * 获取黑名单数量
     * @return 黑名单道具数量
     */
    public int getBlockedCount() {
        return blockedItems.size();
    }

    /**
     * 是否启用调试模式
     * @return true如果启用调试
     */
    public boolean isDebug() {
        return debug;
    }

    /**
     * 获取语言设置
     * @return 语言代码
     */
    public String getLanguage() {
        return language;
    }
}
