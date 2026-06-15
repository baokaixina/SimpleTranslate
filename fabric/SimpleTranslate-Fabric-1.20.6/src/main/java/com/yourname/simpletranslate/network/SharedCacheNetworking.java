package com.yourname.simpletranslate.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class SharedCacheNetworking {
    private SharedCacheNetworking() {
    }

    public static void registerPayloadTypes() {
        PayloadTypeRegistry.playC2S().register(SharedCachePayload.TYPE, SharedCachePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SharedCachePayload.TYPE, SharedCachePayload.CODEC);
    }
}
