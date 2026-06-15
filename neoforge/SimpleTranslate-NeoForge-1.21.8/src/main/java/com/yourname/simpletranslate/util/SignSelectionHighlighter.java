package com.yourname.simpletranslate.util;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class SignSelectionHighlighter {
    private static boolean registered;

    private SignSelectionHighlighter() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        NeoForge.EVENT_BUS.addListener(SignSelectionHighlighter::renderSelections);
    }

    private static void renderSelections(RenderLevelStageEvent.AfterEntities event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        MultiBufferSource.BufferSource consumers = minecraft.renderBuffers().bufferSource();
        if (consumers == null) {
            return;
        }

        VertexConsumer lines = consumers.getBuffer(RenderType.lines());
        Vec3 cameraPos = event.getCamera().getPosition();
        for (SignContextSelectionManager.SelectionView selection :
                SignContextSelectionManager.getRenderableSelections(minecraft.level)) {
            float[] color = colorFor(selection.state());
            AABB box = new AABB(selection.pos()).inflate(0.04D).move(-cameraPos.x, -cameraPos.y, -cameraPos.z);
            ShapeRenderer.renderLineBox(event.getPoseStack(), lines, box, color[0], color[1], color[2], 1.0F);
        }
        consumers.endBatch(RenderType.lines());
    }

    private static float[] colorFor(SignContextSelectionManager.SelectionState state) {
        return switch (state) {
            case TRANSLATING -> new float[] { 1.0F, 0.82F, 0.12F };
            case SELECTED -> new float[] { 0.1F, 0.85F, 1.0F };
        };
    }
}
