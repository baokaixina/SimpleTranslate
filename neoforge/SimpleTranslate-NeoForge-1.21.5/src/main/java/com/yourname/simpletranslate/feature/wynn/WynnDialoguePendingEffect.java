package com.yourname.simpletranslate.feature.wynn;

import com.yourname.simpletranslate.core.ActiveFontManager;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pending-only visual feedback for the verified Wynn dialogue text regions. */
public final class WynnDialoguePendingEffect {
    public static final long TIMEOUT_MILLIS = 180_000L;
    /**
     * Pending dialogue feedback is intentionally a single quiet underline per
     * semantic row. The shared tooltip glow renderer subdivides every edge and
     * expands it several times; using it for a multi-row, every-frame dialogue
     * overlay can submit tens of thousands of GUI fills per frame.
     */
    private static final int PENDING_UNDERLINE_COLOR = 0x6665D7FF;
    @Nullable private static GeometryCache cachedGeometry;

    private WynnDialoguePendingEffect() {
    }

    /**
     * Tracks one complete semantic frame. Cache hits call {@link #stop(String)}
     * before rendering, while failures latch the same identity off until Wynn
     * supplies a different dialogue fingerprint.
     */
    public static final class Tracker {
        @Nullable private String identity;
        @Nullable private String failedIdentity;
        private long startedAtNanos;
        private boolean active;

        public void observe(@Nullable String currentIdentity, boolean waiting, long nowNanos) {
            if (!waiting || currentIdentity == null || currentIdentity.isBlank()) {
                stop(currentIdentity);
                return;
            }
            if (!Objects.equals(identity, currentIdentity)) {
                identity = currentIdentity;
                startedAtNanos = nowNanos;
                active = !Objects.equals(failedIdentity, currentIdentity);
            } else if (active && timedOut(nowNanos)) {
                active = false;
            }
        }

        public void fail(@Nullable String currentIdentity) {
            if (currentIdentity == null) return;
            failedIdentity = currentIdentity;
            if (Objects.equals(identity, currentIdentity)) {
                active = false;
            }
        }

        public void stop(@Nullable String currentIdentity) {
            identity = currentIdentity;
            active = false;
        }

        public boolean isActive(@Nullable String currentIdentity, long nowNanos) {
            if (!active || !Objects.equals(identity, currentIdentity)) return false;
            if (timedOut(nowNanos)) {
                active = false;
                return false;
            }
            return true;
        }

        public void clear() {
            identity = null;
            failedIdentity = null;
            startedAtNanos = 0L;
            active = false;
        }

        private boolean timedOut(long nowNanos) {
            return nowNanos - startedAtNanos >= TIMEOUT_MILLIS * 1_000_000L;
        }
    }

    /**
     * Draws a constant-cost pending cue inside semantic glyph bounds only; it
     * never redraws dialogue chrome or performs animated spread rendering.
     */
    public static boolean render(GuiGraphics graphics, Font font,
                                 WynnDialogueProjection projection,
                                 int x, int y, int sourceWidth) {
        if (graphics == null || font == null || projection == null) {
            return false;
        }
        GeometryCache geometry = resolveGeometry(font, projection);
        if (geometry == null || sourceWidth != geometry.sourceWidth()) return false;
        boolean rendered = false;
        for (RelativeRegion region : geometry.regions()) {
            int left = (int) Math.floor(x + region.prefixAdvance() + region.bounds().left());
            int right = (int) Math.ceil(x + region.prefixAdvance() + region.bounds().right());
            int bottom = y + region.bounds().bottom();
            if (right > left) {
                graphics.fill(left, bottom - 1, right, bottom, PENDING_UNDERLINE_COLOR);
                rendered = true;
            }
        }
        return rendered;
    }

    @Nullable
    private static GeometryCache resolveGeometry(Font font, WynnDialogueProjection projection) {
        long revision = ActiveFontManager.resourceRevision();
        GeometryCache cached = cachedGeometry;
        if (cached != null && cached.font() == font && cached.projection() == projection
                && cached.resourceRevision() == revision) {
            return cached;
        }
        List<WynnDialogueProjection.SemanticSlot> visible = new ArrayList<>(projection.contentSlots());
        if (projection.optionVisibility() == WynnDialogueProjection.OptionVisibility.VISIBLE) {
            visible.addAll(projection.optionSlots());
        }
        List<RelativeRegion> regions = new ArrayList<>();
        WynnDialogueProjection.EventSequence sequence =
                (WynnDialogueProjection.EventSequence) projection.sourceSequence();
        for (WynnDialogueProjection.SemanticSlot slot : visible) {
            for (WynnDialogueProjection.LineRegion region : slot.regions()) {
                WynnDialogueProjection.EventSequence prefix = sequence.slice(0, region.startOrdinal());
                WynnDialogueProjection.EventSequence text = sequence.slice(
                        region.startOrdinal(), region.endOrdinal());
                float prefixAdvance = font.getSplitter().stringWidth(prefix);
                ScreenRectangle bounds = preparedBounds(font, text);
                if (!Float.isFinite(prefixAdvance) || bounds == null) continue;
                regions.add(new RelativeRegion(prefixAdvance, bounds));
            }
        }
        GeometryCache computed = new GeometryCache(font, projection, revision,
                font.width(projection.sourceActionbar()), List.copyOf(regions));
        cachedGeometry = computed;
        return computed;
    }

    @Nullable
    private static ScreenRectangle preparedBounds(Font font, FormattedCharSequence sequence) {
        ScreenRectangle bounds = com.yourname.simpletranslate.core.PreparedBoundsCompat.bounds(font, sequence);
        return bounds == null || bounds.right() <= bounds.left() || bounds.bottom() <= bounds.top()
                ? null : bounds;
    }

    private record RelativeRegion(float prefixAdvance, ScreenRectangle bounds) {
    }

    private record GeometryCache(Font font, WynnDialogueProjection projection,
                                 long resourceRevision, int sourceWidth,
                                 List<RelativeRegion> regions) {
    }
}
