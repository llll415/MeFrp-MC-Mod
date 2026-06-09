package com.mefrp.mc.neoforge;

import com.mefrp.mc.platform.MeFrpPlatform;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class MeFrpNeoForgePlatform implements MeFrpPlatform {
    private static final Logger LOGGER = LoggerFactory.getLogger("MeFrp-MC-Mod");
    private static final ThreadLocal<CommandSourceStack> CURRENT_SOURCE = new ThreadLocal<>();
    private static ClientBridge clientBridge;

    public static void withSource(CommandSourceStack source, Runnable runnable) {
        CURRENT_SOURCE.set(source);
        try {
            runnable.run();
        } finally {
            CURRENT_SOURCE.remove();
        }
    }

    public static void setClientBridge(ClientBridge bridge) {
        clientBridge = bridge;
    }

    @Override
    public Path gameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public boolean isClient() {
        return clientBridge != null;
    }

    @Override
    public boolean isDedicatedServer() {
        return clientBridge == null;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public void runOnMainThread(Runnable runnable) {
        CommandSourceStack source = CURRENT_SOURCE.get();
        if (clientBridge != null) {
            clientBridge.runOnClient(runnable);
            return;
        }
        if (source != null) {
            source.getServer().execute(() -> withSource(source, runnable));
            return;
        }
        runnable.run();
    }

    @Override
    public void sendMessage(String message) {
        runOnMainThread(() -> sendComponent(formatMessage(message)));
    }

    @Override
    public void sendClickableCommand(String text, String command, String hoverText) {
        MutableComponent component = Component.literal(text).withStyle(style -> style
                .withColor(commandColor(text))
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hoverText))));
        runOnMainThread(() -> sendComponent(component));
    }

    @Override
    public void sendClickableCommand(String label, String text, String command, String hoverText) {
        MutableComponent component = formatMessage(label)
                .append(Component.literal(text).withStyle(style -> style
                        .withColor(ChatFormatting.GOLD)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(hoverText)))));
        runOnMainThread(() -> sendComponent(component));
    }

    @Override
    public void sendCopyableText(String label, String value, String hoverText) {
        MutableComponent component = formatMessage(label)
                .append(Component.literal(value).withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent.CopyToClipboard(value))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(hoverText)))));
        runOnMainThread(() -> sendComponent(component));
    }

    @Override
    public void logInfo(String message) {
        LOGGER.info(message);
    }

    @Override
    public void logWarn(String message) {
        LOGGER.warn(message);
    }

    private MutableComponent formatMessage(String message) {
        if (message.startsWith("[MeFrp]")) {
            return Component.literal("[MeFrp]").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(message.substring("[MeFrp]".length())).withStyle(messageColor(message)));
        }
        return Component.literal(message);
    }

    private ChatFormatting messageColor(String message) {
        if (message.contains("失败") || message.contains("错误") || message.contains("无效") || message.contains("未知命令") || message.contains("API 请求失败") || message.contains("操作失败")) {
            return ChatFormatting.RED;
        }
        if (message.contains("未检测") || message.contains("没有检测") || message.contains("不可用") || message.contains("停止未完成") || message.contains("仍显示在线") || message.contains("还没有") || message.contains("请先")) {
            return ChatFormatting.YELLOW;
        }
        if (message.contains("已保存") || message.contains("已启动") || message.contains("已停止") || message.contains("检测到") || message.contains("已开启") || message.contains("已关闭")) {
            return ChatFormatting.GREEN;
        }
        if (message.contains("/mefrp")) {
            return ChatFormatting.GOLD;
        }
        return ChatFormatting.WHITE;
    }

    private ChatFormatting commandColor(String text) {
        if (text.contains("[过载]")) {
            return ChatFormatting.RED;
        }
        if (text.contains("[VIP]")) {
            return ChatFormatting.GOLD;
        }
        return ChatFormatting.AQUA;
    }

    private void sendComponent(Component component) {
        CommandSourceStack source = CURRENT_SOURCE.get();
        if (source != null) {
            source.sendSuccess(() -> component, false);
        } else if (clientBridge != null) {
            clientBridge.sendClientMessage(component);
        }
    }

    @Override
    public boolean setLanOnlineMode(boolean enabled) {
        return clientBridge != null && clientBridge.setLanOnlineMode(enabled);
    }

    @Override
    public boolean getLanOnlineMode() {
        return clientBridge == null || clientBridge.getLanOnlineMode();
    }

    @Override
    public boolean supportsLanOnlineModeControl() {
        return clientBridge != null;
    }

    @Override
    public int getPublishedLanPort() {
        return clientBridge == null ? -1 : clientBridge.getPublishedLanPort();
    }

    @Override
    public boolean isLocalServerHost() {
        return clientBridge != null && clientBridge.isLocalServerHost();
    }

    public interface ClientBridge {
        void runOnClient(Runnable runnable);

        void sendClientMessage(Component component);

        boolean setLanOnlineMode(boolean enabled);

        boolean getLanOnlineMode();

        int getPublishedLanPort();

        boolean isLocalServerHost();
    }
}
