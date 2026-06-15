package com.yourname.simpletranslate.core;

import com.yourname.simpletranslate.util.TranslationCacheKeys;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

/**
 * Stable textual signatures for {@link Style} objects. Used for cache keys
 * only; rendering always reuses the original {@link Style} instances.
 */
public final class StyleSignatures {
    private StyleSignatures() {
    }

    public static String of(Style style) {
        Style effective = style == null ? Style.EMPTY : style;
        StringBuilder signature = new StringBuilder();
        signature.append("color=").append(effective.getColor() == null ? "" : effective.getColor())
                .append(";bold=").append(effective.isBold())
                .append(";italic=").append(effective.isItalic())
                .append(";underlined=").append(effective.isUnderlined())
                .append(";strikethrough=").append(effective.isStrikethrough())
                .append(";obfuscated=").append(effective.isObfuscated())
                .append(";font=").append(effective.getFont() == null ? "" : effective.getFont())
                .append(";insertion=").append(effective.getInsertion() == null ? "" : effective.getInsertion());
        ClickEvent click = effective.getClickEvent();
        if (click != null) {
            signature.append(";click=").append(click.action().getSerializedName())
                    .append(':').append(TranslationCacheKeys.hashSource(click.toString()));
        }
        HoverEvent hover = effective.getHoverEvent();
        if (hover != null) {
            signature.append(";hover=").append(hoverSignature(hover));
        }
        return signature.toString();
    }

    private static String hoverSignature(HoverEvent hover) {
        return hover.action().getSerializedName() + ":"
                + TranslationCacheKeys.hashSource(hover.toString());
    }
}
