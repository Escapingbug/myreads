package io.myreads.app.tts;

import java.text.BreakIterator;
import java.util.*;
import java.util.regex.*;

/** Keep printed structure before normalizing the text that will actually be spoken. */
public final class NarrationPlanner {
    interface TokenCounter { int count(String text); }
    public enum Boundary { CONTINUATION, CLAUSE, SENTENCE, QUESTION, EXCLAMATION, ELLIPSIS, PARAGRAPH, TITLE, CHAPTER }
    static final class Unit {
        final String text;
        final Boundary ending;
        final boolean dialogue;
        Unit(String text, Boundary ending, boolean dialogue) { this.text = text; this.ending = ending; this.dialogue = dialogue; }
        Unit ending(Boundary value) { return new Unit(text, value, dialogue); }
    }
    static final int MAX_TOKENS = 75;
    static final double MAX_SECONDS = 22;
    private static final Pattern ATTRIBUTION = Pattern.compile(
        "^\\s*(?:他|她|我|老人|男人|女人|少年|女孩|男孩)[^。！？!?“「『\\n]{0,12}(?:说|问|答|道|喊|叫|回应|回答|低语)[。！？!?]");

    static List<Unit> paragraph(String original, TokenCounter tokens) {
        List<Unit> result = new ArrayList<>();
        int start = 0;
        Deque<Character> quotes = new ArrayDeque<>();
        for (int i = 0; i < original.length(); i++) {
            char c = original.charAt(i);
            if (c == '\'' && i > 0 && i + 1 < original.length()
                && Character.UnicodeScript.of(original.charAt(i - 1)) == Character.UnicodeScript.LATIN
                && Character.UnicodeScript.of(original.charAt(i + 1)) == Character.UnicodeScript.LATIN) continue;
            if (!quotes.isEmpty() && c == quotes.peek()) { quotes.pop(); continue; }
            int opening = "“「『‘\"'".indexOf(c);
            if (opening >= 0) { quotes.push("”」』’\"'".charAt(opening)); continue; }
            boolean end = "。！？!?；;…".indexOf(c) >= 0 || sentencePeriod(original, i) || c == '.' && i + 1 < original.length() && original.charAt(i + 1) == '.';
            if (!end) continue;
            // Finish a quoted turn, including nested quotation, before creating a request.
            if (!quotes.isEmpty()) {
                int closing = i + 1;
                while (closing < original.length() && "。！？!?….".indexOf(original.charAt(closing)) >= 0) closing++;
                if (quotes.size() != 1 || closing >= original.length() || original.charAt(closing) != quotes.peek()) continue;
            }
            int after = i + 1;
            while (after < original.length() && "。！？!?….”’\"'』」".indexOf(original.charAt(after)) >= 0) after++;
            quotes.clear();
            Boundary ending = boundary(original.substring(i, after));
            if (original.substring(i, after).matches("(?s).*[”’\"'』」].*")) {
                int resumed = resumedDialogue(original, after);
                if (resumed >= 0) {
                    int endOfTurn = quotedEnd(original, resumed);
                    if (endOfTurn >= 0 && fits(SpeechText.normalize(original.substring(start, endOfTurn)),
                        tokens, MAX_TOKENS, MAX_SECONDS)) {
                        // A comma-connected action between two quotations interrupts
                        // one turn: keep the short opening, action and resumed speech.
                        i = after - 1; continue;
                    }
                    // Even a long resumed turn should not leave its four-word
                    // opening detached from the intervening speaker/action clause.
                    after = resumed; ending = Boundary.CLAUSE;
                }
                Matcher attribution = ATTRIBUTION.matcher(original.substring(after));
                if (attribution.find()) {
                    after += attribution.end();
                    ending = boundary(original.substring(after - 1, after));
                }
            }
            add(result, original.substring(start, after), ending, tokens);
            start = after; i = after - 1;
        }
        if (start < original.length()) add(result, original.substring(start), Boundary.SENTENCE, tokens);
        // Read related narration together within the model budget. A complete quoted
        // turn (with its attribution) is its own unit, regardless of its duration.
        List<Unit> grouped = new ArrayList<>();
        for (Unit unit : result) {
            if (!grouped.isEmpty()) {
                Unit previous = grouped.get(grouped.size() - 1);
                String together = previous.text + (endsInLatin(previous.text) ? " " : "") + unit.text;
                if (!previous.dialogue && !unit.dialogue && previous.ending != Boundary.CONTINUATION
                    && previous.ending != Boundary.CLAUSE && previous.ending != Boundary.ELLIPSIS
                    && fits(together, tokens, MAX_TOKENS, MAX_SECONDS)) {
                    grouped.set(grouped.size() - 1, new Unit(together, unit.ending, previous.dialogue || unit.dialogue));
                    continue;
                }
            }
            grouped.add(unit);
        }
        result = grouped;
        if (!result.isEmpty()) result.set(result.size() - 1, result.get(result.size() - 1).ending(Boundary.PARAGRAPH));
        return result;
    }
    private static int resumedDialogue(String text, int after) {
        for (int i = after; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("。！？!?；;…\n".indexOf(c) >= 0 || sentencePeriod(text, i)) return -1;
            if ("“「『‘\"'".indexOf(c) < 0 || contraction(text, i)) continue;
            String bridge = text.substring(after, i).trim();
            return !bridge.isEmpty() && "，,、".indexOf(bridge.charAt(bridge.length() - 1)) >= 0
                && bridge.codePoints().anyMatch(Character::isLetterOrDigit) ? i : -1;
        }
        return -1;
    }
    private static int quotedEnd(String text, int start) {
        Deque<Character> quotes = new ArrayDeque<>();
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (contraction(text, i)) continue;
            if (!quotes.isEmpty() && c == quotes.peek()) {
                quotes.pop(); if (quotes.isEmpty()) return i + 1;
            } else {
                int opening = "“「『‘\"'".indexOf(c);
                if (opening >= 0) quotes.push("”」』’\"'".charAt(opening));
            }
        }
        return -1;
    }
    private static boolean contraction(String text, int i) {
        return text.charAt(i) == '\'' && i > 0 && i + 1 < text.length()
            && Character.UnicodeScript.of(text.charAt(i - 1)) == Character.UnicodeScript.LATIN
            && Character.UnicodeScript.of(text.charAt(i + 1)) == Character.UnicodeScript.LATIN;
    }
    private static boolean endsInLatin(String text) { return text.matches("(?s).*[A-Za-z][.!?]*$"); }
    static boolean sceneBreak(String text) {
        String compact = text.replaceAll("[\\s\\u3000]", "");
        return compact.isEmpty() || compact.matches("[＊*＃#—–─━=_＝·•….。]{3,}");
    }
    private static void add(List<Unit> output, String raw, Boundary ending, TokenCounter tokens) {
        String text = SpeechText.normalize(raw);
        if (ending == Boundary.CLAUSE && raw.matches("(?s).*[，,、][\\s\\u3000]*$"))
            text += text.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) ? "，" : ",";
        if (!text.codePoints().anyMatch(Character::isLetterOrDigit)) return;
        boolean dialogue = raw.matches("(?s).*[“”‘’「」『』\"'].*");
        split(output, text, ending, dialogue, tokens, MAX_TOKENS, MAX_SECONDS);
    }
    private static void split(List<Unit> output, String text, Boundary ending, boolean dialogue,
                              TokenCounter tokens, int maxTokens, double maxSeconds) {
        while (!text.isEmpty()) {
            if (fits(text, tokens, maxTokens, maxSeconds)) { output.add(new Unit(text, ending, dialogue)); break; }
            int low = 1, high = text.codePointCount(0, text.length()), count = 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                String prefix = text.substring(0, text.offsetByCodePoints(0, mid));
                if (fits(prefix, tokens, maxTokens, maxSeconds)) { count = mid; low = mid + 1; }
                else high = mid - 1;
            }
            int limit = text.offsetByCodePoints(0, count), cut = -1;
            Boundary splitEnding = null;
            for (int i = 0; i < limit; i++) if ("。！？!?；;".indexOf(text.charAt(i)) >= 0 || sentencePeriod(text, i)) {
                cut = i + 1; splitEnding = boundary(text.substring(i, i + 1));
            }
            if (cut <= 0) for (int i = 0; i < limit; i++) if ("，,、：:".indexOf(text.charAt(i)) >= 0) cut = i + 1;
            if (splitEnding == null) splitEnding = Boundary.CLAUSE;
            if (cut <= 0) {
                BreakIterator words = BreakIterator.getWordInstance(Locale.ROOT); words.setText(text);
                cut = words.isBoundary(limit) ? limit : words.preceding(limit);
                if (cut <= 0) cut = limit;
                splitEnding = Boundary.CONTINUATION;
            }
            output.add(new Unit(text.substring(0, cut), splitEnding, dialogue));
            text = text.substring(cut);
        }
    }
    static List<Unit> retry(Unit original, TokenCounter tokens) {
        List<Unit> smaller = new ArrayList<>();
        int count = tokens.count(original.text);
        split(smaller, original.text, original.ending, original.dialogue, tokens,
            Math.max(1, count / 2), Math.max(0.5, seconds(original.text) / 2));
        return smaller;
    }
    private static boolean fits(String text, TokenCounter tokens, int budget, double seconds) {
        return tokens.count(text) <= budget && seconds(text) <= seconds;
    }
    static double seconds(String text) {
        int chinese = 0, punctuation = 0;
        for (int c : text.codePoints().toArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) chinese++;
            if ("，。！？,.;:!?".indexOf(c) >= 0) punctuation++;
        }
        String wordsText = text.replaceAll("[\\p{IsHan}\\p{Punct}，。！？]", " ").trim();
        int words = wordsText.isEmpty() ? 0 : wordsText.split("\\s+").length;
        return chinese / 4.5 + words * 0.35 + punctuation * 0.15;
    }
    private static boolean sentencePeriod(String text, int i) {
        return text.charAt(i) == '.' && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1))
            || "”’\"".indexOf(text.charAt(i + 1)) >= 0);
    }
    private static Boundary boundary(String marks) {
        if (marks.contains("…") || marks.contains("..")) return Boundary.ELLIPSIS;
        if (marks.contains("？") || marks.contains("?")) return Boundary.QUESTION;
        if (marks.contains("！") || marks.contains("!")) return Boundary.EXCLAMATION;
        return Boundary.SENTENCE;
    }
}
