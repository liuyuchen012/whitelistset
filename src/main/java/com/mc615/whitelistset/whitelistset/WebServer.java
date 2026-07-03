package com.mc615.whitelistset.whitelistset;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class WebServer {

    private final Whitelistset plugin;
    private final int port;
    private String password;
    private HttpServer server;

    public WebServer(Whitelistset plugin, int port, String password) {
        this.plugin = plugin;
        this.port = port;
        this.password = password;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", new MainHandler());
            server.createContext("/api/", new ApiHandler());
            server.setExecutor(null);
            server.start();
        } catch (IOException e) {
            plugin.getLogger().severe("无法启动管理控制台: " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public void updatePassword(String newPassword) {
        this.password = newPassword;
    }

    private boolean checkAuth(HttpExchange exchange) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            return password.equals(token);
        }
        // 也检查 cookie
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) {
            for (String c : cookie.split(";")) {
                String[] parts = c.trim().split("=", 2);
                if (parts.length == 2 && parts[0].equals("auth_token") && parts[1].equals(password)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void sendJson(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendHtml(HttpExchange exchange, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendRedirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            try {
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                params.put(key, value);
            } catch (Exception ignored) {}
        }
        return params;
    }

    class MainHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkAuth(exchange)) {
                sendHtml(exchange, getLoginPage());
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index")) {
                sendHtml(exchange, getMainPage());
            } else if (path.startsWith("/static/")) {
                serveStatic(exchange, path);
            } else {
                sendHtml(exchange, getMainPage());
            }
        }
    }

    class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();

            // 登录/登出请求不需要认证
            if (path.equals("/api/login")) {
                handleLogin(exchange);
                return;
            } else if (path.equals("/api/logout")) {
                handleLogout(exchange);
                return;
            }

            if (!checkAuth(exchange)) {
                sendJson(exchange, "{\"error\":\"未授权\"}");
                return;
            }

            String method = exchange.getRequestMethod();

            try {
                if (path.equals("/api/server-info")) {
                    handleServerInfo(exchange);
                } else if (path.equals("/api/whitelist")) {
                    if (method.equals("GET")) handleGetWhitelist(exchange);
                    else if (method.equals("POST")) handleAddWhitelist(exchange);
                    else if (method.equals("DELETE")) handleRemoveWhitelist(exchange);
                } else if (path.equals("/api/whitelist/batch")) {
                    handleBatchWhitelist(exchange);
                } else if (path.equals("/api/ban")) {
                    if (method.equals("POST")) handleBan(exchange);
                    else if (method.equals("DELETE")) handleUnban(exchange);
                } else if (path.equals("/api/ban/list")) {
                    handleBanList(exchange);
                } else if (path.equals("/api/settings")) {
                    if (method.equals("GET")) handleGetSettings(exchange);
                    else if (method.equals("POST")) handleSaveSettings(exchange);
                } else if (path.equals("/api/applications")) {
                    handleGetApplications(exchange);
                } else if (path.startsWith("/api/applications/")) {
                    handleApplicationAction(exchange, path);
                } else {
                    sendJson(exchange, "{\"error\":\"未知API\"}");
                }
            } catch (Exception e) {
                sendJson(exchange, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        Map<String, String> params = parseQuery(body);
        String pwd = params.get("password");
        if (pwd != null && pwd.equals(password)) {
            sendJson(exchange, "{\"success\":true,\"token\":\"" + password + "\"}");
        } else {
            sendJson(exchange, "{\"success\":false,\"error\":\"密码错误\"}");
        }
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        // 清除认证 cookie 并返回登录页
        exchange.getResponseHeaders().set("Set-Cookie", "auth_token=; Path=/; Max-Age=0; SameSite=Strict");
        sendRedirect(exchange, "/");
    }

    private void handleServerInfo(HttpExchange exchange) throws IOException {
        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        long fullTime = world != null ? world.getFullTime() : 0;

        // Minecraft 时间: 0=日出, 6000=正午, 12000=日落, 18000=午夜
        long mcTime = fullTime % 24000;
        String mcTimeStr;
        if (mcTime < 6000) mcTimeStr = "白天 (日出)";
        else if (mcTime < 12000) mcTimeStr = "白天 (正午)";
        else if (mcTime < 13800) mcTimeStr = "傍晚 (日落)";
        else if (mcTime < 22200) mcTimeStr = "夜晚";
        else mcTimeStr = "夜晚 (黎明前)";

        // 服务器运行时间
        long serverStartTime = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
        long uptimeMillis = System.currentTimeMillis() - serverStartTime;
        String uptimeStr = formatDuration(uptimeMillis);

        String json = "{"
                + "\"version\":\"" + escapeJson(Bukkit.getVersion()) + "\","
                + "\"bukkitVersion\":\"" + escapeJson(Bukkit.getBukkitVersion()) + "\","
                + "\"onlinePlayers\":" + Bukkit.getOnlinePlayers().size() + ","
                + "\"maxPlayers\":" + Bukkit.getMaxPlayers() + ","
                + "\"mcTime\":" + mcTime + ","
                + "\"mcTimeStr\":\"" + mcTimeStr + "\","
                + "\"uptime\":\"" + escapeJson(uptimeStr) + "\","
                + "\"whitelistEnabled\":" + Bukkit.hasWhitelist() + ","
                + "\"worlds\":" + Bukkit.getWorlds().stream().map(w -> "\"" + escapeJson(w.getName()) + "\"").collect(Collectors.joining(",", "[", "]"))
                + "}";
        sendJson(exchange, json);
    }

    private void handleGetWhitelist(HttpExchange exchange) throws IOException {
        Set<OfflinePlayer> whitelisted = Bukkit.getWhitelistedPlayers();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (OfflinePlayer p : whitelisted) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"name\":\"").append(escapeJson(p.getName() != null ? p.getName() : "未知")).append("\",");
            sb.append("\"uuid\":\"").append(p.getUniqueId().toString()).append("\",");
            sb.append("\"isOp\":").append(p.isOp()).append(",");
            sb.append("\"isBanned\":").append(p.isBanned()).append(",");
            sb.append("\"hasPlayedBefore\":").append(p.hasPlayedBefore());
            sb.append("}");
        }
        sb.append("]");
        sendJson(exchange, sb.toString());
    }

    private void handleAddWhitelist(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        Map<String, String> params = parseQuery(body);
        String playerName = params.get("name");
        if (playerName == null || playerName.isEmpty()) {
            sendJson(exchange, "{\"success\":false,\"error\":\"玩家名不能为空\"}");
            return;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        if (player == null || player.getName() == null) {
            sendJson(exchange, "{\"success\":false,\"error\":\"找不到该玩家\"}");
            return;
        }
        player.setWhitelisted(true);
        sendJson(exchange, "{\"success\":true,\"message\":\"已添加 " + playerName + " 到白名单\"}");
    }

    private void handleRemoveWhitelist(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = parseQuery(query);
        String playerName = params.get("name");
        if (playerName == null || playerName.isEmpty()) {
            sendJson(exchange, "{\"success\":false,\"error\":\"玩家名不能为空\"}");
            return;
        }
        try {
            // Bukkit.getOfflinePlayer 在未登录玩家时也可能返回对象，但 name 可能为 null
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
            if (player != null) {
                player.setWhitelisted(false);
            }
            // 额外尝试通过服务器白名单集合移除，即使 player 对象无效
            Bukkit.getWhitelistedPlayers().stream()
                    .filter(p -> playerName.equalsIgnoreCase(p.getName()))
                    .findFirst()
                    .ifPresent(p -> p.setWhitelisted(false));
            sendJson(exchange, "{\"success\":true,\"message\":\"已从白名单移除 " + playerName + "\"}");
        } catch (Exception e) {
            sendJson(exchange, "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleBatchWhitelist(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        Map<String, String> params = parseQuery(body);
        String namesStr = params.get("names");
        String action = params.getOrDefault("action", "add");
        if (namesStr == null || namesStr.isEmpty()) {
            sendJson(exchange, "{\"success\":false,\"error\":\"玩家名列表不能为空\"}");
            return;
        }
        String[] names = namesStr.split(",");
        int success = 0;
        int failed = 0;
        for (String name : names) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) continue;
            try {
                if ("remove".equals(action)) {
                    // 删除模式
                    OfflinePlayer player = Bukkit.getOfflinePlayer(trimmed);
                    if (player != null) {
                        player.setWhitelisted(false);
                    }
                    Bukkit.getWhitelistedPlayers().stream()
                            .filter(p -> trimmed.equalsIgnoreCase(p.getName()))
                            .findFirst()
                            .ifPresent(p -> p.setWhitelisted(false));
                    success++;
                } else {
                    // 添加模式
                    OfflinePlayer player = Bukkit.getOfflinePlayer(trimmed);
                    if (player != null && player.getName() != null) {
                        player.setWhitelisted(true);
                        success++;
                    } else {
                        failed++;
                    }
                }
            } catch (Exception e) {
                failed++;
            }
        }
        String resultField = "remove".equals(action) ? "\"removed\"" : "\"added\"";
        sendJson(exchange, "{\"success\":true,\"action\":\"" + action + "\"," + resultField + ":" + success + ",\"failed\":" + failed + "}");
    }

    private void handleBan(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        Map<String, String> params = parseQuery(body);
        String playerName = params.get("name");
        if (playerName == null || playerName.isEmpty()) {
            sendJson(exchange, "{\"success\":false,\"error\":\"玩家名不能为空\"}");
            return;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        if (player != null) {
            player.setWhitelisted(false);
            Bukkit.getBanList(org.bukkit.BanList.Type.NAME).addBan(playerName, "被管理员封禁", null, null);
            sendJson(exchange, "{\"success\":true,\"message\":\"已封禁 " + playerName + "\"}");
        } else {
            sendJson(exchange, "{\"success\":false,\"error\":\"找不到该玩家\"}");
        }
    }

    private void handleUnban(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = parseQuery(query);
        String playerName = params.get("name");
        if (playerName == null || playerName.isEmpty()) {
            sendJson(exchange, "{\"success\":false,\"error\":\"玩家名不能为空\"}");
            return;
        }
        Bukkit.getBanList(org.bukkit.BanList.Type.NAME).pardon(playerName);
        sendJson(exchange, "{\"success\":true,\"message\":\"已解禁 " + playerName + "\"}");
    }

    private void handleBanList(HttpExchange exchange) throws IOException {
        Set<org.bukkit.BanEntry> bans = Bukkit.getBanList(org.bukkit.BanList.Type.NAME).getBanEntries();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (org.bukkit.BanEntry ban : bans) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"name\":\"").append(escapeJson(ban.getTarget())).append("\",");
            sb.append("\"reason\":\"").append(escapeJson(ban.getReason() != null ? ban.getReason() : "")).append("\",");
            sb.append("\"created\":\"").append(ban.getCreated() != null ? ban.getCreated().toString() : "").append("\"");
            sb.append("}");
        }
        sb.append("]");
        sendJson(exchange, sb.toString());
    }

    private void handleGetSettings(HttpExchange exchange) throws IOException {
        ConfigManager config = plugin.getConfigManager();
        String json = "{"
                + "\"password\":\"" + escapeJson(config.getPassword()) + "\","
                + "\"whitelistApplyEnabled\":" + config.isWhitelistApplyEnabled() + ","
                + "\"adminPort\":" + config.getAdminPort() + ","
                + "\"applyPort\":" + config.getApplyPort() + ","
                + "\"emailHost\":\"" + escapeJson(config.getEmailHost()) + "\","
                + "\"emailPort\":" + config.getEmailPort() + ","
                + "\"emailUsername\":\"" + escapeJson(config.getEmailUsername()) + "\","
                + "\"emailPassword\":\"" + escapeJson(config.getEmailPassword()) + "\","
                + "\"emailUseSsl\":" + config.isEmailUseSsl()
                + "}";
        sendJson(exchange, json);
    }

    private void handleSaveSettings(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        Map<String, String> params = parseQuery(body);
        ConfigManager config = plugin.getConfigManager();

        if (params.containsKey("password")) {
            String newPwd = params.get("password");
            config.setPassword(newPwd);
            updatePassword(newPwd);
        }
        if (params.containsKey("whitelistApplyEnabled")) {
            boolean enabled = "true".equals(params.get("whitelistApplyEnabled"));
            config.setWhitelistApplyEnabled(enabled);
        }
        if (params.containsKey("emailHost")) config.setEmailHost(params.get("emailHost"));
        if (params.containsKey("emailPort")) {
            try { config.setEmailPort(Integer.parseInt(params.get("emailPort"))); } catch (NumberFormatException ignored) {}
        }
        if (params.containsKey("emailUsername")) config.setEmailUsername(params.get("emailUsername"));
        if (params.containsKey("emailPassword")) config.setEmailPassword(params.get("emailPassword"));
        if (params.containsKey("emailUseSsl")) {
            config.setEmailUseSsl("true".equals(params.get("emailUseSsl")));
        }

        sendJson(exchange, "{\"success\":true,\"message\":\"设置已保存\"}");
    }

    private void handleGetApplications(HttpExchange exchange) throws IOException {
        List<ApplicationManager.WhitelistApplication> apps = plugin.getApplicationManager().getAllApplications();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ApplicationManager.WhitelistApplication app : apps) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"id\":\"").append(app.getId().toString()).append("\",");
            sb.append("\"playerName\":\"").append(escapeJson(app.getPlayerName())).append("\",");
            sb.append("\"qq\":\"").append(escapeJson(app.getQq())).append("\",");
            sb.append("\"email\":\"").append(escapeJson(app.getEmail())).append("\",");
            sb.append("\"isPremium\":").append(app.isPremium()).append(",");
            sb.append("\"status\":\"").append(app.getStatus().name()).append("\",");
            sb.append("\"timestamp\":").append(app.getTimestamp());
            sb.append("}");
        }
        sb.append("]");
        sendJson(exchange, sb.toString());
    }

    private void handleApplicationAction(HttpExchange exchange, String path) throws IOException {
        // path: /api/applications/{id}/approve 或 /api/applications/{id}/reject
        String[] parts = path.split("/");
        if (parts.length < 4) {
            sendJson(exchange, "{\"success\":false,\"error\":\"无效请求\"}");
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(parts[3]);
        } catch (IllegalArgumentException e) {
            sendJson(exchange, "{\"success\":false,\"error\":\"无效ID\"}");
            return;
        }

        String action = parts.length > 4 ? parts[4] : "";

        ApplicationManager.WhitelistApplication app = plugin.getApplicationManager().getApplication(id);
        if (app == null) {
            sendJson(exchange, "{\"success\":false,\"error\":\"找不到该申请\"}");
            return;
        }

        if (action.equals("approve")) {
            plugin.getApplicationManager().approveApplication(id);
            // 添加到白名单
            OfflinePlayer player = Bukkit.getOfflinePlayer(app.getPlayerName());
            if (player != null && player.getName() != null) {
                player.setWhitelisted(true);
            }
            // 发送邮件
            if (!app.getEmail().isEmpty()) {
                new EmailSender(plugin).sendApprovalEmail(app.getEmail(), app.getPlayerName());
            }
            sendJson(exchange, "{\"success\":true,\"message\":\"已批准申请\"}");
        } else if (action.equals("reject")) {
            plugin.getApplicationManager().rejectApplication(id);
            // 发送邮件
            if (!app.getEmail().isEmpty()) {
                new EmailSender(plugin).sendRejectionEmail(app.getEmail(), app.getPlayerName());
            }
            sendJson(exchange, "{\"success\":true,\"message\":\"已拒绝申请\"}");
        } else {
            sendJson(exchange, "{\"success\":false,\"error\":\"未知操作\"}");
        }
    }

    private void serveStatic(HttpExchange exchange, String path) throws IOException {
        exchange.sendResponseHeaders(404, -1);
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String formatDuration(long millis) {
        long days = millis / (24 * 3600 * 1000);
        millis %= (24 * 3600 * 1000);
        long hours = millis / (3600 * 1000);
        millis %= (3600 * 1000);
        long minutes = millis / (60 * 1000);
        millis %= (60 * 1000);
        long seconds = millis / 1000;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("天 ");
        if (hours > 0) sb.append(hours).append("小时 ");
        if (minutes > 0) sb.append(minutes).append("分钟 ");
        sb.append(seconds).append("秒");
        return sb.toString();
    }

    // ==================== HTML 页面 ====================

    private String getLoginPage() {
        return "<!DOCTYPE html><html lang='zh-CN'><head>"
                + "<meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                + "<title>WhitelistSet - 登录</title>"
                + "<style>"
                + "*{margin:0;padding:0;box-sizing:border-box}"
                + "body{font-family:'Segoe UI',Tahoma,Arial,sans-serif;background:linear-gradient(135deg,#1a1a2e 0%,#16213e 50%,#0f3460 100%);min-height:100vh;display:flex;align-items:center;justify-content:center}"
                + ".login-box{background:rgba(255,255,255,0.05);backdrop-filter:blur(10px);border:1px solid rgba(255,255,255,0.1);border-radius:16px;padding:40px;width:380px;box-shadow:0 8px 32px rgba(0,0,0,0.3)}"
                + ".login-box h1{color:#e94560;text-align:center;margin-bottom:8px;font-size:24px}"
                + ".login-box p{color:#aaa;text-align:center;margin-bottom:30px;font-size:13px}"
                + ".input-group{margin-bottom:20px}"
                + ".input-group label{display:block;color:#ccc;margin-bottom:6px;font-size:14px}"
                + ".input-group input{width:100%;padding:12px 16px;background:rgba(255,255,255,0.08);border:1px solid rgba(255,255,255,0.15);border-radius:8px;color:#fff;font-size:15px;outline:none;transition:border .3s}"
                + ".input-group input:focus{border-color:#e94560}"
                + ".login-btn{width:100%;padding:12px;background:#e94560;color:#fff;border:none;border-radius:8px;font-size:16px;cursor:pointer;transition:background .3s}"
                + ".login-btn:hover{background:#d63850}"
                + ".error-msg{color:#e94560;text-align:center;margin-top:12px;font-size:13px;display:none}"
                + "</style></head><body>"
                + "<div class='login-box'>"
                + "<h1>WhitelistSet</h1><p>Minecraft 服务器白名单管理系统</p>"
                + "<div class='input-group'><label>管理员密码</label><input type='password' id='password' placeholder='请输入密码' onkeydown='if(event.key===\"Enter\")login()'></div>"
                + "<button class='login-btn' onclick='login()'>登 录</button>"
                + "<div class='error-msg' id='error'></div>"
                + "</div>"
                + "<script>"
                + "function login(){"
                + "var pwd=document.getElementById('password').value;"
                + "fetch('/api/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'password='+encodeURIComponent(pwd)})"
                + ".then(r=>r.json()).then(d=>{"
                + "if(d.success){document.cookie='auth_token='+d.token+';path=/';location.reload()}"
                + "else{var e=document.getElementById('error');e.style.display='block';e.textContent=d.error}"
                + "})}"
                + "</script></body></html>";
    }

    private String getMainPage() {
        return "<!DOCTYPE html><html lang='zh-CN'><head>"
                + "<meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                + "<title>WhitelistSet - 管理控制台</title>"
                + "<style>"
                + "*{margin:0;padding:0;box-sizing:border-box}"
                + "body{font-family:'Segoe UI',Tahoma,Arial,sans-serif;background:#121212;color:#e0e0e0;display:flex;min-height:100vh}"
                + ".sidebar{width:240px;background:#1a1a1a;border-right:1px solid #333;padding:20px 0;flex-shrink:0}"
                + ".sidebar h2{color:#e94560;padding:0 20px;margin-bottom:30px;font-size:18px}"
                + ".sidebar a{display:block;padding:12px 20px;color:#aaa;text-decoration:none;font-size:14px;transition:all .2s;border-left:3px solid transparent}"
                + ".sidebar a:hover,.sidebar a.active{color:#fff;background:rgba(233,69,96,0.1);border-left-color:#e94560}"
                + ".content{flex:1;padding:30px;overflow-y:auto}"
                + ".tab-content{display:none}"
                + ".tab-content.active{display:block}"
                + ".card{background:#1e1e1e;border:1px solid #333;border-radius:12px;padding:24px;margin-bottom:20px}"
                + ".card h3{color:#e94560;margin-bottom:16px;font-size:16px;border-bottom:1px solid #333;padding-bottom:10px}"
                + ".info-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:16px}"
                + ".info-item{background:#252525;padding:16px;border-radius:8px;border:1px solid #333}"
                + ".info-item .label{color:#888;font-size:12px;margin-bottom:4px}"
                + ".info-item .value{color:#fff;font-size:18px;font-weight:600}"
                + ".btn{display:inline-block;padding:8px 16px;border-radius:6px;border:none;font-size:13px;cursor:pointer;transition:all .2s;margin:0 4px}"
                + ".btn-primary{background:#e94560;color:#fff}.btn-primary:hover{background:#d63850}"
                + ".btn-success{background:#2ecc71;color:#fff}.btn-success:hover{background:#27ae60}"
                + ".btn-danger{background:#e74c3c;color:#fff}.btn-danger:hover{background:#c0392b}"
                + ".btn-warning{background:#f39c12;color:#fff}.btn-warning:hover{background:#e67e22}"
                + ".btn-outline{background:transparent;border:1px solid #555;color:#ccc}.btn-outline:hover{background:#333}"
                + "input,select{background:#252525;border:1px solid #444;border-radius:6px;padding:8px 12px;color:#fff;font-size:13px;outline:none}"
                + "input:focus,select:focus{border-color:#e94560}"
                + "table{width:100%;border-collapse:collapse;margin-top:12px}"
                + "th,td{padding:10px 14px;text-align:left;border-bottom:1px solid #333;font-size:13px}"
                + "th{color:#888;font-weight:500;font-size:12px;text-transform:uppercase}"
                + "tr:hover{background:rgba(255,255,255,0.03)}"
                + ".modal{display:none;position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.7);z-index:1000;align-items:center;justify-content:center}"
                + ".modal.active{display:flex}"
                + ".modal-content{background:#1e1e1e;border:1px solid #444;border-radius:12px;padding:24px;width:440px;max-height:80vh;overflow-y:auto}"
                + ".modal-content h3{color:#e94560;margin-bottom:16px}"
                + ".form-group{margin-bottom:14px}"
                + ".form-group label{display:block;color:#aaa;margin-bottom:4px;font-size:13px}"
                + ".form-group input,.form-group select{width:100%}"
                + ".form-group .toggle{display:flex;align-items:center;gap:10px}"
                + ".toggle-switch{position:relative;width:44px;height:24px;display:inline-block}"
                + ".toggle-switch input{opacity:0;width:0;height:0}"
                + ".toggle-slider{position:absolute;cursor:pointer;top:0;left:0;right:0;bottom:0;background:#444;border-radius:24px;transition:.3s}"
                + ".toggle-slider:before{position:absolute;content:'';height:18px;width:18px;left:3px;bottom:3px;background:#fff;border-radius:50%;transition:.3s}"
                + "input:checked+.toggle-slider{background:#e94560}"
                + "input:checked+.toggle-slider:before{transform:translateX(20px)}"
                + ".toolbar{display:flex;gap:10px;margin-bottom:16px;flex-wrap:wrap;align-items:center}"
                + ".toast{position:fixed;top:20px;right:20px;padding:12px 20px;border-radius:8px;color:#fff;font-size:14px;z-index:2000;display:none;animation:fadeIn .3s}"
                + ".toast.success{background:#2ecc71}.toast.error{background:#e74c3c}"
                + "@keyframes fadeIn{from{opacity:0;transform:translateY(-10px)}to{opacity:1;transform:translateY(0)}}"
                + ".badge{display:inline-block;padding:2px 8px;border-radius:4px;font-size:11px;font-weight:600}"
                + ".badge-success{background:rgba(46,204,113,0.2);color:#2ecc71}"
                + ".badge-danger{background:rgba(231,76,60,0.2);color:#e74c3c}"
                + ".badge-warning{background:rgba(243,156,18,0.2);color:#f39c12}"
                + ".badge-info{background:rgba(52,152,219,0.2);color:#3498db}"
                + "</style></head><body>"
                + "<div class='sidebar'>"
                + "<h2>WhitelistSet</h2>"
                + "<a href='#' class='active' onclick='switchTab(\"overview\")'>服务器总览</a>"
                + "<a href='#' onclick='switchTab(\"whitelist\")'>白名单控制</a>"
                + "<a href='#' onclick='switchTab(\"applications\")'>白名单申请管理</a>"
                + "<a href='#' onclick='switchTab(\"settings\")'>设置</a>"
                + "<a href='#' onclick='logout()' style='margin-top:30px;color:#e74c3c'>登出</a>"
                + "</div>"
                + "<div class='content'>"
                // 服务器总览
                + "<div id='tab-overview' class='tab-content active'>"
                + "<div class='card'><h3>服务器总览</h3>"
                + "<div class='info-grid' id='serverInfo'></div></div>"
                + "</div>"
                // 白名单控制
                + "<div id='tab-whitelist' class='tab-content'>"
                + "<div class='card'><h3>白名单控制</h3>"
                + "<div class='toolbar'>"
                + "<button class='btn btn-primary' onclick='showAddModal()'>添加玩家</button>"
                + "<button class='btn btn-outline' onclick='showBatchAddModal()'>批量添加</button>"
                + "<button class='btn btn-outline' onclick='showBatchDeleteModal()'>批量删除</button>"
                + "<button class='btn btn-outline' onclick='loadWhitelist()'>刷新列表</button>"
                + "</div>"
                + "<table><thead><tr><th><input type='checkbox' id='selectAll' onclick='toggleSelectAll()'></th><th>玩家名</th><th>UUID</th><th>状态</th><th>操作</th></tr></thead>"
                + "<tbody id='whitelistBody'></tbody></table></div>"
                + "</div>"
                // 白名单申请管理
                + "<div id='tab-applications' class='tab-content'>"
                + "<div class='card'><h3>白名单申请管理</h3>"
                + "<div class='toolbar'><button class='btn btn-outline' onclick='loadApplications()'>刷新列表</button></div>"
                + "<table><thead><tr><th>玩家ID</th><th>QQ</th><th>邮箱</th><th>正版</th><th>状态</th><th>申请时间</th><th>操作</th></tr></thead>"
                + "<tbody id='applicationsBody'></tbody></table></div>"
                + "</div>"
                // 设置
                + "<div id='tab-settings' class='tab-content'>"
                + "<div class='card'><h3>控制台设置</h3>"
                + "<div class='form-group'><label>登录密码</label><input type='text' id='setPassword'></div>"
                + "</div>"
                + "<div class='card'><h3>白名单申请设置</h3>"
                + "<div class='form-group'><div class='toggle'><label>开启白名单申请</label>"
                + "<label class='toggle-switch'><input type='checkbox' id='setApplyEnabled' onchange='onApplyToggle()'>"
                + "<span class='toggle-slider'></span></label></div></div>"
                + "<p style='color:#f39c12;font-size:12px;margin-top:8px;display:none' id='applyWarning'>⚠ 修改此设置后需重启服务器以应用更改</p>"
                + "</div>"
                + "<div class='card'><h3>邮箱发件信息设置</h3>"
                + "<div class='form-group'><label>SMTP服务器地址</label><input type='text' id='setEmailHost' placeholder='smtp.qq.com'></div>"
                + "<div class='form-group'><label>SMTP端口</label><input type='number' id='setEmailPort' placeholder='587'></div>"
                + "<div class='form-group'><label>发件邮箱</label><input type='text' id='setEmailUser' placeholder='your@email.com'></div>"
                + "<div class='form-group'><label>邮箱密码/授权码</label><input type='password' id='setEmailPwd' placeholder='授权码'></div>"
                + "<div class='form-group'><div class='toggle'><label>使用SSL</label>"
                + "<label class='toggle-switch'><input type='checkbox' id='setEmailSsl'>"
                + "<span class='toggle-slider'></span></label></div></div>"
                + "</div>"
                + "<button class='btn btn-primary' onclick='saveSettings()' style='margin-top:10px'>保存所有设置</button>"
                + "</div>"
                + "</div>"
                // Modal: 添加玩家
                + "<div class='modal' id='addModal'><div class='modal-content'><h3>添加玩家到白名单</h3>"
                + "<div class='form-group'><label>玩家名称</label><input type='text' id='addPlayerName' placeholder='输入玩家名'></div>"
                + "<div style='display:flex;gap:10px;justify-content:flex-end;margin-top:16px'>"
                + "<button class='btn btn-outline' onclick='closeModal(\"addModal\")'>取消</button>"
                + "<button class='btn btn-primary' onclick='addPlayer()'>添加</button></div>"
                + "</div></div>"
                // Modal: 批量管理
                + "<div class='modal' id='batchModal'><div class='modal-content'><h3 id='batchTitle'>批量添加玩家</h3>"
                + "<input type='hidden' id='batchAction' value='add'>"
                + "<div class='form-group' id='batchAddInput'><label>玩家名称（用逗号分隔）</label>"
                + "<textarea id='batchPlayerNames' style='width:100%;background:#252525;border:1px solid #444;border-radius:6px;padding:8px 12px;color:#fff;font-size:13px;resize:vertical;min-height:100px' placeholder='player1, player2, player3'></textarea></div>"
                + "<div class='form-group' id='batchDeleteInfo' style='display:none'><label>已选玩家</label>"
                + "<div id='batchDeleteList' style='background:#252525;border:1px solid #444;border-radius:6px;padding:8px 12px;color:#fff;font-size:13px;min-height:40px'></div></div>"
                + "<div style='display:flex;gap:10px;justify-content:flex-end;margin-top:16px'>"
                + "<button class='btn btn-outline' onclick='closeModal(\"batchModal\")'>取消</button>"
                + "<button class='btn btn-primary' id='batchConfirmBtn' onclick='batchAction()'>批量添加</button></div>"
                + "</div></div>"
                + "<div class='toast' id='toast'></div>"
                + "<script>"
                + "function getCookie(n){var c=document.cookie.split(';');for(var i=0;i<c.length;i++){var p=c[i].trim().split('=');if(p[0]===n)return p[1]}return''}"
                + "var token=getCookie('auth_token');"
                + "function api(method,url,body){"
                + "var opt={method:method,headers:{'Content-Type':'application/x-www-form-urlencoded'}};"
                + "if(body)opt.body=body;"
                + "return fetch(url,opt).then(r=>r.json());}"
                + "function toast(msg,type){var t=document.getElementById('toast');t.textContent=msg;t.className='toast '+type;t.style.display='block';setTimeout(function(){t.style.display='none'},3000)}"
                + "function switchTab(name){"
                + "document.querySelectorAll('.tab-content').forEach(function(el){el.classList.remove('active')});"
                + "document.getElementById('tab-'+name).classList.add('active');"
                + "document.querySelectorAll('.sidebar a').forEach(function(el){el.classList.remove('active')});"
                + "event.target.classList.add('active');"
                + "if(name==='overview')loadServerInfo();"
                + "if(name==='whitelist')loadWhitelist();"
                + "if(name==='applications')loadApplications();"
                + "if(name==='settings')loadSettings();}"
                + "function showModal(id){document.getElementById(id).classList.add('active')}"
                + "function closeModal(id){document.getElementById(id).classList.remove('active')}"
                + "function showAddModal(){document.getElementById('addPlayerName').value='';showModal('addModal')}"
                + "function showBatchAddModal(){document.getElementById('batchTitle').textContent='批量添加玩家';document.getElementById('batchAction').value='add';document.getElementById('batchAddInput').style.display='block';document.getElementById('batchDeleteInfo').style.display='none';document.getElementById('batchPlayerNames').value='';document.getElementById('batchConfirmBtn').textContent='批量添加';showModal('batchModal')}"
                + "function showBatchDeleteModal(){var cbs=document.querySelectorAll('.wl-checkbox:checked');if(cbs.length===0){toast('请先选择要删除的玩家','error');return}var names=[];cbs.forEach(function(cb){names.push(cb.dataset.name)});document.getElementById('batchTitle').textContent='批量删除玩家（'+names.length+'个）';document.getElementById('batchAction').value='remove';document.getElementById('batchAddInput').style.display='none';document.getElementById('batchDeleteInfo').style.display='block';document.getElementById('batchDeleteList').innerHTML=names.map(function(n){return '<span style=\"display:inline-block;background:#e74c3c22;color:#e74c3c;padding:4px 10px;border-radius:4px;margin:2px\">'+n+'</span>'}).join('');document.getElementById('batchConfirmBtn').textContent='确认删除';showModal('batchModal')}"
                + "function batchAction(){var action=document.getElementById('batchAction').value;if(action==='add'){batchAdd()}else{batchRemove()}}"
                + "function toggleSelectAll(){var sa=document.getElementById('selectAll');var cbs=document.querySelectorAll('.wl-checkbox');cbs.forEach(function(cb){cb.checked=sa.checked})}"
                // 登出
                + "function logout(){fetch('/api/logout',{method:'POST'}).then(function(){location.reload()})}"
                // 服务器总览
                + "function loadServerInfo(){"
                + "api('GET','/api/server-info').then(function(d){"
                + "var h='<div class=\"info-item\"><div class=\"label\">服务器版本</div><div class=\"value\" style=\"font-size:14px\">'+d.bukkitVersion+'</div></div>';"
                + "h+='<div class=\"info-item\"><div class=\"label\">运行时间</div><div class=\"value\" style=\"font-size:14px\">'+d.uptime+'</div></div>';"
                + "h+='<div class=\"info-item\"><div class=\"label\">Minecraft 时间</div><div class=\"value\" style=\"font-size:14px\">'+d.mcTimeStr+'</div></div>';"
                + "h+='<div class=\"info-item\"><div class=\"label\">在线玩家</div><div class=\"value\">'+d.onlinePlayers+' / '+d.maxPlayers+'</div></div>';"
                + "h+='<div class=\"info-item\"><div class=\"label\">白名单状态</div><div class=\"value\" style=\"font-size:14px\">'+(d.whitelistEnabled?'已开启':'未开启')+'</div></div>';"
                + "document.getElementById('serverInfo').innerHTML=h;})}"
                // 白名单
                + "function loadWhitelist(){"
                + "api('GET','/api/whitelist').then(function(d){"
                + "var h='';d.forEach(function(p){"
                + "h+='<tr><td><input type=\"checkbox\" class=\"wl-checkbox\" data-name=\"'+p.name+'\" onclick=\"updateSelectAll()\"></td><td>'+p.name+'</td><td style=\"font-size:11px;color:#888\">'+p.uuid.substring(0,8)+'...</td>';"
                + "h+='<td>'+(p.isBanned?'<span class=\"badge badge-danger\">已封禁</span>':'<span class=\"badge badge-success\">正常</span>')+'</td>';"
                + "h+='<td>';"
                + "h+='<button class=\"btn btn-danger\" style=\"font-size:11px;padding:4px 10px\" onclick=\"removePlayer(\\''+p.name+'\\')\">移除</button>';"
                + "if(p.isBanned){"
                + "h+='<button class=\"btn btn-success\" style=\"font-size:11px;padding:4px 10px\" onclick=\"unbanPlayer(\\''+p.name+'\\')\">解禁</button>';"
                + "}else{"
                + "h+='<button class=\"btn btn-warning\" style=\"font-size:11px;padding:4px 10px\" onclick=\"banPlayer(\\''+p.name+'\\')\">封禁</button>';"
                + "}"
                + "h+='</td></tr>';});"
                + "document.getElementById('whitelistBody').innerHTML=h||'<tr><td colspan=5 style=text-align:center;color:#888>暂无白名单玩家</td></tr>';document.getElementById('selectAll').checked=false})}"
                + "function updateSelectAll(){var cbs=document.querySelectorAll('.wl-checkbox');var sa=document.getElementById('selectAll');sa.checked=cbs.length>0&&Array.prototype.every.call(cbs,function(cb){return cb.checked})}"
                + "function addPlayer(){"
                + "var n=document.getElementById('addPlayerName').value.trim();if(!n)return;"
                + "api('POST','/api/whitelist','name='+encodeURIComponent(n)).then(function(d){"
                + "if(d.success){toast(d.message,'success');closeModal('addModal');loadWhitelist()}else{toast(d.error,'error')}})}"
                + "function batchAdd(){"
                + "var n=document.getElementById('batchPlayerNames').value.trim();if(!n)return;"
                + "api('POST','/api/whitelist/batch','names='+encodeURIComponent(n)+'&action=add').then(function(d){"
                + "if(d.success){toast('成功添加 '+d.added+' 个玩家','success');closeModal('batchModal');loadWhitelist()}else{toast(d.error,'error')}})}"
                + "function batchRemove(){"
                + "var cbs=document.querySelectorAll('.wl-checkbox:checked');if(cbs.length===0)return;var names=[];cbs.forEach(function(cb){names.push(cb.dataset.name)});"
                + "var body='names='+encodeURIComponent(names.join(','))+'&action=remove';"
                + "api('POST','/api/whitelist/batch',body).then(function(d){"
                + "if(d.success){toast('成功移除 '+d.removed+' 个玩家','success');closeModal('batchModal');loadWhitelist()}else{toast(d.error,'error')}})}"
                + "function removePlayer(name){if(confirm('确定要将 '+name+' 移出白名单？')){api('DELETE','/api/whitelist?name='+encodeURIComponent(name)).then(function(d){if(d.success){toast(d.message,'success');loadWhitelist()}else{toast(d.error,'error')}})}}"
                + "function banPlayer(name){if(confirm('确定要封禁 '+name+'？')){api('POST','/api/ban','name='+encodeURIComponent(name)).then(function(d){if(d.success){toast(d.message,'success');loadWhitelist()}else{toast(d.error,'error')}})}}"
                + "function unbanPlayer(name){if(confirm('确定要解禁 '+name+'？')){api('DELETE','/api/ban?name='+encodeURIComponent(name)).then(function(d){if(d.success){toast(d.message,'success');loadWhitelist()}else{toast(d.error,'error')}})}}"
                // 申请管理
                + "function loadApplications(){"
                + "api('GET','/api/applications').then(function(d){"
                + "var h='';d.forEach(function(a){"
                + "var badge='';"
                + "if(a.status==='PENDING')badge='<span class=\"badge badge-warning\">待审核</span>';"
                + "else if(a.status==='APPROVED')badge='<span class=\"badge badge-success\">已通过</span>';"
                + "else badge='<span class=\"badge badge-danger\">已拒绝</span>';"
                + "var d2=new Date(a.timestamp);var ts=d2.toLocaleString('zh-CN');"
                + "h+='<tr><td>'+a.playerName+'</td><td>'+a.qq+'</td><td>'+a.email+'</td>';"
                + "h+='<td>'+(a.isPremium?'<span class=\"badge badge-info\">正版</span>':'<span class=\"badge\">离线</span>')+'</td>';"
                + "h+='<td>'+badge+'</td><td style=\"font-size:11px;color:#888\">'+ts+'</td>';"
                + "h+='<td>';"
                + "if(a.status==='PENDING'){"
                + "h+='<button class=\"btn btn-success\" style=\"font-size:11px;padding:4px 10px\" onclick=\"approveApp(\\''+a.id+'\\')\">批准</button>';"
                + "h+='<button class=\"btn btn-danger\" style=\"font-size:11px;padding:4px 10px\" onclick=\"rejectApp(\\''+a.id+'\\')\">拒绝</button>';"
                + "}else{h+='<span style=\"color:#888;font-size:11px\">已处理</span>'}"
                + "h+='</td></tr>';});"
                + "document.getElementById('applicationsBody').innerHTML=h||'<tr><td colspan=7 style=text-align:center;color:#888>暂无申请记录</td></tr>';})}"
                + "function approveApp(id){if(confirm('确定批准此申请？')){api('POST','/api/applications/'+id+'/approve').then(function(d){if(d.success){toast(d.message,'success');loadApplications()}else{toast(d.error,'error')}})}}"
                + "function rejectApp(id){if(confirm('确定拒绝此申请？')){api('POST','/api/applications/'+id+'/reject').then(function(d){if(d.success){toast(d.message,'success');loadApplications()}else{toast(d.error,'error')}})}}"
                // 设置
                + "function loadSettings(){"
                + "api('GET','/api/settings').then(function(d){"
                + "document.getElementById('setPassword').value=d.password;"
                + "document.getElementById('setApplyEnabled').checked=d.whitelistApplyEnabled;"
                + "document.getElementById('setEmailHost').value=d.emailHost;"
                + "document.getElementById('setEmailPort').value=d.emailPort;"
                + "document.getElementById('setEmailUser').value=d.emailUsername;"
                + "document.getElementById('setEmailPwd').value=d.emailPassword;"
                + "document.getElementById('setEmailSsl').checked=d.emailUseSsl;})}"
                + "function onApplyToggle(){"
                + "document.getElementById('applyWarning').style.display="
                + "document.getElementById('setApplyEnabled').checked?'block':'none'}"
                + "function saveSettings(){"
                + "var p={password:document.getElementById('setPassword').value,"
                + "whitelistApplyEnabled:document.getElementById('setApplyEnabled').checked,"
                + "emailHost:document.getElementById('setEmailHost').value,"
                + "emailPort:document.getElementById('setEmailPort').value,"
                + "emailUsername:document.getElementById('setEmailUser').value,"
                + "emailPassword:document.getElementById('setEmailPwd').value,"
                + "emailUseSsl:document.getElementById('setEmailSsl').checked};"
                + "var qs=Object.keys(p).map(function(k){return k+'='+encodeURIComponent(p[k])}).join('&');"
                + "api('POST','/api/settings',qs).then(function(d){"
                + "if(d.success){"
                + "if(p.password!==getCookie('auth_token')){document.cookie='auth_token='+p.password+';path=/'}"
                + "toast(d.message,'success');"
                + "if(p.whitelistApplyEnabled){alert('白名单申请功能设置已更改，需要重启服务器以应用更改！')}"
                + "}else{toast(d.error,'error')}})}"
                // 初始化
                + "loadServerInfo();"
                + "</script></body></html>";
    }
}
