package com.mefrp.mc.platform;

import java.nio.file.Path;

public interface MeFrpPlatform {
    Path gameDirectory();

    boolean isClient();

    boolean isDedicatedServer();

    boolean isModLoaded(String modId);

    void runOnMainThread(Runnable runnable);

    void sendMessage(String message);

    void sendClickableCommand(String text, String command, String hoverText);

    default void sendClickableCommand(String label, String text, String command, String hoverText) {
        sendMessage(label + text);
    }

    default void sendCopyableText(String label, String value, String hoverText) {
        sendMessage(label + value);
    }

    default void logInfo(String message) {
        System.out.println(message);
    }

    default void logWarn(String message) {
        System.err.println(message);
    }

    default boolean setLanOnlineMode(boolean enabled) {
        return false;
    }

    default boolean getLanOnlineMode() {
        return true;
    }

    default boolean supportsLanOnlineModeControl() {
        return false;
    }

    default int getPublishedLanPort() {
        return -1;
    }

    default boolean isLocalServerHost() {
        return !isClient();
    }

    default boolean hasExternalLanOnlineModeController() {
        return isModLoaded("lanserverproperties") || isModLoaded("mcwifipnp") || classExists("rikka.lanserverproperties.LanServerProperties") || classExists("io.github.satxm.mcwifipnp.MCWiFiPnP");
    }

    default String externalLanOnlineModeControllerName() {
        if (isModLoaded("lanserverproperties") || classExists("rikka.lanserverproperties.LanServerProperties")) {
            return "lanserverproperties";
        }
        if (isModLoaded("mcwifipnp") || classExists("io.github.satxm.mcwifipnp.MCWiFiPnP")) {
            return "mcwifipnp";
        }
        return "";
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, MeFrpPlatform.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            try {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                return loader != null && Class.forName(name, false, loader) != null;
            } catch (ClassNotFoundException ignoredAgain) {
                return false;
            }
        }
    }
}
