package com.mefrp.mc.command;

import com.mefrp.mc.MeFrpConfig;
import com.mefrp.mc.api.ApiException;
import com.mefrp.mc.api.MeFrpApiClient;
import com.mefrp.mc.api.model.Node;
import com.mefrp.mc.api.model.NodeStatus;
import com.mefrp.mc.api.model.Proxy;
import com.mefrp.mc.api.model.ProxyList;
import com.mefrp.mc.api.model.UserInfo;
import com.mefrp.mc.frpc.MefrpcManager;
import com.mefrp.mc.platform.MeFrpPlatform;
import com.mefrp.mc.port.PortDetector;
import com.mefrp.mc.tunnel.TunnelManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MeFrpCommandHandler {
    private final MeFrpPlatform platform;
    private final MefrpcManager mefrpcManager;
    private final PortDetector portDetector;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MeFrp-MC-Mod");
        thread.setDaemon(true);
        return thread;
    });
    private PendingNodeSelection pendingSelection;

    public MeFrpCommandHandler(MeFrpPlatform platform, MefrpcManager mefrpcManager) {
        this.platform = platform;
        this.mefrpcManager = mefrpcManager;
        this.portDetector = new PortDetector(platform);
    }

    public PortDetector portDetector() {
        return portDetector;
    }

    public void handleCommand(String[] args) {
        if (platform.isClient() && !platform.isLocalServerHost()) {
            showClientDisabled();
            return;
        }
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            showHelp();
            return;
        }
        String command = args[0].toLowerCase();
        if ("token".equals(command)) {
            handleToken(args);
        } else if ("start".equals(command)) {
            handleStart(args);
        } else if ("stop".equals(command)) {
            runApi(new ApiTask() { public void run() throws Exception { stopTunnel(); } });
        } else if ("node".equals(command)) {
            runApi(new ApiTask() { public void run() throws Exception { beginNodeSwitch(null); } });
        } else if ("status".equals(command)) {
            runApi(new ApiTask() { public void run() throws Exception { showStatus(); } });
        } else if ("userinfo".equals(command)) {
            runApi(new ApiTask() { public void run() throws Exception { showCurrentUserInfo(); } });
        } else if ("sign".equals(command)) {
            runApi(new ApiTask() { public void run() throws Exception { handleSign(); } });
        } else if ("online".equals(command)) {
            handleOnline(args);
        } else if ("select".equals(command)) {
            handleSelect(args);
        } else {
            platform.sendMessage("[MeFrp] 未知命令。输入 /mefrp help 查看可用命令。");
        }
    }

    public void showClientDisabled() {
        platform.sendMessage("[MeFrp] 检测到当前不是主机，MeFrp-MC-Mod 功能已禁用。");
    }

    public void showJoinHelp() {
        platform.sendMessage("[MeFrp] MeFrp-MC-Mod");
        MeFrpConfig config = MeFrpConfig.getInstance();
        if (!config.hasToken()) {
            platform.sendMessage("[MeFrp] 当前没有绑定访问密钥。");
            platform.sendMessage("[MeFrp] 请前往 MeFrp 官网用户中心获取访问密钥。");
            platform.sendMessage("[MeFrp] 然后输入 /mefrp token <访问密钥> 完成绑定。");
            platform.sendMessage("[MeFrp] 输入 /mefrp help 查看使用方法。");
            showExternalLanControllerHint();
            return;
        }
        executor.execute(new Runnable() { public void run() {
            try {
                UserInfo user = new MeFrpApiClient(config.getToken()).getUserInfo();
                showUserInfo(user);
            } catch (Exception e) {
                platform.sendMessage("[MeFrp] 无法获取账号信息: " + e.getMessage());
                platform.sendMessage("[MeFrp] 输入 /mefrp help 查看使用方法。");
            }
        }});
    }

    public void showHelp() {
        platform.sendMessage("[MeFrp] ===== MeFrp-MC-Mod 使用帮助 =====");
        platform.sendMessage("[MeFrp] 首次使用：1. /mefrp token <访问令牌>  2. 打开局域网  3. /mefrp start");
        platform.sendMessage("[MeFrp] /mefrp start [端口] - 开始联机；不填端口会自动检测");
        platform.sendMessage("[MeFrp] /mefrp stop - 停止联机");
        platform.sendMessage("[MeFrp] /mefrp status - 查看当前状态");
        platform.sendMessage("[MeFrp] /mefrp userinfo - 查看账号、流量、限速和签到状态");
        platform.sendMessage("[MeFrp] /mefrp sign - 查看签到说明");
        platform.sendMessage("[MeFrp] /mefrp node - 选择节点并启动/切换隧道");
        if (shouldExposeOnlineModeCommand()) {
            platform.sendMessage("[MeFrp] /mefrp online off - 关闭正版验证；/mefrp online on - 开启正版验证");
        }
        showExternalLanControllerHint();
    }

    public void showOnlineModeHintIfAvailable() {
        if (showExternalLanControllerHint()) {
            return;
        }
        if (!shouldExposeOnlineModeCommand()) {
            return;
        }
        platform.sendMessage("[MeFrp] 当前局域网正版验证: " + (platform.getLanOnlineMode() ? "已开启" : "已关闭"));
        if (platform.getLanOnlineMode()) {
            platform.sendMessage("[MeFrp] 非正版玩家可能无法加入。如需关闭，请输入 /mefrp online off");
        } else {
            platform.sendMessage("[MeFrp] 非正版玩家可以尝试加入。");
        }
    }

    private void handleToken(String[] args) {
        if (args.length < 2 || args[1].trim().isEmpty()) {
            platform.sendMessage("[MeFrp] 用法: /mefrp token <访问令牌>");
            platform.sendMessage("[MeFrp] 访问令牌可在 MeFrp 官网个人中心获取。");
            return;
        }
        MeFrpConfig.getInstance().setToken(args[1].trim());
        platform.sendMessage("[MeFrp] 访问令牌已保存。现在可以打开局域网后输入 /mefrp start 开始联机。");
    }

    private void handleStart(String[] args) {
        Integer explicitPort = null;
        if (args.length >= 2) {
            try {
                explicitPort = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                platform.sendMessage("[MeFrp] 端口无效，请输入 1-65535 之间的数字");
                return;
            }
        }
        Integer finalExplicitPort = explicitPort;
        runApi(new ApiTask() { public void run() throws Exception { startTunnel(finalExplicitPort); } });
    }

    private void handleOnline(String[] args) {
        if (!shouldExposeOnlineModeCommand()) {
            if (!showExternalLanControllerHint()) {
                platform.sendMessage("[MeFrp] 当前环境不支持 MeFrp 自带正版验证切换功能。");
            }
            return;
        }
        if (args.length < 2) {
            platform.sendMessage("[MeFrp] 用法: /mefrp online <on|off>");
            return;
        }
        boolean enabled;
        if ("on".equalsIgnoreCase(args[1]) || "true".equalsIgnoreCase(args[1])) {
            enabled = true;
        } else if ("off".equalsIgnoreCase(args[1]) || "false".equalsIgnoreCase(args[1])) {
            enabled = false;
        } else {
            platform.sendMessage("[MeFrp] 用法: /mefrp online <on|off>");
            return;
        }
        if (platform.setLanOnlineMode(enabled)) {
            platform.sendMessage("[MeFrp] 局域网正版验证已" + (enabled ? "开启" : "关闭"));
        } else {
            platform.sendMessage("[MeFrp] 切换失败。请确认已经打开局域网联机。");
        }
    }

    private void handleSelect(String[] args) {
        if (args.length < 2 || pendingSelection == null) {
            platform.sendMessage("[MeFrp] 当前没有待选择的节点");
            return;
        }
        try {
            int index = Integer.parseInt(args[1]) - 1;
            if (index < 0 || index >= pendingSelection.nodes.size()) {
                platform.sendMessage("[MeFrp] 节点编号无效");
                return;
            }
            Node node = pendingSelection.nodes.get(index);
            PendingNodeSelection selection = pendingSelection;
            pendingSelection = null;
            runApi(new ApiTask() { public void run() throws Exception { createWithSelectedNode(selection, node); } });
        } catch (NumberFormatException e) {
            platform.sendMessage("[MeFrp] 节点编号无效");
        }
    }

    private void startTunnel(Integer explicitPort) throws ApiException {
        MeFrpConfig config = MeFrpConfig.getInstance();
        if (!config.hasToken()) {
            platform.sendMessage("[MeFrp] 还没有设置访问令牌。");
            platform.sendMessage("[MeFrp] 请先输入 /mefrp token <你的访问令牌>");
            platform.sendMessage("[MeFrp] 访问令牌可在 MeFrp 官网个人中心获取。");
            return;
        }
        OptionalInt port = portDetector.detect(explicitPort);
        if (!port.isPresent()) {
            platform.sendMessage("[MeFrp] 没有检测到局域网端口。");
            platform.sendMessage("[MeFrp] 请先在暂停菜单点击“对局域网开放”。");
            platform.sendMessage("[MeFrp] 如果你知道端口，也可以输入 /mefrp start <端口>。");
            return;
        }
        TunnelManager tunnels = tunnelManager();
        ProxyList list = tunnels.listProxies();
        Optional<Proxy> existing = tunnels.findManagedProxy(list);
        if (!existing.isPresent()) {
            showNodeSelection(tunnels.listAvailableNodes(), port.getAsInt(), null, "请选择一个在线节点来创建隧道");
            return;
        }
        Proxy proxy = existing.get();
        Node node = tunnels.findFullNode(proxy.nodeId).orElse(list.findNode(proxy.nodeId));
        boolean nodeUsable = node != null && node.isOnline && !node.isDisabled && node.supportsTcp();
        if (!nodeUsable) {
            String reason = "当前隧道节点不可用，请重新选择节点";
            showNodeSelection(tunnels.listAvailableNodes(), port.getAsInt(), proxy, reason);
            return;
        }
        if (proxy.localPort != port.getAsInt()) {
            platform.sendMessage("[MeFrp] 检测到本地端口变化，正在重建隧道并保留远程端口 " + proxy.remotePort);
            tunnels.recreateManagedProxy(proxy, port.getAsInt());
            list = tunnels.listProxies();
            proxy = tunnels.findManagedProxy(list).orElse(proxy);
        }
        if (isTunnelAlreadyRunning(proxy, port.getAsInt())) {
            showAlreadyStarted(tunnels.connectionAddress(proxy, list));
            return;
        }
        tunnels.enableProxy(proxy);
        ProxyList refreshed = tunnels.listProxies();
        Proxy enabled = tunnels.findManagedProxy(refreshed).orElse(proxy);
        try {
            startMefrpcAndShow(tunnels, enabled, refreshed);
        } catch (Exception e) {
            throw new ApiException(e.getMessage(), e);
        }
    }

    private void stopTunnel() throws ApiException {
        Proxy existing = currentProxyOrWarn();
        if (existing == null) {
            return;
        }
        TunnelManager tunnels = tunnelManager();
        mefrpcManager.stop();
        Proxy current = waitForOffline(tunnels, existing, 5000L);
        if (current.isOnline) {
            try {
                platform.sendMessage("[MeFrp] 远端仍显示在线，正在强制下线。");
                tunnels.kickProxy(current);
                current = waitForOffline(tunnels, current, 5000L);
            } catch (Exception e) {
                platform.sendMessage("[MeFrp] 强制下线失败: " + e.getMessage());
            }
        }
        try {
            current = refreshManagedProxy(tunnels, current);
            if (current.isOnline) {
                platform.sendMessage("[MeFrp] 停止未完成：远端仍显示在线。请稍等几秒后再次输入 /mefrp stop。");
                return;
            }
            if (current.isDisabled) {
                platform.sendMessage("[MeFrp] 隧道被禁用，正在恢复启用状态。");
                tunnels.enableProxy(current);
            }
        } catch (Exception e) {
            platform.sendMessage("[MeFrp] 恢复隧道启用状态失败: " + e.getMessage());
        }
        platform.sendMessage("[MeFrp] 隧道已停止。好友将无法继续通过连接地址进入。");
    }

    private void beginNodeSwitch(Integer portOverride) throws ApiException {
        MeFrpConfig config = MeFrpConfig.getInstance();
        if (!config.hasToken()) {
            platform.sendMessage("[MeFrp] 还没有设置访问令牌。");
            platform.sendMessage("[MeFrp] 请先输入 /mefrp token <你的访问令牌>");
            platform.sendMessage("[MeFrp] 访问令牌可在 MeFrp 官网个人中心获取。");
            return;
        }
        OptionalInt detectedPort = portDetector.detect(portOverride);
        if (!detectedPort.isPresent()) {
            platform.sendMessage("[MeFrp] 没有检测到局域网端口。");
            platform.sendMessage("[MeFrp] 请先在暂停菜单点击“对局域网开放”。");
            platform.sendMessage("[MeFrp] 如果你知道端口，也可以输入 /mefrp start <端口>。");
            return;
        }
        TunnelManager tunnels = tunnelManager();
        ProxyList list = tunnels.listProxies();
        Proxy existing = tunnels.findManagedProxy(list).orElse(null);
        int port = detectedPort.getAsInt();
        String title = existing == null ? "请选择一个在线节点来启动隧道" : "请选择一个新的在线节点并启动隧道";
        showNodeSelection(tunnels.listAvailableNodes(), port, existing, title);
    }

    private void showCurrentUserInfo() throws ApiException {
        MeFrpConfig config = MeFrpConfig.getInstance();
        if (!config.hasToken()) {
            platform.sendMessage("[MeFrp] 当前没有绑定访问密钥。");
            platform.sendMessage("[MeFrp] 请前往 MeFrp 官网用户中心获取访问密钥。");
            platform.sendMessage("[MeFrp] 然后输入 /mefrp token <访问密钥> 完成绑定。");
            return;
        }
        showUserInfo(new MeFrpApiClient(config.getToken()).getUserInfo());
    }

    private void handleSign() throws ApiException {
        MeFrpConfig config = MeFrpConfig.getInstance();
        if (!config.hasToken()) {
            platform.sendMessage("[MeFrp] 当前没有绑定访问密钥。");
            platform.sendMessage("[MeFrp] 请前往 MeFrp 官网用户中心获取访问密钥。");
            platform.sendMessage("[MeFrp] 然后输入 /mefrp token <访问密钥> 完成绑定。");
            return;
        }
        platform.sendMessage("[MeFrp] 签到需要进行人机验证。");
        platform.sendMessage("[MeFrp] 请前往 MeFrp 官网用户中心完成签到。");
        platform.sendMessage("[MeFrp] 签到后可输入 /mefrp userinfo 刷新账号信息。");
    }

    private void showStatus() throws ApiException {
        MeFrpConfig config = MeFrpConfig.getInstance();
        platform.sendMessage("[MeFrp] ===== 当前状态 =====");
        platform.sendMessage("[MeFrp] 访问令牌: " + (config.hasToken() ? "已设置" : "未设置"));
        if (!config.hasToken()) {
            platform.sendMessage("[MeFrp] 隧道: 未查询。请先输入 /mefrp token <访问令牌>");
            return;
        }
        TunnelManager tunnels = tunnelManager();
        ProxyList list = tunnels.listProxies();
        Optional<Proxy> proxy = tunnels.findManagedProxy(list);
        if (!proxy.isPresent()) {
            platform.sendMessage("[MeFrp] 隧道: 未创建。输入 /mefrp start 创建并启动。");
            return;
        }
        Proxy current = proxy.get();
        platform.sendMessage("[MeFrp] 本地端口: " + current.localPort);
        platform.sendMessage("[MeFrp] 隧道: " + (current.isDisabled ? "已停止" : "已启用") + (current.isOnline ? " / 在线" : " / 离线"));
        platform.sendMessage("[MeFrp] 节点: " + nodeName(list.findNode(current.nodeId)));
        platform.sendCopyableText("[MeFrp] 连接地址: ", tunnels.connectionAddress(current, list), "点击复制连接地址");
        showMefrpcStatus();
    }

    private Proxy currentProxyOrWarn() throws ApiException {
        MeFrpConfig config = MeFrpConfig.getInstance();
        if (!config.hasToken()) {
            platform.sendMessage("[MeFrp] 还没有设置访问令牌。");
            platform.sendMessage("[MeFrp] 请先输入 /mefrp token <你的访问令牌>");
            return null;
        }
        TunnelManager tunnels = tunnelManager();
        ProxyList list = tunnels.listProxies();
        Optional<Proxy> proxy = tunnels.findManagedProxy(list);
        if (!proxy.isPresent()) {
            platform.sendMessage("[MeFrp] 隧道还没有创建。请先打开局域网，然后输入 /mefrp start。");
            return null;
        }
        return proxy.get();
    }

    private void showNodeSelection(List<Node> nodes, int port, Proxy oldProxy, String title) {
        List<Node> available = new ArrayList<>(nodes);
        if (available.isEmpty()) {
            platform.sendMessage("[MeFrp] 没有可用在线节点，请稍后再试。");
            return;
        }
        Map<Integer, NodeStatus> statuses = nodeStatusesById();
        pendingSelection = new PendingNodeSelection(available, statuses, port, oldProxy);
        platform.sendMessage("[MeFrp] " + title + ":");
        for (int i = 0; i < available.size(); i++) {
            Node node = available.get(i);
            String vipLabel = node.isVip() ? " [VIP]" : "";
            NodeStatus status = statuses.get(node.nodeId);
            String overloadLabel = isOverloaded(status) ? " [过载]" : "";
            String text = (i + 1) + ". #" + node.nodeId + " " + node.name + vipLabel + overloadLabel + " | " + regionName(node.region) + " | " + node.bandwidth + " | 负载 " + loadText(status) + " [点击选择]";
            platform.sendClickableCommand(text, "/mefrp select " + (i + 1), "选择节点 " + node.name);
        }
        platform.sendMessage("[MeFrp] 也可以输入 /mefrp select <编号> 选择节点。");
    }

    private Map<Integer, NodeStatus> nodeStatusesById() {
        Map<Integer, NodeStatus> result = new HashMap<>();
        try {
            for (NodeStatus status : new MeFrpApiClient(MeFrpConfig.getInstance().getToken()).listNodeStatus()) {
                result.put(status.nodeId, status);
            }
        } catch (Exception e) {
            platform.logWarn("[MeFrp] failed to load node status: " + e.getMessage());
        }
        return result;
    }

    private String loadText(NodeStatus status) {
        if (status == null || status.loadPercent < 0) {
            return "未知";
        }
        return Math.min(100, status.loadPercent) + "%";
    }

    private boolean isOverloaded(NodeStatus status) {
        return status != null && status.loadPercent > 90;
    }

    private void createWithSelectedNode(PendingNodeSelection selection, Node node) throws ApiException {
        NodeStatus status = selection.nodeStatuses.get(node.nodeId);
        if (isOverloaded(status)) {
            platform.sendMessage("[MeFrp] 该节点当前负载 " + loadText(status) + "，已过载。");
            platform.sendMessage("[MeFrp] 过载节点不可选择，请换一个负载较低的节点。");
            return;
        }
        if (node.isVip()) {
            UserInfo user = new MeFrpApiClient(MeFrpConfig.getInstance().getToken()).getUserInfo();
            if (!user.canUseVipNodes()) {
                platform.sendMessage("[MeFrp] 该节点为 VIP 专属节点。");
                platform.sendMessage("[MeFrp] 当前账号身份: " + blankFallback(user.friendlyGroup, blankFallback(user.group, "未知")));
                platform.sendMessage("[MeFrp] 普通用户无法使用该节点，请选择普通节点。");
                return;
            }
        }
        TunnelManager tunnels = tunnelManager();
        if (selection.oldProxy != null) {
            tunnels.deleteProxy(selection.oldProxy);
        }
        Proxy created = tunnels.createManagedProxy(node, selection.port);
        tunnels.enableProxy(created);
        ProxyList refreshed = tunnels.listProxies();
        Proxy current = tunnels.findManagedProxy(refreshed).orElse(created);
        try {
            startMefrpcAndShow(tunnels, current, refreshed);
        } catch (Exception e) {
            throw new ApiException(e.getMessage(), e);
        }
    }

    private void startMefrpcAndShow(TunnelManager tunnels, Proxy proxy, ProxyList list) throws Exception {
        ProxyList latest = tunnels.listProxies();
        proxy = tunnels.findManagedProxy(latest).orElse(proxy);
        list = latest;
        String address = tunnels.connectionAddress(proxy, list);
        if (address.startsWith("节点地址未知")) {
            throw new ApiException("无法获取节点连接地址，请尝试切换节点");
        }
        String frpToken = new MeFrpApiClient(MeFrpConfig.getInstance().getToken()).getFrpToken();
        mefrpcManager.stop();
        proxy = prepareProxyForStart(tunnels, proxy);
        try {
            platform.sendMessage("[MeFrp] 正在启用隧道...");
            tunnels.enableProxy(proxy);
            Thread.sleep(1200L);
        } catch (Exception e) {
            if (!isForbiddenFailure(e.getMessage())) {
                throw new ApiException("启用隧道失败: " + e.getMessage(), e);
            }
            platform.sendMessage("[MeFrp] 启用隧道被拒绝，正在清理旧连接后重试...");
            try {
                tunnels.kickProxy(proxy);
                Thread.sleep(1000L);
                tunnels.enableProxy(proxy);
                Thread.sleep(1200L);
            } catch (Exception retryError) {
                throw new ApiException("启用隧道重试失败: " + retryError.getMessage(), retryError);
            }
        }
        try {
            platform.sendMessage("[MeFrp] 正在启动 mefrpc...");
            mefrpcManager.start(frpToken, proxy.proxyId);
        } catch (Exception e) {
            if (!isAlreadyOnlineFailure(e.getMessage())) {
                throw new ApiException("mefrpc 启动失败: " + e.getMessage(), e);
            }
            platform.sendMessage("[MeFrp] mefrpc 提示隧道当前在线，正在清理旧连接后重试...");
            try {
                tunnels.kickProxy(proxy);
                Thread.sleep(1000L);
                tunnels.enableProxy(proxy);
                Thread.sleep(1200L);
                mefrpcManager.start(frpToken, proxy.proxyId);
            } catch (Exception retryError) {
                throw new ApiException("mefrpc 重试启动失败: " + retryError.getMessage(), retryError);
            }
        }
        showStarted(address);
    }

    private Proxy prepareProxyForStart(TunnelManager tunnels, Proxy proxy) throws ApiException {
        Proxy current = refreshManagedProxy(tunnels, proxy);
        if (current.isOnline) {
            try {
                platform.sendMessage("[MeFrp] 远端仍显示在线，正在清理旧连接...");
                tunnels.kickProxy(current);
                current = waitForOffline(tunnels, current, 5000L);
            } catch (Exception e) {
                platform.sendMessage("[MeFrp] 清理旧连接失败，将继续尝试启动: " + e.getMessage());
            }
        }
        current = refreshManagedProxy(tunnels, current);
        if (current.isDisabled) {
            tunnels.enableProxy(current);
            try {
                Thread.sleep(1200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            current = refreshManagedProxy(tunnels, current);
        }
        return current;
    }

    private Proxy waitForOffline(TunnelManager tunnels, Proxy fallback, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        Proxy current = fallback;
        while (System.currentTimeMillis() < deadline) {
            try {
                current = refreshManagedProxy(tunnels, current);
                if (!current.isOnline) {
                    return current;
                }
                Thread.sleep(500L);
            } catch (Exception e) {
                return current;
            }
        }
        return current;
    }

    private Proxy refreshManagedProxy(TunnelManager tunnels, Proxy fallback) throws ApiException {
        ProxyList refreshed = tunnels.listProxies();
        return tunnels.findManagedProxy(refreshed).orElse(fallback);
    }

    private boolean isAlreadyOnlineFailure(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("当前在线") || message.contains("already online") || message.contains("is online");
    }

    private boolean isForbiddenFailure(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("403") || message.toLowerCase(java.util.Locale.ROOT).contains("forbidden");
    }

    private void showStarted(String address) {
        platform.sendMessage("[MeFrp] 隧道已启动！好友可以用下面的地址加入。");
        platform.sendCopyableText("[MeFrp] 连接地址: ", address, "点击复制连接地址");
        platform.sendMessage("[MeFrp] 点击连接地址可复制，然后发给好友。");
        showOnlineModeHintIfAvailable();
    }

    private boolean isTunnelAlreadyRunning(Proxy proxy, int detectedPort) {
        return proxy.localPort == detectedPort
                && !proxy.isDisabled
                && proxy.isOnline
                && mefrpcManager.isRunning();
    }

    private void showAlreadyStarted(String address) {
        platform.sendMessage("[MeFrp] 隧道已经启动，无需重复启动。");
        platform.sendCopyableText("[MeFrp] 连接地址: ", address, "点击复制连接地址");
    }

    private void showMefrpcStatus() {
        MefrpcManager.ProcessStatus status = mefrpcManager.processStatus();
        platform.sendMessage("[MeFrp] mefrpc: " + (status.running ? "运行中" : "未运行"));
        platform.sendMessage("[MeFrp] PID: " + formatPid(status));
        platform.sendMessage("[MeFrp] 内存: " + formatMemory(status.memoryBytes));
    }

    private String formatPid(MefrpcManager.ProcessStatus status) {
        if (!status.running) {
            return "无";
        }
        return status.pid > 0L ? String.valueOf(status.pid) : "未知";
    }

    private String formatMemory(long memoryBytes) {
        if (memoryBytes < 0L) {
            return "未知";
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", memoryBytes / 1024.0D / 1024.0D);
    }

    private void showUserInfo(UserInfo user) {
        platform.sendMessage("[MeFrp] 账号: " + blankFallback(user.username, "未知"));
        platform.sendMessage("[MeFrp] 身份: " + blankFallback(user.friendlyGroup, blankFallback(user.group, "未知")));
        platform.sendMessage("[MeFrp] 账号状态: " + userStatus(user.status));
        platform.sendMessage("[MeFrp] 实名状态: " + (user.isRealname ? "已实名" : "未实名"));
        if (user.todaySigned) {
            platform.sendMessage("[MeFrp] 今日签到: 已签到");
        } else {
            platform.sendClickableCommand("[MeFrp] 今日签到: 未签到 ", "[点击签到]", "/mefrp sign", "点击执行今日签到");
        }
        platform.sendMessage("[MeFrp] 剩余流量: " + formatTrafficMb(user.traffic));
        platform.sendMessage("[MeFrp] 限速: 上行 " + formatSpeed(user.outBound) + " / 下行 " + formatSpeed(user.inBound));
        platform.sendMessage("[MeFrp] 隧道数量: " + user.usedProxies + " / " + user.maxProxies);
        platform.sendMessage("[MeFrp] 输入 /mefrp userinfo 可随时查看账号信息。");
        if (user.status == 1) {
            platform.sendMessage("[MeFrp] 当前账号已被封禁，无法正常使用 MeFrp。");
        } else if (user.status == 2) {
            platform.sendMessage("[MeFrp] 当前账号流量已超限，可能无法正常启动隧道。");
        }
        platform.sendMessage("[MeFrp] 输入 /mefrp help 查看使用方法。");
        showExternalLanControllerHint();
    }

    private String userStatus(int status) {
        switch (status) {
            case 0:
                return "正常";
            case 1:
                return "封禁";
            case 2:
                return "流量超限";
            default:
                return "未知状态 " + status;
        }
    }

    private String formatTrafficMb(long trafficMb) {
        if (trafficMb < 1024L) {
            return trafficMb + " MB";
        }
        return String.format(java.util.Locale.ROOT, "%.1f GB", trafficMb / 1024.0D);
    }

    private String formatSpeed(int speedKbps) {
        double mbps = speedKbps * 8.0D / 1024.0D;
        if (mbps == Math.rint(mbps)) {
            return String.format(java.util.Locale.ROOT, "%.0f Mbps", mbps);
        }
        return String.format(java.util.Locale.ROOT, "%.1f Mbps", mbps);
    }

    private String blankFallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private TunnelManager tunnelManager() {
        return new TunnelManager(new MeFrpApiClient(MeFrpConfig.getInstance().getToken()));
    }

    private void runApi(ApiTask task) {
        executor.execute(new Runnable() { public void run() {
            try {
                task.run();
            } catch (ApiException e) {
                platform.sendMessage("[MeFrp] API 请求失败: " + e.getMessage());
            } catch (Exception e) {
                platform.sendMessage("[MeFrp] 操作失败: " + e.getMessage());
            }
        }});
    }

    private boolean shouldExposeOnlineModeCommand() {
        return platform.isClient() && platform.supportsLanOnlineModeControl() && !platform.hasExternalLanOnlineModeController();
    }

    private boolean showExternalLanControllerHint() {
        if (!platform.hasExternalLanOnlineModeController()) {
            return false;
        }
        String name = platform.externalLanOnlineModeControllerName();
        if (name == null || name.trim().isEmpty()) {
            name = "其他可管理正版验证开关的 Mod";
        }
        platform.sendMessage("[MeFrp] 检测到 " + name + "，MeFrp 不接管局域网正版验证开关。");
        platform.sendMessage("[MeFrp] 请在该 Mod 的局域网设置界面中调整正版验证；/mefrp start 仍会负责启动穿透。");
        return true;
    }

    private String nodeName(Node node) {
        return node == null ? "未知" : node.name;
    }

    private String regionName(String region) {
        if ("cn".equals(region)) {
            return "中国大陆";
        }
        if ("cnos".equals(region)) {
            return "港澳台";
        }
        if ("oversea".equals(region)) {
            return "海外";
        }
        return region == null || region.trim().isEmpty() ? "未知区域" : region;
    }

    private interface ApiTask {
        void run() throws Exception;
    }

    private static class PendingNodeSelection {
        private final List<Node> nodes;
        private final Map<Integer, NodeStatus> nodeStatuses;
        private final int port;
        private final Proxy oldProxy;

        private PendingNodeSelection(List<Node> nodes, Map<Integer, NodeStatus> nodeStatuses, int port, Proxy oldProxy) {
            this.nodes = nodes;
            this.nodeStatuses = nodeStatuses;
            this.port = port;
            this.oldProxy = oldProxy;
        }
    }
}
