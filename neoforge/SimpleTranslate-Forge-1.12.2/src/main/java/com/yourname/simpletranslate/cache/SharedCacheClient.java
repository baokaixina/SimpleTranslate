package com.yourname.simpletranslate.cache;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Forge 1.12.2 client adapter for the baseline opt-in server cache exchange. */
public final class SharedCacheClient {
    private static final long UPLOAD_DELAY_MS = 1500L;
    private static final long SNAPSHOT_UPLOAD_DELAY_MS = 250L;
    private static final Map<String, SharedCacheEntry> PENDING_UPLOADS =
            new LinkedHashMap<String, SharedCacheEntry>();
    private static final AtomicBoolean REMOTE_IMPORTING = new AtomicBoolean();
    private static boolean initialized;
    private static boolean serverSupported;
    private static long nextUploadAt;
    private static int lastSnapshotQueued;
    private static int uploadedEntries;
    private static int receivedEntries;

    private SharedCacheClient() { }

    public static synchronized void register() {
        if (initialized) return;
        initialized = true;
        FMLCommonHandler.instance().bus().register(new ClientEvents());
    }

    public static void onJoinedWorld() {
        clearPending();
        serverSupported = false;
        tryStartSession();
    }

    public static void onDisconnected() {
        clearPending();
        serverSupported = false;
    }

    public static void onShareSettingChanged() {
        if (SimpleTranslateForge1122.isCacheServerShareEnabled()) {
            tryStartSession();
        } else {
            clearPending();
            if (canSend()) SharedCacheNetworking.sendToServer(SharedCachePayload.disable());
            serverSupported = false;
        }
    }

    public static boolean isServerSupported() { return serverSupported; }
    public static int queuedUploadCount() { synchronized (PENDING_UPLOADS) { return PENDING_UPLOADS.size(); } }
    public static int lastSnapshotQueued() { return lastSnapshotQueued; }
    public static int uploadedEntries() { return uploadedEntries; }
    public static int receivedEntries() { return receivedEntries; }

    public static void enqueueLocalSnapshot() {
        if (REMOTE_IMPORTING.get() || !SimpleTranslateForge1122.isCacheServerShareEnabled()) return;
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        TranslationCache cache = engine == null ? null : engine.getTranslationCache();
        if (cache == null) return;
        int queued = 0;
        for (TranslationCache.CacheViewEntry entry : cache.getEntries().values()) {
            if (enqueueLocalEntryInternal(entry, true)) queued++;
        }
        lastSnapshotQueued = queued;
        if (queued > 0) scheduleUpload(SNAPSHOT_UPLOAD_DELAY_MS);
    }

    public static void enqueueLocalEntry(TranslationCache.CacheViewEntry entry) {
        enqueueLocalEntryInternal(entry, false);
    }

    private static boolean enqueueLocalEntryInternal(TranslationCache.CacheViewEntry entry, boolean snapshot) {
        if (REMOTE_IMPORTING.get() || !SimpleTranslateForge1122.isCacheServerShareEnabled()
                || entry == null || entry.sharedImported()) return false;
        SharedCacheEntry shared = toSharedEntry(entry);
        if (!shared.isShareable()) return false;
        synchronized (PENDING_UPLOADS) {
            boolean added = !PENDING_UPLOADS.containsKey(shared.key());
            PENDING_UPLOADS.put(shared.key(), shared);
            scheduleUploadLocked(snapshot ? SNAPSHOT_UPLOAD_DELAY_MS : UPLOAD_DELAY_MS);
            return added;
        }
    }

    private static void tryStartSession() {
        if (!SimpleTranslateForge1122.isCacheServerShareEnabled() || !canSend()) {
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
                || !SimpleTranslateForge1122.isCacheServerShareEnabled()) return;
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        if (engine == null || !REMOTE_IMPORTING.compareAndSet(false, true)) return;
        int imported;
        try {
            imported = engine.importSharedCacheEntries(payload.entries());
        } finally {
            REMOTE_IMPORTING.set(false);
        }
        if (imported > 0) {
            receivedEntries += imported;
            SimpleTranslateForge1122.onSharedTranslationCacheImported();
        }
    }

    private static void flushPendingUploads() {
        if (!SimpleTranslateForge1122.isCacheServerShareEnabled() || !canSend()) {
            serverSupported = false;
            return;
        }
        serverSupported = true;
        long now = System.currentTimeMillis();
        List<SharedCacheEntry> batch;
        synchronized (PENDING_UPLOADS) {
            if (PENDING_UPLOADS.isEmpty() || now < nextUploadAt) return;
            batch = drainUploadBatchLocked();
            nextUploadAt = PENDING_UPLOADS.isEmpty() ? 0L : now + UPLOAD_DELAY_MS;
        }
        if (!batch.isEmpty()) {
            SharedCacheNetworking.sendToServer(SharedCachePayload.entries(batch));
            uploadedEntries += batch.size();
        }
    }

    private static List<SharedCacheEntry> drainUploadBatchLocked() {
        List<SharedCacheEntry> batch = new ArrayList<SharedCacheEntry>();
        int bytes = SharedCachePayload.estimatedPacketBytes(Collections.<SharedCacheEntry>emptyList());
        Iterator<SharedCacheEntry> iterator = PENDING_UPLOADS.values().iterator();
        while (iterator.hasNext()) {
            SharedCacheEntry entry = iterator.next();
            if (entry == null || !entry.isShareable()) { iterator.remove(); continue; }
            int entryBytes = entry.estimatedWireBytes();
            if (!batch.isEmpty() && (batch.size() >= SharedCachePayload.MAX_ENTRIES_PER_PACKET
                    || bytes + entryBytes > SharedCachePayload.MAX_PACKET_BYTES)) break;
            if (entryBytes + SharedCachePayload.estimatedPacketBytes(Collections.<SharedCacheEntry>emptyList())
                    > SharedCachePayload.MAX_PACKET_BYTES) { iterator.remove(); continue; }
            batch.add(entry);
            bytes += entryBytes;
            iterator.remove();
        }
        return batch;
    }

    private static void scheduleUpload(long delayMs) {
        synchronized (PENDING_UPLOADS) { scheduleUploadLocked(delayMs); }
    }
    private static void scheduleUploadLocked(long delayMs) {
        long target = System.currentTimeMillis() + Math.max(0L, delayMs);
        if (nextUploadAt == 0L || nextUploadAt > target) nextUploadAt = target;
    }
    private static void clearPending() {
        synchronized (PENDING_UPLOADS) {
            PENDING_UPLOADS.clear();
            nextUploadAt = 0L;
            lastSnapshotQueued = 0;
        }
    }
    private static boolean canSend() { return SharedCacheNetworking.canSendToServer(); }
    private static SharedCacheEntry toSharedEntry(TranslationCache.CacheViewEntry entry) {
        return new SharedCacheEntry(entry.key(), entry.translation(), entry.sourceText(),
                entry.translationText(), entry.surface(), entry.createdAt(),
                entry.editedByPlayer(), entry.editedAt());
    }

    public static final class ClientEvents {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) flushPendingUploads();
        }
    }
}
