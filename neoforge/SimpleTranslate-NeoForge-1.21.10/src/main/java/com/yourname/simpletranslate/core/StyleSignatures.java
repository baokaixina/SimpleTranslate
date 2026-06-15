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
            signature.append(";click=").append(click.action().getSerializedName())
                    .append(':').append(TranslationCacheKeys.hashSource(clickValue(click)));
        }
        HoverEvent hover = effective.getHoverEvent();
        if (hover != null) {
            signature.append(";hover=").append(hoverSignature(hover));
        }
        return signature.toString();
    }

    private static String hoverSignature(HoverEvent hover) {
        HoverEvent.Action action = hover.action();
        String valueHash;
        if (hover instanceof HoverEvent.ShowText showText) {
            Component text = showText.value();
            valueHash = TranslationCacheKeys.hashSource(text == null ? "" : text.getString());
        } else if (hover instanceof HoverEvent.ShowItem showItem) {
            valueHash = TranslationCacheKeys.hashSource(String.valueOf(showItem.item()));
        } else if (hover instanceof HoverEvent.ShowEntity showEntity) {
            HoverEvent.EntityTooltipInfo entity = showEntity.entity();
            valueHash = TranslationCacheKeys.hashSource(entity == null ? "" :
                    entity.name.map(Component::getString).orElse(""));
        } else {
            valueHash = TranslationCacheKeys.hashSource(String.valueOf(hover));
        }
        return action.getSerializedName() + ":" + valueHash;
    }

    private static String clickValue(ClickEvent click) {
        if (click instanceof ClickEvent.SuggestCommand suggestCommand) {
            return suggestCommand.command();
        }
        if (click instanceof ClickEvent.RunCommand runCommand) {
            return runCommand.command();
        }
        if (click instanceof ClickEvent.CopyToClipboard copyToClipboard) {
            return copyToClipboard.value();
        }
        if (click instanceof ClickEvent.OpenUrl openUrl) {
            return String.valueOf(openUrl.uri());
        }
        if (click instanceof ClickEvent.OpenFile openFile) {
            return openFile.path();
        }
        if (click instanceof ClickEvent.ChangePage changePage) {
            return Integer.toString(changePage.page());
        }
        return String.valueOf(click);
    }
}
