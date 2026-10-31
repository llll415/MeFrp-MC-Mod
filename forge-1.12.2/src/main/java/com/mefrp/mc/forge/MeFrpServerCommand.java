package com.mefrp.mc.forge;

import com.mefrp.mc.MeFrp;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

public class MeFrpServerCommand extends CommandBase {
    @Override
    public String getName() {
        return "mefrp";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/mefrp <help|token|start|stop|status|userinfo|sign|node|online|select>";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        if (sender.getCommandSenderEntity() != null) {
            sender.sendMessage(new TextComponentString("[MeFrp] MeFrp 服务端命令只能由服务器控制台或 RCON 执行。"));
            return;
        }
        MeFrpForgePlatform.withSource(sender, new Runnable() {
            @Override
            public void run() {
                MeFrp.commandHandler().handleCommand(args);
            }
        });
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
