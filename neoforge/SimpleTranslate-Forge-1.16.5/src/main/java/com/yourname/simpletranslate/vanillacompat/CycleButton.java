package com.yourname.simpletranslate.vanillacompat;

import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.ITextComponent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Compatibility implementation of the 1.19 CycleButton API for 1.16.5.
 * It deliberately lives in the missing vanilla package so the shared screen
 * sources keep their exact CycleButton call sites.
 */
public class CycleButton<T> extends Button {
    private final Function<T, ITextComponent> valueLabel;
    private final List<T> values;
    private final OnValueChange<T> onValueChange;
    private T value;

    private CycleButton(int x, int y, int width, int height, ITextComponent narration,
                        Function<T, ITextComponent> valueLabel, List<T> values, T initial,
                        OnValueChange<T> onValueChange) {
        super(x, y, width, height, valueLabel.apply(initial), button -> ((CycleButton<?>) button).cycle());
        this.valueLabel = valueLabel;
        this.values = values;
        this.value = initial;
        this.onValueChange = onValueChange;
        setMessage(valueLabel.apply(initial));
    }

    private void cycle() {
        if (values.isEmpty()) {
            return;
        }
        int index = values.indexOf(value);
        value = values.get((index + 1 + values.size()) % values.size());
        setMessage(valueLabel.apply(value));
        if (onValueChange != null) {
            onValueChange.onValueChange(this, value);
        }
    }

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        if (!values.isEmpty() && !values.contains(value)) {
            return;
        }
        this.value = value;
        setMessage(valueLabel.apply(value));
    }

    public interface OnValueChange<T> {
        void onValueChange(CycleButton<T> button, T value);
    }

    public static <T> Builder<T> builder(Function<T, ITextComponent> valueLabel) {
        return new Builder<>(valueLabel);
    }

    public static Builder<Boolean> onOffBuilder(boolean initial) {
        return new Builder<Boolean>(value -> value
                ? new net.minecraft.util.text.TranslationTextComponent("gui.yes")
                : new net.minecraft.util.text.TranslationTextComponent("gui.no"))
                .withInitialValue(initial)
                .withValues(true, false);
    }

    public static final class Builder<T> {
        private final Function<T, ITextComponent> valueLabel;
        private final List<T> values = new ArrayList<>();
        private T initial;

        private Builder(Function<T, ITextComponent> valueLabel) {
            this.valueLabel = valueLabel;
        }

        public Builder<T> withInitialValue(T initial) {
            this.initial = initial;
            return this;
        }

        @SafeVarargs
        public final Builder<T> withValues(T... values) {
            this.values.clear();
            this.values.addAll(Arrays.asList(values));
            return this;
        }

        public Builder<T> withValues(Collection<T> values) {
            this.values.clear();
            this.values.addAll(values);
            return this;
        }

        public CycleButton<T> create(int x, int y, int width, int height, ITextComponent narration,
                                     OnValueChange<T> onValueChange) {
            List<T> choices = values.isEmpty()
                    ? (initial == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(initial)))
                    : new ArrayList<>(values);
            T selected = initial == null && !choices.isEmpty() ? choices.get(0) : initial;
            return new CycleButton<>(x, y, width, height, narration, valueLabel, choices, selected, onValueChange);
        }
    }
}
