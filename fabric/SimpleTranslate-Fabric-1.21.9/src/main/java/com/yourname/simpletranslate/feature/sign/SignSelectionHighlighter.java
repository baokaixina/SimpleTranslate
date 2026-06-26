package com.yourname.simpletranslate.feature.sign;
import com.yourname.simpletranslate.core.SafeTranslate;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.world.phys.AABB;

public final class SignSelectionHighlighter {
    private static boolean registered;

    private SignSelectionHighlighter() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
    }

    public static void submitSelectionOutline(
            SignRenderState renderState,
            PoseStack poseStack,
            SubmitNodeCollector collector) {
        SafeTranslate.guard(
                () -> simple_translate$submitSelectionOutlineImpl(renderState, poseStack, collector),
                "sign.renderSelection");
    }

    private static void simple_translate$submitSelectionOutlineImpl(
            SignRenderState renderState,
            PoseStack poseStack,
            SubmitNodeCollector collector) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || renderState == null
                || renderState.blockPos == null || poseStack == null || collector == null) {
            return;
        }
        for (SignContextSelectionManager.SelectionView selection :
                SignContextSelectionManager.getRenderableSelections(minecraft.level)) {
            if (!renderState.blockPos.equals(selection.pos())) {
                continue;
            }
            float[] color = colorFor(selection.state());
            AABB box = new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D).inflate(0.04D);
            collector.submitCustomGeometry(
                    poseStack,
                    RenderType.lines(),
                    (pose, lines) -> ShapeRenderer.renderLineBox(
                            pose, lines, box, color[0], color[1], color[2], 1.0F));
            return;
        }
    }

    private static float[] colorFor(SignContextSelectionManager.SelectionState state) {
        return switch (state) {
            case TRANSLATING -> new float[] { 1.0F, 0.82F, 0.12F };
            case SELECTED -> new float[] { 0.1F, 0.85F, 1.0F };
        };
    }
}
