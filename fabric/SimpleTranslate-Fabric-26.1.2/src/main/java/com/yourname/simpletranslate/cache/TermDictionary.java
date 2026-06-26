package com.yourname.simpletranslate.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.api.TranslationRequest;

import java.io.IOException;
import java.lang.reflect.Type;
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

    private final Path termFile;
    private final Map<String, String> terms; // term -> translation
    private final Map<String, Integer> termCounts; // term -> occurrence count
    private final Map<Character, List<String>> termsByFirstChar = new ConcurrentHashMap<>();
    private final Gson gson;

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

    /**
     * Load terms from file
     */
    public void load() {
        try {
            Files.createDirectories(termFile.getParent());

            if (Files.exists(termFile)) {
                String json = Files.readString(termFile);
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
                    SimpleTranslateMod.getLogger().debug("Loaded {} terms", terms.size());
                }
            }
        } catch (Exception e) {
            terms.clear();
            termCounts.clear();
            rebuildTermIndex();
            SimpleTranslateMod.getLogger().error("Failed to load term dictionary; reset to empty", e);
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
            Files.writeString(termFile, json);
        } catch (IOException e) {
            SimpleTranslateMod.getLogger().error("Failed to save term dictionary", e);
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
            SimpleTranslateMod.getLogger().info("Term '{}' auto-detected (appeared {} times)", term, count);
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
    }

    /**
     * Remove a term
     */
    public void removeTerm(String term) {
        terms.remove(term);
        termCounts.remove(term);
        rebuildTermIndex();
        save();
    }

    /**
     * Update a term's translation
     */
    public void updateTerm(String term, String translation) {
        if (terms.containsKey(term)) {
            terms.put(term, translation);
            save();
        }
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
        return Map.copyOf(terms);
    }

    /**
     * Get all term counts (read-only view)
     */
    public Map<String, Integer> getAllCounts() {
        return Map.copyOf(termCounts);
    }

    /**
     * Get terms that need translation (empty translation)
     */
    public List<String> getPendingTerms() {
        return terms.entrySet().stream()
                .filter(e -> e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Export terms to file
     */
    public void exportToFile(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        String json = gson.toJson(terms);
        Files.writeString(file, json);
        SimpleTranslateMod.getLogger().info("Exported {} terms to {}", terms.size(), file);
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

        String json = Files.readString(file);
        Type type = new TypeToken<Map<String, String>>() {
        }.getType();
        Map<String, String> imported = gson.fromJson(json, type);

        if (imported != null) {
            if (!merge) {
                terms.clear();
            }
            terms.putAll(imported);
            rebuildTermIndex();
            SimpleTranslateMod.getLogger().info("Imported {} terms from {}", imported.size(), file);
            save();
        }
    }

    /**
     * Clear all terms and counts
     */
    public void clear() {
        terms.clear();
        termCounts.clear();
        termsByFirstChar.clear();
    }

    /**
     * Collect term hints whose source text appears in the given payload.
     */
    public List<TranslationRequest.Term> matchTermsInText(String text) {
        if (text == null || text.isBlank() || terms.isEmpty()) {
            return List.of();
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
                if (translation != null && !translation.isBlank() && text.contains(term)) {
                    hints.add(new TranslationRequest.Term(term, translation));
                }
            }
        }
        return hints.isEmpty() ? List.of() : List.copyOf(hints);
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
