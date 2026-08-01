package com.yourname.simpletranslate.cache;

import com.yourname.simpletranslate.SimpleTranslateMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.NetworkEvent;
import net.neoforged.neoforge.network.NetworkRegistry;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.simple.SimpleChannel;

/**
 * NeoForge 20.2 transport for the shared-cache channel. 20.2 still ships the
 * SimpleChannel API (the payload/registrar stack only arrives in 20.4), so
 * registration mirrors the 1.20.1 Forge port with neoforge packages; the wire
 * format and semantics are the donor's (see {@link SharedCachePayload}).
 */
public final class SharedCacheNetworking {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            SharedCachePayload.CHANNEL,
            () -> PROTOCOL,
            ignored -> true,
            ignored -> true);
    private static boolean registered;

    private SharedCacheNetworking() {
    }

    public static synchronized void registerPayloadTypes() {
        if (registered) {
            return;
        }
        registered = true;
        CHANNEL.messageBuilder(SharedCachePayload.class, 0)
                .encoder(SharedCachePayload::write)
                .decoder(SharedCachePayload::read)
                .consumerMainThread(SharedCacheNetworking::handle)
                .add();
    }

    private static void handle(SharedCachePayload payload, NetworkEvent.Context context) {
        if (context.getDirection().getReceptionSide().isClient()) {
            SharedCacheClient.handlePayload(payload);
        } else {
            SharedCacheServer.handlePayload(context.getSender(), payload);
        }
        context.setPacketHandled(true);
    }

    public static void sendToServer(SharedCachePayload payload) {
        try {
            CHANNEL.sendToServer(payload);
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().debug("Unable to send shared cache packet to server", e);
        }
    }

    public static void sendToPlayer(ServerPlayer player, SharedCachePayload payload) {
        if (player == null || payload == null) {
            return;
        }
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
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
