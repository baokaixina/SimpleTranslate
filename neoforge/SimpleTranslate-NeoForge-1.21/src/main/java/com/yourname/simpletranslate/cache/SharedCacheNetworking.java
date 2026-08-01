package com.yourname.simpletranslate.cache;

import com.yourname.simpletranslate.SimpleTranslateMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * NeoForge 21.0/21.1 payload wiring. Verified against the neoforge 21.0.167 and
 * 21.1.243 universal jars: only the single-handler
 * {@code PayloadRegistrar.playBidirectional(Type, StreamCodec, IPayloadHandler)}
 * overload exists on this generation (the dual-handler overload and
 * {@code ClientPacketDistributor} arrive with 21.2), and
 * {@code PacketDistributor.sendToServer/sendToPlayer} are the static entry points.
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
