package com.yourname.simpletranslate.util;

import java.util.concurrent.atomic.AtomicLong;

public final class TranslationJobIds {
    private static final AtomicLong NEXT_JOB_ID = new AtomicLong(1L);

    private TranslationJobIds() {
    }

    public static String next() {
        return Long.toString(NEXT_JOB_ID.getAndIncrement());
    }
}
