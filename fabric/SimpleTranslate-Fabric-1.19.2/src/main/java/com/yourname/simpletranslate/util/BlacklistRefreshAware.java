package com.yourname.simpletranslate.util;

/**
 * Implemented by client UI mixins that can immediately hide translated text
 * after blacklist changes.
 */
public interface BlacklistRefreshAware {
    boolean simple_translate$refreshBlacklistedTranslations();
}
