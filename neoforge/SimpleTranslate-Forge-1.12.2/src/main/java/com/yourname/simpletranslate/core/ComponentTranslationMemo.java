package com.yourname.simpletranslate.core;

import net.minecraft.util.text.ITextComponent;

/**
 * Immutable render-surface memo stored outside the configured Mixin package.
 *
 * <p>Merged Mixin bytecode may reference this type at runtime. Mixin 0.8 rejects
 * direct loading of helper classes beneath a configured Mixin package, so this
 * support type must remain in an ordinary product package.</p>
 */
public final class ComponentTranslationMemo {
    public final long revision;
    public final String source;
    public final ITextComponent translated;
    public final long nextProbeAt;

    public ComponentTranslationMemo(long revision, String source,
                                    ITextComponent translated, long nextProbeAt) {
        this.revision = revision;
        this.source = source;
        this.translated = translated;
        this.nextProbeAt = nextProbeAt;
    }
}
