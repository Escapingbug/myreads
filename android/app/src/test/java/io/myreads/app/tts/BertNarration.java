package io.myreads.app.tts;

import java.util.*;

/** Preserve sentence prosody and paragraph positions; split long inputs only. */
final class BertNarration {
    private static final int MAX_CHARACTERS = 75;
    static List<OfficialNarration.Chunk> chapter(List<OfficialNarration.Paragraph> paragraphs) {
        List<OfficialNarration.Chunk> chunks = new ArrayList<>();
        for (OfficialNarration.Paragraph paragraph : paragraphs) {
            String text = java.text.Normalizer.normalize(paragraph.text, java.text.Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
            // Sentence boundaries preserve each comma and its semantic context.
            for (String sentence : sentences(text)) {
                if (sentence.codePoints().noneMatch(Character::isLetterOrDigit)) continue;
                List<String> parts = sentence.codePointCount(0,sentence.length()) <= MAX_CHARACTERS
                    ? Collections.singletonList(sentence) : OfficialNarration.split(sentence,
                        value -> value.codePointCount(0, value.length()), MAX_CHARACTERS);
                for (String part : parts) {
                    chunks.add(new OfficialNarration.Chunk(part, paragraph.index, 0));
                }
            }
        }
        return chunks;
    }
    private static List<String> sentences(String text) {
        List<String> result = new ArrayList<>(); int start=0;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            boolean end="。！？!?".indexOf(c)>=0 || c=='.' && (i+1==text.length()
                || Character.isWhitespace(text.charAt(i+1)) || "”’\"".indexOf(text.charAt(i+1))>=0);
            if(!end)continue;
            int after=i+1;
            while(after<text.length() && "。！？!?”’\"'」』".indexOf(text.charAt(after))>=0)after++;
            result.add(text.substring(start,after).trim());start=after;i=after-1;
        }
        if(start<text.length())result.add(text.substring(start).trim());
        return result;
    }
}
