package com.mefrp.mc.neoforge;

import com.mefrp.mc.MeFrp;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

@Mod("mefrpmc")
public class MeFrpNeoForge {
    public static final MeFrpNeoForgePlatform PLATFORM = new MeFrpNeoForgePlatform();

    public MeFrpNeoForge() {
        MeFrp.init(PLATFORM);
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            MeFrpNeoForgePlatform.setClientBridge(new MeFrpNeoForgeClientBridge());
        }
    }

    @EventBusSubscriber(modid = "mefrpmc")
    public static class CommonEvents {
        @SubscribeEvent
        public static void registerCommands(RegisterCommandsEvent event) {
            if (FMLEnvironment.getDist() != Dist.DEDICATED_SERVER) {
                return;
            }
            registerCommand(event.getDispatcher(), true);
        }

        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent event) {
            MeFrp.onShutdown();
        }
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
                MeFrpNeoForgePlatform.withSource(source, () -> MeFrp.platform().sendMessage("[MeFrp] MeFrp 服务端命令只能由服务器控制台或 RCON 执行。"));
                return 1;
            }
            MeFrpNeoForgePlatform.withSource(source, () -> MeFrp.commandHandler().handleCommand(args));
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

    @EventBusSubscriber(modid = "mefrpmc", value = Dist.CLIENT)
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
        public static void onClientTick(ClientTickEvent.Post event) {
            boolean playerPresent = Minecraft.getInstance().player != null;
            if (playerPresent && !playerWasPresent) {
                Minecraft.getInstance().execute(MeFrp::onPlayerReady);
            }
            playerWasPresent = playerPresent;
        }
    }
}
