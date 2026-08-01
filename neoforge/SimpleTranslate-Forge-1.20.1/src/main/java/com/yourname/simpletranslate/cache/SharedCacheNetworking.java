package com.yourname.simpletranslate.cache;

import com.yourname.simpletranslate.SimpleTranslateMod;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

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

    private static void handle(SharedCachePayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
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

    /**
     * Mirrors Fabric's {@code ClientPlayNetworking.canSend(channel)}: only true
     * when the connected server actually registered this channel.
     */
    public static boolean canSendToServer() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.getConnection() == null) {
                return false;
            }
            return CHANNEL.isRemotePresent(minecraft.getConnection().getConnection());
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean canSendToPlayer(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return false;
        }
        try {
            return CHANNEL.isRemotePresent(player.connection.connection);
        } catch (Exception e) {
            return false;
        }
    }
}
