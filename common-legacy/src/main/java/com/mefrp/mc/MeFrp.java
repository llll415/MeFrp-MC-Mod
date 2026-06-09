package com.mefrp.mc;

import com.mefrp.mc.command.MeFrpCommandHandler;
import com.mefrp.mc.frpc.MefrpcManager;
import com.mefrp.mc.platform.MeFrpPlatform;

public final class MeFrp {
    private static MeFrpPlatform platform;
    private static MeFrpCommandHandler commandHandler;
    private static MefrpcManager mefrpcManager;
    private static long lastPlayerReadyMillis;

    private MeFrp() {
    }

    public static void init(MeFrpPlatform platform) {
        MeFrp.platform = platform;
        MeFrpConfig.init(platform.gameDirectory());
        mefrpcManager = new MefrpcManager(platform);
        commandHandler = new MeFrpCommandHandler(platform, mefrpcManager);
        Runtime.getRuntime().addShutdownHook(new Thread(MeFrp::onShutdown, "MeFrp-MC-Mod shutdown"));
    }

    public static MeFrpPlatform platform() {
        return platform;
    }

    public static MeFrpCommandHandler commandHandler() {
        return commandHandler;
    }

    public static MefrpcManager mefrpcManager() {
        return mefrpcManager;
    }

    public static void onChatMessage(String message) {
        if (commandHandler.portDetector().acceptChatMessage(message)) {
            onLanOpened();
        }
    }

    public static void onLanOpened() {
        commandHandler.portDetector().markLanOpened();
        commandHandler.showOnlineModeHintIfAvailable();
    }

    public static void onPlayerReady() {
        long now = System.currentTimeMillis();
        if (now - lastPlayerReadyMillis < 3000L) {
            return;
        }
        lastPlayerReadyMillis = now;
        if (platform.isClient() && !platform.isLocalServerHost()) {
            commandHandler.showClientDisabled();
            return;
        }
        commandHandler.showJoinHelp();
    }

    public static void onShutdown() {
        if (mefrpcManager != null) {
            mefrpcManager.stop();
        }
    }
}
