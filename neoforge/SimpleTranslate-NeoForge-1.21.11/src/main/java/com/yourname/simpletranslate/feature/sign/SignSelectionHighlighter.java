package com.yourname.simpletranslate.feature.sign;
import com.yourname.simpletranslate.core.SafeTranslate;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import org.joml.Vector3f;

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
            int argb = argb(color[0], color[1], color[2], 1.0F);
            collector.submitCustomGeometry(
                    poseStack,
                    RenderTypes.lines(),
                    (pose, lines) -> renderBoxLines(pose, lines, -0.04F, -0.04F, -0.04F, 1.04F, 1.04F, 1.04F, argb));
            return;
        }
    }

    private static void renderBoxLines(
            PoseStack.Pose pose,
            VertexConsumer lines,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            int color) {
        line(pose, lines, minX, minY, minZ, maxX, minY, minZ, color);
        line(pose, lines, minX, maxY, minZ, maxX, maxY, minZ, color);
        line(pose, lines, minX, minY, maxZ, maxX, minY, maxZ, color);
        line(pose, lines, minX, maxY, maxZ, maxX, maxY, maxZ, color);

        line(pose, lines, minX, minY, minZ, minX, maxY, minZ, color);
        line(pose, lines, maxX, minY, minZ, maxX, maxY, minZ, color);
        line(pose, lines, minX, minY, maxZ, minX, maxY, maxZ, color);
        line(pose, lines, maxX, minY, maxZ, maxX, maxY, maxZ, color);

        line(pose, lines, minX, minY, minZ, minX, minY, maxZ, color);
        line(pose, lines, maxX, minY, minZ, maxX, minY, maxZ, color);
        line(pose, lines, minX, maxY, minZ, minX, maxY, maxZ, color);
        line(pose, lines, maxX, maxY, minZ, maxX, maxY, maxZ, color);
    }

    private static void line(
            PoseStack.Pose pose,
            VertexConsumer lines,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            int color) {
        Vector3f normal = new Vector3f(x2 - x1, y2 - y1, z2 - z1).normalize();
        lines.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, normal).setLineWidth(1.0F);
        lines.addVertex(pose, x2, y2, z2).setColor(color).setNormal(pose, normal).setLineWidth(1.0F);
    }

    private static int argb(float r, float g, float b, float a) {
        return ((int) (a * 255.0F) & 0xFF) << 24
                | ((int) (r * 255.0F) & 0xFF) << 16
                | ((int) (g * 255.0F) & 0xFF) << 8
                | ((int) (b * 255.0F) & 0xFF);
    }

    private static float[] colorFor(SignContextSelectionManager.SelectionState state) {
        return switch (state) {
            case TRANSLATING -> new float[] { 1.0F, 0.82F, 0.12F };
            case SELECTED -> new float[] { 0.1F, 0.85F, 1.0F };
        };
    }
}
