package com.mc615.whitelistset.whitelistset;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ApplicationManager {

    private final Whitelistset plugin;
    private final Map<UUID, WhitelistApplication> applications = new ConcurrentHashMap<>();
    private File dataFile;

    public ApplicationManager(Whitelistset plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "applications.yml");
        loadApplications();
    }

    public void addApplication(WhitelistApplication app) {
        applications.put(app.getId(), app);
        saveApplications();
    }

    public WhitelistApplication getApplication(UUID id) {
        return applications.get(id);
    }

    public List<WhitelistApplication> getPendingApplications() {
        return applications.values().stream()
                .filter(a -> a.getStatus() == ApplicationStatus.PENDING)
                .collect(Collectors.toList());
    }

    public List<WhitelistApplication> getAllApplications() {
        return new ArrayList<>(applications.values());
    }

    public void approveApplication(UUID id) {
        WhitelistApplication app = applications.get(id);
        if (app != null) {
            app.setStatus(ApplicationStatus.APPROVED);
            saveApplications();
        }
    }

    public void rejectApplication(UUID id) {
        WhitelistApplication app = applications.get(id);
        if (app != null) {
            app.setStatus(ApplicationStatus.REJECTED);
            saveApplications();
        }
    }

    public void loadApplications() {
        applications.clear();
        if (!dataFile.exists()) return;

        FileConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : config.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String playerName = config.getString(key + ".player-name", "");
                String qq = config.getString(key + ".qq", "");
                String email = config.getString(key + ".email", "");
                boolean isPremium = config.getBoolean(key + ".is-premium", false);
                String statusStr = config.getString(key + ".status", "PENDING");
                ApplicationStatus status = ApplicationStatus.valueOf(statusStr);
                long timestamp = config.getLong(key + ".timestamp", System.currentTimeMillis());

                WhitelistApplication app = new WhitelistApplication(id, playerName, qq, email, isPremium, timestamp);
                app.setStatus(status);
                applications.put(id, app);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("无法加载申请记录: " + key);
            }
        }
    }

    public void saveApplications() {
        FileConfiguration config = new YamlConfiguration();
        for (WhitelistApplication app : applications.values()) {
            String key = app.getId().toString();
            config.set(key + ".player-name", app.getPlayerName());
            config.set(key + ".qq", app.getQq());
            config.set(key + ".email", app.getEmail());
            config.set(key + ".is-premium", app.isPremium());
            config.set(key + ".status", app.getStatus().name());
            config.set(key + ".timestamp", app.getTimestamp());
        }

        try {
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("无法保存申请记录: " + e.getMessage());
        }
    }

    public enum ApplicationStatus {
        PENDING, APPROVED, REJECTED
    }

    public static class WhitelistApplication {
        private final UUID id;
        private final String playerName;
        private final String qq;
        private final String email;
        private final boolean isPremium;
        private final long timestamp;
        private ApplicationStatus status;

        public WhitelistApplication(UUID id, String playerName, String qq, String email, boolean isPremium, long timestamp) {
            this.id = id;
            this.playerName = playerName;
            this.qq = qq;
            this.email = email;
            this.isPremium = isPremium;
            this.timestamp = timestamp;
            this.status = ApplicationStatus.PENDING;
        }

        public UUID getId() { return id; }
        public String getPlayerName() { return playerName; }
        public String getQq() { return qq; }
        public String getEmail() { return email; }
        public boolean isPremium() { return isPremium; }
        public long getTimestamp() { return timestamp; }
        public ApplicationStatus getStatus() { return status; }
        public void setStatus(ApplicationStatus status) { this.status = status; }
    }
}
