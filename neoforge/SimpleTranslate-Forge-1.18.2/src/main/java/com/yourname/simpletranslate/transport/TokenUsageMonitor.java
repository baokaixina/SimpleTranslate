package com.yourname.simpletranslate.transport;

import com.yourname.simpletranslate.api.TokenUsage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory token usage collector. Writes happen on stream-reader threads;
 * reads happen on the render thread. The ring buffer is synchronized; the
 * totals use {@link AtomicLong} for lock-free reads.
 *
 * <p>Cleared on world switch / config change via {@link #clear()}.</p>
 */
public final class TokenUsageMonitor {
    private static final int MAX_ENTRIES = 160;

    private static final ArrayDeque<TokenUsage> RING_BUFFER = new ArrayDeque<>();
    private static final AtomicLong TOTAL_PROMPT = new AtomicLong();
    private static final AtomicLong TOTAL_COMPLETION = new AtomicLong();
    private static final AtomicLong TOTAL_TOKENS = new AtomicLong();
    private static final AtomicLong REQUEST_COUNT = new AtomicLong();
    private static final AtomicLong TOTAL_ELAPSED_MS = new AtomicLong();

    private TokenUsageMonitor() {
    }

    public static void record(TokenUsage usage) {
        if (usage == null) {
            return;
        }
        synchronized (RING_BUFFER) {
            if (RING_BUFFER.size() >= MAX_ENTRIES) {
                RING_BUFFER.pollFirst();
            }
            RING_BUFFER.addLast(usage);
        }
        TOTAL_PROMPT.addAndGet(usage.promptTokens());
        TOTAL_COMPLETION.addAndGet(usage.completionTokens());
        TOTAL_TOKENS.addAndGet(usage.totalTokens());
        REQUEST_COUNT.incrementAndGet();
        TOTAL_ELAPSED_MS.addAndGet(usage.elapsedMs());
    }

    public static List<TokenUsage> snapshot() {
        synchronized (RING_BUFFER) {
            return List.copyOf(RING_BUFFER);
        }
    }

    public static Totals totals() {
        long count = REQUEST_COUNT.get();
        long elapsed = TOTAL_ELAPSED_MS.get();
        return new Totals(
                TOTAL_PROMPT.get(),
                TOTAL_COMPLETION.get(),
                TOTAL_TOKENS.get(),
                count,
                count > 0 ? elapsed / count : 0);
    }

    public static void clear() {
        synchronized (RING_BUFFER) {
            RING_BUFFER.clear();
        }
        TOTAL_PROMPT.set(0);
        TOTAL_COMPLETION.set(0);
        TOTAL_TOKENS.set(0);
        REQUEST_COUNT.set(0);
        TOTAL_ELAPSED_MS.set(0);
    }

    public record Totals(long promptTokens, long completionTokens, long totalTokens,
                         long requestCount, long avgElapsedMs) {
    }
}
