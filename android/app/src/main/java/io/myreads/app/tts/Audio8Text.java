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
            int boundary = -1;
            for (int i = 0; i < cut; i++) {
                if ("。！？!?；;，,：: \n".indexOf(rest.charAt(i)) >= 0 && i >= cut / 2) boundary = i + 1;
            }
            if (boundary > 0) cut = boundary;
            String part = rest.substring(0, cut).trim();
            if (!part.isEmpty()) result.add(part);
            rest = rest.substring(cut).trim();
        }
        return result;
    }
}
