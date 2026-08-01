package com.yourname.simpletranslate.cache;

import com.yourname.simpletranslate.SimpleTranslateMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * NeoForge 20.6 payload wiring for the optional shared cache. 20.6 has no
 * ClientPacketDistributor and playBidirectional takes a single handler, so the
 * side is discriminated by the context player type (verified against the
 * neoforge-20.6.139 universal jar).
 */
public final class SharedCacheNetworking {
    private static final String PROTOCOL = "1";

    private SharedCacheNetworking() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(SharedCacheNetworking::registerPayloads);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL).optional();
        registrar.playBidirectional(SharedCachePayload.TYPE, SharedCachePayload.CODEC,
                SharedCacheNetworking::handle);
    }

    private static void handle(SharedCachePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> SharedCacheServer.handlePayload(player, payload));
        } else {
            context.enqueueWork(() -> SharedCacheClient.handlePayload(payload));
        }
    }

    public static void sendToServer(SharedCachePayload payload) {
        try {
            PacketDistributor.sendToServer(payload);
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().debug("Unable to send shared cache packet to server", e);
        }
    }

    public static void sendToPlayer(ServerPlayer player, SharedCachePayload payload) {
        if (player == null || payload == null) {
            return;
        }
        try {
            PacketDistributor.sendToPlayer(player, payload);
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
