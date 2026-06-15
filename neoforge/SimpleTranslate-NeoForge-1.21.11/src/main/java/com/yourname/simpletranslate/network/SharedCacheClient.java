package com.yourname.simpletranslate.network;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SharedCacheClient {
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
        NeoForge.EVENT_BUS.addListener(SharedCacheClient::onClientTick);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        flushPendingUploads();
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
        if (ModConfig.CACHE_SERVER_SHARE_ENABLED.get()) {
            tryStartSession();
        } else {
            PENDING_UPLOADS.clear();
            nextUploadAt = 0L;
            lastSnapshotQueued = 0;
            if (canSend()) {
                SharedCacheNetworking.sendToServer(SharedCachePayload.disable());
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
        if (remoteImporting || !ModConfig.CACHE_SERVER_SHARE_ENABLED.get()) {
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
        if (remoteImporting || !ModConfig.CACHE_SERVER_SHARE_ENABLED.get() || entry == null) {
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
        if (!ModConfig.CACHE_SERVER_SHARE_ENABLED.get()) {
            return;
        }
        if (!canSend()) {
            serverSupported = false;
            return;
        }
        serverSupported = true;
        SharedCacheNetworking.sendToServer(SharedCachePayload.hello());
        SharedCacheNetworking.sendToServer(SharedCachePayload.requestSnapshot());
        enqueueLocalSnapshot();
    }

    public static void handlePayload(SharedCachePayload payload) {
        if (payload == null || payload.kind() != SharedCachePayload.KIND_ENTRIES
                || !ModConfig.CACHE_SERVER_SHARE_ENABLED.get()) {
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
            SimpleTranslateMod.onTranslationCacheEdited();
        }
    }

    private static void flushPendingUploads() {
        if (!ModConfig.CACHE_SERVER_SHARE_ENABLED.get() || !canSend()) {
            serverSupported = false;
            return;
        }
        serverSupported = true;
        long now = System.currentTimeMillis();
        List<SharedCacheEntry> batch = new ArrayList<>();
        synchronized (PENDING_UPLOADS) {
            if (PENDING_UPLOADS.isEmpty() || now < nextUploadAt) {
                return;
            }
            var iterator = PENDING_UPLOADS.values().iterator();
            while (iterator.hasNext() && batch.size() < SharedCachePayload.MAX_ENTRIES_PER_PACKET) {
                batch.add(iterator.next());
                iterator.remove();
            }
            nextUploadAt = PENDING_UPLOADS.isEmpty() ? 0L : now + UPLOAD_DELAY_MS;
        }
        if (!batch.isEmpty()) {
            SharedCacheNetworking.sendToServer(SharedCachePayload.entries(batch));
            uploadedEntries += batch.size();
        }
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
        return SharedCacheNetworking.canSendToServer();
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
