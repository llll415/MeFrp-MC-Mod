package com.mefrp.mc.forge;

import com.mefrp.mc.MeFrp;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

@Mod("mefrpmc")
public class MeFrpForge {
    public static final MeFrpForgePlatform PLATFORM = new MeFrpForgePlatform();

    public MeFrpForge() {
        MeFrp.init(PLATFORM);
        MinecraftForge.EVENT_BUS.register(this);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            MeFrpForgePlatform.setClientBridge(new MeFrpForgeClientBridge());
        }
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) {
            return;
        }
        registerCommand(event.getDispatcher(), true);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        MeFrp.onShutdown();
    }

    private static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher, boolean withSource) {
        dispatcher.register(literal("mefrp")
                .executes(context -> run(context.getSource(), withSource))
                .then(literal("help").executes(context -> run(context.getSource(), withSource, "help")))
                .then(literal("token").then(argument("accessToken", StringArgumentType.string()).executes(context -> run(context.getSource(), withSource, "token", StringArgumentType.getString(context, "accessToken")))))
                .then(literal("start").executes(context -> run(context.getSource(), withSource, "start")).then(argument("port", IntegerArgumentType.integer(1, 65535)).executes(context -> run(context.getSource(), withSource, "start", String.valueOf(IntegerArgumentType.getInteger(context, "port"))))))
                .then(literal("stop").executes(context -> run(context.getSource(), withSource, "stop")))
                .then(literal("node").executes(context -> run(context.getSource(), withSource, "node")))
                .then(literal("status").executes(context -> run(context.getSource(), withSource, "status")))
                .then(literal("userinfo").executes(context -> run(context.getSource(), withSource, "userinfo")))
                .then(literal("sign").executes(context -> run(context.getSource(), withSource, "sign")))
                .then(literal("online").then(literal("on").executes(context -> run(context.getSource(), withSource, "online", "on"))).then(literal("off").executes(context -> run(context.getSource(), withSource, "online", "off"))))
                .then(literal("select").then(argument("index", IntegerArgumentType.integer(1)).executes(context -> run(context.getSource(), withSource, "select", String.valueOf(IntegerArgumentType.getInteger(context, "index")))))));
    }

    private static int run(CommandSourceStack source, boolean withSource, String... args) {
        if (withSource) {
            if (!isConsoleOrRconSource(source)) {
                MeFrpForgePlatform.withSource(source, () -> MeFrp.platform().sendMessage("[MeFrp] MeFrp 服务端命令只能由服务器控制台或 RCON 执行。"));
                return 1;
            }
            MeFrpForgePlatform.withSource(source, () -> MeFrp.commandHandler().handleCommand(args));
        } else {
            MeFrp.commandHandler().handleCommand(args);
        }
        return 1;
    }

    private static boolean isConsoleOrRconSource(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer) {
            return false;
        }
        Object rawSource = rawCommandSource(source);
        return rawSource != null && isConsoleOrRconClass(rawSource.getClass().getName());
    }

    private static Object rawCommandSource(CommandSourceStack source) {
        try {
            java.lang.reflect.Field field = source.getClass().getDeclaredField("source");
            field.setAccessible(true);
            return field.get(source);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static boolean isConsoleOrRconClass(String className) {
        return className.contains("MinecraftServer") || className.contains("DedicatedServer") || className.contains("Rcon");
    }

    @Mod.EventBusSubscriber(modid = "mefrpmc", value = Dist.CLIENT)
    public static class ClientEvents {
        private static boolean playerWasPresent;

        @SubscribeEvent
        public static void registerClientCommands(RegisterClientCommandsEvent event) {
            registerCommand(event.getDispatcher(), false);
        }

        @SubscribeEvent
        public static void onChat(ClientChatReceivedEvent event) {
            MeFrp.onChatMessage(event.getMessage().getString());
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
            boolean playerPresent = Minecraft.getInstance().player != null;
            if (playerPresent && !playerWasPresent) {
                Minecraft.getInstance().execute(MeFrp::onPlayerReady);
            }
            playerWasPresent = playerPresent;
        }
    }
}
