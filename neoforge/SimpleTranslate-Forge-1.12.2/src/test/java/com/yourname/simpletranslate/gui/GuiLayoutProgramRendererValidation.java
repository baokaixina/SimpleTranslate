package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.core.ProtectedTextRuns;

/** Build-only structural checks for the legacy PUA layout replay. */
public final class GuiLayoutProgramRendererValidation {
    private GuiLayoutProgramRendererValidation() { }

    public static void run() {
        String source = "\u00a7a\uE100 Open Menu";
        String translated = "\u00a7a\uE100 打开菜单";
        require(GuiLayoutProgramRenderer.isLayoutProgram(source),
                "PUA resource-pack string was not detected as a layout program");
        require(GuiLayoutProgramRenderer.hasCompatibleVisualRuns(source, translated),
                "matching formatting/PUA runs rejected a translated visible span");
        require(!GuiLayoutProgramRenderer.hasCompatibleVisualRuns(source, "\u00a7a\uE101 打开菜单"),
                "changed client-owned PUA glyph was accepted");
        require(!GuiLayoutProgramRenderer.isLayoutProgram("\u00a7aOpen Menu"),
                "ordinary legacy colour formatting was misclassified as a layout program");
        require(ProtectedTextRuns.split(source).size() >= 2,
                "protected-run classifier collapsed layout metadata into visible text");
        require(GuiLayoutProgramRenderer.acceptsMeasuredAdvances(-3.0F, -3.0F, true),
                "protected negative advance was rejected");
        require(!GuiLayoutProgramRenderer.acceptsMeasuredAdvances(10.0F, -1.0F, false),
                "negative translated text advance was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
