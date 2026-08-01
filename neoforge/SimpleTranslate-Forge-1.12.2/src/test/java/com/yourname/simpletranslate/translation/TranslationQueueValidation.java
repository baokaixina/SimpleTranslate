package com.yourname.simpletranslate.translation;

import com.yourname.simpletranslate.api.TranslationResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Build-only regression for real transport-future ownership and cancellation. */
public final class TranslationQueueValidation {
    private TranslationQueueValidation() { }

    public static void run() throws Exception {
        final TranslationRequestQueue queue = new TranslationRequestQueue();
        try {
            final CountDownLatch started = new CountDownLatch(1);
            final AtomicBoolean requestCanceled = new AtomicBoolean(false);
            CompletableFuture<TranslationResult> exposed = queue.submit(
                    "cancel-fixture", "sign.manual.fixture",
                    new Supplier<CompletableFuture<TranslationResult>>() {
                        @Override public CompletableFuture<TranslationResult> get() {
                            started.countDown();
                            return new TrackingFuture(requestCanceled);
                        }
                    });
            require(started.await(3, TimeUnit.SECONDS), "queue did not start transport future");
            require(queue.cancelSurfacePrefix("sign.manual") == 1,
                    "surface cancellation did not find running request");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!requestCanceled.get() && System.nanoTime() < deadline) Thread.yield();
            require(requestCanceled.get(), "queue canceled only its worker, not the real transport future");
            require(exposed.get(2, TimeUnit.SECONDS) instanceof TranslationResult.Failed,
                    "canceled request did not complete with the normal failure result");

            TranslationResult resumed = queue.submit("success-fixture", "chat.fixture",
                    new Supplier<CompletableFuture<TranslationResult>>() {
                        @Override public CompletableFuture<TranslationResult> get() {
                            return CompletableFuture.completedFuture(TranslationResult.success("[]"));
                        }
                    }).get(2, TimeUnit.SECONDS);
            require(resumed instanceof TranslationResult.Success,
                    "queue did not accept work after a scoped cancellation");
        } finally {
            queue.shutdown();
        }
    }

    private static final class TrackingFuture extends CompletableFuture<TranslationResult> {
        private final AtomicBoolean canceled;
        private TrackingFuture(AtomicBoolean canceled) { this.canceled = canceled; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            canceled.set(true);
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
