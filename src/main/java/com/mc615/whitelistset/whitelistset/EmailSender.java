package com.mc615.whitelistset.whitelistset;

import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class EmailSender {

    private final Whitelistset plugin;

    public EmailSender(Whitelistset plugin) {
        this.plugin = plugin;
    }

    public boolean sendApprovalEmail(String toEmail, String playerName) {
        String subject = "[服务器白名单] 申请已通过";
        String content = buildEmailContent(playerName, true);
        return sendEmail(toEmail, subject, content);
    }

    public boolean sendRejectionEmail(String toEmail, String playerName) {
        String subject = "[服务器白名单] 申请未通过";
        String content = buildEmailContent(playerName, false);
        return sendEmail(toEmail, subject, content);
    }

    private String buildEmailContent(String playerName, boolean approved) {
        String boundary = "==Boundary_" + System.currentTimeMillis() + "==";
        StringBuilder sb = new StringBuilder();
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: multipart/alternative; boundary=\"").append(boundary).append("\"\r\n\r\n");

        // 纯文本版本
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n\r\n");
        if (approved) {
            sb.append("亲爱的玩家 ").append(playerName).append("：\r\n\r\n");
            sb.append("恭喜！您的白名单申请已被审核通过。\r\n");
            sb.append("您现在可以进入服务器开始游戏了。\r\n\r\n");
            sb.append("祝您游戏愉快！\r\n");
        } else {
            sb.append("亲爱的玩家 ").append(playerName).append("：\r\n\r\n");
            sb.append("很遗憾，您的白名单申请未能通过审核。\r\n");
            sb.append("如有疑问，请联系服务器管理员。\r\n\r\n");
            sb.append("感谢您的关注。\r\n");
        }

        // HTML 版本
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: text/html; charset=UTF-8\r\n\r\n");
        if (approved) {
            sb.append("<html><body style='font-family: Arial, sans-serif; padding: 20px;'>")
                    .append("<h2 style='color: #4CAF50;'>白名单申请已通过</h2>")
                    .append("<p>亲爱的玩家 <strong>").append(playerName).append("</strong>：</p>")
                    .append("<p>恭喜！您的白名单申请已被审核通过。</p>")
                    .append("<p>您现在可以进入服务器开始游戏了。</p>")
                    .append("<br><p>祝您游戏愉快！</p>")
                    .append("</body></html>");
        } else {
            sb.append("<html><body style='font-family: Arial, sans-serif; padding: 20px;'>")
                    .append("<h2 style='color: #f44336;'>白名单申请未通过</h2>")
                    .append("<p>亲爱的玩家 <strong>").append(playerName).append("</strong>：</p>")
                    .append("<p>很遗憾，您的白名单申请未能通过审核。</p>")
                    .append("<p>如有疑问，请联系服务器管理员。</p>")
                    .append("<br><p>感谢您的关注。</p>")
                    .append("</body></html>");
        }

        sb.append("--").append(boundary).append("--\r\n");
        return sb.toString();
    }

    private boolean sendEmail(String toEmail, String subject, String content) {
        ConfigManager config = plugin.getConfigManager();

        String host = config.getEmailHost();
        int port = config.getEmailPort();
        String username = config.getEmailUsername();
        String password = config.getEmailPassword();
        boolean useSsl = config.isEmailUseSsl();

        if (username.isEmpty() || password.isEmpty()) {
            plugin.getLogger().warning("邮件发送失败: 未配置邮箱信息");
            return false;
        }

        try {
            Socket socket;
            if (useSsl) {
                socket = SSLSocketFactory.getDefault().createSocket(host, port);
            } else {
                socket = new Socket(host, port);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            // 读取服务器欢迎消息
            readResponse(reader);

            // EHLO
            writer.println("EHLO " + host);
            readMultiResponse(reader);

            // 如果非SSL，需要 STARTTLS
            if (!useSsl) {
                writer.println("STARTTLS");
                readResponse(reader);
                // 升级到 SSL - 直接创建新的 SSL Socket
                socket.close();
                socket = SSLSocketFactory.getDefault().createSocket(host, port);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                writer.println("EHLO " + host);
                readMultiResponse(reader);
            }

            // AUTH LOGIN
            writer.println("AUTH LOGIN");
            readResponse(reader);
            writer.println(Base64.getEncoder().encodeToString(username.getBytes(StandardCharsets.UTF_8)));
            readResponse(reader);
            writer.println(Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));
            readResponse(reader);

            // MAIL FROM
            writer.println("MAIL FROM:<" + username + ">");
            readResponse(reader);

            // RCPT TO
            writer.println("RCPT TO:<" + toEmail + ">");
            readResponse(reader);

            // DATA
            writer.println("DATA");
            readResponse(reader);

            // 邮件内容
            writer.println("From: " + username);
            writer.println("To: " + toEmail);
            writer.println("Subject: =?UTF-8?B?" + Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8)) + "?=");
            writer.println(content);
            writer.println(".");
            readResponse(reader);

            // QUIT
            writer.println("QUIT");

            socket.close();
            plugin.getLogger().info("邮件已发送至: " + toEmail);
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("邮件发送失败: " + e.getMessage());
            return false;
        }
    }

    private void readResponse(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line != null && line.startsWith("4") || (line != null && line.startsWith("5"))) {
            plugin.getLogger().warning("SMTP 响应: " + line);
        }
    }

    private void readMultiResponse(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.length() >= 4 && line.charAt(3) == ' ') {
                break;
            }
        }
    }
}
