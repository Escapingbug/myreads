package io.myreads.app.tts;

import java.util.*;

/** The exact code sequence represented by the retained codec state. */
final class CodecHistory {
    private List<int[]> decoded = Collections.emptyList();

    // A rolling window that dropped even one old frame needs a fresh decoder.
    // Matching only the tail would reuse a state conditioned on different audio.
    int reusableFrames(List<int[]> prefix) {
        if (decoded.size() > prefix.size()) return -1;
        for (int i = 0; i < decoded.size(); i++)
            if (!Arrays.equals(decoded.get(i), prefix.get(i))) return -1;
        return decoded.size();
    }

    void accept(List<int[]> prefix, List<int[]> target) {
        if (prefix.size() > NarrationContext.MAX_FRAMES || target.size() >= 375)
            throw new IllegalArgumentException("语音解码上下文过长");
        List<int[]> next = new ArrayList<>(prefix.size() + target.size());
        append(next, prefix); append(next, target);
        decoded = next;
    }

    private void append(List<int[]> next, List<int[]> part) {
        for (int[] row : part) {
            if (row.length != 16) throw new IllegalArgumentException("语音编码宽度不一致");
            next.add(row.clone());
        }
    }

    void clear() { decoded = Collections.emptyList(); }
}
