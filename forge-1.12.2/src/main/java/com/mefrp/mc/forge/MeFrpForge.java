package com.mefrp.mc.forge;

import com.mefrp.mc.MeFrp;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.NetworkCheckHandler;
import net.minecraftforge.fml.relauncher.Side;

import java.util.Map;

@Mod(modid = "mefrpmc", acceptedMinecraftVersions = "[1.12.2]", acceptableRemoteVersions = "*", clientSideOnly = true, useMetadata = true)
public class MeFrpForge {
    public static final MeFrpForgePlatform PLATFORM = new MeFrpForgePlatform();
    private static boolean playerWasPresent;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MeFrp.init(PLATFORM);
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
        ClientCommandHandler.instance.registerCommand(new MeFrpClientCommand());
        ClientCommandHandler.instance.registerCommand(new MeFrpCopyCommand());
    }

    @Mod.EventHandler
    public void stopping(FMLServerStoppingEvent event) {
        MeFrp.onShutdown();
    }

    @NetworkCheckHandler
    public boolean acceptRemoteVersion(Map<String, String> remoteMods, Side remoteSide) {
        return true;
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        MeFrp.onChatMessage(event.getMessage().getUnformattedText());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        boolean playerPresent = Minecraft.getMinecraft().player != null;
        if (playerPresent && !playerWasPresent) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    MeFrp.onPlayerReady();
                }
            });
        }
        playerWasPresent = playerPresent;
    }
}
