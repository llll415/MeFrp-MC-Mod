package com.mefrp.mc.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

public class MeFrpFabricClientBridge implements MeFrpFabricPlatform.ClientBridge {
    @Override
    public void runOnClient(Runnable runnable) {
        Minecraft.getInstance().execute(runnable);
    }

    @Override
    public void sendClientMessage(Component component) {
        if (Minecraft.getInstance().gui != null) {
            Minecraft.getInstance().gui.getChat().addMessage(component);
        }
    }

    @Override
    public boolean setLanOnlineMode(boolean enabled) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || !server.isPublished()) {
            return false;
        }
        server.setUsesAuthentication(enabled);
        return true;
    }

    @Override
    public boolean getLanOnlineMode() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        return server == null || server.usesAuthentication();
    }

    @Override
    public int getPublishedLanPort() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || !server.isPublished()) {
            return -1;
        }
        return server.getPort();
    }

    @Override
    public boolean isLocalServerHost() {
        return Minecraft.getInstance().getSingleplayerServer() != null;
    }
}
