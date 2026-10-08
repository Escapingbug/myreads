package io.myreads.app.tts;

import java.util.*;

/** A bounded transcript/audio window. Eviction always removes a complete generated unit. */
final class NarrationContext {
    static final int MAX_UNITS = 3, MAX_FRAMES = 300, MAX_TEXT_TOKENS = 150;
    static final int MAX_CONTINUATIONS = 4, MIN_FRAMES = 25;
    static final class Window {
        final String text;
        final List<int[]> codes;
        final int units;
        Window(String text, List<int[]> codes, int units) {
            this.text = text; this.codes = codes; this.units = units;
        }
    }
    private final Deque<NarrationCache.Clip> history = new ArrayDeque<>();
    private int continuations;
    void clear() { history.clear(); continuations = 0; }
    Window before(NarrationPlanner.Unit target) {
        if (target.ending == NarrationPlanner.Boundary.TITLE || continuations >= MAX_CONTINUATIONS) clear();
        if (history.isEmpty()) return null;
        Window window = window();
        return window.codes.size() < MIN_FRAMES ? null : window;
    }
    void accept(NarrationCache.Clip clip, Window used, NarrationPlanner.TokenCounter tokens) {
        if (clip.unit.ending == NarrationPlanner.Boundary.TITLE || clip.unit.ending == NarrationPlanner.Boundary.CHAPTER
            || clip.codes.isEmpty()) { clear(); return; }
        continuations = used == null ? 0 : continuations + 1;
        history.addLast(clip);
        while (!history.isEmpty() && (history.size() > MAX_UNITS || frameCount() > MAX_FRAMES
            || tokens.count(transcript()) > MAX_TEXT_TOKENS)) history.removeFirst();
    }
    private int frameCount() { int frames = 0; for (NarrationCache.Clip clip : history) frames += clip.codes.size(); return frames; }
    private String transcript() {
        StringBuilder text = new StringBuilder();
        for (NarrationCache.Clip clip : history) text.append(clip.unit.text).append('\n');
        return text.toString();
    }
    private Window window() {
        List<int[]> codes = new ArrayList<>();
        for (NarrationCache.Clip clip : history) for (int[] row : clip.codes) codes.add(row.clone());
        return new Window(transcript(), Collections.unmodifiableList(codes), history.size());
    }
}
