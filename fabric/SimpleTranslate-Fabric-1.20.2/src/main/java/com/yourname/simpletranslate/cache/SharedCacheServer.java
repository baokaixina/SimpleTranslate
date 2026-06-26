package com.yourname.simpletranslate.cache;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SharedCacheServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("SimpleTranslateSharedCache");
    private static final String STORE_FILE = "simple_translate_shared_cache.json";
    private static final int MAX_OUTBOUND_PACKETS_PER_PLAYER_TICK = 2;
    private static final SharedCacheStore STORE = new SharedCacheStore();
    private static final Set<UUID> ENABLED_PLAYERS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Queue<SharedCacheEntry>> OUTBOUND_QUEUES = new ConcurrentHashMap<>();
    private static boolean registered;

    private SharedCacheServer() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;

        ServerLifecycleEvents.SERVER_STARTED.register(SharedCacheServer::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> STORE.saveNow());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            flushOutboundQueues(server);
            STORE.saveIfDue(System.currentTimeMillis());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (handler.player != null) {
                ENABLED_PLAYERS.remove(handler.player.getUUID());
                OUTBOUND_QUEUES.remove(handler.player.getUUID());
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(SharedCachePayload.CHANNEL, (server, player, handler, buffer, responseSender) -> {
            SharedCachePayload payload;
            try {
                payload = SharedCachePayload.read(buffer);
            } catch (Exception e) {
                LOGGER.warn("Ignored invalid shared cache payload from {}: {}",
                        player == null ? "unknown" : player.getScoreboardName(), e.getMessage());
                return;
            }
            server.execute(() -> handlePayload(server, player, payload));
        });
    }

    private static void onServerStarted(MinecraftServer server) {
        ENABLED_PLAYERS.clear();
        OUTBOUND_QUEUES.clear();
        Path storeFile = server.getWorldPath(LevelResource.ROOT).resolve(STORE_FILE);
        STORE.load(storeFile);
    }

    private static void handlePayload(MinecraftServer server, ServerPlayer player, SharedCachePayload payload) {
        if (player == null || payload == null) {
            return;
        }
        switch (payload.kind()) {
            case SharedCachePayload.KIND_HELLO -> {
                ENABLED_PLAYERS.add(player.getUUID());
            }
            case SharedCachePayload.KIND_REQUEST_SNAPSHOT -> {
                if (ENABLED_PLAYERS.contains(player.getUUID())) {
                    sendSnapshot(player);
                }
            }
            case SharedCachePayload.KIND_DISABLE -> {
                ENABLED_PLAYERS.remove(player.getUUID());
                OUTBOUND_QUEUES.remove(player.getUUID());
            }
            case SharedCachePayload.KIND_ENTRIES -> {
                if (!ENABLED_PLAYERS.contains(player.getUUID())) {
                    return;
                }
                List<SharedCacheEntry> accepted = STORE.putMissing(payload.entries());
                if (!accepted.isEmpty()) {
                    broadcastEntries(server, player, accepted);
                }
            }
            default -> LOGGER.debug("Ignored unknown shared cache payload kind {}", payload.kind());
        }
    }

    private static void sendSnapshot(ServerPlayer player) {
        if (!canSend(player)) {
            return;
        }
        OUTBOUND_QUEUES.put(player.getUUID(), new ArrayDeque<>(STORE.allEntries()));
    }

    private static void broadcastEntries(MinecraftServer server, ServerPlayer source, List<SharedCacheEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null || player.getUUID().equals(source.getUUID())
                    || !ENABLED_PLAYERS.contains(player.getUUID()) || !canSend(player)) {
                continue;
            }
            Queue<SharedCacheEntry> queue = OUTBOUND_QUEUES.computeIfAbsent(player.getUUID(), ignored -> new ArrayDeque<>());
            queue.addAll(entries);
        }
    }

    private static void flushOutboundQueues(MinecraftServer server) {
        if (server == null || OUTBOUND_QUEUES.isEmpty()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null || !ENABLED_PLAYERS.contains(player.getUUID()) || !canSend(player)) {
                continue;
            }
            Queue<SharedCacheEntry> queue = OUTBOUND_QUEUES.get(player.getUUID());
            if (queue == null || queue.isEmpty()) {
                continue;
            }
            int sent = 0;
            while (sent < MAX_OUTBOUND_PACKETS_PER_PLAYER_TICK && !queue.isEmpty()) {
                List<SharedCacheEntry> batch = drainQueueBatch(queue);
                if (batch.isEmpty()) {
                    break;
                }
                sendPayload(player, SharedCachePayload.entries(batch));
                sent++;
            }
            if (queue.isEmpty()) {
                OUTBOUND_QUEUES.remove(player.getUUID());
            }
        }
    }

    private static List<SharedCacheEntry> drainQueueBatch(Queue<SharedCacheEntry> queue) {
        if (queue == null || queue.isEmpty()) {
            return List.of();
        }
        List<SharedCacheEntry> batch = new ArrayList<>();
        int bytes = SharedCachePayload.estimatedPacketBytes(List.of());
        while (!queue.isEmpty()) {
            SharedCacheEntry entry = queue.peek();
            if (entry == null || !entry.isShareable()) {
                queue.poll();
                continue;
            }
            int entryBytes = entry.estimatedWireBytes();
            if (!batch.isEmpty()
                    && (batch.size() >= SharedCachePayload.MAX_ENTRIES_PER_PACKET
                    || bytes + entryBytes > SharedCachePayload.MAX_PACKET_BYTES)) {
                break;
            }
            if (entryBytes + SharedCachePayload.estimatedPacketBytes(List.of()) > SharedCachePayload.MAX_PACKET_BYTES) {
                queue.poll();
                continue;
            }
            batch.add(queue.poll());
            bytes += entryBytes;
        }
        return batch;
    }

    private static boolean canSend(ServerPlayer player) {
        return player != null && ServerPlayNetworking.canSend(player, SharedCachePayload.CHANNEL);
    }

    private static void sendPayload(ServerPlayer player, SharedCachePayload payload) {
        try {
            FriendlyByteBuf buffer = PacketByteBufs.create();
            payload.write(buffer);
            ServerPlayNetworking.send(player, SharedCachePayload.CHANNEL, buffer);
        } catch (Exception e) {
            LOGGER.warn("Failed to send shared cache payload to {}: {}",
                    player == null ? "unknown" : player.getScoreboardName(), e.getMessage());
        }
    }
}
