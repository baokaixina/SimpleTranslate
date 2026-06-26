package com.yourname.simpletranslate.api;

public sealed interface TranslationResult
        permits TranslationResult.Success, TranslationResult.Failed {

    record Success(String payload) implements TranslationResult {
        public Success {
            payload = payload == null ? "" : payload;
        }
    }

    record Failed(String reason) implements TranslationResult {
        public Failed {
            reason = reason == null ? "" : reason;
        }
    }
}
