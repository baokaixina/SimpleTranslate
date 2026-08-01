package com.yourname.simpletranslate.feature.wynn;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WynnDialogueRenderPlanTest {

    @Test
    void paragraphFlowWrapsChineseAcrossSuccessiveRows() {
        assertEquals(List.of("在物品鉴定师", "处鉴定物品，", "然后回来找我。"),
                WynnDialogueRenderPlan.wrapParagraphForTest(
                        "在物品鉴定师处鉴定物品，然后回来找我。", 6, 6, 7));
    }

    @Test
    void paragraphFlowUsesWordBoundaryWhenAvailable() {
        assertEquals(List.of("Bring this", "item to the", "identifier"),
                WynnDialogueRenderPlan.wrapParagraphForTest(
                        "Bring this item to the identifier", 10, 11, 10));
    }

    @Test
    void paragraphFlowFailsRatherThanClippingPastNativeRows() {
        assertTrue(WynnDialogueRenderPlan.wrapParagraphForTest(
                "这是一个超过原生对话框容量的非常长的段落", 4, 4).isEmpty());
    }

    @Test
    void overlaySlicesKeepEverySpanColour() {
        Style white = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF));
        Style aqua = Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF));
        MutableComponent overlay = Component.empty();
        overlay.append(Component.literal("你可以在").withStyle(white));
        overlay.append(Component.literal("物品鉴定师").withStyle(aqua));
        overlay.append(Component.literal("处鉴定").withStyle(white));

        WynnDialogueRenderPlan.NormalizedOverlay normalized =
                WynnDialogueRenderPlan.normalizeOverlay(overlay);
        assertEquals("你可以在物品鉴定师处鉴定", normalized.text());

        MutableComponent slice = WynnDialogueRenderPlan.sliceOverlaySpans(normalized.spans(), 3, 9);
        assertEquals("在物品鉴定师", slice.getString());
        assertEquals(2, slice.getSiblings().size());
        assertEquals(white, slice.getSiblings().get(0).getStyle());
        assertEquals(aqua, slice.getSiblings().get(1).getStyle());
    }

    @Test
    void normalizeOverlayCollapsesWhitespaceWithoutLosingSpanAlignment() {
        Style white = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF));
        Style aqua = Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF));
        MutableComponent overlay = Component.empty();
        overlay.append(Component.literal("你 好").withStyle(white));
        overlay.append(Component.literal("  世界").withStyle(aqua));

        WynnDialogueRenderPlan.NormalizedOverlay normalized =
                WynnDialogueRenderPlan.normalizeOverlay(overlay);
        assertEquals("你 好 世界", normalized.text());

        MutableComponent slice = WynnDialogueRenderPlan.sliceOverlaySpans(
                normalized.spans(), 0, normalized.text().length());
        assertEquals(normalized.text(), slice.getString());
        int lastIndex = slice.getSiblings().size() - 1;
        assertTrue(lastIndex >= 0);
        assertEquals(aqua, slice.getSiblings().get(lastIndex).getStyle());
    }

    @Test
    void cjkTextFillsEveryRowBesideAnIconInsteadOfStoppingAtOneSpace() {
        assertEquals(List.of("你可以在物品鉴定师处鉴定", "物品。它们遍布世界各地的", "城镇。"),
                WynnDialogueRenderPlan.wrapParagraphForTest(
                        "你可以在物品鉴定师处鉴定物品。它们遍布世界各地的城镇。", 12, 12, 12));
    }

    @Test
    void closingPunctuationNeverStartsAWrappedRow() {
        assertEquals(List.of("你可以在物品鉴定师处鉴定物", "品。它们遍布"),
                WynnDialogueRenderPlan.wrapParagraphForTest(
                        "你可以在物品鉴定师处鉴定物品。它们遍布", 13, 13));
    }

    @Test
    void latinWordsStayAtomicInsideCjkProse() {
        assertEquals(List.of("先鉴定", "armour 再", "来找我"),
                WynnDialogueRenderPlan.wrapParagraphForTest(
                        "先鉴定 armour 再来找我", 8, 8, 8));
    }
}
