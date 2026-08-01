package com.yourname.simpletranslate.translation;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.api.TranslationResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Java-8 request scheduler used by every 1.12.2 production translation.
 * The queue owns deduplication, priority, lanes, retries and cancellation, and
 * tracks the real asynchronous HTTP future returned by the transport.
 */
final class TranslationRequestQueue {
    private static final int MAX_UNIQUE_TASKS = 512;
    private static final int MAX_ATTEMPTS = 3;
    private static final int RATE_LIMIT_MAX_PARALLEL = 3;
    private static final long RATE_LIMIT_THROTTLE_MS = 30000L;
    private static final long ERROR_STATUS_TTL_MS = 60000L;
    private static final long[] RETRY_DELAYS_MS = {1500L, 4000L};

    private final Object lock = new Object();
    private final Map<String, LaneState> lanes = new HashMap<String, LaneState>();
    private final Map<String, QueuedTask> tasksByLaneKey = new HashMap<String, QueuedTask>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            5, 5, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
            namedFactory("SimpleTranslate-1.12.2-Queue-"));
    private final ScheduledExecutorService retryTimer =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    namedFactory("SimpleTranslate-1.12.2-Retry-"));

    private int maxParallelRequests = 5;
    private int globalInFlight;
    private long nextSequence;
    private long generation;
    private long rateLimitedUntil;
    private volatile String lastErrorMessage;
    private volatile long lastErrorTimestamp;

    CompletableFuture<TranslationResult> submit(
            String key, String surface,
            Supplier<CompletableFuture<TranslationResult>> operation) {
        if (key == null || operation == null) {
            return CompletableFuture.completedFuture(TranslationResult.failed("empty request"));
        }
        final String normalizedSurface = normalize(surface);
        final String laneId = laneForSurface(normalizedSurface);
        final String laneKey = laneId + '\u0001' + key;
        QueuedTask dropped = null;
        QueuedTask submitted;
        synchronized (lock) {
            QueuedTask existing = tasksByLaneKey.get(laneKey);
            if (existing != null && !existing.future.isDone()) {
                existing.coalescedCount++;
                return existing.future;
            }
            if (tasksByLaneKey.size() >= MAX_UNIQUE_TASKS) {
                dropped = removeOneLowPriorityTaskLocked();
                if (dropped == null) {
                    SimpleTranslateForge1122.getLogger().warn(
                            "Translation queue full; rejected protected task lane={} surface={}",
                            laneId, normalizedSurface);
                    return CompletableFuture.completedFuture(
                            TranslationResult.failed("request queue is full"));
                }
            }
            submitted = new QueuedTask(++nextSequence, generation, laneKey, laneId,
                    normalizedSurface, priorityFor(normalizedSurface),
                    protectedTaskFor(normalizedSurface), operation);
            tasksByLaneKey.put(laneKey, submitted);
            lane(laneId).queued.add(submitted);
            scheduleLocked();
        }
        if (dropped != null) {
            dropped.future.complete(TranslationResult.failed("request queue evicted background work"));
        }
        return submitted.future;
    }

    void setMaxParallelRequests(int value) {
        synchronized (lock) {
            maxParallelRequests = Math.max(1, Math.min(8, value));
            int current = workers.getCorePoolSize();
            if (maxParallelRequests < current) workers.setCorePoolSize(maxParallelRequests);
            workers.setMaximumPoolSize(maxParallelRequests);
            if (maxParallelRequests > current) workers.setCorePoolSize(maxParallelRequests);
            scheduleLocked();
        }
    }

    void clear() { cancelMatching(null); }

    int cancelSurfacePrefix(String surfacePrefix) {
        String normalized = normalize(surfacePrefix);
        if ("generic".equals(normalized) || normalized.isEmpty()) return 0;
        return cancelMatching(normalized);
    }

    String getRecentErrorStatus() {
        String value = lastErrorMessage;
        if (value == null || value.trim().isEmpty()) return null;
        if (System.currentTimeMillis() - lastErrorTimestamp > ERROR_STATUS_TTL_MS) return null;
        return value;
    }

    void shutdown() {
        clear();
        workers.shutdownNow();
        retryTimer.shutdownNow();
    }

    private int cancelMatching(String surfacePrefix) {
        List<QueuedTask> canceled = new ArrayList<QueuedTask>();
        List<Future<?>> workerFutures = new ArrayList<Future<?>>();
        List<CompletableFuture<TranslationResult>> requestFutures =
                new ArrayList<CompletableFuture<TranslationResult>>();
        synchronized (lock) {
            if (surfacePrefix == null) {
                generation++;
                lastErrorMessage = null;
                lastErrorTimestamp = 0L;
                rateLimitedUntil = 0L;
            }
            for (LaneState lane : lanes.values()) {
                Iterator<QueuedTask> queued = lane.queued.iterator();
                while (queued.hasNext()) {
                    QueuedTask task = queued.next();
                    if (matches(task, surfacePrefix)) {
                        queued.remove();
                        cancelTaskLocked(task, canceled, workerFutures, requestFutures);
                    }
                }
                for (QueuedTask task : new ArrayList<QueuedTask>(lane.running)) {
                    if (matches(task, surfacePrefix)) {
                        releaseRunningSlotLocked(task);
                        cancelTaskLocked(task, canceled, workerFutures, requestFutures);
                    }
                }
            }
            for (QueuedTask task : new ArrayList<QueuedTask>(tasksByLaneKey.values())) {
                if (matches(task, surfacePrefix) && !canceled.contains(task)) {
                    cancelTaskLocked(task, canceled, workerFutures, requestFutures);
                }
            }
            scheduleLocked();
        }
        for (Future<?> future : workerFutures) future.cancel(true);
        for (CompletableFuture<TranslationResult> future : requestFutures) future.cancel(true);
        for (QueuedTask task : canceled) {
            task.future.complete(TranslationResult.failed("request canceled"));
        }
        return canceled.size();
    }

    private static boolean matches(QueuedTask task, String prefix) {
        return prefix == null || task.surface.startsWith(prefix);
    }

    private void cancelTaskLocked(QueuedTask task, List<QueuedTask> canceled,
                                  List<Future<?>> workerFutures,
                                  List<CompletableFuture<TranslationResult>> requestFutures) {
        task.canceled = true;
        tasksByLaneKey.remove(task.laneKey, task);
        if (task.workerFuture != null) workerFutures.add(task.workerFuture);
        if (task.requestFuture != null) requestFutures.add(task.requestFuture);
        if (task.retryFuture != null) task.retryFuture.cancel(false);
        canceled.add(task);
    }

    private void scheduleLocked() {
        while (globalInFlight < effectiveMaxParallelRequestsLocked()) {
            final QueuedTask task = pollBestLocked();
            if (task == null) return;
            LaneState lane = lane(task.laneId);
            task.running = true;
            task.startedAt = System.currentTimeMillis();
            lane.running.add(task);
            lane.runningCount++;
            globalInFlight++;
            task.workerFuture = workers.submit(new Runnable() {
                @Override public void run() { runTask(task); }
            });
        }
    }

    private void runTask(final QueuedTask task) {
        try {
            CompletableFuture<TranslationResult> requestFuture = task.operation.get();
            if (requestFuture == null) {
                finish(task, TranslationResult.failed("empty transport future"), null);
                return;
            }
            boolean cancel;
            synchronized (lock) {
                cancel = task.canceled || task.generation != generation;
                if (!cancel) task.requestFuture = requestFuture;
            }
            if (cancel) {
                requestFuture.cancel(true);
                return;
            }
            requestFuture.whenComplete(new java.util.function.BiConsumer<TranslationResult, Throwable>() {
                @Override public void accept(TranslationResult result, Throwable error) {
                    Throwable cause = unwrap(error);
                    if (cause instanceof RetryableRequestException) {
                        retry(task, (RetryableRequestException) cause);
                    } else {
                        finish(task, result, cause);
                    }
                }
            });
        } catch (RetryableRequestException retryable) {
            retry(task, retryable);
        } catch (Throwable error) {
            finish(task, null, error);
        }
    }

    private void finish(QueuedTask task, TranslationResult result, Throwable error) {
        boolean deliver;
        synchronized (lock) {
            releaseRunningSlotLocked(task);
            tasksByLaneKey.remove(task.laneKey, task);
            deliver = !task.canceled && task.generation == generation;
            if (deliver && error != null && !(error instanceof CancellationException)) {
                lastErrorMessage = safeError(error);
                lastErrorTimestamp = System.currentTimeMillis();
                SimpleTranslateForge1122.getLogger().warn(
                        "Translation queue failed lane={} surface={} reason={}",
                        task.laneId, task.surface, lastErrorMessage);
            }
            scheduleLocked();
        }
        if (!deliver) return;
        task.future.complete(error == null && result != null
                ? result : TranslationResult.failed("translation request failed"));
    }

    private void retry(final QueuedTask task, RetryableRequestException reason) {
        boolean complete = false;
        synchronized (lock) {
            releaseRunningSlotLocked(task);
            if (task.canceled || task.generation != generation) {
                tasksByLaneKey.remove(task.laneKey, task);
                complete = true;
            } else {
                task.attempt++;
                if (reason.isRateLimited()) {
                    rateLimitedUntil = Math.max(rateLimitedUntil,
                            System.currentTimeMillis() + RATE_LIMIT_THROTTLE_MS);
                    lastErrorMessage = "Rate limited (HTTP 429)";
                    lastErrorTimestamp = System.currentTimeMillis();
                }
                if (task.attempt >= MAX_ATTEMPTS) {
                    tasksByLaneKey.remove(task.laneKey, task);
                    lastErrorMessage = safeError(reason);
                    lastErrorTimestamp = System.currentTimeMillis();
                    complete = true;
                } else {
                    final long delay = retryDelay(task.attempt);
                    task.retryFuture = retryTimer.schedule(new Runnable() {
                        @Override public void run() {
                            synchronized (lock) {
                                if (task.canceled || task.generation != generation || task.future.isDone()
                                        || tasksByLaneKey.get(task.laneKey) != task) return;
                                task.retryFuture = null;
                                lane(task.laneId).queued.add(task);
                                scheduleLocked();
                            }
                        }
                    }, delay, TimeUnit.MILLISECONDS);
                }
            }
            scheduleLocked();
        }
        if (complete) task.future.complete(TranslationResult.failed("translation retries exhausted"));
    }

    private void releaseRunningSlotLocked(QueuedTask task) {
        if (!task.running) return;
        task.running = false;
        task.requestFuture = null;
        LaneState lane = lane(task.laneId);
        if (lane.running.remove(task)) lane.runningCount = Math.max(0, lane.runningCount - 1);
        globalInFlight = Math.max(0, globalInFlight - 1);
    }

    private QueuedTask pollBestLocked() {
        long now = System.currentTimeMillis();
        QueuedTask best = null;
        LaneState bestLane = null;
        int bestIndex = -1;
        long bestScore = Long.MIN_VALUE;
        for (LaneState lane : lanes.values()) {
            if (lane.runningCount >= maxForLane(lane.id)) continue;
            for (int index = 0; index < lane.queued.size(); index++) {
                QueuedTask candidate = lane.queued.get(index);
                long ageBoost = Math.min(180L, Math.max(0L, now - candidate.createdAt) / 5000L);
                long score = candidate.priority + ageBoost;
                if (best == null || score > bestScore
                        || (score == bestScore && candidate.sequence < best.sequence)) {
                    best = candidate;
                    bestLane = lane;
                    bestIndex = index;
                    bestScore = score;
                }
            }
        }
        if (best == null) return null;
        bestLane.queued.remove(bestIndex);
        return best;
    }

    private QueuedTask removeOneLowPriorityTaskLocked() {
        QueuedTask candidate = null;
        LaneState candidateLane = null;
        for (LaneState lane : lanes.values()) {
            for (QueuedTask task : lane.queued) {
                if (task.protectedTask) continue;
                if (candidate == null || task.priority < candidate.priority
                        || (task.priority == candidate.priority && task.sequence < candidate.sequence)) {
                    candidate = task;
                    candidateLane = lane;
                }
            }
        }
        if (candidate == null) return null;
        candidateLane.queued.remove(candidate);
        tasksByLaneKey.remove(candidate.laneKey, candidate);
        candidate.canceled = true;
        return candidate;
    }

    private LaneState lane(String id) {
        LaneState lane = lanes.get(id);
        if (lane == null) {
            lane = new LaneState(id);
            lanes.put(id, lane);
        }
        return lane;
    }

    private static int maxForLane(String lane) {
        return "tooltip_hover".equals(lane) || "chat".equals(lane) || "hud".equals(lane) ? 2 : 1;
    }

    private static int priorityFor(String surface) {
        if (surface.startsWith("sign.manual")) return 500;
        if (surface.startsWith("hud.title") || surface.startsWith("title.")) return 480;
        if (surface.startsWith("tooltip") || surface.startsWith("hover")
                || "item_tooltip".equals(surface) || "hover_text".equals(surface)) return 400;
        if (surface.startsWith("hud.actionbar") || surface.startsWith("actionbar.")) return 360;
        if (surface.startsWith("chat")) return 350;
        if (surface.startsWith("hud") || surface.startsWith("scoreboard")
                || surface.startsWith("bossbar")) return 330;
        if (surface.startsWith("book")) return 220;
        if (surface.startsWith("advancement")) return 210;
        if (surface.contains(".fcs.")) return 10;
        return 250;
    }

    private static boolean protectedTaskFor(String surface) {
        return surface.startsWith("sign.manual") || surface.startsWith("tooltip")
                || surface.startsWith("hover") || surface.startsWith("chat")
                || surface.startsWith("hud") || surface.startsWith("title.")
                || surface.startsWith("actionbar.") || surface.startsWith("scoreboard")
                || surface.startsWith("bossbar") || surface.startsWith("book")
                || "item_tooltip".equals(surface) || "hover_text".equals(surface);
    }

    private static String laneForSurface(String surface) {
        return com.yourname.simpletranslate.core.Surface.classify(surface).requestLane();
    }

    private int effectiveMaxParallelRequestsLocked() {
        return System.currentTimeMillis() < rateLimitedUntil
                ? Math.max(1, Math.min(maxParallelRequests, RATE_LIMIT_MAX_PARALLEL))
                : maxParallelRequests;
    }

    private static long retryDelay(int completedAttempts) {
        int index = Math.max(0, Math.min(completedAttempts - 1, RETRY_DELAYS_MS.length - 1));
        return RETRY_DELAYS_MS[index];
    }

    private static String normalize(String surface) {
        return surface == null || surface.trim().isEmpty()
                ? "generic" : surface.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String safeError(Throwable error) {
        if (error == null) return "request failed";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static ThreadFactory namedFactory(final String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger nextId = new AtomicInteger(1);
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + nextId.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private static final class LaneState {
        private final String id;
        private final List<QueuedTask> queued = new ArrayList<QueuedTask>();
        private final List<QueuedTask> running = new ArrayList<QueuedTask>();
        private int runningCount;
        private LaneState(String id) { this.id = id; }
    }

    private static final class QueuedTask {
        private final long sequence;
        private final long generation;
        private final long createdAt = System.currentTimeMillis();
        private final String laneKey;
        private final String laneId;
        private final String surface;
        private final int priority;
        private final boolean protectedTask;
        private final Supplier<CompletableFuture<TranslationResult>> operation;
        private final CompletableFuture<TranslationResult> future =
                new CompletableFuture<TranslationResult>();
        private boolean canceled;
        private boolean running;
        private int attempt;
        private int coalescedCount;
        private long startedAt;
        private Future<?> workerFuture;
        private ScheduledFuture<?> retryFuture;
        private CompletableFuture<TranslationResult> requestFuture;

        private QueuedTask(long sequence, long generation, String laneKey, String laneId,
                           String surface, int priority, boolean protectedTask,
                           Supplier<CompletableFuture<TranslationResult>> operation) {
            this.sequence = sequence;
            this.generation = generation;
            this.laneKey = laneKey;
            this.laneId = laneId;
            this.surface = surface;
            this.priority = priority;
            this.protectedTask = protectedTask;
            this.operation = operation;
        }
    }

    /** Signals a transient transport/provider failure eligible for bounded retry. */
    static final class RetryableRequestException extends RuntimeException {
        RetryableRequestException(String message) { super(message); }
        RetryableRequestException(String message, Throwable cause) { super(message, cause); }
        boolean isRateLimited() { return getMessage() != null && getMessage().contains("HTTP 429"); }
    }
}
