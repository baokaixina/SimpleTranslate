package com.yourname.simpletranslate.network;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.TranslationCache;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SharedCacheClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("SimpleTranslateSharedCache");
    private static final long UPLOAD_DELAY_MS = 1500L;
    private static final long SNAPSHOT_UPLOAD_DELAY_MS = 250L;
    private static final Map<String, SharedCacheEntry> PENDING_UPLOADS = new LinkedHashMap<>();
    private static boolean initialized;
    private static boolean serverSupported;
    private static boolean remoteImporting;
    private static long nextUploadAt;
    private static int lastSnapshotQueued;
    private static int uploadedEntries;
    private static int receivedEntries;

    private SharedCacheClient() {
    }

    public static synchronized void register() {
        if (initialized) {
            return;
        }
        initialized = true;
        ClientPlayNetworking.registerGlobalReceiver(SharedCachePayload.CHANNEL, (client, handler, buffer, responseSender) -> {
            SharedCachePayload payload;
            try {
                payload = SharedCachePayload.read(buffer);
            } catch (Exception e) {
                LOGGER.warn("Ignored invalid shared cache payload from server: {}", e.getMessage());
                return;
            }
            client.execute(() -> handlePayload(payload));
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> flushPendingUploads());
    }

    public static void onJoinedWorld() {
        PENDING_UPLOADS.clear();
        nextUploadAt = 0L;
        serverSupported = false;
        lastSnapshotQueued = 0;
        tryStartSession();
    }

    public static void onDisconnected() {
        PENDING_UPLOADS.clear();
        nextUploadAt = 0L;
        serverSupported = false;
        lastSnapshotQueued = 0;
    }

    public static void onShareSettingChanged() {
        if (SimpleTranslateMod.isCacheServerShareEnabled()) {
            tryStartSession();
        } else {
            PENDING_UPLOADS.clear();
            nextUploadAt = 0L;
            lastSnapshotQueued = 0;
            if (canSend()) {
                sendPayload(SharedCachePayload.disable());
            }
            serverSupported = false;
        }
    }

    public static boolean isServerSupported() {
        return serverSupported;
    }

    public static int queuedUploadCount() {
        synchronized (PENDING_UPLOADS) {
            return PENDING_UPLOADS.size();
        }
    }

    public static int lastSnapshotQueued() {
        return lastSnapshotQueued;
    }

    public static int uploadedEntries() {
        return uploadedEntries;
    }

    public static int receivedEntries() {
        return receivedEntries;
    }

    public static void enqueueLocalSnapshot() {
        if (remoteImporting || !SimpleTranslateMod.isCacheServerShareEnabled()) {
            return;
        }
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache == null) {
            return;
        }
        int queued = 0;
        for (TranslationCache.CacheViewEntry entry : cache.getEntries().values()) {
            if (enqueueLocalEntryInternal(entry, true)) {
                queued++;
            }
        }
        lastSnapshotQueued = queued;
        if (queued > 0) {
            scheduleUpload(SNAPSHOT_UPLOAD_DELAY_MS);
        }
    }

    public static void enqueueLocalEntry(TranslationCache.CacheViewEntry entry) {
        enqueueLocalEntryInternal(entry, false);
    }

    private static boolean enqueueLocalEntryInternal(TranslationCache.CacheViewEntry entry, boolean snapshot) {
        if (remoteImporting || !SimpleTranslateMod.isCacheServerShareEnabled() || entry == null) {
            return false;
        }
        if (entry.sharedImported()) {
            return false;
        }
        SharedCacheEntry shared = toSharedEntry(entry);
        if (!shared.isShareable()) {
            return false;
        }
        synchronized (PENDING_UPLOADS) {
            boolean added = !PENDING_UPLOADS.containsKey(shared.key());
            PENDING_UPLOADS.put(shared.key(), shared);
            scheduleUploadLocked(snapshot ? SNAPSHOT_UPLOAD_DELAY_MS : UPLOAD_DELAY_MS);
            return added;
        }
    }

    private static void tryStartSession() {
        if (!SimpleTranslateMod.isCacheServerShareEnabled()) {
            return;
        }
        if (!canSend()) {
            serverSupported = false;
            return;
        }
        serverSupported = true;
        sendPayload(SharedCachePayload.hello());
        sendPayload(SharedCachePayload.requestSnapshot());
        enqueueLocalSnapshot();
    }

    private static void handlePayload(SharedCachePayload payload) {
        if (payload == null || payload.kind() != SharedCachePayload.KIND_ENTRIES
                || !SimpleTranslateMod.isCacheServerShareEnabled()) {
            return;
        }
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache == null) {
            return;
        }
        int imported = 0;
        remoteImporting = true;
        try {
            for (SharedCacheEntry entry : payload.entries()) {
                if (cache.putSharedIfAbsent(entry.key(), entry.translation(), entry.sourceText(),
                        entry.translationText(), entry.editedByPlayer(), entry.createdAt(), entry.editedAt())) {
                    imported++;
                }
            }
        } finally {
            remoteImporting = false;
        }
        if (imported > 0) {
            receivedEntries += imported;
            cache.saveNow();
            SimpleTranslateMod.onSharedTranslationCacheImported();
        }
    }

    private static void flushPendingUploads() {
        if (!SimpleTranslateMod.isCacheServerShareEnabled() || !canSend()) {
            serverSupported = false;
            return;
        }
        serverSupported = true;
        long now = System.currentTimeMillis();
        List<SharedCacheEntry> batch;
        synchronized (PENDING_UPLOADS) {
            if (PENDING_UPLOADS.isEmpty() || now < nextUploadAt) {
                return;
            }
            batch = drainUploadBatchLocked();
            nextUploadAt = PENDING_UPLOADS.isEmpty() ? 0L : now + UPLOAD_DELAY_MS;
        }
        if (!batch.isEmpty()) {
            sendPayload(SharedCachePayload.entries(batch));
            uploadedEntries += batch.size();
        }
    }

    private static List<SharedCacheEntry> drainUploadBatchLocked() {
        List<SharedCacheEntry> batch = new ArrayList<>();
        int bytes = SharedCachePayload.estimatedPacketBytes(List.of());
        var iterator = PENDING_UPLOADS.values().iterator();
        while (iterator.hasNext()) {
            SharedCacheEntry entry = iterator.next();
            if (entry == null || !entry.isShareable()) {
                iterator.remove();
                continue;
            }
            int entryBytes = entry.estimatedWireBytes();
            if (!batch.isEmpty()
                    && (batch.size() >= SharedCachePayload.MAX_ENTRIES_PER_PACKET
                    || bytes + entryBytes > SharedCachePayload.MAX_PACKET_BYTES)) {
                break;
            }
            if (entryBytes + SharedCachePayload.estimatedPacketBytes(List.of()) > SharedCachePayload.MAX_PACKET_BYTES) {
                iterator.remove();
                continue;
            }
            batch.add(entry);
            bytes += entryBytes;
            iterator.remove();
        }
        return batch;
    }

    private static void scheduleUpload(long delayMs) {
        synchronized (PENDING_UPLOADS) {
            scheduleUploadLocked(delayMs);
        }
    }

    private static void scheduleUploadLocked(long delayMs) {
        long now = System.currentTimeMillis();
        long target = now + Math.max(0L, delayMs);
        if (nextUploadAt == 0L || nextUploadAt > target) {
            nextUploadAt = target;
        }
    }

    private static boolean canSend() {
        try {
            return Minecraft.getInstance().player != null && ClientPlayNetworking.canSend(SharedCachePayload.CHANNEL);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void sendPayload(SharedCachePayload payload) {
        try {
            FriendlyByteBuf buffer = PacketByteBufs.create();
            payload.write(buffer);
            ClientPlayNetworking.send(SharedCachePayload.CHANNEL, buffer);
        } catch (Exception e) {
            LOGGER.warn("Failed to send shared cache payload kind {}: {}", payload == null ? -1 : payload.kind(),
                    e.getMessage());
        }
    }

    private static SharedCacheEntry toSharedEntry(TranslationCache.CacheViewEntry entry) {
        return new SharedCacheEntry(
                entry.key(),
                entry.translation(),
                entry.sourceText(),
                entry.translationText(),
                entry.surface(),
                entry.createdAt(),
                entry.editedByPlayer(),
                entry.editedAt());
    }
}
