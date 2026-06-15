package com.yourname.simpletranslate.network;

import com.yourname.simpletranslate.SimpleTranslateMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent;
import net.neoforged.neoforge.network.handling.PlayPayloadContext;
import net.neoforged.neoforge.network.registration.IPayloadRegistrar;

public final class SharedCacheNetworking {
    private static final String PROTOCOL = "1";

    private SharedCacheNetworking() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(SharedCacheNetworking::registerPayloads);
    }

    private static void registerPayloads(RegisterPayloadHandlerEvent event) {
        IPayloadRegistrar registrar = event.registrar(SimpleTranslateMod.MODID).versioned(PROTOCOL).optional();
        registrar.play(SharedCachePayload.CHANNEL, SharedCachePayload::read, direction -> direction
                .client(SharedCacheNetworking::handleClient)
                .server(SharedCacheNetworking::handleServer));
    }

    private static void handleClient(SharedCachePayload payload, PlayPayloadContext context) {
        context.workHandler().execute(() -> SharedCacheClient.handlePayload(payload));
    }

    private static void handleServer(SharedCachePayload payload, PlayPayloadContext context) {
        context.player().ifPresent(player -> {
            if (player instanceof ServerPlayer serverPlayer) {
                context.workHandler().execute(() -> SharedCacheServer.handlePayload(serverPlayer, payload));
            }
        });
    }

    public static void sendToServer(SharedCachePayload payload) {
        try {
            PacketDistributor.SERVER.noArg().send(payload);
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().debug("Unable to send shared cache packet to server", e);
        }
    }

    public static void sendToPlayer(ServerPlayer player, SharedCachePayload payload) {
        if (player == null || payload == null) {
            return;
        }
        try {
            PacketDistributor.PLAYER.with(player).send(payload);
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().debug("Unable to send shared cache packet to player", e);
        }
    }

    public static boolean canSendToServer() {
        return true;
    }

    public static boolean canSendToPlayer(ServerPlayer player) {
        return player != null;
    }
}
