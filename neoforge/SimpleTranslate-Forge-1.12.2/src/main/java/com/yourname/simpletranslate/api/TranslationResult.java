package com.yourname.simpletranslate.api;

/** Java-8 form of the baseline Success/Failed translation result. */
public abstract class TranslationResult {
    private final String componentJsonArray;
    private final String failureReason;

    private TranslationResult(String componentJsonArray, String failureReason) {
        this.componentJsonArray = componentJsonArray;
        this.failureReason = failureReason == null ? "" : failureReason;
    }

    public static TranslationResult success(String componentJsonArray) {
        return new Success(componentJsonArray);
    }

    public static TranslationResult failed(String failureReason) {
        return new Failed(failureReason);
    }

    public boolean isSuccess() { return this instanceof Success; }
    public String getComponentJsonArray() { return componentJsonArray; }
    public String getFailureReason() { return failureReason; }

    public static final class Success extends TranslationResult {
        public Success(String payload) { super(payload == null ? "" : payload, ""); }
        public String payload() { return getComponentJsonArray(); }
    }

    public static final class Failed extends TranslationResult {
        public Failed(String reason) { super(null, reason == null ? "" : reason); }
        public String reason() { return getFailureReason(); }
    }
}
