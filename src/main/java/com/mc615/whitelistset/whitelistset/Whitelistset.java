package com.mc615.whitelistset.whitelistset;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class Whitelistset extends JavaPlugin {

    private static Whitelistset instance;
    private ConfigManager configManager;
    private ApplicationManager applicationManager;
    private WebServer webServer;
    private ApplyServer applyServer;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        configManager = new ConfigManager(this);
        applicationManager = new ApplicationManager(this);

        // 自动开启服务器白名单
        Bukkit.setWhitelist(true);
        getLogger().info("服务器白名单已自动开启");

        // 启动管理控制台
        int adminPort = configManager.getAdminPort();
        String password = configManager.getPassword();
        webServer = new WebServer(this, adminPort, password);
        webServer.start();
        getLogger().info("管理控制台已启动，端口: " + adminPort);

        // 检查是否需要启动白名单申请服务
        if (configManager.isWhitelistApplyEnabled()) {
            startApplyServer();
        }

        getLogger().info("WhitelistSet 插件已启用");
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
        }
        if (applyServer != null) {
            applyServer.stop();
        }
        applicationManager.saveApplications();
        getLogger().info("WhitelistSet 插件已禁用");
    }

    public void startApplyServer() {
        if (applyServer != null) {
            applyServer.stop();
        }
        int applyPort = configManager.getApplyPort();
        applyServer = new ApplyServer(this, applyPort);
        applyServer.start();
        getLogger().info("白名单申请服务已启动，端口: " + applyPort);
    }

    public void stopApplyServer() {
        if (applyServer != null) {
            applyServer.stop();
            applyServer = null;
            getLogger().info("白名单申请服务已停止");
        }
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ApplicationManager getApplicationManager() {
        return applicationManager;
    }

    public static Whitelistset getInstance() {
        return instance;
    }
}
