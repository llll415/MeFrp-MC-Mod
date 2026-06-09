package com.mefrp.mc.forge;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class MeFrpCopyCommand extends CommandBase {
    @Override
    public String getName() {
        return "mefrp-copy";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/mefrp-copy <value>";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        if (args.length < 1) {
            return;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(args[0]);
            GuiScreen.setClipboardString(new String(decoded, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }
}
