package com.yourname.simpletranslate.util;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.TranslationBlacklist;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.translation.OcrTranslationService;
import com.yourname.simpletranslate.translation.VisionOcrTranslationService;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class OcrManager {
    private static final OcrTranslationService SERVICE = new VisionOcrTranslationService();

    private OcrManager() {
    }

    public static CompletableFuture<OcrTranslationService.OcrResult> recognize(byte[] pngBytes) {
        if (!ModConfig.GLOBAL_ENABLED.get() || !ModConfig.OCR_ENABLED.get()) {
            return CompletableFuture.completedFuture(OcrTranslationService.OcrResult.failure("OCR is disabled"));
        }
        if (pngBytes == null || pngBytes.length == 0) {
            return CompletableFuture.completedFuture(OcrTranslationService.OcrResult.failure("No screenshot data"));
        }
        if (!SERVICE.isReady()) {
            return CompletableFuture.completedFuture(OcrTranslationService.OcrResult.failure(SERVICE.describeActiveProfile()));
        }

        String imageHash = sha256(pngBytes);
        String cacheKey = cacheKey(imageHash);
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache != null && ModConfig.CACHE_ENABLED.get()) {
            Optional<String> cached = cache.get(cacheKey);
            if (cached.isPresent()) {
                OcrTranslationService.OcrResult result =
                        OcrTranslationService.OcrResult.fromCacheValue(cached.get());
                if (result != null) {
                    return CompletableFuture.completedFuture(result);
                }
                cache.remove(cacheKey);
                cache.save();
            }
        }

        String sourceLanguage = ModConfig.SOURCE_LANGUAGE.get();
        String targetLanguage = ModConfig.TARGET_LANGUAGE.get();
        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        TranslationCache requestCache = cache;
        return SERVICE.translateImage(pngBytes, imageHash, sourceLanguage, targetLanguage)
                .handle((result, error) -> {
                    if (error != null) {
                        return OcrTranslationService.OcrResult.failure(
                                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                    }
                    OcrTranslationService.OcrResult safe = validateResult(result);
                    if (safe != null && safe.success() && safe.hasText()
                            && requestCache != null
                            && ModConfig.CACHE_ENABLED.get()
                            && requestCache == SimpleTranslateMod.getTranslationCache()
                            && SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)) {
                        requestCache.put(cacheKey, safe.toCacheValue(), safe.sourceText(), safe.translationText());
                        requestCache.save();
                    }
                    return safe == null ? OcrTranslationService.OcrResult.failure("OCR response rejected") : safe;
                });
    }

    public static CompletableFuture<OcrTranslationService.OcrResult> verifyAccess() {
        return SERVICE.verifyAccess().thenApply(OcrManager::validateResult);
    }

    public static String activeProfileDescription() {
        return SERVICE.describeActiveProfile();
    }

    public static String cacheKey(String imageHash) {
        String context = "ocrProfile=" + SERVICE.describeActiveProfile()
                + "\ntarget=" + ModConfig.TARGET_LANGUAGE.get();
        return TranslationCacheKeys.key(OcrTranslationService.SURFACE, "image:" + imageHash,
                context, "image-hash", "ocr-json-v1");
    }

    private static OcrTranslationService.OcrResult validateResult(OcrTranslationService.OcrResult result) {
        if (result == null) {
            return OcrTranslationService.OcrResult.failure("OCR returned no result");
        }
        if (!result.success()) {
            return result;
        }
        TranslationBlacklist blacklist = SimpleTranslateMod.getTranslationBlacklist();
        if (blacklist != null) {
            if (blacklist.isBlacklisted(result.sourceText())
                    || blacklist.containsBlacklistedEntry(result.translationText())) {
                return OcrTranslationService.OcrResult.failure("OCR text is blacklisted");
            }
        }
        return result;
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(java.util.Arrays.hashCode(bytes));
        }
    }
}
