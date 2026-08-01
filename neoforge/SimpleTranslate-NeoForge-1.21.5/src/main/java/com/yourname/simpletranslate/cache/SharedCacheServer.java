package com.yourname.simpletranslate.cache;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
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

        NeoForge.EVENT_BUS.addListener(SharedCacheServer::onServerStarted);
        NeoForge.EVENT_BUS.addListener(SharedCacheServer::onServerStopping);
        NeoForge.EVENT_BUS.addListener(SharedCacheServer::onServerTick);
        NeoForge.EVENT_BUS.addListener(SharedCacheServer::onPlayerLoggedOut);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        ENABLED_PLAYERS.clear();
        OUTBOUND_QUEUES.clear();
        Path storeFile = event.getServer().getWorldPath(LevelResource.ROOT).resolve(STORE_FILE);
        STORE.load(storeFile);
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        STORE.saveNow();
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        flushOutboundQueues(event.getServer());
        STORE.saveIfDue(System.currentTimeMillis());
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ENABLED_PLAYERS.remove(player.getUUID());
            OUTBOUND_QUEUES.remove(player.getUUID());
        }
    }

    public static void handlePayload(MinecraftServer server, ServerPlayer player, SharedCachePayload payload) {
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
        return player != null && SharedCacheNetworking.canSendToPlayer(player);
    }

    private static void sendPayload(ServerPlayer player, SharedCachePayload payload) {
        try {
            SharedCacheNetworking.sendToPlayer(player, payload);
        } catch (Exception e) {
            LOGGER.warn("Failed to send shared cache payload to {}: {}",
                    player == null ? "unknown" : player.getScoreboardName(), e.getMessage());
        }
    }
}
