package com.mefrp.mc.forge;

import com.mefrp.mc.platform.MeFrpPlatform;
import io.netty.channel.ChannelFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkSystem;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraftforge.fml.common.Loader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;

public class MeFrpForgePlatform implements MeFrpPlatform {
    private static final Logger LOGGER = LogManager.getLogger("MeFrp-MC-Mod");

    @Override
    public Path gameDirectory() {
        return Paths.get(Minecraft.getMinecraft().gameDir.toURI());
    }

    @Override
    public boolean isClient() {
        return true;
    }

    @Override
    public boolean isDedicatedServer() {
        return false;
    }

    @Override
    public boolean isLocalServerHost() {
        return Minecraft.getMinecraft().getIntegratedServer() != null;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return Loader.isModLoaded(modId);
    }

    @Override
    public void runOnMainThread(final Runnable runnable) {
        Minecraft.getMinecraft().addScheduledTask(runnable);
    }

    @Override
    public void sendMessage(final String message) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                sendComponent(formatMessage(message));
            }
        });
    }

    @Override
    public void sendClickableCommand(final String text, final String command, final String hoverText) {
        final TextComponentString component = new TextComponentString(text);
        component.setStyle(new Style()
                .setColor(commandColor(text))
                .setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponentString(hoverText))));
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                sendComponent(component);
            }
        });
    }

    @Override
    public void sendClickableCommand(String label, String text, String command, String hoverText) {
        TextComponentString component = formatMessage(label);
        TextComponentString clickable = new TextComponentString(text);
        clickable.setStyle(new Style()
                .setColor(TextFormatting.GOLD)
                .setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponentString(hoverText))));
        component.appendSibling(clickable);
        final TextComponentString finalComponent = component;
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                sendComponent(finalComponent);
            }
        });
    }

    @Override
    public void sendCopyableText(String label, String value, String hoverText) {
        String encoded = Base64.getUrlEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        TextComponentString component = formatMessage(label);
        TextComponentString copyable = new TextComponentString(value);
        copyable.setStyle(new Style()
                .setColor(TextFormatting.GREEN)
                .setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mefrp-copy " + encoded))
                .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponentString(hoverText))));
        component.appendSibling(copyable);
        final TextComponentString finalComponent = component;
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                sendComponent(finalComponent);
            }
        });
    }

    @Override
    public void logInfo(String message) {
        LOGGER.info(message);
    }

    @Override
    public void logWarn(String message) {
        LOGGER.warn(message);
    }

    @Override
    public boolean setLanOnlineMode(boolean enabled) {
        IntegratedServer server = Minecraft.getMinecraft().getIntegratedServer();
        if (server == null || !server.getPublic()) {
            return false;
        }
        server.setOnlineMode(enabled);
        return true;
    }

    @Override
    public boolean getLanOnlineMode() {
        IntegratedServer server = Minecraft.getMinecraft().getIntegratedServer();
        return server == null || server.isServerInOnlineMode();
    }

    @Override
    public boolean supportsLanOnlineModeControl() {
        return true;
    }

    @Override
    public int getPublishedLanPort() {
        IntegratedServer server = Minecraft.getMinecraft().getIntegratedServer();
        if (server == null || !server.getPublic()) {
            return -1;
        }
        int endpointPort = findEndpointPort(server.getNetworkSystem());
        if (validPort(endpointPort)) {
            return endpointPort;
        }
        int serverPort = server.getServerPort();
        return validPort(serverPort) ? serverPort : -1;
    }

    private TextComponentString formatMessage(String message) {
        if (message.startsWith("[MeFrp]")) {
            TextComponentString prefix = new TextComponentString("[MeFrp]");
            prefix.setStyle(new Style().setColor(TextFormatting.AQUA));
            TextComponentString body = new TextComponentString(message.substring("[MeFrp]".length()));
            body.setStyle(new Style().setColor(messageColor(message)));
            prefix.appendSibling(body);
            return prefix;
        }
        return new TextComponentString(message);
    }

    private TextFormatting messageColor(String message) {
        if (message.contains("/mefrp")) {
            return TextFormatting.GOLD;
        }
        return TextFormatting.WHITE;
    }

    private TextFormatting commandColor(String text) {
        if (text.contains("[VIP]")) {
            return TextFormatting.GOLD;
        }
        return TextFormatting.AQUA;
    }

    private void sendComponent(ITextComponent component) {
        if (Minecraft.getMinecraft().player != null) {
            Minecraft.getMinecraft().player.sendMessage(component);
        }
    }

    private int findEndpointPort(NetworkSystem networkSystem) {
        List<?> endpoints = readEndpoints(networkSystem);
        if (endpoints == null) {
            return -1;
        }
        for (int index = endpoints.size() - 1; index >= 0; index--) {
            Object endpoint = endpoints.get(index);
            if (endpoint instanceof ChannelFuture) {
                SocketAddress address = ((ChannelFuture) endpoint).channel().localAddress();
                if (address instanceof InetSocketAddress) {
                    int port = ((InetSocketAddress) address).getPort();
                    if (validPort(port)) {
                        return port;
                    }
                }
            }
        }
        return -1;
    }

    private List<?> readEndpoints(NetworkSystem networkSystem) {
        String[] fieldNames = {"endpoints", "field_151274_e"};
        for (String fieldName : fieldNames) {
            try {
                Field field = NetworkSystem.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(networkSystem);
                if (value instanceof List) {
                    return (List<?>) value;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private boolean validPort(int port) {
        return port >= 1 && port <= 65535;
    }
}
