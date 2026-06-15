package com.yourname.simpletranslate.core;

import com.yourname.simpletranslate.util.TranslationCacheKeys;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
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
            signature.append(";click=").append(click.getAction().getSerializedName())
                    .append(':').append(TranslationCacheKeys.hashSource(click.getValue()));
        }
        HoverEvent hover = effective.getHoverEvent();
        if (hover != null) {
            signature.append(";hover=").append(hoverSignature(hover));
        }
        return signature.toString();
    }

    private static String hoverSignature(HoverEvent hover) {
        HoverEvent.Action<?> action = hover.getAction();
        String valueHash;
        if (action == HoverEvent.Action.SHOW_TEXT) {
            Component text = hover.getValue(HoverEvent.Action.SHOW_TEXT);
            valueHash = TranslationCacheKeys.hashSource(text == null ? "" : text.getString());
        } else if (action == HoverEvent.Action.SHOW_ITEM) {
            HoverEvent.ItemStackInfo item = hover.getValue(HoverEvent.Action.SHOW_ITEM);
            valueHash = TranslationCacheKeys.hashSource(item == null ? "" : String.valueOf(item.getItemStack()));
        } else if (action == HoverEvent.Action.SHOW_ENTITY) {
            HoverEvent.EntityTooltipInfo entity = hover.getValue(HoverEvent.Action.SHOW_ENTITY);
            valueHash = TranslationCacheKeys.hashSource(entity == null ? "" :
                    entity.name.map(Component::getString).orElse(""));
        } else {
            valueHash = TranslationCacheKeys.hashSource(String.valueOf(hover));
        }
        return action.getSerializedName() + ":" + valueHash;
    }
}
