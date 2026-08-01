package com.yourname.simpletranslate.core;

import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonPassthroughPipelineTest {

    @Test
    void ordinarySurfaceKeepsItsAtomicGroups() {
        assertEquals(List.of(2, 2),
                JsonPassthroughPipeline.recoveryAtomicGroupSizesForTest(
                        "gui.component.visible_frame.item_tooltip.v1", "",
                        4, List.of(2, 2)));
    }

    @Test
    void bookSurfaceRemainsOneAtomicRecoveryGroup() {
        assertEquals(List.of(4),
                JsonPassthroughPipeline.recoveryAtomicGroupSizesForTest(
                        "book.page.component.v1", "", 4, List.of(2, 2)));
    }

    @Test
    void semanticProjectionRejectsWrongTopLevelSlotCounts() {
        ComponentVisualProjection projection = ComponentVisualProjection.project(
                "[{\"text\":\"NPC\"},{\"text\":\"Complete dialogue paragraph.\"}]", "zh_cn");

        assertNotNull(projection);
        assertNull(projection.rebuildResponseJson("[{\"text\":\"NPC\"}]"));
        assertNull(projection.rebuildResponseJson(
                "[{\"text\":\"NPC\"},{\"text\":\"段落\"},{\"text\":\"extra\"}]"));

        JsonArray rebuilt = projection.rebuildResponseJson(
                "[{\"text\":\"NPC\"},{\"text\":\"完整的对话段落。\"}]");
        assertNotNull(rebuilt);
        assertEquals(2, rebuilt.size());
    }
}
