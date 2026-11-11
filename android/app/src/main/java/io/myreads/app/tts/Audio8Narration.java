package io.myreads.app.tts;

import java.util.*;

/** Cheap paragraph planning; the engine then enforces the real tokenizer's 49-token limit. */
final class Audio8Narration {
    static List<OfficialNarration.Chunk> chapter(List<OfficialNarration.Paragraph> paragraphs) {
        List<OfficialNarration.Chunk> chunks = new ArrayList<>();
        for (OfficialNarration.Paragraph paragraph : paragraphs) {
            String text = paragraph.text
                .replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
            if (text.codePoints().noneMatch(Character::isLetterOrDigit)) continue;
            for (String part : Audio8Text.split(text, value -> value.codePointCount(0,value.length()), 49))
                chunks.add(new OfficialNarration.Chunk(part, paragraph.index, 0));
        }
        return chunks;
    }
}
