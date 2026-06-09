package com.mefrp.mc.fabric;

import com.mefrp.mc.MeFrp;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v1.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import static net.fabricmc.fabric.api.client.command.v1.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v1.ClientCommandManager.literal;

public class MeFrpFabricClient implements ClientModInitializer {
    private static boolean playerWasPresent;
    private static boolean lanWasPublished;

    @Override
    public void onInitializeClient() {
        MeFrpFabricPlatform.setClientBridge(new MeFrpFabricClientBridge());
        registerClientCommand(ClientCommandManager.DISPATCHER);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(new Runnable() {
            @Override
            public void run() {
                MeFrp.onPlayerReady();
            }
        }));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean playerPresent = client.player != null;
            if (playerPresent && !playerWasPresent) {
                client.execute(new Runnable() {
                    @Override
                    public void run() {
                        MeFrp.onPlayerReady();
                    }
                });
            }
            playerWasPresent = playerPresent;

            boolean lanPublished = client.getSingleplayerServer() != null && client.getSingleplayerServer().isPublished();
            if (lanPublished && !lanWasPublished) {
                MeFrp.onLanOpened();
            }
            lanWasPublished = lanPublished;
        });
    }

    private void registerClientCommand(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("mefrp")
                .executes(context -> run())
                .then(literal("help").executes(context -> run("help")))
                .then(literal("token").then(argument("accessToken", StringArgumentType.string()).executes(context -> run("token", StringArgumentType.getString(context, "accessToken")))))
                .then(literal("start").executes(context -> run("start")).then(argument("port", IntegerArgumentType.integer(1, 65535)).executes(context -> run("start", String.valueOf(IntegerArgumentType.getInteger(context, "port"))))))
                .then(literal("stop").executes(context -> run("stop")))
                .then(literal("node").executes(context -> run("node")))
                .then(literal("status").executes(context -> run("status")))
                .then(literal("userinfo").executes(context -> run("userinfo")))
                .then(literal("sign").executes(context -> run("sign")))
                .then(literal("online").then(literal("on").executes(context -> run("online", "on"))).then(literal("off").executes(context -> run("online", "off"))))
                .then(literal("select").then(argument("index", IntegerArgumentType.integer(1)).executes(context -> run("select", String.valueOf(IntegerArgumentType.getInteger(context, "index")))))));
    }

    private int run(String... args) {
        MeFrp.commandHandler().handleCommand(args);
        return 1;
    }
}
