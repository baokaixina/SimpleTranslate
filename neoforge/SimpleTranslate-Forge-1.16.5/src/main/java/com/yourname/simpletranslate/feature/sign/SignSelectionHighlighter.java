package com.yourname.simpletranslate.feature.sign;
import com.yourname.simpletranslate.core.SafeTranslate;

import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.vector.Vector3d;

public final class SignSelectionHighlighter {
    private static boolean registered;

    private SignSelectionHighlighter() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        MinecraftForge.EVENT_BUS.addListener(SignSelectionHighlighter::renderSelections);
    }

    private static void renderSelections(RenderWorldLastEvent event) {
        SafeTranslate.guard(() -> simple_translate$renderSelectionsImpl(event), "sign.renderSelections");
    }

    private static void simple_translate$renderSelectionsImpl(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        if (event == null) {
            return;
        }

        IRenderTypeBuffer.Impl consumers = minecraft.renderBuffers().bufferSource();
        if (consumers == null) {
            return;
        }

        IVertexBuilder lines = consumers.getBuffer(RenderType.lines());
        // Forge 1.16.5 RenderWorldLastEvent has no camera accessor; resolve the
        // camera from GameRenderer (ActiveRenderInfo#getPosition, javap-verified).
        Vector3d cameraPos = minecraft.gameRenderer.getMainCamera().getPosition();
        for (SignContextSelectionManager.SelectionView selection :
                SignContextSelectionManager.getRenderableSelections(minecraft.level)) {
            float[] color = colorFor(selection.state());
            AxisAlignedBB box = new AxisAlignedBB(selection.pos()).inflate(0.04D).move(-cameraPos.x, -cameraPos.y, -cameraPos.z);
            WorldRenderer.renderLineBox(event.getMatrixStack(), lines, box, color[0], color[1], color[2], 1.0F);
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
