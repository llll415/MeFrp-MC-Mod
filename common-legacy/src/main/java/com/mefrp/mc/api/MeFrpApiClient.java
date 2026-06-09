package com.mefrp.mc.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mefrp.mc.MeFrpConstants;
import com.mefrp.mc.api.model.Node;
import com.mefrp.mc.api.model.NodeStatus;
import com.mefrp.mc.api.model.Proxy;
import com.mefrp.mc.api.model.ProxyList;
import com.mefrp.mc.api.model.UserInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MeFrpApiClient {
    private static final Gson GSON = new Gson();
    private static final int MAX_ATTEMPTS = 3;
    private final String token;

    public MeFrpApiClient(String token) {
        this.token = token;
    }

    public List<Node> listNodes() throws ApiException {
        JsonObject root = request("GET", "/auth/node/list", null);
        JsonArray data = root.getAsJsonArray("data");
        List<Node> nodes = new ArrayList<>();
        for (JsonElement element : data) {
            nodes.add(GSON.fromJson(element, Node.class));
        }
        return nodes;
    }

    public ProxyList listProxies() throws ApiException {
        JsonObject root = request("GET", "/auth/proxy/list", null);
        JsonObject data = root.getAsJsonObject("data");
        ProxyList list = new ProxyList();
        if (data.has("nodes") && data.get("nodes").isJsonArray()) {
            for (JsonElement element : data.getAsJsonArray("nodes")) {
                list.nodes.add(GSON.fromJson(element, Node.class));
            }
        }
        if (data.has("proxies") && data.get("proxies").isJsonArray()) {
            for (JsonElement element : data.getAsJsonArray("proxies")) {
                list.proxies.add(GSON.fromJson(element, Proxy.class));
            }
        }
        return list;
    }

    public void createProxy(int nodeId, String proxyName, int localPort, int remotePort) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("nodeId", nodeId);
        body.addProperty("proxyName", proxyName);
        body.addProperty("localIp", "127.0.0.1");
        body.addProperty("localPort", localPort);
        body.addProperty("remotePort", remotePort);
        body.addProperty("domain", "");
        body.addProperty("proxyType", "tcp");
        body.addProperty("accessKey", "");
        body.addProperty("hostHeaderRewrite", "");
        body.addProperty("httpPlugin", "");
        body.add("requestHeaders", new JsonObject());
        body.addProperty("httpUser", "");
        body.addProperty("httpPassword", "");
        body.addProperty("crtPath", "");
        body.addProperty("keyPath", "");
        body.addProperty("proxyProtocolVersion", "");
        body.addProperty("useEncryption", false);
        body.addProperty("useCompression", false);
        request("POST", "/auth/proxy/create", body);
    }

    public void toggleProxy(int proxyId, boolean disabled) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("proxyId", proxyId);
        body.addProperty("isDisabled", disabled);
        request("POST", "/auth/proxy/toggle", body);
    }

    public void deleteProxy(int proxyId) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("proxyId", proxyId);
        request("POST", "/auth/proxy/delete", body);
    }

    public void kickProxy(int proxyId) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("proxyId", proxyId);
        request("POST", "/auth/proxy/kick", body);
    }

    public void updateProxy(Proxy proxy, int localPort) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("proxyId", proxy.proxyId);
        body.addProperty("proxyName", proxy.proxyName);
        body.addProperty("proxyType", proxy.proxyType == null || proxy.proxyType.trim().isEmpty() ? "tcp" : proxy.proxyType);
        body.addProperty("localIp", proxy.localIp == null || proxy.localIp.trim().isEmpty() ? "127.0.0.1" : proxy.localIp);
        body.addProperty("localPort", localPort);
        body.addProperty("remotePort", proxy.remotePort);
        body.addProperty("nodeId", proxy.nodeId);
        body.addProperty("domain", "");
        body.addProperty("proxyProtocolVersion", "");
        body.addProperty("useEncryption", false);
        body.addProperty("useCompression", false);
        body.addProperty("hostHeaderRewrite", "");
        body.addProperty("headerXFromWhere", "");
        request("POST", "/auth/proxy/update", body);
    }

    public String getFrpToken() throws ApiException {
        JsonObject root = request("GET", "/auth/user/frpToken", null);
        JsonObject data = root.getAsJsonObject("data");
        if (data == null || !data.has("token") || data.get("token").getAsString().trim().isEmpty()) {
            throw new ApiException("获取启动令牌失败");
        }
        return data.get("token").getAsString();
    }

    public UserInfo getUserInfo() throws ApiException {
        JsonObject root = request("GET", "/auth/user/info", null);
        JsonObject data = root.getAsJsonObject("data");
        if (data == null) {
            throw new ApiException("获取用户信息失败");
        }
        return GSON.fromJson(data, UserInfo.class);
    }

    public List<NodeStatus> listNodeStatus() throws ApiException {
        JsonObject root = request("GET", "/auth/node/status", null);
        JsonArray data = root.getAsJsonArray("data");
        List<NodeStatus> statuses = new ArrayList<>();
        for (JsonElement element : data) {
            statuses.add(GSON.fromJson(element, NodeStatus.class));
        }
        return statuses;
    }

    private JsonObject request(String method, String path, JsonObject body) throws ApiException {
        ApiException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpURLConnection connection = (HttpURLConnection) URI.create(MeFrpConstants.API_BASE_URL + path).toURL().openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(20000);
                connection.setRequestMethod(method);
                connection.setRequestProperty("User-Agent", MeFrpConstants.USER_AGENT);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                if (body != null) {
                    byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    connection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                    OutputStream output = connection.getOutputStream();
                    try {
                        output.write(bytes);
                    } finally {
                        output.close();
                    }
                }
                int statusCode = connection.getResponseCode();
                InputStream stream = statusCode >= 200 && statusCode < 400 ? connection.getInputStream() : connection.getErrorStream();
                String text = readAll(stream).trim();
                if (text.startsWith("<")) {
                    throw new ApiException("API 可能被 CDN/WAF 拦截，请稍后再试");
                }
                JsonObject root = GSON.fromJson(text, JsonObject.class);
                if (statusCode < 200 || statusCode >= 300) {
                    throw new ApiException(apiErrorMessage(statusCode, root));
                }
                if (root.has("code") && root.get("code").getAsInt() != 200) {
                    throw new ApiException(apiErrorMessage(root.get("code").getAsInt(), root));
                }
                return root;
            } catch (IOException | RuntimeException e) {
                last = new ApiException("网络连接失败", e);
            } catch (ApiException e) {
                last = e;
            }

            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ApiException("请求被中断", e);
                }
            }
        }
        throw new ApiException(last == null ? "API 调用失败" : last.getMessage() + "（已重试2次）", last);
    }

    private String apiErrorMessage(int code, JsonObject root) {
        if (root != null && root.has("message") && !root.get("message").isJsonNull()) {
            String message = root.get("message").getAsString();
            if (message != null && !message.trim().isEmpty()) {
                return message;
            }
        }
        if (code == 401) {
            return "Token 无效或已过期";
        }
        return "HTTP " + code;
    }

    private String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        byte[] buffer = new byte[4096];
        StringBuilder builder = new StringBuilder();
        try {
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return builder.toString();
        } finally {
            stream.close();
        }
    }
}
