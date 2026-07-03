package com.mc615.whitelistset.whitelistset;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ApplyServer {

    private final Whitelistset plugin;
    private final int port;
    private HttpServer server;

    public ApplyServer(Whitelistset plugin, int port) {
        this.plugin = plugin;
        this.port = port;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", new ApplyHandler());
            server.createContext("/api/", new ApplyApiHandler());
            server.setExecutor(null);
            server.start();
        } catch (IOException e) {
            plugin.getLogger().severe("无法启动白名单申请服务: " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
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

    private void sendJson(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
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

    class ApplyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendHtml(exchange, getApplyPage());
        }
    }

    class ApplyApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/submit")) {
                handleSubmit(exchange);
            } else {
                sendJson(exchange, "{\"error\":\"未知API\"}");
            }
        }
    }

    private void handleSubmit(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        Map<String, String> params = parseQuery(body);

        String playerName = params.get("playerName");
        String qq = params.get("qq");
        String email = params.get("email");
        boolean isPremium = "true".equals(params.get("isPremium"));

        plugin.getLogger().info("收到白名单申请: playerName=" + playerName + ", qq=" + qq + ", email=" + email);

        if (playerName == null || playerName.trim().isEmpty()) {
            sendJson(exchange, "{\"success\":false,\"error\":\"玩家ID不能为空\"}");
            return;
        }
        if (qq == null || qq.trim().isEmpty()) {
            sendJson(exchange, "{\"success\":false,\"error\":\"QQ号不能为空\"}");
            return;
        }
        if (email == null || email.trim().isEmpty()) {
            sendJson(exchange, "{\"success\":false,\"error\":\"邮箱不能为空\"}");
            return;
        }

        UUID id = UUID.randomUUID();
        ApplicationManager.WhitelistApplication app = new ApplicationManager.WhitelistApplication(
                id, playerName.trim(), qq.trim(), email.trim(), isPremium, System.currentTimeMillis()
        );
        plugin.getApplicationManager().addApplication(app);
        plugin.getLogger().info("白名单申请已保存: id=" + id + ", player=" + playerName.trim());

        sendJson(exchange, "{\"success\":true,\"message\":\"申请已提交，请等待管理员审核\"}");
    }

    private String getApplyPage() {
        return "<!DOCTYPE html><html lang='zh-CN'><head>"
                + "<meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                + "<title>服务器白名单申请</title>"
                + "<style>"
                + "*{margin:0;padding:0;box-sizing:border-box}"
                + "body{font-family:'Segoe UI',Tahoma,Arial,sans-serif;background:linear-gradient(135deg,#1a1a2e 0%,#16213e 50%,#0f3460 100%);min-height:100vh;display:flex;align-items:center;justify-content:center}"
                + ".form-box{background:rgba(255,255,255,0.05);backdrop-filter:blur(10px);border:1px solid rgba(255,255,255,0.1);border-radius:16px;padding:40px;width:420px;box-shadow:0 8px 32px rgba(0,0,0,0.3)}"
                + ".form-box h1{color:#e94560;text-align:center;margin-bottom:8px;font-size:22px}"
                + ".form-box p.subtitle{color:#aaa;text-align:center;margin-bottom:28px;font-size:13px}"
                + ".form-group{margin-bottom:18px}"
                + ".form-group label{display:block;color:#ccc;margin-bottom:6px;font-size:14px}"
                + ".form-group input,.form-group select{width:100%;padding:10px 14px;background:rgba(255,255,255,0.08);border:1px solid rgba(255,255,255,0.15);border-radius:8px;color:#fff;font-size:14px;outline:none;transition:border .3s}"
                + ".form-group input:focus,.form-group select:focus{border-color:#e94560}"
                + ".form-group select{appearance:none;cursor:pointer}"
                + ".form-group select option{background:#1a1a2e;color:#fff}"
                + ".radio-group{display:flex;gap:20px}"
                + ".radio-group label{display:flex;align-items:center;gap:8px;cursor:pointer;font-size:14px}"
                + ".radio-group input[type=radio]{width:auto;accent-color:#e94560}"
                + ".submit-btn{width:100%;padding:12px;background:#e94560;color:#fff;border:none;border-radius:8px;font-size:16px;cursor:pointer;transition:background .3s;margin-top:8px}"
                + ".submit-btn:hover{background:#d63850}"
                + ".submit-btn:disabled{opacity:0.6;cursor:not-allowed}"
                + ".result{text-align:center;margin-top:16px;font-size:14px;display:none}"
                + ".result.success{color:#2ecc71;display:block}"
                + ".result.error{color:#e74c3c;display:block}"
                + "</style></head><body>"
                + "<div class='form-box'>"
                + "<h1>服务器白名单申请</h1>"
                + "<p class='subtitle'>填写以下信息申请加入服务器白名单</p>"
                + "<form id='applyForm' onsubmit='submitForm(event)'>"
                + "<div class='form-group'><label>玩家ID *</label><input type='text' id='playerName' placeholder='请输入您的 Minecraft ID' required></div>"
                + "<div class='form-group'><label>QQ号 *</label><input type='text' id='qq' placeholder='请输入您的QQ号' required></div>"
                + "<div class='form-group'><label>邮箱 *</label><input type='email' id='email' placeholder='请输入您的邮箱地址' required></div>"
                + "<div class='form-group'><label>账号类型 *</label>"
                + "<div class='radio-group'>"
                + "<label><input type='radio' name='isPremium' value='true'> 正版账号</label>"
                + "<label><input type='radio' name='isPremium' value='false' checked> 离线账号</label>"
                + "</div></div>"
                + "<button type='submit' class='submit-btn' id='submitBtn'>提交申请</button>"
                + "</form>"
                + "<div class='result' id='result'></div>"
                + "</div>"
                + "<script>"
                + "function submitForm(e){e.preventDefault();"
                + "var btn=document.getElementById('submitBtn');btn.disabled=true;btn.textContent='提交中...';"
                + "var r=document.getElementById('result');r.style.display='none';"
                + "var isPremiumEl=document.querySelector('input[name=isPremium]:checked');"
                + "if(!isPremiumEl){showResult(false,'请选择账号类型');resetBtn();return}"
                + "var isPremium=isPremiumEl.value;"
                + "var body='playerName='+encodeURIComponent(document.getElementById('playerName').value)+'&'"
                + "'qq='+encodeURIComponent(document.getElementById('qq').value)+'&'"
                + "'email='+encodeURIComponent(document.getElementById('email').value)+'&'"
                + "'isPremium='+isPremium;"
                + "fetch('/api/submit',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body})"
                + ".then(function(resp){if(!resp.ok)throw new Error('HTTP '+resp.status);return resp.json()})"
                + ".then(function(d){"
                + "if(d.success){showResult(true,d.message);document.getElementById('applyForm').reset()}"
                + "else{showResult(false,d.error)}"
                + "}).catch(function(err){console.error(err);showResult(false,'提交失败: '+(err.message||'网络错误'))})"
                + ".then(function(){resetBtn()})"
                + "}"
                + "function showResult(success,msg){var r=document.getElementById('result');r.className=success?'result success':'result error';r.textContent=msg;r.style.display='block'}"
                + "function resetBtn(){var btn=document.getElementById('submitBtn');btn.disabled=false;btn.textContent='提交申请'}"
                + "</script></body></html>";
    }
}
