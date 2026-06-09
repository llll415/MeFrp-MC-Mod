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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class MeFrpApiClient {
    private static final Gson GSON = new Gson();
    private static final int MAX_ATTEMPTS = 3;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
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
        body.addProperty("proxyType", proxy.proxyType == null || proxy.proxyType.isBlank() ? "tcp" : proxy.proxyType);
        body.addProperty("localIp", proxy.localIp == null || proxy.localIp.isBlank() ? "127.0.0.1" : proxy.localIp);
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
        if (data == null || !data.has("token") || data.get("token").getAsString().isBlank()) {
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
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(MeFrpConstants.API_BASE_URL + path))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", MeFrpConstants.USER_AGENT)
                        .header("Authorization", "Bearer " + token);
                if (body == null) {
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
                } else {
                    builder.header("Content-Type", "application/json");
                    builder.method(method, HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
                }

                HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                String text = response.body() == null ? "" : response.body().trim();
                if (text.startsWith("<")) {
                    throw new ApiException("API 可能被 CDN/WAF 拦截，请稍后再试");
                }
                JsonObject root = GSON.fromJson(text, JsonObject.class);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new ApiException(apiErrorMessage(response.statusCode(), root));
                }
                if (root.has("code") && root.get("code").getAsInt() != 200) {
                    throw new ApiException(apiErrorMessage(root.get("code").getAsInt(), root));
                }
                return root;
            } catch (IOException | InterruptedException | RuntimeException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
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
            if (message != null && !message.isBlank()) {
                return message;
            }
        }
        if (code == 401) {
            return "Token 无效或已过期";
        }
        return "HTTP " + code;
    }
}
