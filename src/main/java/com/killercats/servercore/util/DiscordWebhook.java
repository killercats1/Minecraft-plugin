package com.killercats.servercore.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Discord webhook client (no extra libraries). Every method blocks, so call it from a
 * background thread, never the main server thread.
 */
public final class DiscordWebhook {

    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)");
    private static final int MAX_ATTEMPTS = 5;

    private DiscordWebhook() {
    }

    public static boolean isValidUrl(String url) {
        return url != null && (url.startsWith("https://discord.com/api/webhooks/")
                || url.startsWith("https://discordapp.com/api/webhooks/")
                || url.startsWith("https://canary.discord.com/api/webhooks/")
                || url.startsWith("https://ptb.discord.com/api/webhooks/"));
    }

    /** Posts a plain text message (max 2000 characters, longer text is cut). */
    public static void send(String url, String content) throws IOException {
        String body = "{\"content\":" + json(cut(content, 2000)) + ",\"allowed_mentions\":{\"parse\":[]}}";
        post(url, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Uploads part of a file as an attachment.
     *
     * @param offset where in the file the part starts
     * @param length how many bytes to send
     */
    public static void sendFilePart(String url, String content, File file, long offset, int length, String fileName) throws IOException {
        byte[] data = new byte[length];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            raf.readFully(data);
        }
        String boundary = "----ServerCore" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream out = new ByteArrayOutputStream(length + 1024);
        String payload = "{\"content\":" + json(cut(content, 2000)) + ",\"allowed_mentions\":{\"parse\":[]}}";
        write(out, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n\r\n" + payload + "\r\n");
        write(out, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"" + fileName.replace("\"", "") + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n");
        out.write(data);
        write(out, "\r\n--" + boundary + "--\r\n");
        post(url, "multipart/form-data; boundary=" + boundary, out.toByteArray());
    }

    private static void post(String url, String contentType, byte[] body) throws IOException {
        for (int attempt = 1; ; attempt++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(120000);
            connection.setRequestProperty("Content-Type", contentType);
            connection.setRequestProperty("User-Agent", "ServerCore (https://github.com/killercats1/Minecraft-plugin)");
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(body);
            }
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                drain(connection.getInputStream());
                return;
            }
            String error = read(connection.getErrorStream());
            if (code == 429 && attempt < MAX_ATTEMPTS) {
                // Rate limited: wait as long as Discord asks, then try again.
                double seconds = 2;
                Matcher matcher = RETRY_AFTER.matcher(error);
                if (matcher.find()) {
                    seconds = Double.parseDouble(matcher.group(1));
                }
                sleep((long) (Math.min(60, seconds) * 1000) + 250);
                continue;
            }
            if (code >= 500 && attempt < MAX_ATTEMPTS) {
                sleep(2000L * attempt);
                continue;
            }
            throw new IOException("Discord answered HTTP " + code + (error.isEmpty() ? "" : ": " + cut(error, 200)));
        }
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }

    private static void write(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
    }

    private static void drain(InputStream in) throws IOException {
        if (in == null) {
            return;
        }
        try (InputStream stream = in) {
            byte[] buffer = new byte[1024];
            while (stream.read(buffer) > 0) {
                // discard
            }
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int n;
            while ((n = stream.read(buffer)) > 0 && out.size() < 8192) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    /** Quotes and escapes a string for JSON. */
    public static String json(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
