package com.yourname.simpletranslate.feature.wynn;

import com.yourname.simpletranslate.core.ActiveFontManager;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WynnSemanticStyleTest {

    @Test
    void overlayCopiesExplicitRgbShadowAndDecorationsIntoCjkFont() {
        Style source = Style.EMPTY
                .withColor(TextColor.fromRgb(0x12ABEF))
                .withShadowColor(0x66778899)
                .withBold(true)
                .withItalic(true)
                .withUnderlined(true)
                .withStrikethrough(true)
                .withFont(ResourceLocation.fromNamespaceAndPath("minecraft", "hud/dialogue/text/test/body_0"));

        Style overlay = WynnSemanticStyle.forOverlay(source);

        assertEquals(0x12ABEF, overlay.getColor().getValue());
        assertEquals(source.getShadowColor(), overlay.getShadowColor());
        assertTrue(overlay.isBold());
        assertTrue(overlay.isItalic());
        assertTrue(overlay.isUnderlined());
        assertTrue(overlay.isStrikethrough());
        assertEquals(ActiveFontManager.CJK_FALLBACK_FONT, overlay.getFont());
    }

    @Test
    void visibleStyleIdentityDistinguishesRgbShadowAndSourceFont() {
        Style base = Style.EMPTY
                .withColor(TextColor.fromRgb(0x336699))
                .withShadowColor(0x01020304)
                .withFont(ResourceLocation.fromNamespaceAndPath("minecraft", "hud/dialogue/text/test/body_0"));
        Style differentRgb = base.withColor(TextColor.fromRgb(0x33669A));
        Style differentShadow = base.withShadowColor(0x01020305);
        Style differentFont = base.withFont(ResourceLocation.fromNamespaceAndPath("minecraft", "hud/dialogue/text/test/body_1"));

        assertTrue(WynnSemanticStyle.sameStableStyle(base, base));
        assertFalse(WynnSemanticStyle.sameStableStyle(base, differentRgb));
        assertFalse(WynnSemanticStyle.sameStableStyle(base, differentShadow));
        assertFalse(WynnSemanticStyle.sameStableStyle(base, differentFont));
        assertTrue(WynnSemanticStyle.sameBodyOverlayAppearance(base, differentFont));
        assertNotEquals(WynnSemanticStyle.visualFingerprint(base),
                WynnSemanticStyle.visualFingerprint(differentRgb));
        assertNull(WynnSemanticStyle.forOverlay(Style.EMPTY).getColor());
    }

    @Test
    void obfuscatedSourceCannotBecomeStableOverlay() {
        assertFalse(WynnSemanticStyle.isOverlaySafe(Style.EMPTY.withObfuscated(true)));
        assertTrue(WynnSemanticStyle.isOverlaySafe(Style.EMPTY));
    }
}
