package com.yourname.simpletranslate.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public final class SharedCachePayload implements CustomPacketPayload {
    public static final int KIND_HELLO = 0;
    public static final int KIND_REQUEST_SNAPSHOT = 1;
    public static final int KIND_ENTRIES = 2;
    public static final int KIND_DISABLE = 3;
    public static final int MAX_ENTRIES_PER_PACKET = 64;

    public static final Type<SharedCachePayload> TYPE = new Type<>(
            new ResourceLocation("simple_translate", "cache_sync/v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SharedCachePayload> CODEC =
            CustomPacketPayload.codec(SharedCachePayload::write, SharedCachePayload::read);

    private final int kind;
    private final List<SharedCacheEntry> entries;

    public SharedCachePayload(int kind, List<SharedCacheEntry> entries) {
        this.kind = kind;
        this.entries = entries == null ? List.of() : List.copyOf(entries);
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

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(kind);
        int count = Math.min(entries.size(), MAX_ENTRIES_PER_PACKET);
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            entries.get(i).write(buffer);
        }
    }

    private static SharedCachePayload read(RegistryFriendlyByteBuf buffer) {
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
}
