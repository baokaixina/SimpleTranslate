package com.yourname.simpletranslate.cache;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Forge 1.12.2 server lifecycle and rate-limited broadcast adapter. */
public final class SharedCacheServer {
    private static final String STORE_FILE = "simple_translate_shared_cache.json";
    private static final int MAX_OUTBOUND_PACKETS_PER_PLAYER_TICK = 2;
    private static final SharedCacheStore STORE = new SharedCacheStore();
    private static final Set<UUID> ENABLED_PLAYERS = Collections.newSetFromMap(
            new ConcurrentHashMap<UUID, Boolean>());
    private static final Map<UUID, Queue<SharedCacheEntry>> OUTBOUND_QUEUES =
            new ConcurrentHashMap<UUID, Queue<SharedCacheEntry>>();
    private static boolean registered;

    private SharedCacheServer() { }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        FMLCommonHandler.instance().bus().register(new ServerEvents());
    }

    public static void onServerStarted(MinecraftServer server) {
        ENABLED_PLAYERS.clear();
        OUTBOUND_QUEUES.clear();
        WorldServer overworld = server == null ? null : server.getWorld(0);
        Path file = overworld == null ? null
                : overworld.getSaveHandler().getWorldDirectory().toPath().resolve(STORE_FILE);
        STORE.load(file);
    }

    public static void onServerStopping() { STORE.saveNow(); }
    public static SharedCacheStore store() { return STORE; }

    public static void handlePayload(EntityPlayerMP player, SharedCachePayload payload) {
        if (player == null || payload == null) return;
        UUID id = player.getUniqueID();
        switch (payload.kind()) {
            case SharedCachePayload.KIND_HELLO:
                ENABLED_PLAYERS.add(id);
                break;
            case SharedCachePayload.KIND_REQUEST_SNAPSHOT:
                if (ENABLED_PLAYERS.contains(id)) sendSnapshot(player);
                break;
            case SharedCachePayload.KIND_DISABLE:
                ENABLED_PLAYERS.remove(id);
                OUTBOUND_QUEUES.remove(id);
                break;
            case SharedCachePayload.KIND_ENTRIES:
                if (!ENABLED_PLAYERS.contains(id)) return;
                List<SharedCacheEntry> accepted = STORE.putMissing(payload.entries());
                if (!accepted.isEmpty()) broadcastEntries(player.getServer(), player, accepted);
                break;
            default:
                break;
        }
    }

    private static void sendSnapshot(EntityPlayerMP player) {
        if (player == null || !SharedCacheNetworking.canSendToPlayer(player)) return;
        OUTBOUND_QUEUES.put(player.getUniqueID(), new ArrayDeque<SharedCacheEntry>(STORE.allEntries()));
    }

    private static void broadcastEntries(MinecraftServer server, EntityPlayerMP source,
                                         List<SharedCacheEntry> entries) {
        if (server == null || source == null || entries == null || entries.isEmpty()) return;
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            if (player == null || player.getUniqueID().equals(source.getUniqueID())
                    || !ENABLED_PLAYERS.contains(player.getUniqueID())
                    || !SharedCacheNetworking.canSendToPlayer(player)) continue;
            Queue<SharedCacheEntry> queue = OUTBOUND_QUEUES.get(player.getUniqueID());
            if (queue == null) {
                queue = new ArrayDeque<SharedCacheEntry>();
                OUTBOUND_QUEUES.put(player.getUniqueID(), queue);
            }
            queue.addAll(entries);
        }
    }

    private static void flushOutboundQueues(MinecraftServer server) {
        if (server == null || OUTBOUND_QUEUES.isEmpty()) return;
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            if (player == null || !ENABLED_PLAYERS.contains(player.getUniqueID())
                    || !SharedCacheNetworking.canSendToPlayer(player)) continue;
            Queue<SharedCacheEntry> queue = OUTBOUND_QUEUES.get(player.getUniqueID());
            if (queue == null || queue.isEmpty()) continue;
            int sent = 0;
            while (sent < MAX_OUTBOUND_PACKETS_PER_PLAYER_TICK && !queue.isEmpty()) {
                List<SharedCacheEntry> batch = drainQueueBatch(queue);
                if (batch.isEmpty()) break;
                SharedCacheNetworking.sendToPlayer(player, SharedCachePayload.entries(batch));
                sent++;
            }
            if (queue.isEmpty()) OUTBOUND_QUEUES.remove(player.getUniqueID());
        }
    }

    private static List<SharedCacheEntry> drainQueueBatch(Queue<SharedCacheEntry> queue) {
        if (queue == null || queue.isEmpty()) return Collections.emptyList();
        List<SharedCacheEntry> batch = new ArrayList<SharedCacheEntry>();
        int bytes = SharedCachePayload.estimatedPacketBytes(Collections.<SharedCacheEntry>emptyList());
        while (!queue.isEmpty()) {
            SharedCacheEntry entry = queue.peek();
            if (entry == null || !entry.isShareable()) { queue.poll(); continue; }
            int entryBytes = entry.estimatedWireBytes();
            if (!batch.isEmpty() && (batch.size() >= SharedCachePayload.MAX_ENTRIES_PER_PACKET
                    || bytes + entryBytes > SharedCachePayload.MAX_PACKET_BYTES)) break;
            if (entryBytes + SharedCachePayload.estimatedPacketBytes(Collections.<SharedCacheEntry>emptyList())
                    > SharedCachePayload.MAX_PACKET_BYTES) { queue.poll(); continue; }
            batch.add(queue.poll());
            bytes += entryBytes;
        }
        return batch;
    }

    public static final class ServerEvents {
        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
            flushOutboundQueues(server);
            STORE.saveIfDue(System.currentTimeMillis());
        }

        @SubscribeEvent
        public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.player instanceof EntityPlayerMP) {
                UUID id = event.player.getUniqueID();
                ENABLED_PLAYERS.remove(id);
                OUTBOUND_QUEUES.remove(id);
            }
        }
    }
}
