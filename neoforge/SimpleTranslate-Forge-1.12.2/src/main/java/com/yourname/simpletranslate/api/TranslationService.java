package com.yourname.simpletranslate.api;

import java.util.concurrent.CompletableFuture;

/** The sole asynchronous production translation SPI for the 1.12.2 port. */
public interface TranslationService {
    CompletableFuture<TranslationResult> translate(TranslationRequest request);
}
