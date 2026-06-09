package com.mefrp.mc.fabric;

import com.mefrp.mc.MeFrp;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class MeFrpFabric implements ModInitializer {
    public static final MeFrpFabricPlatform PLATFORM = new MeFrpFabricPlatform();

    @Override
    public void onInitialize() {
        MeFrp.init(PLATFORM);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            if (environment == Commands.CommandSelection.DEDICATED) {
                registerCommand(dispatcher);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> MeFrp.onShutdown());
    }

    private void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("mefrp")
                .executes(context -> {
                    return run(context.getSource());
                })
                .then(literal("help").executes(context -> run(context.getSource(), "help")))
                .then(literal("token").then(argument("accessToken", StringArgumentType.string()).executes(context -> run(context.getSource(), "token", StringArgumentType.getString(context, "accessToken")))))
                .then(literal("start").executes(context -> run(context.getSource(), "start")).then(argument("port", IntegerArgumentType.integer(1, 65535)).executes(context -> run(context.getSource(), "start", String.valueOf(IntegerArgumentType.getInteger(context, "port"))))))
                .then(literal("stop").executes(context -> run(context.getSource(), "stop")))
                .then(literal("node").executes(context -> run(context.getSource(), "node")))
                .then(literal("status").executes(context -> run(context.getSource(), "status")))
                .then(literal("userinfo").executes(context -> run(context.getSource(), "userinfo")))
                .then(literal("sign").executes(context -> run(context.getSource(), "sign")))
                .then(literal("online").then(literal("on").executes(context -> run(context.getSource(), "online", "on"))).then(literal("off").executes(context -> run(context.getSource(), "online", "off"))))
                .then(literal("select").then(argument("index", IntegerArgumentType.integer(1)).executes(context -> run(context.getSource(), "select", String.valueOf(IntegerArgumentType.getInteger(context, "index")))))));
    }

    private int run(CommandSourceStack source, String... args) {
        if (!isConsoleOrRconSource(source)) {
            MeFrpFabricPlatform.withSource(source, () -> MeFrp.platform().sendMessage("[MeFrp] MeFrp 服务端命令只能由服务器控制台或 RCON 执行。"));
            return 1;
        }
        MeFrpFabricPlatform.withSource(source, () -> MeFrp.commandHandler().handleCommand(args));
        return 1;
    }

    private boolean isConsoleOrRconSource(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer) {
            return false;
        }
        Object rawSource = rawCommandSource(source);
        return rawSource != null && isConsoleOrRconClass(rawSource.getClass().getName());
    }

    private Object rawCommandSource(CommandSourceStack source) {
        try {
            java.lang.reflect.Field field = source.getClass().getDeclaredField("source");
            field.setAccessible(true);
            return field.get(source);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private boolean isConsoleOrRconClass(String className) {
        return className.contains("MinecraftServer") || className.contains("DedicatedServer") || className.contains("Rcon");
    }
}
