package com.yourname.simpletranslate.core;

import com.yourname.simpletranslate.mixin.FontManagerAccessor;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds the live {@link FontManager} captured after reload so glyph fallback can
 * resolve the mod-owned CJK fallback without a non-private static method on a
 * Mixin. Resource packs used by servers commonly replace
 * {@code minecraft:default}; the private font id deliberately lives in the
 * mod namespace so those replacements cannot remove its unifont provider.
 */
public final class ActiveFontManager {
    public static final ResourceLocation CJK_FALLBACK_FONT =
            ResourceLocation.fromNamespaceAndPath("simple_translate", "cjk");

    @Nullable
    private static volatile FontManager active;
    private static final AtomicLong RESOURCE_REVISION = new AtomicLong();

    private ActiveFontManager() {
    }

    public static void setActive(@Nullable FontManager manager) {
        active = manager;
        // FontManager#apply can replace FontSets while retaining the same
        // manager and client Font instances. Layout caches therefore need a
        // resource revision in addition to object identity.
        RESOURCE_REVISION.incrementAndGet();
    }

    public static void clearIfActive(@Nullable FontManager manager) {
        if (active == manager) {
            active = null;
            RESOURCE_REVISION.incrementAndGet();
        }
    }

    public static long resourceRevision() {
        return RESOURCE_REVISION.get();
    }

    @Nullable
    public static FontSet getCjkFallbackFontSet() {
        return getFontSet(CJK_FALLBACK_FONT);
    }

    /** Resolves any live FontSet through the active 1.21.5 font manager. */
    @Nullable
    public static FontSet getFontSet(ResourceLocation font) {
        FontManager manager = active;
        if (manager == null || font == null) {
            return null;
        }
        return ((FontManagerAccessor) manager).simple_translate$getFontSetRaw(font);
    }
}
