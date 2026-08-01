package com.yourname.simpletranslate.transport;

import com.yourname.simpletranslate.api.TokenUsage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe, session-only usage monitor for the 1.12.2 HTTP transport. */
public final class TokenUsageMonitor {
    private static final int MAX_ENTRIES = 160;
    private static final ArrayDeque<TokenUsage> RING_BUFFER = new ArrayDeque<TokenUsage>();
    private static final AtomicLong TOTAL_PROMPT = new AtomicLong();
    private static final AtomicLong TOTAL_COMPLETION = new AtomicLong();
    private static final AtomicLong TOTAL_TOKENS = new AtomicLong();
    private static final AtomicLong REQUEST_COUNT = new AtomicLong();
    private static final AtomicLong TOTAL_ELAPSED_MS = new AtomicLong();

    private TokenUsageMonitor() {
    }

    public static void record(TokenUsage usage) {
        if (usage == null) return;
        synchronized (RING_BUFFER) {
            if (RING_BUFFER.size() >= MAX_ENTRIES) RING_BUFFER.pollFirst();
            RING_BUFFER.addLast(usage);
        }
        TOTAL_PROMPT.addAndGet(usage.getPromptTokens());
        TOTAL_COMPLETION.addAndGet(usage.getCompletionTokens());
        TOTAL_TOKENS.addAndGet(usage.getTotalTokens());
        REQUEST_COUNT.incrementAndGet();
        TOTAL_ELAPSED_MS.addAndGet(usage.getElapsedMs());
    }

    public static List<TokenUsage> snapshot() {
        synchronized (RING_BUFFER) {
            return new ArrayList<TokenUsage>(RING_BUFFER);
        }
    }

    public static Totals totals() {
        long count = REQUEST_COUNT.get();
        long elapsed = TOTAL_ELAPSED_MS.get();
        return new Totals(TOTAL_PROMPT.get(), TOTAL_COMPLETION.get(), TOTAL_TOKENS.get(), count,
                count == 0L ? 0L : elapsed / count);
    }

    public static void clear() {
        synchronized (RING_BUFFER) {
            RING_BUFFER.clear();
        }
        TOTAL_PROMPT.set(0L);
        TOTAL_COMPLETION.set(0L);
        TOTAL_TOKENS.set(0L);
        REQUEST_COUNT.set(0L);
        TOTAL_ELAPSED_MS.set(0L);
    }

    public static final class Totals {
        private final long promptTokens;
        private final long completionTokens;
        private final long totalTokens;
        private final long requestCount;
        private final long averageElapsedMs;

        private Totals(long promptTokens, long completionTokens, long totalTokens, long requestCount, long averageElapsedMs) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
            this.requestCount = requestCount;
            this.averageElapsedMs = averageElapsedMs;
        }

        public long getPromptTokens() { return promptTokens; }
        public long getCompletionTokens() { return completionTokens; }
        public long getTotalTokens() { return totalTokens; }
        public long getRequestCount() { return requestCount; }
        public long getAverageElapsedMs() { return averageElapsedMs; }
    }
}
