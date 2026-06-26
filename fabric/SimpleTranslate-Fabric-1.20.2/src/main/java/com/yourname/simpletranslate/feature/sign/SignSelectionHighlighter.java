package com.yourname.simpletranslate.feature.sign;
import com.yourname.simpletranslate.core.SafeTranslate;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class SignSelectionHighlighter {
    private static boolean registered;

    private SignSelectionHighlighter() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        WorldRenderEvents.AFTER_ENTITIES.register(SignSelectionHighlighter::renderSelections);
    }

    private static void renderSelections(WorldRenderContext context) {
        SafeTranslate.guard(() -> simple_translate$renderSelectionsImpl(context), "sign.renderSelections");
    }

    private static void simple_translate$renderSelectionsImpl(WorldRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        if (context == null) {
            return;
        }

        MultiBufferSource consumers = context.consumers();
        if (consumers == null) {
            return;
        }

        Camera camera = context.camera();
        if (camera == null) {
            return;
        }
        VertexConsumer lines = consumers.getBuffer(RenderType.lines());
        Vec3 cameraPos = camera.getPosition();
        for (SignContextSelectionManager.SelectionView selection :
                SignContextSelectionManager.getRenderableSelections(minecraft.level)) {
            float[] color = colorFor(selection.state());
            AABB box = new AABB(selection.pos()).inflate(0.04D).move(-cameraPos.x, -cameraPos.y, -cameraPos.z);
            LevelRenderer.renderLineBox(context.matrixStack(), lines, box, color[0], color[1], color[2], 1.0F);
        }
    }

    private static float[] colorFor(SignContextSelectionManager.SelectionState state) {
        return switch (state) {
            case TRANSLATING -> new float[] { 1.0F, 0.82F, 0.12F };
            case SELECTED -> new float[] { 0.1F, 0.85F, 1.0F };
        };
    }
}
