package com.yourname.simpletranslate.api;

/** Immutable Java-8 token-usage snapshot. */
public final class TokenUsage {
    private final String apiFormat;
    private final String model;
    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;
    private final long elapsedMs;
    private final long timestampMillis;
    private final String surface;

    public TokenUsage(String model, int promptTokens, int completionTokens, int totalTokens,
                      long elapsedMs, long timestampMillis, String surface) {
        this("deepseek_chat", model, promptTokens, completionTokens, totalTokens,
                elapsedMs, timestampMillis, surface);
    }

    public TokenUsage(String apiFormat, String model, int promptTokens, int completionTokens,
                      int totalTokens, long elapsedMs, long timestampMillis, String surface) {
        this.apiFormat = apiFormat == null ? "" : apiFormat;
        this.model = model == null ? "" : model;
        this.promptTokens = Math.max(0, promptTokens);
        this.completionTokens = Math.max(0, completionTokens);
        this.totalTokens = Math.max(0, totalTokens);
        this.elapsedMs = Math.max(0L, elapsedMs);
        this.timestampMillis = timestampMillis;
        this.surface = surface == null ? "" : surface;
    }

    public String apiFormat() { return apiFormat; }
    public String model() { return model; }
    public int promptTokens() { return promptTokens; }
    public int completionTokens() { return completionTokens; }
    public int totalTokens() { return totalTokens; }
    public long elapsedMs() { return elapsedMs; }
    public long timestampMillis() { return timestampMillis; }
    public String surface() { return surface; }
    public String getApiFormat() { return apiFormat; }
    public String getModel() { return model; }
    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public int getTotalTokens() { return totalTokens; }
    public long getElapsedMs() { return elapsedMs; }
    public long getTimestampMillis() { return timestampMillis; }
    public String getSurface() { return surface; }
}
