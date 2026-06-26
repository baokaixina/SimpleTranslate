package com.yourname.simpletranslate;

import com.yourname.simpletranslate.cache.SharedCacheNetworking;
import com.yourname.simpletranslate.cache.SharedCacheServer;
import net.fabricmc.api.ModInitializer;

public class SimpleTranslateServerMod implements ModInitializer {
    @Override
    public void onInitialize() {
        SharedCacheNetworking.registerPayloadTypes();
        SharedCacheServer.register();
    }
}
