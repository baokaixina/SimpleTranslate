package com.yourname.simpletranslate.cache;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Exact Forge 14.23.5 SimpleNetworkWrapper adapter for cache_sync/v1 semantics. */
public final class SharedCacheNetworking {
    // Forge 1.12 custom channel identifiers are capped at 20 characters.
    private static final String CHANNEL_ID = "stx2_cache_sync";
    private static final Logger LOGGER = LogManager.getLogger("SimpleTranslateSharedCache-1.12.2");
    private static final SimpleNetworkWrapper CHANNEL =
            NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL_ID);
    private static boolean registered;

    private SharedCacheNetworking() { }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        CHANNEL.registerMessage(ServerHandler.class, SharedCachePacket.class, 0, Side.SERVER);
        CHANNEL.registerMessage(ClientHandler.class, SharedCachePacket.class, 1, Side.CLIENT);
    }

    public static void sendToServer(SharedCachePayload payload) {
        if (payload == null) return;
        try {
            CHANNEL.sendToServer(new SharedCachePacket(payload));
        } catch (Exception error) {
            LOGGER.debug("Unable to send shared cache packet to server", error);
        }
    }

    public static void sendToPlayer(EntityPlayerMP player, SharedCachePayload payload) {
        if (player == null || payload == null) return;
        try {
            CHANNEL.sendTo(new SharedCachePacket(payload), player);
        } catch (Exception error) {
            LOGGER.debug("Unable to send shared cache packet to player", error);
        }
    }

    public static boolean canSendToServer() {
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft.player == null || minecraft.getConnection() == null) return false;
            NetworkManager manager = minecraft.getConnection().getNetworkManager();
            NetworkDispatcher dispatcher = manager == null ? null : NetworkDispatcher.get(manager);
            return manager != null && manager.isChannelOpen() && dispatcher != null
                    && dispatcher.getModList() != null
                    && dispatcher.getModList().containsKey(SimpleTranslateForge1122.MOD_ID);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean canSendToPlayer(EntityPlayerMP player) {
        try {
            if (player == null || player.connection == null) return false;
            NetworkManager manager = player.connection.netManager;
            NetworkDispatcher dispatcher = manager == null ? null : NetworkDispatcher.get(manager);
            return manager != null && manager.isChannelOpen() && dispatcher != null
                    && dispatcher.getModList() != null
                    && dispatcher.getModList().containsKey(SimpleTranslateForge1122.MOD_ID);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static final class ServerHandler implements IMessageHandler<SharedCachePacket, IMessage> {
        @Override public IMessage onMessage(final SharedCachePacket message, MessageContext context) {
            final EntityPlayerMP player = context.getServerHandler().player;
            if (player != null && message != null) {
                player.getServerWorld().addScheduledTask(new Runnable() {
                    @Override public void run() { SharedCacheServer.handlePayload(player, message.payload()); }
                });
            }
            return null;
        }
    }

    public static final class ClientHandler implements IMessageHandler<SharedCachePacket, IMessage> {
        @Override public IMessage onMessage(final SharedCachePacket message, MessageContext context) {
            if (message != null) {
                Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                    @Override public void run() { SharedCacheClient.handlePayload(message.payload()); }
                });
            }
            return null;
        }
    }
}
