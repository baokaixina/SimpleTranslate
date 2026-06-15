package com.yourname.simpletranslate.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public final class SharedCachePayload implements CustomPacketPayload {
    public static final int KIND_HELLO = 0;
    public static final int KIND_REQUEST_SNAPSHOT = 1;
    public static final int KIND_ENTRIES = 2;
    public static final int KIND_DISABLE = 3;
    public static final int MAX_ENTRIES_PER_PACKET = 64;
    public static final int MAX_PACKET_BYTES = 30_000;
    private static final int PACKET_HEADER_BYTES = 8;

    public static final Identifier CHANNEL =
            Identifier.fromNamespaceAndPath("simple_translate", "cache_sync/v1");
    public static final CustomPacketPayload.Type<SharedCachePayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<RegistryFriendlyByteBuf, SharedCachePayload> CODEC =
            CustomPacketPayload.codec(SharedCachePayload::write, SharedCachePayload::read);

    private final int kind;
    private final List<SharedCacheEntry> entries;

    public SharedCachePayload(int kind, List<SharedCacheEntry> entries) {
        this.kind = kind;
        List<List<SharedCacheEntry>> batches = batches(entries);
        this.entries = batches.isEmpty() ? List.of() : List.copyOf(batches.get(0));
    }

    public static SharedCachePayload hello() {
        return new SharedCachePayload(KIND_HELLO, List.of());
    }

    public static SharedCachePayload requestSnapshot() {
        return new SharedCachePayload(KIND_REQUEST_SNAPSHOT, List.of());
    }

    public static SharedCachePayload disable() {
        return new SharedCachePayload(KIND_DISABLE, List.of());
    }

    public static SharedCachePayload entries(List<SharedCacheEntry> entries) {
        return new SharedCachePayload(KIND_ENTRIES, entries);
    }

    public int kind() {
        return kind;
    }

    public List<SharedCacheEntry> entries() {
        return entries;
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(kind);
        int count = Math.min(entries.size(), MAX_ENTRIES_PER_PACKET);
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            entries.get(i).write(buffer);
        }
    }

    public static SharedCachePayload read(FriendlyByteBuf buffer) {
        int kind = buffer.readVarInt();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ENTRIES_PER_PACKET) {
            throw new IllegalArgumentException("Invalid shared cache entry count: " + count);
        }
        List<SharedCacheEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(SharedCacheEntry.read(buffer));
        }
        return new SharedCachePayload(kind, entries);
    }

    public static List<List<SharedCacheEntry>> batches(List<SharedCacheEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<List<SharedCacheEntry>> result = new ArrayList<>();
        List<SharedCacheEntry> batch = new ArrayList<>();
        int bytes = estimatedPacketBytes(List.of());
        for (SharedCacheEntry entry : entries) {
            if (entry == null || !entry.isShareable()) {
                continue;
            }
            int entryBytes = entry.estimatedWireBytes();
            if (entryBytes + PACKET_HEADER_BYTES > MAX_PACKET_BYTES) {
                continue;
            }
            if (!batch.isEmpty()
                    && (batch.size() >= MAX_ENTRIES_PER_PACKET || bytes + entryBytes > MAX_PACKET_BYTES)) {
                result.add(List.copyOf(batch));
                batch.clear();
                bytes = estimatedPacketBytes(List.of());
            }
            batch.add(entry);
            bytes += entryBytes;
        }
        if (!batch.isEmpty()) {
            result.add(List.copyOf(batch));
        }
        return result;
    }

    public static int estimatedPacketBytes(List<SharedCacheEntry> entries) {
        int bytes = PACKET_HEADER_BYTES;
        if (entries != null) {
            for (SharedCacheEntry entry : entries) {
                if (entry != null) {
                    bytes += entry.estimatedWireBytes();
                }
            }
        }
        return bytes;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
