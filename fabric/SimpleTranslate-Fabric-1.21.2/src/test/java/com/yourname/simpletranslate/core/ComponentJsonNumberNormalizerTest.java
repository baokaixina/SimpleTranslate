package com.yourname.simpletranslate.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentJsonNumberNormalizerTest {

    @Test
    void unitSuffixStaysOutsideTheDynamicMarker() {
        List<String> values = new ArrayList<>();
        String masked = ComponentJsonNumberNormalizer.normalizeNumbers(
                "Charges are restored every 10m", values);

        assertEquals("Charges are restored every ⟦N0⟧m", masked,
                "unit suffix stays outside the marker so the model sees the duration grammar");
        assertEquals(List.of("10"), values,
                "only the numeric part is captured for restore");
        assertEquals("Charges are restored every 10m",
                ComponentJsonNumberNormalizer.restoreNumbers(masked, values),
                "marker plus literal suffix restores the original token exactly");
    }

    @Test
    void tickingValuesShareOneCacheKeyWhileTheUnitStaysVisible() {
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        String maskedFirst = ComponentJsonNumberNormalizer.normalizeNumbers(
                "Charges are restored every 10m", first);
        String maskedSecond = ComponentJsonNumberNormalizer.normalizeNumbers(
                "Charges are restored every 12m", second);

        assertEquals(maskedFirst, maskedSecond);
        assertEquals(List.of("10"), first);
        assertEquals(List.of("12"), second);
    }

    @Test
    void percentOrdinalAndMillisecondSuffixesStayLiteral() {
        List<String> percentValues = new ArrayList<>();
        assertEquals("⟦N0⟧%",
                ComponentJsonNumberNormalizer.normalizeNumbers("50%", percentValues));
        assertEquals(List.of("50"), percentValues);

        List<String> ordinalValues = new ArrayList<>();
        assertEquals("⟦N0⟧rd",
                ComponentJsonNumberNormalizer.normalizeNumbers("3rd", ordinalValues));
        assertEquals(List.of("3"), ordinalValues);

        List<String> pingValues = new ArrayList<>();
        assertEquals("Ping: ⟦N0⟧ms",
                ComponentJsonNumberNormalizer.normalizeNumbers("Ping: 5ms", pingValues),
                "two-letter ms suffix is preferred over single-letter units");
        assertEquals(List.of("5"), pingValues);
    }

    @Test
    void suffixLessShapesKeepWholeTokenMasking() {
        List<String> fractionValues = new ArrayList<>();
        assertEquals("Durability: ⟦N0⟧",
                ComponentJsonNumberNormalizer.normalizeNumbers("Durability: 69/80", fractionValues));
        assertEquals(List.of("69/80"), fractionValues);

        List<String> coordinateValues = new ArrayList<>();
        String coordinates = ComponentJsonNumberNormalizer.normalizeNumbers(
                "at [12, 53, -1584]", coordinateValues);
        assertFalse(coordinates.contains("12"), "coordinate digits are masked");
        assertTrue(coordinates.contains("⟦N"), "coordinates still use positional markers");
        assertEquals("at [12, 53, -1584]",
                ComponentJsonNumberNormalizer.restoreNumbers(coordinates, coordinateValues),
                "coordinates restore verbatim");
    }

    @Test
    void promptMaskingKeepsTheSameLiteralSuffixShape() {
        assertEquals("Time left: <number>m",
                ComponentJsonNumberNormalizer.maskPromptDynamicNumbers("Time left: 10m"));
    }
}
