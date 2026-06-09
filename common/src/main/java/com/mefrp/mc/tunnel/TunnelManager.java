package com.mefrp.mc.tunnel;

import com.mefrp.mc.MeFrpConfig;
import com.mefrp.mc.MeFrpConstants;
import com.mefrp.mc.api.ApiException;
import com.mefrp.mc.api.MeFrpApiClient;
import com.mefrp.mc.api.model.Node;
import com.mefrp.mc.api.model.Proxy;
import com.mefrp.mc.api.model.ProxyList;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public class TunnelManager {
    private final MeFrpApiClient api;

    public TunnelManager(MeFrpApiClient api) {
        this.api = api;
    }

    public ProxyList listProxies() throws ApiException {
        return api.listProxies();
    }

    public List<Node> listAvailableNodes() throws ApiException {
        return api.listNodes().stream()
                .filter(node -> node.isOnline && !node.isDisabled && node.supportsTcp())
                .sorted(Comparator.comparingInt(node -> node.nodeId))
                .toList();
    }

    public Optional<Proxy> findManagedProxy(ProxyList list) {
        String tunnelName = MeFrpConfig.getInstance().getTunnelName();
        return list.proxies.stream()
                .filter(proxy -> tunnelName.equals(proxy.proxyName))
                .findFirst();
    }

    public Optional<Node> findFullNode(int nodeId) throws ApiException {
        return api.listNodes().stream()
                .filter(node -> node.nodeId == nodeId)
                .findFirst();
    }

    public void enableProxy(Proxy proxy) throws ApiException {
        api.toggleProxy(proxy.proxyId, false);
    }

    public void disableProxy(Proxy proxy) throws ApiException {
        api.toggleProxy(proxy.proxyId, true);
    }

    public void kickProxy(Proxy proxy) throws ApiException {
        api.kickProxy(proxy.proxyId);
    }

    public void deleteProxy(Proxy proxy) throws ApiException {
        api.deleteProxy(proxy.proxyId);
    }

    public void updateLocalPort(Proxy proxy, int localPort) throws ApiException {
        api.updateProxy(proxy, localPort);
    }

    public void recreateManagedProxy(Proxy proxy, int localPort) throws ApiException {
        api.deleteProxy(proxy.proxyId);
        api.createProxy(proxy.nodeId, MeFrpConstants.TUNNEL_NAME, localPort, proxy.remotePort);
    }

    public Proxy createManagedProxy(Node node, int localPort) throws ApiException {
        int remotePort = chooseRemotePort(node.allowPort);
        api.createProxy(node.nodeId, MeFrpConstants.TUNNEL_NAME, localPort, remotePort);
        ProxyList list = api.listProxies();
        return findManagedProxy(list).orElseThrow(() -> new ApiException("隧道创建成功但未能读取隧道信息"));
    }

    public String connectionAddress(Proxy proxy, ProxyList list) {
        Node node = list.findNode(proxy.nodeId);
        String hostname = node == null ? "" : node.hostname;
        if (hostname == null || hostname.isBlank() || hostname.contains("权限") || hostname.contains("失败") || hostname.contains("错误")) {
            return "节点地址未知:" + proxy.remotePort;
        }
        return hostname + ":" + proxy.remotePort;
    }

    private int chooseRemotePort(String allowPort) throws ApiException {
        if (allowPort == null || allowPort.isBlank()) {
            throw new ApiException("节点未提供可用端口范围");
        }
        String firstRange = allowPort.split("[;,]")[0].trim();
        String[] parts = firstRange.split("-");
        try {
            if (parts.length == 1) {
                int port = Integer.parseInt(parts[0].trim());
                if (port >= 1 && port <= 65535) {
                    return port;
                }
            } else if (parts.length == 2) {
                int min = Integer.parseInt(parts[0].trim());
                int max = Integer.parseInt(parts[1].trim());
                if (min >= 1 && max <= 65535 && min <= max) {
                    return ThreadLocalRandom.current().nextInt(min, max + 1);
                }
            }
        } catch (NumberFormatException ignored) {
        }
        throw new ApiException("节点端口范围无效: " + allowPort);
    }
}
