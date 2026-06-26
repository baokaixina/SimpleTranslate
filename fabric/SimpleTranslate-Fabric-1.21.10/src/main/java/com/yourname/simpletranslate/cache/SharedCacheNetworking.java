package com.yourname.simpletranslate.cache;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class SharedCacheNetworking {
    private static boolean registered;

    private SharedCacheNetworking() {
    }

    public static synchronized void registerPayloadTypes() {
        if (registered) {
            return;
        }
        registered = true;
        PayloadTypeRegistry.playC2S().register(SharedCachePayload.TYPE, SharedCachePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SharedCachePayload.TYPE, SharedCachePayload.CODEC);
    }
}
