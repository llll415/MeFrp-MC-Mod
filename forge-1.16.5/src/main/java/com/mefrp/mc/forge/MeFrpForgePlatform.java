package com.mefrp.mc.forge;

import com.mefrp.mc.platform.MeFrpPlatform;
import net.minecraft.command.CommandSource;
import net.minecraft.util.text.Color;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

public class MeFrpForgePlatform implements MeFrpPlatform {
    private static final Logger LOGGER = LogManager.getLogger("MeFrp-MC-Mod");
    private static final ThreadLocal<CommandSource> CURRENT_SOURCE = new ThreadLocal<CommandSource>();
    private static ClientBridge clientBridge;

    public static void withSource(CommandSource source, Runnable runnable) {
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
    public void runOnMainThread(final Runnable runnable) {
        final CommandSource source = CURRENT_SOURCE.get();
        if (clientBridge != null) {
            clientBridge.runOnClient(runnable);
            return;
        }
        if (source != null && source.getServer() != null) {
            source.getServer().execute(new Runnable() {
                @Override
                public void run() {
                    withSource(source, runnable);
                }
            });
            return;
        }
        runnable.run();
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
        final StringTextComponent component = new StringTextComponent(text);
        component.setStyle(Style.EMPTY.withColor(commandColor(text))
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new StringTextComponent(hoverText))));
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                sendComponent(component);
            }
        });
    }

    @Override
    public void sendClickableCommand(String label, String text, String command, String hoverText) {
        StringTextComponent component = formatMessage(label);
        StringTextComponent clickable = new StringTextComponent(text);
        clickable.setStyle(Style.EMPTY.withColor(TextFormatting.GOLD)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new StringTextComponent(hoverText))));
        component.append(clickable);
        final StringTextComponent finalComponent = component;
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                sendComponent(finalComponent);
            }
        });
    }

    @Override
    public void sendCopyableText(String label, String value, String hoverText) {
        StringTextComponent component = formatMessage(label);
        StringTextComponent copyable = new StringTextComponent(value);
        copyable.setStyle(Style.EMPTY.withColor(TextFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, value))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new StringTextComponent(hoverText))));
        component.append(copyable);
        final StringTextComponent finalComponent = component;
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

    private StringTextComponent formatMessage(String message) {
        if (message.startsWith("[MeFrp]")) {
            StringTextComponent prefix = new StringTextComponent("[MeFrp]");
            prefix.setStyle(Style.EMPTY.withColor(TextFormatting.AQUA));
            StringTextComponent body = new StringTextComponent(message.substring("[MeFrp]".length()));
            body.setStyle(Style.EMPTY.withColor(messageColor(message)));
            prefix.append(body);
            return prefix;
        }
        return new StringTextComponent(message);
    }

    private TextFormatting messageColor(String message) {
        if (message.contains("失败") || message.contains("错误") || message.contains("无效") || message.contains("未知命令") || message.contains("API 请求失败") || message.contains("操作失败")) {
            return TextFormatting.RED;
        }
        if (message.contains("未检测") || message.contains("没有检测") || message.contains("不可用") || message.contains("停止未完成") || message.contains("仍显示在线") || message.contains("还没有") || message.contains("请先")) {
            return TextFormatting.YELLOW;
        }
        if (message.contains("已保存") || message.contains("已启动") || message.contains("已停止") || message.contains("检测到") || message.contains("已开启") || message.contains("已关闭")) {
            return TextFormatting.GREEN;
        }
        if (message.contains("/mefrp")) {
            return TextFormatting.GOLD;
        }
        return TextFormatting.WHITE;
    }

    private TextFormatting commandColor(String text) {
        if (text.contains("[过载]")) {
            return TextFormatting.RED;
        }
        if (text.contains("[VIP]")) {
            return TextFormatting.GOLD;
        }
        return TextFormatting.AQUA;
    }

    private void sendComponent(ITextComponent component) {
        CommandSource source = CURRENT_SOURCE.get();
        if (source != null) {
            source.sendSuccess(component, false);
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

        void sendClientMessage(ITextComponent component);

        boolean setLanOnlineMode(boolean enabled);

        boolean getLanOnlineMode();

        int getPublishedLanPort();

        boolean isLocalServerHost();
    }
}
