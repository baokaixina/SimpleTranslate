package com.yourname.simpletranslate.cache;

public final class SharedCacheNetworking {
    private static boolean registered;

    private SharedCacheNetworking() {
    }

    /**
     * Minecraft 1.20 Fabric uses classic ResourceLocation channels, so there
     * is no payload type to register. Kept as the version-neutral entry point
     * called by both client and server bootstrap.
     */
    public static synchronized void registerPayloadTypes() {
        if (registered) {
            return;
        }
        registered = true;
    }
}
