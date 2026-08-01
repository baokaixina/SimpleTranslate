package com.yourname.simpletranslate.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Baseline cache-sync protocol model; Forge serialization lives in SharedCachePacket. */
public final class SharedCachePayload {
    public static final int KIND_HELLO = 0;
    public static final int KIND_REQUEST_SNAPSHOT = 1;
    public static final int KIND_ENTRIES = 2;
    public static final int KIND_DISABLE = 3;
    public static final int MAX_ENTRIES_PER_PACKET = 64;
    public static final int MAX_PACKET_BYTES = 30000;
    private static final int PACKET_HEADER_BYTES = 8;

    private final int kind;
    private final List<SharedCacheEntry> entries;

    public SharedCachePayload(int kind, List<SharedCacheEntry> entries) {
        this.kind = kind;
        if (kind == KIND_ENTRIES) {
            List<List<SharedCacheEntry>> batches = batches(entries);
            this.entries = batches.isEmpty() ? Collections.<SharedCacheEntry>emptyList()
                    : Collections.unmodifiableList(new ArrayList<SharedCacheEntry>(batches.get(0)));
        } else {
            this.entries = Collections.emptyList();
        }
    }

    public static SharedCachePayload hello() { return new SharedCachePayload(KIND_HELLO, null); }
    public static SharedCachePayload requestSnapshot() { return new SharedCachePayload(KIND_REQUEST_SNAPSHOT, null); }
    public static SharedCachePayload disable() { return new SharedCachePayload(KIND_DISABLE, null); }
    public static SharedCachePayload entries(List<SharedCacheEntry> entries) {
        return new SharedCachePayload(KIND_ENTRIES, entries);
    }
    public int kind() { return kind; }
    public List<SharedCacheEntry> entries() { return entries; }

    public static List<List<SharedCacheEntry>> batches(List<SharedCacheEntry> entries) {
        if (entries == null || entries.isEmpty()) return Collections.emptyList();
        List<List<SharedCacheEntry>> result = new ArrayList<List<SharedCacheEntry>>();
        List<SharedCacheEntry> batch = new ArrayList<SharedCacheEntry>();
        int bytes = PACKET_HEADER_BYTES;
        for (SharedCacheEntry entry : entries) {
            if (entry == null || !entry.isShareable()) continue;
            int entryBytes = entry.estimatedWireBytes();
            if (entryBytes + PACKET_HEADER_BYTES > MAX_PACKET_BYTES) continue;
            if (!batch.isEmpty() && (batch.size() >= MAX_ENTRIES_PER_PACKET
                    || bytes + entryBytes > MAX_PACKET_BYTES)) {
                result.add(Collections.unmodifiableList(new ArrayList<SharedCacheEntry>(batch)));
                batch.clear();
                bytes = PACKET_HEADER_BYTES;
            }
            batch.add(entry);
            bytes += entryBytes;
        }
        if (!batch.isEmpty()) result.add(Collections.unmodifiableList(new ArrayList<SharedCacheEntry>(batch)));
        return result;
    }

    public static int estimatedPacketBytes(List<SharedCacheEntry> entries) {
        int bytes = PACKET_HEADER_BYTES;
        if (entries != null) for (SharedCacheEntry entry : entries) {
            if (entry != null) bytes += entry.estimatedWireBytes();
        }
        return bytes;
    }
}
