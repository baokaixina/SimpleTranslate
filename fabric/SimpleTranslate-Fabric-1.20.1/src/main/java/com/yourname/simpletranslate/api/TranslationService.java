package com.yourname.simpletranslate.api;

import java.util.concurrent.CompletableFuture;

public interface TranslationService {
    CompletableFuture<TranslationResult> translate(TranslationRequest request);
}
