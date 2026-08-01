package com.yourname.simpletranslate.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.yourname.simpletranslate.core.AtomicFiles;
import com.yourname.simpletranslate.core.TextContextMemory;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.api.TranslationRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages term dictionary for consistent translations
 * Automatically detects frequently occurring terms and allows manual additions
 */
public class TermDictionary {
    private static final Logger LOGGER = LogManager.getLogger("SimpleTranslate/Terms-1.12.2");

    private final Path termFile;
    private final Map<String, String> terms; // term -> translation
    private final Map<String, Integer> termCounts; // term -> occurrence count
    private final Map<Character, List<String>> termsByFirstChar = new ConcurrentHashMap<>();
    private final Gson gson;
    private volatile String promptFingerprint = CacheKey.hash("");

    // Pattern to extract potential terms (capitalized words, phrases in quotes,
    // etc.)
    private static final Pattern TERM_PATTERN = Pattern.compile(
            "\"([^\"]+)\"|'([^']+)'|\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*)\\b");

    public TermDictionary(Path termFile) {
        this.termFile = termFile;
        this.terms = new ConcurrentHashMap<>();
        this.termCounts = new ConcurrentHashMap<>();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public TermDictionary(File termFile) { this(termFile.toPath()); }

    /**
     * Load terms from file
     */
    public void load() {
        try {
            Files.createDirectories(termFile.getParent());

            if (Files.exists(termFile)) {
                String json = new String(Files.readAllBytes(termFile), StandardCharsets.UTF_8);
                TermData data = gson.fromJson(json, TermData.class);
                if (data != null) {
                    if (data.terms != null) {
                        terms.clear();
                        terms.putAll(data.terms);
                        rebuildTermIndex();
                    }
                    if (data.counts != null) {
                        termCounts.clear();
                        termCounts.putAll(data.counts);
                    }
                    LOGGER.debug("Loaded {} terms", terms.size());
                }
            }
            refreshPromptFingerprint(false);
        } catch (Exception e) {
            terms.clear();
            termCounts.clear();
            rebuildTermIndex();
            refreshPromptFingerprint(false);
            LOGGER.error("Failed to load term dictionary; reset to empty", e);
        }
    }

    /**
     * Save terms to file
     */
    public void save() {
        try {
            Files.createDirectories(termFile.getParent());
            TermData data = new TermData(terms, termCounts);
            String json = gson.toJson(data);
            AtomicFiles.writeString(termFile, json);
        } catch (IOException e) {
            LOGGER.error("Failed to save term dictionary", e);
        }
    }

    /**
     * Analyze text and record potential terms
     */
    public void analyzeAndRecordTerms(String text) {
        Matcher matcher = TERM_PATTERN.matcher(text);
        while (matcher.find()) {
            String term = matcher.group(1);
            if (term == null)
                term = matcher.group(2);
            if (term == null)
                term = matcher.group(3);

            if (term != null && term.length() >= 2) {
                recordOccurrence(term);
            }
        }
    }

    /**
     * Record an occurrence of a potential term
     */
    public void recordOccurrence(String term) {
        int count = termCounts.merge(term, 1, Integer::sum);
        int threshold = ModConfig.TERM_AUTO_DETECT_COUNT.get();

        // If term reaches threshold and not already in dictionary, add it
        if (count == threshold && !terms.containsKey(term)) {
            // Mark as pending (empty translation means needs translation)
            terms.put(term, "");
            indexTerm(term);
            refreshPromptFingerprint(true);
            LOGGER.info("Term '{}' auto-detected (appeared {} times)", term, count);
            save();
        }
    }

    /**
     * Add a term with its translation
     */
    public void addTerm(String term, String translation) {
        terms.put(term, translation);
        indexTerm(term);
        save();
        refreshPromptFingerprint(true);
    }

    /**
     * Remove a term
     */
    public void removeTerm(String term) {
        terms.remove(term);
        termCounts.remove(term);
        rebuildTermIndex();
        save();
        refreshPromptFingerprint(true);
    }


    /**
     * Get translation for a term
     */
    public Optional<String> getTranslation(String term) {
        String translation = terms.get(term);
        if (translation != null && !translation.isEmpty()) {
            return Optional.of(translation);
        }
        return Optional.empty();
    }

    /**
     * Get all terms (read-only view)
     */
    public Map<String, String> getAllTerms() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(terms));
    }

    /** Compatibility aliases used by the existing 1.12.2 screens during migration. */
    public void put(String source, String translation) { addTerm(source, translation); }
    public void remove(String source) { removeTerm(source); }
    public List<String> entries() {
        List<String> result = new ArrayList<String>();
        for (Map.Entry<String, String> entry : getAllTerms().entrySet()) {
            result.add(entry.getKey() + " = " + entry.getValue());
        }
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    public String promptHints(String sourceText) {
        StringBuilder result = new StringBuilder();
        for (TranslationRequest.Term term : matchTermsInText(sourceText)) {
            if (result.length() > 0) result.append("; ");
            result.append(term.source()).append(" => ").append(term.target());
        }
        return result.toString();
    }

    public String fingerprint() { return promptFingerprint(); }



    /**
     * Export terms to file
     */
    public void exportToFile(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        String json = gson.toJson(terms);
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        LOGGER.info("Exported {} terms to {}", terms.size(), file);
    }

    /**
     * Import terms from file
     *
     * @param merge If true, merge with existing. If false, replace.
     */
    public void importFromFile(Path file, boolean merge) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("Import file does not exist: " + file);
        }

        String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Type type = new TypeToken<Map<String, String>>() {
        }.getType();
        Map<String, String> imported = gson.fromJson(json, type);

        if (imported != null) {
            if (!merge) {
                terms.clear();
            }
            terms.putAll(imported);
            rebuildTermIndex();
            LOGGER.info("Imported {} terms from {}", imported.size(), file);
            save();
            refreshPromptFingerprint(true);
        }
    }

    /**
     * Clear all terms and counts
     */
    public void clear() {
        terms.clear();
        termCounts.clear();
        termsByFirstChar.clear();
        refreshPromptFingerprint(true);
        save();
    }

    /** Stable identity of only the non-empty term hints that can affect a model prompt. */
    public String promptFingerprint() {
        return promptFingerprint;
    }

    /**
     * Collect term hints whose source text appears in the given payload.
     */
    public List<TranslationRequest.Term> matchTermsInText(String text) {
        if (text == null || text.trim().isEmpty() || terms.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Character> chars = new HashSet<>();
        for (int i = 0; i < text.length(); i++) {
            chars.add(Character.toLowerCase(text.charAt(i)));
        }
        Set<String> checked = new HashSet<>();
        List<TranslationRequest.Term> hints = new ArrayList<>();
        for (char c : chars) {
            List<String> bucket = termsByFirstChar.get(c);
            if (bucket == null) {
                continue;
            }
            for (String term : bucket) {
                if (!checked.add(term)) {
                    continue;
                }
                String translation = terms.get(term);
                if (translation != null && !translation.trim().isEmpty() && text.contains(term)) {
                    hints.add(new TranslationRequest.Term(term, translation));
                }
            }
        }
        return hints.isEmpty() ? Collections.<TranslationRequest.Term>emptyList()
                : Collections.unmodifiableList(new ArrayList<TranslationRequest.Term>(hints));
    }

    private void rebuildTermIndex() {
        termsByFirstChar.clear();
        for (String term : terms.keySet()) {
            indexTerm(term);
        }
    }

    private void indexTerm(String term) {
        if (term == null || term.isEmpty()) {
            return;
        }
        char bucket = Character.toLowerCase(term.charAt(0));
        termsByFirstChar.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(term);
    }

    private void refreshPromptFingerprint(boolean notifyRuntime) {
        List<Map.Entry<String, String>> effective = new ArrayList<Map.Entry<String, String>>();
        for (Map.Entry<String, String> entry : terms.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().trim().isEmpty()
                    && entry.getValue() != null && !entry.getValue().trim().isEmpty()) {
                effective.add(new AbstractMap.SimpleImmutableEntry<String, String>(
                        entry.getKey(), entry.getValue()));
            }
        }
        Collections.sort(effective, new Comparator<Map.Entry<String, String>>() {
            @Override public int compare(Map.Entry<String, String> left, Map.Entry<String, String> right) {
                int key = left.getKey().compareTo(right.getKey());
                return key != 0 ? key : left.getValue().compareTo(right.getValue());
            }
        });
        StringBuilder identity = new StringBuilder();
        for (Map.Entry<String, String> entry : effective) {
            identity.append(entry.getKey()).append('\u0000')
                    .append(entry.getValue()).append('\n');
        }
        String next = CacheKey.hash(identity.toString());
        String previous = this.promptFingerprint;
        this.promptFingerprint = next;
        if (notifyRuntime && !Objects.equals(previous, next)) {
            TextContextMemory.settingsChanged();
        }
    }

    /**
     * Data class for JSON serialization
     */
    private static class TermData {
        Map<String, String> terms;
        Map<String, Integer> counts;

        TermData(Map<String, String> terms, Map<String, Integer> counts) {
            this.terms = terms;
            this.counts = counts;
        }
    }
}
