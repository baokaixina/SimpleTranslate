package com.yourname.simpletranslate.feature.tooltip;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TooltipTranslationTriggerStateTest {
    private static final long MILLIS = TimeUnit.MILLISECONDS.toNanos(1L);

    @AfterEach
    void clearState() {
        TooltipTranslationTriggerState.clearHoverIntent();
    }

    @Test
    void stableFinalRowsBecomeEligibleAfterHoverDwell() {
        long start = MILLIS;
        assertFalse(TooltipTranslationTriggerState.allowHoverRequestAtForTest("final", start));
        assertFalse(TooltipTranslationTriggerState.allowHoverRequestAtForTest(
                "final", start + 349L * MILLIS));
        assertTrue(TooltipTranslationTriggerState.allowHoverRequestAtForTest(
                "final", start + 350L * MILLIS));
    }

    @Test
    void changedRowsRestartHoverDwell() {
        long start = MILLIS;
        assertFalse(TooltipTranslationTriggerState.allowHoverRequestAtForTest("preliminary", start));
        assertFalse(TooltipTranslationTriggerState.allowHoverRequestAtForTest(
                "final", start + 349L * MILLIS));
        assertFalse(TooltipTranslationTriggerState.allowHoverRequestAtForTest(
                "final", start + 698L * MILLIS));
        assertTrue(TooltipTranslationTriggerState.allowHoverRequestAtForTest(
                "final", start + 699L * MILLIS));
    }

    @Test
    void continuityGapRestartsHoverDwell() {
        long start = MILLIS;
        long resumed = start + TimeUnit.SECONDS.toNanos(3L);
        assertFalse(TooltipTranslationTriggerState.allowHoverRequestAtForTest("final", start));
        assertFalse(TooltipTranslationTriggerState.allowHoverRequestAtForTest("final", resumed));
        assertFalse(TooltipTranslationTriggerState.allowHoverRequestAtForTest(
                "final", resumed + 349L * MILLIS));
        assertTrue(TooltipTranslationTriggerState.allowHoverRequestAtForTest(
                "final", resumed + 350L * MILLIS));
    }

    @Test
    void absentItemTooltipRestartsHoverDwellForAnIdenticalSignature() {
        long start = MILLIS;
        assertFalse(TooltipTranslationTriggerState.allowHoverRequestAtForTest("final", start));
        assertFalse(TooltipTranslationTriggerState.allowHoverRequestAtForTest(
                "final", start + 300L * MILLIS));

        TooltipTranslationTriggerState.clearItemHoverIntent();

        assertFalse(TooltipTranslationTriggerState.allowHoverRequestAtForTest(
                "final", start + 301L * MILLIS));
        assertFalse(TooltipTranslationTriggerState.allowHoverRequestAtForTest(
                "final", start + 650L * MILLIS));
        assertTrue(TooltipTranslationTriggerState.allowHoverRequestAtForTest(
                "final", start + 651L * MILLIS));
    }
}
