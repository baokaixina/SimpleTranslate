package com.yourname.simpletranslate.util;

public record TranslationSlot(
        String id,
        String text,
        String role,
        int maxWidth,
        boolean fixedSlot,
        String styleSignature,
        int lineIndex,
        int slotIndex,
        String tokenMask) {

    public TranslationSlot {
        id = id == null || id.isBlank() ? "" : id;
        text = text == null ? "" : text;
        role = role == null || role.isBlank() ? "body" : role;
        styleSignature = styleSignature == null ? "" : styleSignature;
        tokenMask = tokenMask == null ? "" : tokenMask;
    }

    public static TranslationSlot translatable(String id, String text, String role, int maxWidth,
                                               String styleSignature, int lineIndex, int slotIndex,
                                               String tokenMask) {
        return new TranslationSlot(id, text, role, maxWidth, false, styleSignature, lineIndex, slotIndex, tokenMask);
    }

    public static TranslationSlot fixed(String id, String text, String role, int lineIndex, int slotIndex,
                                        String styleSignature) {
        return new TranslationSlot(id, text, role, 0, true, styleSignature, lineIndex, slotIndex, "");
    }

    public TranslationSlot withText(String newText) {
        return new TranslationSlot(id, newText, role, maxWidth, fixedSlot, styleSignature, lineIndex, slotIndex, tokenMask);
    }

    public String layoutSignature() {
        return lineIndex + ":" + slotIndex + ":" + maxWidth + ":" + fixedSlot + ":" + role + ":" + styleSignature;
    }
}
