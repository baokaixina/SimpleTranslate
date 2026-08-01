package com.yourname.simpletranslate.cache;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Exact Forge 1.12.2 wire adapter for the complete cache-sync payload. */
public final class SharedCachePacket implements IMessage {
    private SharedCachePayload payload = SharedCachePayload.hello();

    public SharedCachePacket() { }
    public SharedCachePacket(SharedCachePayload payload) {
        this.payload = payload == null ? SharedCachePayload.hello() : payload;
    }
    public SharedCachePayload payload() { return payload; }

    @Override public void fromBytes(ByteBuf buffer) {
        int kind = ByteBufUtils.readVarInt(buffer, 2);
        int count = ByteBufUtils.readVarInt(buffer, 2);
        if (count < 0 || count > SharedCachePayload.MAX_ENTRIES_PER_PACKET) {
            throw new IllegalArgumentException("Invalid shared cache entry count: " + count);
        }
        List<SharedCacheEntry> entries = new ArrayList<SharedCacheEntry>(count);
        for (int i = 0; i < count; i++) {
            String key = readBounded(buffer, SharedCacheEntry.MAX_KEY_CHARS);
            String translation = readBounded(buffer, SharedCacheEntry.MAX_TRANSLATION_CHARS);
            String sourceText = readBounded(buffer, SharedCacheEntry.MAX_DISPLAY_CHARS);
            String translationText = readBounded(buffer, SharedCacheEntry.MAX_DISPLAY_CHARS);
            String surface = readBounded(buffer, SharedCacheEntry.MAX_SURFACE_CHARS);
            SharedCacheEntry entry = new SharedCacheEntry(key, translation, sourceText, translationText,
                    surface, buffer.readLong(), buffer.readBoolean(), buffer.readLong());
            if (!entry.isShareable()) throw new IllegalArgumentException("Invalid shared cache entry");
            entries.add(entry);
        }
        payload = new SharedCachePayload(kind, entries);
    }

    @Override public void toBytes(ByteBuf buffer) {
        List<SharedCacheEntry> entries = payload.kind() == SharedCachePayload.KIND_ENTRIES
                ? payload.entries() : Collections.<SharedCacheEntry>emptyList();
        ByteBufUtils.writeVarInt(buffer, payload.kind(), 2);
        ByteBufUtils.writeVarInt(buffer, entries.size(), 2);
        for (SharedCacheEntry entry : entries) {
            ByteBufUtils.writeUTF8String(buffer, entry.key());
            ByteBufUtils.writeUTF8String(buffer, entry.translation());
            ByteBufUtils.writeUTF8String(buffer, entry.sourceText());
            ByteBufUtils.writeUTF8String(buffer, entry.translationText());
            ByteBufUtils.writeUTF8String(buffer, entry.surface());
            buffer.writeLong(entry.createdAt());
            buffer.writeBoolean(entry.editedByPlayer());
            buffer.writeLong(entry.editedAt());
        }
    }

    private static String readBounded(ByteBuf buffer, int maxUtf8Bytes) {
        String value = ByteBufUtils.readUTF8String(buffer);
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > maxUtf8Bytes) {
            throw new IllegalArgumentException("Oversized shared cache field");
        }
        return value;
    }
}
