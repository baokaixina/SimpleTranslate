package com.yourname.simpletranslate.api;

/**
 * Snapshot of token usage for a single completed translation request.
 * Recorded by {@link com.yourname.simpletranslate.transport.TokenUsageMonitor}
 * and displayed on the token monitor screen.
 */
public record TokenUsage(
        String apiFormat,
        String model,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        long elapsedMs,
        long timestampMillis,
        String surface) {
}
