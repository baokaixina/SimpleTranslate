package com.yourname.simpletranslate.feature.wynn;

import com.yourname.simpletranslate.core.ActiveFontManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WynnDialogueProjectionTest {
    private static final ResourceLocation NAME_FONT =
            ResourceLocation.fromNamespaceAndPath("minecraft", "hud/dialogue/text/nameplate");
    private static final ResourceLocation BODY_FONT =
            ResourceLocation.fromNamespaceAndPath("minecraft", "hud/dialogue/text/test/body_0");

    @Test
    void singleStyleBodyUsesOneParagraphAndSourceOwnedOverlayStyle() {
        Style bodyStyle = Style.EMPTY
                .withColor(TextColor.fromRgb(0xFCB56D))
                .withShadowColor(0x55221100)
                .withBold(true)
                .withFont(BODY_FONT);
        WynnDialogueProjection projection = WynnDialogueProjection.project(
                dialogue("Hold on there, recruit.", bodyStyle, "", bodyStyle));

        assertNotNull(projection);
        WynnDialogueProjection.SemanticSlot body = bodySlot(projection);
        assertEquals("Hold on there, recruit.", body.sourceText());
        assertEquals(0, body.requestComponent().getSiblings().size());
        assertEquals(1, body.bodyAnchors().size());

        List<Component> translatedContent = projection.contentSlots().stream()
                .map(slot -> (Component) Component.literal(slot.kind() == WynnDialogueProjection.SemanticKind.BODY
                        ? "等一下，新兵。" : slot.sourceText()))
                .toList();
        WynnDialogueRenderPlan plan = projection.bindTranslations(translatedContent, List.of());

        assertNotNull(plan);
        WynnDialogueRenderPlan.TranslatedSlot translatedBody = plan.translatedSlots().stream()
                .filter(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.BODY)
                .findFirst()
                .orElseThrow();
        Style overlay = translatedBody.component().getStyle();
        assertEquals(0xFCB56D, overlay.getColor().getValue());
        assertEquals(0x55221100, overlay.getShadowColor());
        assertTrue(overlay.isBold());
        assertEquals(ActiveFontManager.CJK_FALLBACK_FONT, overlay.getFont());
        assertFalse(translatedBody.bodyMaskOrdinals().isEmpty());
    }

    @Test
    void multiColourBodyProjectsAndBindsWithSourceColours() {
        Style first = Style.EMPTY.withColor(TextColor.fromRgb(0x55AAFF)).withFont(BODY_FONT);
        Style second = Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA22)).withFont(BODY_FONT);
        WynnDialogueProjection projection = WynnDialogueProjection.project(
                dialogue("Hold on ", first, "there, recruit.", second));

        assertNotNull(projection);
        WynnDialogueProjection.SemanticSlot body = bodySlot(projection);
        assertEquals("Hold on there, recruit.", body.sourceText());
        assertEquals(2, body.requestComponent().getSiblings().size(),
                "A multi-appearance BODY exposes every source run's style to the model");

        MutableComponent response = Component.empty();
        response.append(Component.literal("等一下，").withStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0x55AAFF))));
        response.append(Component.literal("新兵。").withStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA22))));
        List<Component> translatedContent = projection.contentSlots().stream()
                .map(slot -> (Component) (slot.kind() == WynnDialogueProjection.SemanticKind.BODY
                        ? response
                        : slot.kind() == WynnDialogueProjection.SemanticKind.NAME
                        ? Component.literal("守卫")
                        : Component.literal(slot.sourceText())))
                .toList();
        WynnDialogueRenderPlan plan = projection.bindTranslations(translatedContent, List.of());

        assertNotNull(plan);
        WynnDialogueRenderPlan.TranslatedSlot bound = plan.translatedSlots().stream()
                .filter(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.BODY)
                .findFirst()
                .orElseThrow();
        assertEquals("等一下，新兵。", bound.component().getString());
        assertEquals(2, bound.component().getSiblings().size());
        Style firstOverlay = bound.component().getSiblings().get(0).getStyle();
        Style secondOverlay = bound.component().getSiblings().get(1).getStyle();
        assertEquals(0x55AAFF, firstOverlay.getColor().getValue());
        assertEquals(0xFFAA22, secondOverlay.getColor().getValue());
        assertEquals(ActiveFontManager.CJK_FALLBACK_FONT,
                firstOverlay.getFont());
        assertEquals(ActiveFontManager.CJK_FALLBACK_FONT,
                secondOverlay.getFont());
    }

    @Test
    void multiColourBodyAcceptsFlattenedResponseWithDominantAppearance() {
        Style first = Style.EMPTY.withColor(TextColor.fromRgb(0x55AAFF)).withFont(BODY_FONT);
        Style second = Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA22)).withFont(BODY_FONT);
        WynnDialogueProjection projection = WynnDialogueProjection.project(
                dialogue("Hold on ", first, "there, recruit.", second));

        assertNotNull(projection);
        List<Component> translatedContent = projection.contentSlots().stream()
                .map(slot -> (Component) (slot.kind() == WynnDialogueProjection.SemanticKind.BODY
                        ? Component.literal("等一下，新兵。")
                        : slot.kind() == WynnDialogueProjection.SemanticKind.NAME
                        ? Component.literal("守卫")
                        : Component.literal(slot.sourceText())))
                .toList();
        WynnDialogueRenderPlan plan = projection.bindTranslations(translatedContent, List.of());

        assertNotNull(plan);
        WynnDialogueRenderPlan.TranslatedSlot bound = plan.translatedSlots().stream()
                .filter(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.BODY)
                .findFirst()
                .orElseThrow();
        Style overlay = bound.component().getStyle();
        assertEquals(0xFFAA22, overlay.getColor().getValue(),
                "A flattened paragraph falls back to the appearance covering most source text");
        assertEquals(ActiveFontManager.CJK_FALLBACK_FONT,
                overlay.getFont());
    }

    @Test
    void multiColourBodyRejectsForeignResponseColour() {
        Style first = Style.EMPTY.withColor(TextColor.fromRgb(0x55AAFF)).withFont(BODY_FONT);
        Style second = Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA22)).withFont(BODY_FONT);
        WynnDialogueProjection projection = WynnDialogueProjection.project(
                dialogue("Hold on ", first, "there, recruit.", second));

        assertNotNull(projection);
        MutableComponent response = Component.empty();
        response.append(Component.literal("等一下，").withStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0x55AAFF))));
        response.append(Component.literal("新兵。").withStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0x123456))));
        List<Component> translatedContent = projection.contentSlots().stream()
                .map(slot -> (Component) (slot.kind() == WynnDialogueProjection.SemanticKind.BODY
                        ? response
                        : slot.kind() == WynnDialogueProjection.SemanticKind.NAME
                        ? Component.literal("守卫")
                        : Component.literal(slot.sourceText())))
                .toList();
        WynnDialogueRenderPlan plan = projection.bindTranslations(translatedContent, List.of());

        assertNotNull(plan, "A rejected BODY must not discard independently verified NAME translation");
        assertTrue(plan.translatedSlots().stream()
                .anyMatch(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.NAME));
        assertTrue(plan.translatedSlots().stream()
                .noneMatch(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.BODY));
    }

    @Test
    void bodyOverlayRejectsResponseThatAddsSiblingFragments() {
        Style bodyStyle = Style.EMPTY.withColor(TextColor.fromRgb(0xFCB56D)).withFont(BODY_FONT);
        WynnDialogueProjection projection = WynnDialogueProjection.project(
                dialogue("Hold on there, recruit.", bodyStyle, "", bodyStyle));

        assertNotNull(projection);
        MutableComponent malformedBody = Component.literal("等一下，");
        malformedBody.append(Component.literal("新兵。"));
        List<Component> translatedContent = projection.contentSlots().stream()
                .map(slot -> (Component) (slot.kind() == WynnDialogueProjection.SemanticKind.BODY
                        ? malformedBody
                        : slot.kind() == WynnDialogueProjection.SemanticKind.NAME
                        ? Component.literal("守卫")
                        : Component.literal(slot.sourceText())))
                .toList();

        WynnDialogueRenderPlan plan = projection.bindTranslations(translatedContent, List.of());
        assertNotNull(plan, "A rejected BODY must not discard independently verified NAME translation");
        assertTrue(plan.translatedSlots().stream()
                .anyMatch(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.NAME));
        assertTrue(plan.translatedSlots().stream()
                .noneMatch(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.BODY));
    }

    @Test
    void bodyOverlayRejectsSiblingFreeNonLiteralResponse() {
        Style bodyStyle = Style.EMPTY.withColor(TextColor.fromRgb(0xFCB56D)).withFont(BODY_FONT);
        WynnDialogueProjection projection = WynnDialogueProjection.project(
                dialogue("Hold on there, recruit.", bodyStyle, "", bodyStyle));

        assertNotNull(projection);
        Component dynamicBody = Component.translatable("fixture.wynn.dynamic.body");
        assertTrue(dynamicBody.getContents() instanceof TranslatableContents);
        List<Component> translatedContent = projection.contentSlots().stream()
                .map(slot -> (Component) (slot.kind() == WynnDialogueProjection.SemanticKind.BODY
                        ? dynamicBody
                        : slot.kind() == WynnDialogueProjection.SemanticKind.NAME
                        ? Component.literal("守卫")
                        : Component.literal(slot.sourceText())))
                .toList();

        WynnDialogueRenderPlan plan = projection.bindTranslations(translatedContent, List.of());
        assertNotNull(plan, "A non-literal BODY must not discard independently verified NAME translation");
        assertTrue(plan.translatedSlots().stream()
                .anyMatch(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.NAME));
        assertTrue(plan.translatedSlots().stream()
                .noneMatch(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.BODY));
    }

    @Test
    void shaderMarkerColourBodyStaysSourceOnly() {
        Style plain = Style.EMPTY.withFont(BODY_FONT);
        Style marker = Style.EMPTY.withColor(TextColor.fromRgb(0x00EB34)).withFont(BODY_FONT);
        WynnDialogueProjection projection = WynnDialogueProjection.project(
                dialogue("Hold on ", plain, "there, recruit.", marker));

        assertNotNull(projection);
        assertTrue(projection.contentSlots().stream()
                .noneMatch(slot -> slot.kind() == WynnDialogueProjection.SemanticKind.BODY),
                "#00EB34 is a Wynn shader movement instruction, not a display colour");
    }

    @Test
    void shadowVariedBodyProjectsAndBindsWithSourceShadows() {
        Style first = Style.EMPTY.withColor(TextColor.fromRgb(0x55AAFF)).withFont(BODY_FONT);
        Style second = Style.EMPTY.withColor(TextColor.fromRgb(0x55AAFF))
                .withShadowColor(0x40000000).withFont(BODY_FONT);
        WynnDialogueProjection projection = WynnDialogueProjection.project(
                dialogue("Hold on ", first, "there, recruit.", second));

        assertNotNull(projection);
        WynnDialogueProjection.SemanticSlot body = bodySlot(projection);
        assertEquals(2, body.requestComponent().getSiblings().size(),
                "colour-identical but shadow-varied runs stay one translatable BODY");

        MutableComponent response = Component.empty();
        response.append(Component.literal("等一下，").withStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0x55AAFF))));
        response.append(Component.literal("新兵。").withStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0x55AAFF)).withShadowColor(0x40000000)));
        List<Component> translatedContent = projection.contentSlots().stream()
                .map(slot -> (Component) (slot.kind() == WynnDialogueProjection.SemanticKind.BODY
                        ? response
                        : slot.kind() == WynnDialogueProjection.SemanticKind.NAME
                        ? Component.literal("守卫")
                        : Component.literal(slot.sourceText())))
                .toList();
        WynnDialogueRenderPlan plan = projection.bindTranslations(translatedContent, List.of());

        assertNotNull(plan);
        WynnDialogueRenderPlan.TranslatedSlot bound = plan.translatedSlots().stream()
                .filter(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.BODY)
                .findFirst()
                .orElseThrow();
        assertEquals(2, bound.component().getSiblings().size());
        assertNull(bound.component().getSiblings().get(0).getStyle().getShadowColor(),
                "the shadow-less source run keeps no shadow on its translated span");
        assertEquals(0x40000000,
                bound.component().getSiblings().get(1).getStyle().getShadowColor(),
                "each translated span keeps its own source shadow");
    }

    @Test
    void decorationMismatchedBodyStaysSourceOnly() {
        Style first = Style.EMPTY.withFont(BODY_FONT);
        Style second = Style.EMPTY.withBold(true).withFont(BODY_FONT);
        WynnDialogueProjection projection = WynnDialogueProjection.project(
                dialogue("Hold on ", first, "there, recruit.", second));

        assertNotNull(projection);
        assertTrue(projection.contentSlots().stream()
                .noneMatch(slot -> slot.kind() == WynnDialogueProjection.SemanticKind.BODY),
                "decoration-mismatched runs keep the BODY source-only");
    }

    @Test
    void inlineIconDocksIntoTranslatedFlowBeforeItsKeywordSpan() {
        ResourceLocation merchantFont = ResourceLocation.fromNamespaceAndPath("minecraft", "hud/dialogue/text/merchant/body_0");
        Style plain = Style.EMPTY.withFont(BODY_FONT);
        Style purple = Style.EMPTY.withColor(TextColor.fromRgb(0xFF55FF)).withFont(BODY_FONT);
        MutableComponent source = Component.empty();
        source.append(Component.literal("Guard").withStyle(Style.EMPTY.withFont(NAME_FONT)));
        source.append(Component.literal("You can identify items at ").withStyle(plain));
        source.append(Component.literal("\uE003").withStyle(Style.EMPTY.withFont(merchantFont)));
        source.append(Component.literal(" Item Identifier.").withStyle(purple));
        WynnDialogueProjection projection = WynnDialogueProjection.project(source);

        assertNotNull(projection);
        WynnDialogueProjection.SemanticSlot body = bodySlot(projection);
        int iconOrdinal = body.regions().stream()
                .flatMap(region -> region.visualBeforeOrdinals().stream())
                .findFirst().orElseThrow();

        MutableComponent response = Component.empty();
        response.append(Component.literal("你可以在"));
        response.append(Component.literal("物品鉴定师").withStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF55FF))));
        response.append(Component.literal("处鉴定物品。"));
        List<Component> translatedContent = projection.contentSlots().stream()
                .map(slot -> (Component) (slot.kind() == WynnDialogueProjection.SemanticKind.BODY
                        ? response
                        : Component.literal(slot.sourceText())))
                .toList();
        WynnDialogueRenderPlan plan = projection.bindTranslations(translatedContent, List.of());

        assertNotNull(plan);
        WynnDialogueRenderPlan.TranslatedSlot bound = plan.translatedSlots().stream()
                .filter(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.BODY)
                .findFirst().orElseThrow();
        assertEquals(List.of(new WynnDialogueProjection.DockedIcon(iconOrdinal, 4)),
                bound.dockedIcons(),
                "merchant icon docks into the translated flow before its keyword span");
        assertEquals(0xE003, projection.events().get(iconOrdinal).codePoint());
    }

    @Test
    void flattenedResponseKeepsInlineIconOnSourceReplay() {
        ResourceLocation merchantFont = ResourceLocation.fromNamespaceAndPath("minecraft", "hud/dialogue/text/merchant/body_0");
        Style plain = Style.EMPTY.withFont(BODY_FONT);
        Style purple = Style.EMPTY.withColor(TextColor.fromRgb(0xFF55FF)).withFont(BODY_FONT);
        MutableComponent source = Component.empty();
        source.append(Component.literal("Guard").withStyle(Style.EMPTY.withFont(NAME_FONT)));
        source.append(Component.literal("You can identify items at ").withStyle(plain));
        source.append(Component.literal("\uE003").withStyle(Style.EMPTY.withFont(merchantFont)));
        source.append(Component.literal(" Item Identifier.").withStyle(purple));
        WynnDialogueProjection projection = WynnDialogueProjection.project(source);

        assertNotNull(projection);
        List<Component> translatedContent = projection.contentSlots().stream()
                .map(slot -> (Component) (slot.kind() == WynnDialogueProjection.SemanticKind.BODY
                        ? Component.literal("你可以在物品鉴定师处鉴定物品。")
                        : Component.literal(slot.sourceText())))
                .toList();
        WynnDialogueRenderPlan plan = projection.bindTranslations(translatedContent, List.of());

        assertNotNull(plan);
        WynnDialogueRenderPlan.TranslatedSlot bound = plan.translatedSlots().stream()
                .filter(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.BODY)
                .findFirst().orElseThrow();
        assertTrue(bound.dockedIcons().isEmpty(),
                "a flattened response leaves the icon at its untouched source replay position");
    }

    @Test
    void rowWrappedKeywordStillDocksInlineIcon() {
        ResourceLocation body1Font = ResourceLocation.fromNamespaceAndPath("minecraft", "hud/dialogue/text/test/body_1");
        ResourceLocation merchantFont = ResourceLocation.fromNamespaceAndPath("minecraft", "hud/dialogue/text/merchant/body_0");
        Style plain = Style.EMPTY.withFont(BODY_FONT);
        Style plain1 = Style.EMPTY.withFont(body1Font);
        Style purple = Style.EMPTY.withColor(TextColor.fromRgb(0xFF55FF)).withFont(BODY_FONT);
        Style purple1 = Style.EMPTY.withColor(TextColor.fromRgb(0xFF55FF)).withFont(body1Font);
        Style aqua1 = Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF)).withFont(body1Font);
        MutableComponent source = Component.empty();
        source.append(Component.literal("Guard").withStyle(Style.EMPTY.withFont(NAME_FONT)));
        source.append(Component.literal("Identify your helmet at the ").withStyle(plain));
        source.append(Component.literal("\uE003").withStyle(Style.EMPTY.withFont(merchantFont)));
        source.append(Component.literal("Item").withStyle(purple));
        source.append(Component.literal("Identifier").withStyle(purple1));
        source.append(Component.literal(" and then ").withStyle(plain1));
        source.append(Component.literal("return to me").withStyle(aqua1));
        WynnDialogueProjection projection = WynnDialogueProjection.project(source);

        assertNotNull(projection);
        WynnDialogueProjection.SemanticSlot body = bodySlot(projection);
        int iconOrdinal = body.regions().stream()
                .flatMap(region -> region.visualBeforeOrdinals().stream())
                .findFirst().orElseThrow();

        MutableComponent response = Component.empty();
        response.append(Component.literal("在"));
        response.append(Component.literal("物品鉴定师").withStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF55FF))));
        response.append(Component.literal("处鉴定你的头盔，然后"));
        response.append(Component.literal("装备好回来").withStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF))));
        List<Component> translatedContent = projection.contentSlots().stream()
                .map(slot -> (Component) (slot.kind() == WynnDialogueProjection.SemanticKind.BODY
                        ? response
                        : Component.literal(slot.sourceText())))
                .toList();
        WynnDialogueRenderPlan plan = projection.bindTranslations(translatedContent, List.of());

        assertNotNull(plan);
        WynnDialogueRenderPlan.TranslatedSlot bound = plan.translatedSlots().stream()
                .filter(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.BODY)
                .findFirst().orElseThrow();
        assertEquals(List.of(new WynnDialogueProjection.DockedIcon(iconOrdinal, 1)),
                bound.dockedIcons(),
                "a keyword wrapped across two same-colour rows still docks its icon");
    }

    @Test
    void sameAppearanceOnBothSidesKeepsIconOnSourceReplay() {
        ResourceLocation merchantFont = ResourceLocation.fromNamespaceAndPath("minecraft", "hud/dialogue/text/merchant/body_0");
        Style plain = Style.EMPTY.withFont(BODY_FONT);
        MutableComponent source = Component.empty();
        source.append(Component.literal("Guard").withStyle(Style.EMPTY.withFont(NAME_FONT)));
        source.append(Component.literal("Before ").withStyle(plain));
        source.append(Component.literal("\uE003").withStyle(Style.EMPTY.withFont(merchantFont)));
        source.append(Component.literal(" After").withStyle(plain));
        WynnDialogueProjection projection = WynnDialogueProjection.project(source);

        assertNotNull(projection);
        MutableComponent response = Component.empty();
        response.append(Component.literal("之前"));
        response.append(Component.literal("之后"));
        List<Component> translatedContent = projection.contentSlots().stream()
                .map(slot -> (Component) (slot.kind() == WynnDialogueProjection.SemanticKind.BODY
                        ? response
                        : Component.literal(slot.sourceText())))
                .toList();
        WynnDialogueRenderPlan plan = projection.bindTranslations(translatedContent, List.of());

        assertNotNull(plan);
        WynnDialogueRenderPlan.TranslatedSlot bound = plan.translatedSlots().stream()
                .filter(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.BODY)
                .findFirst().orElseThrow();
        assertTrue(bound.dockedIcons().isEmpty(),
                "the keyword appearance also occurs before the icon, so docking is ambiguous");
    }

    @Test
    void mixedBodyCarrierFamiliesCannotMakeAnOverlaySafe() {
        Style first = Style.EMPTY.withFont(BODY_FONT);
        Style foreign = Style.EMPTY.withFont(ResourceLocation.fromNamespaceAndPath("minecraft", "hud/dialogue/text/foreign/body_1"));
        WynnDialogueProjection projection = WynnDialogueProjection.project(
                dialogue("Hold on ", first, "there, recruit.", foreign));

        assertNotNull(projection);
        assertTrue(projection.contentSlots().stream()
                .noneMatch(slot -> slot.kind() == WynnDialogueProjection.SemanticKind.BODY));
    }

    @Test
    void nonBodySlotRejectsInjectedPrivateUseAndFormatControls() {
        Style bodyStyle = Style.EMPTY.withFont(BODY_FONT);
        WynnDialogueProjection projection = WynnDialogueProjection.project(
                dialogue("Hold on there, recruit.", bodyStyle, "", bodyStyle));

        assertNotNull(projection);
        List<Component> puaContent = projection.contentSlots().stream()
                .map(slot -> (Component) (slot.kind() == WynnDialogueProjection.SemanticKind.NAME
                        ? Component.literal("守卫")
                        : slot.kind() == WynnDialogueProjection.SemanticKind.BODY
                        ? Component.literal("等一下，新兵。")
                        : Component.literal(slot.sourceText())))
                .toList();
        assertTrue(projection.bindTranslations(puaContent, List.of()) == null);

        List<Component> formatContent = projection.contentSlots().stream()
                .map(slot -> (Component) (slot.kind() == WynnDialogueProjection.SemanticKind.NAME
                        ? Component.literal("守卫‍")
                        : slot.kind() == WynnDialogueProjection.SemanticKind.BODY
                        ? Component.literal("等一下，新兵。")
                        : Component.literal(slot.sourceText())))
                .toList();
        assertTrue(projection.bindTranslations(formatContent, List.of()) == null);
    }

    @Test
    void bodyOverlayTextRejectsVisualControlsAndPrivateUseGlyphs() {
        assertTrue(WynnDialogueProjection.isSafeBodyOverlayText("请先鉴定护甲。"));
        assertFalse(WynnDialogueProjection.isSafeBodyOverlayText("请先\n鉴定护甲。"));
        assertFalse(WynnDialogueProjection.isSafeBodyOverlayText("请先鉴定护甲。"));
        assertFalse(WynnDialogueProjection.isSafeBodyOverlayText("请先★鉴定护甲。"));
    }

    private static WynnDialogueProjection.SemanticSlot bodySlot(WynnDialogueProjection projection) {
        return projection.contentSlots().stream()
                .filter(slot -> slot.kind() == WynnDialogueProjection.SemanticKind.BODY)
                .findFirst()
                .orElseThrow();
    }

    private static Component dialogue(String firstBodyText, Style firstBodyStyle,
                                      String secondBodyText, Style secondBodyStyle) {
        MutableComponent source = Component.empty();
        source.append(Component.literal("Guard").withStyle(Style.EMPTY
                .withColor(TextColor.fromRgb(0x44AA44))
                .withFont(NAME_FONT)));
        source.append(Component.literal(firstBodyText).withStyle(firstBodyStyle));
        if (!secondBodyText.isEmpty()) {
            source.append(Component.literal(secondBodyText).withStyle(secondBodyStyle));
        }
        return source;
    }
}
