package com.yourname.simpletranslate.network;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SharedCacheServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("SimpleTranslateSharedCache");
    private static final String STORE_FILE = "simple_translate_shared_cache.json";
    private static final SharedCacheStore STORE = new SharedCacheStore();
    private static final Set<UUID> ENABLED_PLAYERS = ConcurrentHashMap.newKeySet();
    private static boolean registered;

    private SharedCacheServer() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        MinecraftForge.EVENT_BUS.addListener(SharedCacheServer::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(SharedCacheServer::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(SharedCacheServer::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(SharedCacheServer::onPlayerLoggedOut);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        ENABLED_PLAYERS.clear();
        MinecraftServer server = event.getServer();
        Path storeFile = server.getWorldPath(LevelResource.ROOT).resolve(STORE_FILE);
        STORE.load(storeFile);
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        STORE.saveNow();
        ENABLED_PLAYERS.clear();
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            STORE.saveIfDue(System.currentTimeMillis());
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ENABLED_PLAYERS.remove(player.getUUID());
        }
    }

    public static void handlePayload(ServerPlayer player, SharedCachePayload payload) {
        if (player == null || payload == null) {
            return;
        }
        MinecraftServer server = player.server;
        switch (payload.kind()) {
            case SharedCachePayload.KIND_HELLO -> ENABLED_PLAYERS.add(player.getUUID());
            case SharedCachePayload.KIND_REQUEST_SNAPSHOT -> {
                if (ENABLED_PLAYERS.contains(player.getUUID())) {
                    sendSnapshot(player);
                }
            }
            case SharedCachePayload.KIND_DISABLE -> ENABLED_PLAYERS.remove(player.getUUID());
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
        for (List<SharedCacheEntry> batch : batches(STORE.allEntries())) {
            SharedCacheNetworking.sendToPlayer(player, SharedCachePayload.entries(batch));
        }
    }

    private static void broadcastEntries(MinecraftServer server, ServerPlayer source, List<SharedCacheEntry> entries) {
        for (List<SharedCacheEntry> batch : batches(entries)) {
            SharedCachePayload payload = SharedCachePayload.entries(batch);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player == null || player.getUUID().equals(source.getUUID())
                        || !ENABLED_PLAYERS.contains(player.getUUID()) || !canSend(player)) {
                    continue;
                }
                SharedCacheNetworking.sendToPlayer(player, payload);
            }
        }
    }

    private static boolean canSend(ServerPlayer player) {
        return SharedCacheNetworking.canSendToPlayer(player);
    }

    private static List<List<SharedCacheEntry>> batches(List<SharedCacheEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<List<SharedCacheEntry>> result = new ArrayList<>();
        for (int i = 0; i < entries.size(); i += SharedCachePayload.MAX_ENTRIES_PER_PACKET) {
            result.add(entries.subList(i, Math.min(entries.size(), i + SharedCachePayload.MAX_ENTRIES_PER_PACKET)));
        }
        return result;
    }
}
