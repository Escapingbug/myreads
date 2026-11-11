package io.myreads.app.tts;

import java.util.ArrayList;
import java.util.List;

/** Split without rewriting punctuation, case or the model's natural pauses. */
final class Audio8Text {
    static List<String> split(String text, OfficialNarration.Tokens tokens, int budget) {
        List<String> result = new ArrayList<>();
        String rest = text.trim();
        while (!rest.isEmpty()) {
            if (tokens.count(rest) <= budget) { result.add(rest); break; }
            int count = Math.min(rest.codePointCount(0, rest.length()), 512);
            int cut = rest.offsetByCodePoints(0, count);
            while (cut > 0 && tokens.count(rest.substring(0, cut).trim()) > budget)
                cut = rest.offsetByCodePoints(cut, -1);
            if (cut == 0) throw new IllegalArgumentException("单个字符超过听书输入限制");
            int sentence = -1, clause = -1, word = -1;
            for (int i = 0; i < cut; i++) {
                if (i < cut / 2) continue;
                char c = rest.charAt(i);
                if ("。！？!?；;".indexOf(c) >= 0 || c == '.' &&
                    (i + 1 == rest.length() || Character.isWhitespace(rest.charAt(i + 1)))) sentence = i + 1;
                else if ("，,：:".indexOf(c) >= 0) clause = i + 1;
                else if (Character.isWhitespace(c)) word = i + 1;
            }
            int boundary = sentence > 0 ? sentence : clause > 0 ? clause : word;
            if (boundary > 0) cut = boundary;
            String part = rest.substring(0, cut).trim();
            if (!part.isEmpty()) result.add(part);
            rest = rest.substring(cut).trim();
        }
        return result;
    }
}
