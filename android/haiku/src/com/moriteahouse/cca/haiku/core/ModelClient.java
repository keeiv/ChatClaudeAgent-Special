// HTTPS calls to the model providers. The API key is added here, on the native side, so it never enters the web
// page. The request and response bodies are passed through as text; the page builds and reads the JSON.
package com.moriteahouse.cca.haiku.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ModelClient {
    public static final int MAX_BODY = 8 * 1024 * 1024;

    public static final class Response {
        public final int status;
        public final String body;
        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private final Map<String, String> bases = new LinkedHashMap<>();

    /** Production endpoints. Tests pass their own local server for both providers. */
    public ModelClient() {
        this("https://api.anthropic.com/v1", "https://api.openai.com/v1");
    }

    public ModelClient(String anthropic, String openai) {
        bases.put("anthropic", anthropic);
        bases.put("openai", openai);
    }

    public static boolean knownProvider(String provider) {
        return "anthropic".equals(provider) || "openai".equals(provider);
    }

    public Response models(String provider, String key) throws IOException {
        return call(provider, key, "GET", "/models", null);
    }

    public Response send(String provider, String key, String body) throws IOException {
        if (body == null || body.length() > MAX_BODY) throw new IOException("要求太大");
        return call(provider, key, "POST", "anthropic".equals(provider) ? "/messages" : "/chat/completions", body);
    }

    private Response call(String provider, String key, String method, String path, String body) throws IOException {
        if (!knownProvider(provider)) throw new IOException("不支援的供應商");
        if (key == null || key.isEmpty()) throw new IOException("還沒有設定這個供應商的 API 金鑰");
        HttpURLConnection http = (HttpURLConnection) new URL(bases.get(provider) + path).openConnection();
        http.setInstanceFollowRedirects(false);
        http.setConnectTimeout(20000);
        http.setReadTimeout(240000);
        http.setRequestMethod(method);
        http.setRequestProperty("content-type", "application/json");
        if ("anthropic".equals(provider)) {
            http.setRequestProperty("x-api-key", key);
            http.setRequestProperty("anthropic-version", "2023-06-01");
        } else {
            http.setRequestProperty("Authorization", "Bearer " + key);
        }
        if (body != null) {
            http.setDoOutput(true);
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            // Buffered, not streaming: a streamed POST that gets 401 throws instead of returning the error body.
            try (OutputStream out = http.getOutputStream()) {
                out.write(data);
            }
        }
        int status = http.getResponseCode();
        InputStream in = status >= 400 ? http.getErrorStream() : http.getInputStream();
        String text = in == null ? "" : readCapped(in);
        http.disconnect();
        // A provider error may echo the request headers; the key never goes back to the page.
        return new Response(status, text.replace(key, "[已隱藏]"));
    }

    private static String readCapped(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = stream.read(buffer)) > 0) {
                if (out.size() + n > MAX_BODY) throw new IOException("回應超過 8 MiB");
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
