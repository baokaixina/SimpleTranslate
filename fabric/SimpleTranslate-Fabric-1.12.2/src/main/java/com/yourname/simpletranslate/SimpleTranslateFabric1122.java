package com.yourname.simpletranslate;

import com.yourname.simpletranslate.legacy.LegacyFabricRuntime;
import net.fabricmc.api.ClientModInitializer;

public final class SimpleTranslateFabric1122 implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LegacyFabricRuntime.initialize();
    }
}
