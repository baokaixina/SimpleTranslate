package com.yourname.simpletranslate.translation;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-lane LLM request queue.
 *
 * <p>Each feature lane runs at most one HTTP request at a time except the
 * tooltip/hover lane, which may run two short interactive jobs in parallel.
 * Different lanes may run in parallel up to {@code api.maxParallelRequests}. This keeps
 * tooltip/chat/sign/HUD responsive without letting one surface share pending,
 * retry, or failure state with another surface.</p>
 */
public final class TranslationRequestQueue {
    private static final int MAX_UNIQUE_TASKS = 512;
    private static final int MAX_WORKERS = 8;
    private static final long RATE_LIMIT_THROTTLE_MS = 30_000L;
    private static final int RATE_LIMIT_MAX_PARALLEL = 3;
    private static final long[] RETRY_DELAYS_MS = { 1500L, 4000L, 10000L };
    private static final Object LOCK = new Object();
    private static final Map<String, LaneState> LANES = new LinkedHashMap<>();
    private static final Map<String, QueuedTask> TASKS_BY_LANE_KEY = new LinkedHashMap<>();
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(MAX_WORKERS, runnable -> {
        Thread thread = new Thread(runnable, "SimpleTranslate-RequestLane");
        thread.setDaemon(true);
        return thread;
    });
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SimpleTranslate-RequestTimer");
        thread.setDaemon(true);
        return thread;
    });

    private static long nextSequence;
    private static long generation;
    private static int globalInFlight;
    private static long rateLimitedUntilMs;

    private TranslationRequestQueue() {
    }

    public static CompletableFuture<String> submit(String key, String surface, Priority priority, int maxAttempts,
                                                   Callable<String> request) {
        if (request == null) {
            return CompletableFuture.completedFuture(null);
        }
        String normalizedKey = normalizeKey(key);
        String normalizedSurface = surface == null || surface.isBlank() ? "generic" : surface;
        String laneId = laneForSurface(normalizedSurface);
        String laneKey = laneId + "\u0001" + normalizedKey;
        Priority effectivePriority = priority == null ? Priority.NORMAL : priority;
        int attempts = Math.max(1, maxAttempts);

        synchronized (LOCK) {
            QueuedTask existing = TASKS_BY_LANE_KEY.get(laneKey);
            if (existing != null && !existing.future.isDone()) {
                existing.coalescedCount++;
                SimpleTranslateMod.getLogger().debug(
                        "Translation queue coalesced id={} lane={} surface={} coalesced={}",
                        existing.id, existing.laneId, existing.surface, existing.coalescedCount);
                return existing.future;
            }

            if (TASKS_BY_LANE_KEY.size() >= MAX_UNIQUE_TASKS && !dropOneLowPriorityTask()) {
                CompletableFuture<String> rejected = new CompletableFuture<>();
                rejected.complete(null);
                SimpleTranslateMod.getLogger().warn(
                        "Translation queue full; rejected protected task lane={} surface={} key={}",
                        laneId, normalizedSurface, shortKey(normalizedKey));
                return rejected;
            }

            QueuedTask task = new QueuedTask(
                    ++nextSequence,
                    generation,
                    normalizedKey,
                    laneKey,
                    laneId,
                    normalizedSurface,
                    effectivePriority,
                    attempts,
                    request);
            TASKS_BY_LANE_KEY.put(laneKey, task);
            lane(laneId).queue.add(task);
            SimpleTranslateMod.getLogger().debug(
                    "Translation queue enqueued id={} lane={} surface={} priority={} laneSize={} globalSize={}",
                    task.id, task.laneId, task.surface, task.priority,
                    lane(task.laneId).queue.size(), TASKS_BY_LANE_KEY.size());
            scheduleDrainLocked();
            return task.future;
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            generation++;
            for (LaneState lane : LANES.values()) {
                for (QueuedTask task : lane.queue) {
                    task.canceled = true;
                    task.future.complete(null);
                }
                lane.queue.clear();
                for (QueuedTask task : new ArrayList<>(lane.runningTasks)) {
                    task.canceled = true;
                    task.future.complete(null);
                    releaseRunningSlotLocked(task);
                    if (task.workerFuture != null) {
                        task.workerFuture.cancel(true);
                    }
                }
                lane.runningTasks.clear();
                lane.runningCount = 0;
            }
            for (QueuedTask task : TASKS_BY_LANE_KEY.values()) {
                task.canceled = true;
                task.future.complete(null);
            }
            TASKS_BY_LANE_KEY.clear();
            SimpleTranslateMod.getLogger().debug("Translation queue cleared generation={}", generation);
        }
    }

    public static int cancelSurfacePrefix(String surfacePrefix) {
        String normalizedPrefix = surfacePrefix == null ? "" : surfacePrefix.trim().toLowerCase(Locale.ROOT);
        if (normalizedPrefix.isBlank()) {
            return 0;
        }

        int canceled = 0;
        synchronized (LOCK) {
            for (LaneState lane : LANES.values()) {
                Iterator<QueuedTask> iterator = lane.queue.iterator();
                while (iterator.hasNext()) {
                    QueuedTask task = iterator.next();
                    if (task.surface.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix)) {
                        iterator.remove();
                        TASKS_BY_LANE_KEY.remove(task.laneKey, task);
                        task.canceled = true;
                        task.future.complete(null);
                        canceled++;
                    }
                }
                for (QueuedTask task : new ArrayList<>(lane.runningTasks)) {
                    if (task.surface.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix)) {
                        task.canceled = true;
                        if (task.workerFuture != null) {
                            task.workerFuture.cancel(true);
                        }
                        releaseRunningSlotLocked(task);
                        TASKS_BY_LANE_KEY.remove(task.laneKey, task);
                        task.future.complete(null);
                        canceled++;
                    }
                }
            }
        }
        if (canceled > 0) {
            SimpleTranslateMod.getLogger().debug(
                    "Translation queue canceled tasks surfacePrefix={} count={}",
                    normalizedPrefix, canceled);
        }
        return canceled;
    }

    private static void scheduleDrainLocked() {
        while (globalInFlight < maxParallelRequests()) {
            QueuedTask task = pollBestTaskLocked();
            if (task == null) {
                return;
            }
            task.running = true;
            task.startedAt = System.currentTimeMillis();
            LaneState lane = lane(task.laneId);
            lane.runningCount++;
            lane.runningTasks.add(task);
            globalInFlight++;
            task.workerFuture = WORKER.submit(() -> runTask(task));
        }
    }

    private static void runTask(QueuedTask task) {
        long waitMs = Math.max(0L, task.startedAt - task.createdAt);
        SimpleTranslateMod.getLogger().debug(
                "Translation queue start id={} lane={} surface={} priority={} attempt={}/{} waitMs={} globalInFlight={} laneInFlight={}/{} coalesced={}",
                task.id, task.laneId, task.surface, task.priority, task.attempt + 1,
                task.maxAttempts, waitMs, currentGlobalInFlight(),
                currentLaneInFlight(task.laneId), laneMaxInFlight(task.laneId), task.coalescedCount);
        try {
            String result = task.request.call();
            finishTask(task, result, null);
        } catch (RetryableTranslationException retryable) {
            retryTask(task, retryable);
        } catch (Exception e) {
            finishTask(task, null, e);
        }
    }

    private static QueuedTask pollBestTaskLocked() {
        long now = System.currentTimeMillis();
        QueuedTask best = null;
        LaneState bestLane = null;
        int bestIndex = -1;
        long bestScore = Long.MIN_VALUE;

        for (LaneState lane : LANES.values()) {
            if (lane.runningCount >= laneMaxInFlight(lane.id) || lane.queue.isEmpty()) {
                continue;
            }
            int laneBestIndex = 0;
            long laneBestScore = Long.MIN_VALUE;
            for (int i = 0; i < lane.queue.size(); i++) {
                QueuedTask candidate = lane.queue.get(i);
                long ageBoost = Math.min(180L, Math.max(0L, now - candidate.createdAt) / 5000L);
                long score = candidate.priority.weight + ageBoost;
                if (score > laneBestScore
                        || (score == laneBestScore && candidate.sequence < lane.queue.get(laneBestIndex).sequence)) {
                    laneBestScore = score;
                    laneBestIndex = i;
                }
            }
            QueuedTask candidate = lane.queue.get(laneBestIndex);
            if (laneBestScore > bestScore
                    || (laneBestScore == bestScore && (best == null || candidate.sequence < best.sequence))) {
                best = candidate;
                bestLane = lane;
                bestIndex = laneBestIndex;
                bestScore = laneBestScore;
            }
        }

        if (best == null || bestLane == null || bestIndex < 0) {
            return null;
        }
        return bestLane.queue.remove(bestIndex);
    }

    private static void finishTask(QueuedTask task, String result, Exception error) {
        long runMs = Math.max(0L, System.currentTimeMillis() - task.startedAt);
        synchronized (LOCK) {
            if (task.canceled || task.generation != generation) {
                releaseRunningSlotLocked(task);
                TASKS_BY_LANE_KEY.remove(task.laneKey, task);
                scheduleDrainLocked();
                return;
            }
            releaseRunningSlotLocked(task);
            TASKS_BY_LANE_KEY.remove(task.laneKey, task);
            if (error != null) {
                SimpleTranslateMod.getLogger().warn(
                        "Translation queue failed id={} lane={} surface={} runMs={} error={}",
                        task.id, task.laneId, task.surface, runMs,
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            } else {
                SimpleTranslateMod.getLogger().debug(
                        "Translation queue finish id={} lane={} surface={} runMs={} blank={}",
                        task.id, task.laneId, task.surface, runMs, result == null || result.isBlank());
            }
            task.future.complete(error == null ? result : null);
            scheduleDrainLocked();
        }
    }

    private static void retryTask(QueuedTask task, RetryableTranslationException retryable) {
        long runMs = Math.max(0L, System.currentTimeMillis() - task.startedAt);
        synchronized (LOCK) {
            if (task.canceled || task.generation != generation) {
                releaseRunningSlotLocked(task);
                TASKS_BY_LANE_KEY.remove(task.laneKey, task);
                task.future.complete(null);
                scheduleDrainLocked();
                return;
            }
            releaseRunningSlotLocked(task);
            task.attempt++;
            if (isRateLimit(retryable)) {
                rateLimitedUntilMs = Math.max(rateLimitedUntilMs, System.currentTimeMillis() + RATE_LIMIT_THROTTLE_MS);
                SimpleTranslateMod.getLogger().warn(
                        "Translation queue rate limited; temporarily capping global parallelism at {} for {} ms",
                        RATE_LIMIT_MAX_PARALLEL, RATE_LIMIT_THROTTLE_MS);
            }
            if (task.attempt >= task.maxAttempts || task.generation != generation) {
                TASKS_BY_LANE_KEY.remove(task.laneKey, task);
                task.future.complete(null);
                SimpleTranslateMod.getLogger().warn(
                        "Translation queue exhausted retries id={} lane={} surface={} runMs={} reason={}",
                        task.id, task.laneId, task.surface, runMs,
                        retryable.getMessage() == null ? "retryable-error" : retryable.getMessage());
                scheduleDrainLocked();
                return;
            }

            long delay = retryDelay(task.attempt);
            SimpleTranslateMod.getLogger().warn(
                    "Translation queue retry id={} lane={} surface={} nextAttempt={}/{} delayMs={} runMs={} reason={}",
                    task.id, task.laneId, task.surface, task.attempt + 1, task.maxAttempts,
                    delay, runMs, retryable.getMessage() == null ? "retryable-error" : retryable.getMessage());
            TIMER.schedule(() -> {
                synchronized (LOCK) {
                    if (task.future.isDone() || task.generation != generation || !TASKS_BY_LANE_KEY.containsKey(task.laneKey)) {
                        return;
                    }
                    lane(task.laneId).queue.add(task);
                    scheduleDrainLocked();
                }
            }, delay, TimeUnit.MILLISECONDS);

            scheduleDrainLocked();
        }
    }

    private static void releaseRunningSlotLocked(QueuedTask task) {
        if (!task.running) {
            return;
        }
        task.running = false;
        globalInFlight = Math.max(0, globalInFlight - 1);
        LaneState lane = lane(task.laneId);
        lane.runningTasks.remove(task);
        lane.runningCount = Math.max(0, lane.runningCount - 1);
    }

    private static long retryDelay(int completedAttempts) {
        int index = Math.max(0, Math.min(completedAttempts - 1, RETRY_DELAYS_MS.length - 1));
        return RETRY_DELAYS_MS[index];
    }

    private static boolean dropOneLowPriorityTask() {
        QueuedTask candidate = null;
        LaneState candidateLane = null;
        for (LaneState lane : LANES.values()) {
            for (QueuedTask task : lane.queue) {
                if (task.priority.protectedTask) {
                    continue;
                }
                if (candidate == null
                        || task.priority.weight < candidate.priority.weight
                        || (task.priority.weight == candidate.priority.weight && task.sequence < candidate.sequence)) {
                    candidate = task;
                    candidateLane = lane;
                }
            }
        }
        if (candidate == null || candidateLane == null) {
            return false;
        }
        candidateLane.queue.remove(candidate);
        TASKS_BY_LANE_KEY.remove(candidate.laneKey, candidate);
        candidate.future.complete(null);
        SimpleTranslateMod.getLogger().warn(
                "Translation queue dropped low priority task id={} lane={} surface={} priority={}",
                candidate.id, candidate.laneId, candidate.surface, candidate.priority);
        return true;
    }

    private static final AtomicLong ANONYMOUS_SEQUENCE = new AtomicLong();

    private static String normalizeKey(String key) {
        return key == null || key.isBlank() ? "anonymous:" + ANONYMOUS_SEQUENCE.incrementAndGet() : key;
    }

    private static String shortKey(String key) {
        if (key == null || key.isBlank()) {
            return "none";
        }
        return Integer.toHexString(key.hashCode());
    }

    private static int maxParallelRequests() {
        try {
            int configured = Math.max(1, Math.min(MAX_WORKERS, ModConfig.API_MAX_PARALLEL_REQUESTS.get()));
            if (System.currentTimeMillis() < rateLimitedUntilMs) {
                return Math.max(1, Math.min(configured, RATE_LIMIT_MAX_PARALLEL));
            }
            return configured;
        } catch (Exception ignored) {
            return 5;
        }
    }

    private static int currentGlobalInFlight() {
        synchronized (LOCK) {
            return globalInFlight;
        }
    }

    private static int currentLaneInFlight(String laneId) {
        synchronized (LOCK) {
            return lane(laneId).runningCount;
        }
    }

    private static int laneMaxInFlight(String laneId) {
        return "tooltip_hover".equals(laneId) ? 2 : 1;
    }

    private static boolean isRateLimit(RetryableTranslationException retryable) {
        String message = retryable == null ? "" : retryable.getMessage();
        return message != null && message.contains("HTTP 429");
    }

    private static LaneState lane(String laneId) {
        return LANES.computeIfAbsent(laneId == null || laneId.isBlank() ? "background" : laneId, LaneState::new);
    }

    private static String laneForSurface(String surface) {
        return com.yourname.simpletranslate.util.TranslationCacheKeys.requestLaneFromSurface(surface);
    }

    public enum Priority {
        MANUAL_SIGN(500, true),
        TITLE_URGENT(480, true),
        INTERACTIVE(400, true),
        ACTIONBAR_URGENT(360, true),
        CHAT(350, true),
        HUD(330, true),
        NORMAL(250, false),
        BOOK(220, true),
        ADVANCEMENT_INTERACTIVE(210, false),
        BACKGROUND(120, false),
        FRAGMENT(10, false);

        private final int weight;
        private final boolean protectedTask;

        Priority(int weight, boolean protectedTask) {
            this.weight = weight;
            this.protectedTask = protectedTask;
        }
    }

    public static final class RetryableTranslationException extends RuntimeException {
        public RetryableTranslationException(String message) {
            super(message);
        }

        public RetryableTranslationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class LaneState {
        private final String id;
        private final List<QueuedTask> queue = new ArrayList<>();
        private final List<QueuedTask> runningTasks = new ArrayList<>();
        private int runningCount;

        private LaneState(String id) {
            this.id = id;
        }
    }

    private static final class QueuedTask {
        private final long id;
        private final long sequence;
        private final long generation;
        private final String key;
        private final String laneKey;
        private final String laneId;
        private final String surface;
        private final Priority priority;
        private final int maxAttempts;
        private final Callable<String> request;
        private final CompletableFuture<String> future = new CompletableFuture<>();
        private final long createdAt = System.currentTimeMillis();
        private int attempt;
        private int coalescedCount;
        private boolean running;
        private boolean canceled;
        private long startedAt;
        private Future<?> workerFuture;

        private QueuedTask(long sequence, long generation, String key, String laneKey, String laneId,
                           String surface, Priority priority, int maxAttempts, Callable<String> request) {
            this.id = sequence;
            this.sequence = sequence;
            this.generation = generation;
            this.key = key;
            this.laneKey = laneKey;
            this.laneId = laneId;
            this.surface = surface;
            this.priority = priority;
            this.maxAttempts = maxAttempts;
            this.request = request;
        }
    }
}
