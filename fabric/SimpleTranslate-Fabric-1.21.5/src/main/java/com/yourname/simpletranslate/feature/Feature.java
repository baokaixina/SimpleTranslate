package com.yourname.simpletranslate.feature;

/**
 * Base contract for all translation feature modules. A feature owns its
 * lifecycle hooks: enable/disable, world switch, and runtime reset (when
 * language, cache, or blacklist changes invalidate in-flight state).
 *
 * <p>Features are registered in {@link com.yourname.simpletranslate.SimpleTranslateMod}
 * and their hooks are called on the main client thread.</p>
 */
public interface Feature {

    /** Called once during client initialization to register listeners, key bindings, etc. */
    default void onInitialize() {
    }

    /** Called when the player joins a world (or switches worlds). */
    default void onWorldSwitch(String worldId) {
    }

    /** Called when the translation runtime is reset (config/cache/blacklist change). */
    default void onRuntimeReset(String reason) {
    }

    /** Human-readable name for diagnostics. */
    String name();
}
