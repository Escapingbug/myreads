// Adapted from OpenMOSS/MOSS-TTS-Nano 8b7bcc9/onnx_tts_runtime.py, Apache-2.0.
package io.myreads.app.tts;

import java.util.*;
import java.util.function.Function;

/** Official 75-token greedy sentence packing, independent of printed paragraphs. */
public final class OfficialNarration {
    private static final String SENTENCE = ".!?。！？；;", CLAUSE = ",，、；;：:", CLOSING = "\"'”’)]}）】》」』";
    public interface Tokens { int count(String text); }
    public static final class Paragraph {
        final String text; final int index;
        public Paragraph(String text, int index) { this.text = text; this.index = index; }
    }
    public static final class Chunk {
        final String text; final int paragraph, pauseSamples;
        public Chunk(String text, int paragraph, int pauseSamples) {
            this.text = text; this.paragraph = paragraph; this.pauseSamples = pauseSamples;
        }
    }
    private static final class Slice {
        final String text; final int start;
        Slice(String text, int start) { this.text = text; this.start = start; }
    }
    public static List<Chunk> chapter(List<Paragraph> paragraphs, Function<String, String> normalize, Tokens tokens) {
        List<String> raw = new ArrayList<>(), parts = new ArrayList<>();
        for (Paragraph p : paragraphs) { raw.add(p.text); parts.add(normalize.apply(p.text)); }
        String prepared = normalize.apply(OfficialText.join("\n", raw));
        int[] ends = new int[paragraphs.size()]; int cursor = 0;
        boolean direct = OfficialText.join("", parts).equals(prepared);
        List<String> prefix = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            if (direct) cursor += parts.get(i).length();
            else {
                // Context-sensitive normalization is uncommon in prose. Derive
                // source boundaries from normalized prefixes when it does occur.
                prefix.add(raw.get(i)); cursor = normalize.apply(OfficialText.join("\n", prefix)).length();
            }
            ends[i] = Math.min(prepared.length(), cursor);
        }
        List<Slice> slices = packed(prepared, tokens, 75); List<Chunk> result = new ArrayList<>();
        int paragraph = 0;
        for (int i = 0; i < slices.size(); i++) {
            Slice s = slices.get(i);
            while (paragraph + 1 < ends.length && s.start >= ends[paragraph]) paragraph++;
            result.add(new Chunk(s.text, paragraphs.get(paragraph).index, i + 1 < slices.size() ? pauseSamples(s.text) : 0));
        }
        return result;
    }
    public static List<String> split(String prepared, Tokens tokens, int maxTokens) {
        List<String> result = new ArrayList<>(); for (Slice s : packed(prepared, tokens, maxTokens)) result.add(s.text); return result;
    }
    public static int pauseSamples(String text) {
        String stripped = OfficialText.strip(text);
        int words = stripped.isEmpty() ? 0 : stripped.split("\\s+").length;
        return words <= 4 ? 19200 : 11520;
    }
    private static List<Slice> packed(String original, Tokens tokens, int maxTokens) {
        String normalized = OfficialText.strip(original); if (normalized.isEmpty()) return Collections.emptyList();
        int budget = Math.max(1, maxTokens); String prepared = normalized.replace('\r', ' ').replace('\n', ' ');
        while (prepared.contains("  ")) prepared = prepared.replace("  ", " ");
        if (cjk(prepared)) {
            if (SENTENCE.indexOf(prepared.charAt(prepared.length() - 1)) < 0) prepared += "。";
        } else {
            int cp = prepared.codePointAt(0);
            if (Character.isLowerCase(cp)) prepared = new String(Character.toChars(Character.toUpperCase(cp))) + prepared.substring(Character.charCount(cp));
            if (Character.isLetterOrDigit(prepared.codePointBefore(prepared.length()))) prepared += ".";
            if (prepared.split("\\s+").length < 5) prepared = "        " + prepared;
        }
        List<Slice> sentences = splitPunctuation(prepared, SENTENCE, 0), slices = new ArrayList<>();
        for (Slice sentence : sentences) {
            if (tokens.count(sentence.text) <= budget) { slices.add(sentence); continue; }
            for (Slice clause : splitPunctuation(sentence.text, CLAUSE, sentence.start)) {
                if (tokens.count(clause.text) <= budget) slices.add(clause);
                else slices.addAll(byBudget(clause, tokens, budget));
            }
        }
        List<Slice> chunks = new ArrayList<>(); String current = ""; int count = 0, start = 0;
        for (Slice slice : slices) {
            int size = tokens.count(slice.text);
            if (current.isEmpty()) { current = slice.text; count = size; start = slice.start; }
            else if (count + size > budget) { chunks.add(new Slice(OfficialText.strip(current), start)); current = slice.text; count = size; start = slice.start; }
            else { current += cjk(current) || cjk(slice.text) ? slice.text : " " + slice.text; count = tokens.count(current); }
        }
        if (!current.isEmpty()) chunks.add(new Slice(OfficialText.strip(current), start));
        // Upstream deliberately returns the original normalized text when the
        // entire request fits into one chunk (including case and punctuation).
        return chunks.size() > 1 ? chunks : Collections.singletonList(new Slice(normalized, 0));
    }
    private static List<Slice> splitPunctuation(String text, String punctuation, int offset) {
        List<Slice> result = new ArrayList<>(); int start = 0, i = 0;
        while (i < text.length()) {
            if (punctuation.indexOf(text.charAt(i)) >= 0) {
                int end = i + 1; while (end < text.length() && CLOSING.indexOf(text.charAt(end)) >= 0) end++;
                add(result, text, start, end, offset); start = end;
                while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
                i = start;
            } else i++;
        }
        add(result, text, start, text.length(), offset); return result;
    }
    private static void add(List<Slice> result, String text, int from, int to, int offset) {
        while (from < to && Character.isWhitespace(text.charAt(from))) from++;
        String value = OfficialText.strip(text.substring(from, to)); if (!value.isEmpty()) result.add(new Slice(value, offset + from));
    }
    private static List<Slice> byBudget(Slice slice, Tokens tokens, int budget) {
        List<Slice> result = new ArrayList<>(); String remaining = OfficialText.strip(slice.text); int at = slice.start;
        while (!remaining.isEmpty()) {
            if (tokens.count(remaining) <= budget) { result.add(new Slice(remaining, at)); break; }
            int low = 1, high = remaining.codePointCount(0, remaining.length()), best = 1;
            while (low <= high) {
                int mid = (low + high) >>> 1, end = remaining.offsetByCodePoints(0, mid);
                if (tokens.count(OfficialText.strip(remaining.substring(0, end))) <= budget) { best = mid; low = mid + 1; }
                else high = mid - 1;
            }
            int cut = remaining.offsetByCodePoints(0, best), pos = cut;
            for (int n = 0; n < 24 && pos > 0; n++) {
                int cp = remaining.codePointBefore(pos); pos -= Character.charCount(cp);
                if ((SENTENCE + CLAUSE + " ").indexOf(cp) >= 0) { cut = pos + Character.charCount(cp); break; }
            }
            result.add(new Slice(OfficialText.strip(remaining.substring(0, cut)), at));
            at += cut; String rest = remaining.substring(cut); String stripped = OfficialText.strip(rest); at += rest.length() - stripped.length(); remaining = stripped;
        }
        return result;
    }
    private static boolean cjk(String text) {
        return text.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff || c >= 0x3400 && c <= 0x4dbf
            || c >= 0x3040 && c <= 0x30ff || c >= 0xac00 && c <= 0xd7af);
    }
}
