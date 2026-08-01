package com.yourname.simpletranslate.feature.book;

import com.yourname.simpletranslate.core.ComponentListTranslationResult;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.core.LegacyComponentFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.text.ITextComponent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Full-book Component-JSON translation with ordered page context and bounded chunks. */
public final class BookTranslationSession {
    private static final int MAX_PAGES_PER_REQUEST = 6;
    private static final int MAX_SOURCE_CHARS = 3200;
    private static final long FAILURE_RETRY_MS = 6000L;
    private static final Map<String, List<String>> READY = Collections.synchronizedMap(
            new LinkedHashMap<String, List<String>>(16, 0.75F, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > 40;
                }
            });
    private static final Set<String> PENDING = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> REJECTED = Collections.synchronizedSet(new HashSet<String>());
    private static final Map<String, Long> RETRY_AFTER = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>());
    private static volatile long generation;

    private BookTranslationSession() { }

    public static String request(NBTTagList pages) {
        if (pages == null || pages.tagCount() == 0) return null;
        final String key = bookKey(pages);
        if (READY.containsKey(key)) return key;
        Long retryAt = RETRY_AFTER.get(key);
        if (retryAt != null && System.currentTimeMillis() < retryAt.longValue()) return key;
        RETRY_AFTER.remove(key);
        if (!PENDING.add(key)) return key;

        final List<String> originals = new ArrayList<String>(pages.tagCount());
        final List<ITextComponent> components = new ArrayList<ITextComponent>(pages.tagCount());
        for (int i = 0; i < pages.tagCount(); i++) {
            String raw = pages.getStringTagAt(i);
            originals.add(raw);
            components.add(parsePage(raw));
        }
        com.yourname.simpletranslate.cache.TranslationBlacklist blacklist =
                com.yourname.simpletranslate.SimpleTranslateForge1122.getTranslationBlacklist();
        if (blacklist != null) {
            StringBuilder document = new StringBuilder();
            for (ITextComponent component : components) document.append(component.getUnformattedText()).append('\n');
            if (blacklist.containsBlacklistedEntry(document.toString())) {
                PENDING.remove(key);
                REJECTED.add(key);
                return key;
            }
        }
        REJECTED.remove(key);
        final List<String> translated = new ArrayList<String>(originals);
        final List<CompletableFuture<ComponentListTranslationResult>> requests =
                new ArrayList<CompletableFuture<ComponentListTranslationResult>>();
        final List<Integer> starts = new ArrayList<Integer>();

        for (int start = 0; start < components.size();) {
            int end = start;
            int chars = 0;
            while (end < components.size() && end - start < MAX_PAGES_PER_REQUEST) {
                int next = components.get(end).getUnformattedText().length();
                if (end > start && chars + next > MAX_SOURCE_CHARS) break;
                chars += next;
                end++;
            }
            final int chunkStart = start;
            List<ITextComponent> chunk = new ArrayList<ITextComponent>(components.subList(start, end));
            starts.add(Integer.valueOf(chunkStart));
            requests.add(DirectSurfaceTranslator.translateComponentsAsync(chunk, "book.pages.direct",
                    "book-context-pages", true, buildContext(components, start, end)));
            start = end;
        }

        CompletableFuture<?>[] futures = requests.toArray(new CompletableFuture<?>[requests.size()]);
        CompletableFuture.allOf(futures).whenComplete(new java.util.function.BiConsumer<Void, Throwable>() {
            @Override public void accept(Void ignored, Throwable error) {
                Minecraft minecraft = Minecraft.getMinecraft();
                if (minecraft == null) { PENDING.remove(key); return; }
                minecraft.addScheduledTask(new Runnable() {
                    @Override public void run() {
                        try {
                            boolean acceptedAll = error == null;
                            for (int r = 0; r < requests.size(); r++) {
                                int start = starts.get(r).intValue();
                                int end = r + 1 < starts.size() ? starts.get(r + 1).intValue() : originals.size();
                                ComponentListTranslationResult result;
                                try { result = requests.get(r).getNow(null); }
                                catch (RuntimeException failedChunk) { result = null; }
                                if (result == null) { acceptedAll = false; break; }
                                if (!result.translated) {
                                    // A handled=false chunk contains no eligible text (or was
                                    // blacklisted) and is valid as unchanged original content.
                                    if (result.handled) { acceptedAll = false; break; }
                                    continue;
                                }
                                if (result.components == null || result.components.size() != end - start) {
                                    acceptedAll = false;
                                    break;
                                }
                                for (int i = 0; i < result.components.size(); i++) {
                                    translated.set(start + i,
                                            ITextComponent.Serializer.componentToJson(result.components.get(i)));
                                }
                            }
                            if (acceptedAll) {
                                READY.put(key, Collections.unmodifiableList(new ArrayList<String>(translated)));
                                RETRY_AFTER.remove(key);
                            } else {
                                RETRY_AFTER.put(key, Long.valueOf(System.currentTimeMillis() + FAILURE_RETRY_MS));
                            }
                        } finally {
                            PENDING.remove(key);
                        }
                    }
                });
            }
        });
        return key;
    }

    public static String translatedPage(String key, int page, String original) {
        if (key == null || page < 0) return original;
        List<String> ready = READY.get(key);
        if (ready != null && page < ready.size()) return ready.get(page);
        if (PENDING.contains(key)) {
            ITextComponent source = parsePage(original);
            ITextComponent translating = new net.minecraft.util.text.TextComponentString("翻译中...");
            if (source.getStyle() != null) translating.setStyle(source.getStyle().createShallowCopy());
            return ITextComponent.Serializer.componentToJson(translating);
        }
        return original;
    }

    public static String translatedPlainPage(String key, int page, String original) {
        String value = translatedPage(key, page, original);
        if (value == original || value == null) return original;
        try {
            ITextComponent component = ITextComponent.Serializer.jsonToComponent(value);
            return component == null ? original : component.getUnformattedText();
        } catch (RuntimeException ignored) {
            return value;
        }
    }

    public static boolean isPending(String key) { return key != null && PENDING.contains(key); }
    public static boolean isReady(String key) { return key != null && READY.containsKey(key); }
    public static boolean isRejected(String key) { return key != null && REJECTED.contains(key); }

    public static void clear() { generation++; READY.clear(); PENDING.clear(); REJECTED.clear(); RETRY_AFTER.clear(); }

    private static ITextComponent parsePage(String raw) {
        try {
            ITextComponent parsed = ITextComponent.Serializer.jsonToComponent(raw);
            return parsed == null ? LegacyComponentFactory.empty() : parsed;
        } catch (Exception ignored) {
            return LegacyComponentFactory.literal(raw == null ? "" : raw);
        }
    }

    private static String buildContext(List<ITextComponent> pages, int start, int end) {
        int from = Math.max(0, start - 2);
        int to = Math.min(pages.size(), end + 2);
        StringBuilder context = new StringBuilder("Ordered book pages; keep one response Component per page.");
        for (int i = from; i < to; i++) {
            String text = pages.get(i).getUnformattedText();
            if (text.length() > 600) text = text.substring(0, 600);
            context.append("\nPage ").append(i + 1).append(i >= start && i < end ? " [translate]: " : " [context]: ").append(text);
        }
        return context.toString();
    }

    private static String bookKey(NBTTagList pages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < pages.tagCount(); i++) {
                digest.update(pages.getStringTagAt(i).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            StringBuilder value = new StringBuilder(64);
            for (byte current : digest.digest()) value.append(String.format(java.util.Locale.ROOT, "%02x", current & 255));
            return com.yourname.simpletranslate.SimpleTranslateForge1122.getRuntimeRevision()+":"+generation+":"+value.toString();
        } catch (Exception ignored) {
            return com.yourname.simpletranslate.SimpleTranslateForge1122.getRuntimeRevision()+":"+generation+":"+Integer.toHexString(pages.toString().hashCode());
        }
    }
}
