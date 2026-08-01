package com.yourname.simpletranslate.keybind;

/** Tracks only the physical primary input edge; modifier changes cannot retrigger it. */
public final class ShortcutEdgeTracker {
    private boolean previousPrimaryDown;
    public boolean update(boolean primaryDown, boolean exactModifiers) {
        boolean trigger = primaryDown && !previousPrimaryDown && exactModifiers;
        previousPrimaryDown = primaryDown;
        return trigger;
    }
}
