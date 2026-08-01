package com.yourname.simpletranslate.core;

import com.yourname.simpletranslate.mixin.FontManagerAccessor;
import net.minecraft.client.gui.fonts.FontResourceManager;
import net.minecraft.client.gui.fonts.Font;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds the live {@link FontResourceManager} captured after reload so glyph fallback can
 * resolve the mod-owned CJK fallback without a non-private static method on a
 * Mixin. Resource packs used by servers commonly replace
 * {@code minecraft:default}; the private font id deliberately lives in the
 * mod namespace so those replacements cannot remove its unifont provider.
 */
public final class ActiveFontManager {
    public static final ResourceLocation CJK_FALLBACK_FONT =
            new ResourceLocation("simple_translate", "cjk");

    @Nullable
    private static volatile FontResourceManager active;
    private static final AtomicLong RESOURCE_REVISION = new AtomicLong();

    private ActiveFontManager() {
    }

    public static void setActive(@Nullable FontResourceManager manager) {
        active = manager;
        // FontResourceManager#apply can replace FontSets while retaining the same
        // manager and client Font instances. Layout caches therefore need a
        // resource revision in addition to object identity.
        RESOURCE_REVISION.incrementAndGet();
    }

    public static void clearIfActive(@Nullable FontResourceManager manager) {
        if (active == manager) {
            active = null;
            RESOURCE_REVISION.incrementAndGet();
        }
    }

    /**
     * Forge loader adaptation: the reload listener is the anonymous
     * FontResourceManager$1 whose synthetic outer field cannot be shadowed on
     * ForgeGradle/Mixin 0.8.5, so the reload mixin only bumps the revision;
     * the manager instance itself is captured once at construction.
     */
    public static void notifyReloaded() {
        RESOURCE_REVISION.incrementAndGet();
    }

    public static long resourceRevision() {
        return RESOURCE_REVISION.get();
    }

    @Nullable
    public static Font getCjkFallbackFontSet() {
        FontResourceManager manager = active;
        if (manager == null) {
            return null;
        }
        return ((FontManagerAccessor) manager).simple_translate$getFontSets().get(CJK_FALLBACK_FONT);
    }
}
