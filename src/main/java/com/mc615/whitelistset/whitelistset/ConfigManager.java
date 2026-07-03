package com.mc615.whitelistset.whitelistset;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

public class ConfigManager {

    private final Whitelistset plugin;
    private File configFile;
    private FileConfiguration config;

    public ConfigManager(Whitelistset plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.getDataFolder().mkdirs();
            plugin.saveResource("config.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        setDefaults();
    }

    private void setDefaults() {
        config.addDefault("admin-port", 8080);
        config.addDefault("apply-port", 8081);
        config.addDefault("password", "admin123");
        config.addDefault("whitelist-apply.enabled", false);
        config.addDefault("email.host", "smtp.qq.com");
        config.addDefault("email.port", 587);
        config.addDefault("email.username", "");
        config.addDefault("email.password", "");
        config.addDefault("email.use-ssl", true);
        config.options().copyDefaults(true);
        saveConfig();
    }

    public void saveConfig() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("无法保存配置文件: " + e.getMessage());
        }
    }

    public void reloadConfig() {
        config = YamlConfiguration.loadConfiguration(configFile);
    }

    public int getAdminPort() {
        return config.getInt("admin-port", 8080);
    }

    public int getApplyPort() {
        return config.getInt("apply-port", 8081);
    }

    public String getPassword() {
        return config.getString("password", "admin123");
    }

    public void setPassword(String password) {
        config.set("password", password);
        saveConfig();
    }

    public boolean isWhitelistApplyEnabled() {
        return config.getBoolean("whitelist-apply.enabled", false);
    }

    public void setWhitelistApplyEnabled(boolean enabled) {
        config.set("whitelist-apply.enabled", enabled);
        saveConfig();
    }

    public String getEmailHost() {
        return config.getString("email.host", "smtp.qq.com");
    }

    public void setEmailHost(String host) {
        config.set("email.host", host);
        saveConfig();
    }

    public int getEmailPort() {
        return config.getInt("email.port", 587);
    }

    public void setEmailPort(int port) {
        config.set("email.port", port);
        saveConfig();
    }

    public String getEmailUsername() {
        return config.getString("email.username", "");
    }

    public void setEmailUsername(String username) {
        config.set("email.username", username);
        saveConfig();
    }

    public String getEmailPassword() {
        return config.getString("email.password", "");
    }

    public void setEmailPassword(String password) {
        config.set("email.password", password);
        saveConfig();
    }

    public boolean isEmailUseSsl() {
        return config.getBoolean("email.use-ssl", true);
    }

    public void setEmailUseSsl(boolean useSsl) {
        config.set("email.use-ssl", useSsl);
        saveConfig();
    }

    public FileConfiguration getConfig() {
        return config;
    }
}
