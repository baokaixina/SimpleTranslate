package com.yourname.simpletranslate.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Crash-safe small-file writes: content lands in a sibling temporary file
 * first and is then moved over the target, so a crash or power loss mid-write
 * can never leave a truncated configuration or cache file behind.
 */
public final class AtomicFiles {
    private AtomicFiles() {
    }

    public static void writeString(Path file, String content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, content);
            moveAtomically(temporary, file);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    public static void moveAtomically(Path temporary, Path file) throws IOException {
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
