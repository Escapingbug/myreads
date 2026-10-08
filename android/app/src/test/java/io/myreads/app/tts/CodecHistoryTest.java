package io.myreads.app.tts;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class CodecHistoryTest {
    private List<int[]> frames(int... values) {
        List<int[]> result = new ArrayList<>();
        for (int value : values) { int[] row = new int[16]; Arrays.fill(row, value); result.add(row); }
        return result;
    }
    @Test public void reusesExactHistoryAndCanWarmCodesFromAnInterveningCacheHit() {
        CodecHistory state = new CodecHistory();
        state.accept(frames(1), frames(2, 3));
        assertEquals(3, state.reusableFrames(frames(1, 2, 3)));
        assertEquals(3, state.reusableFrames(frames(1, 2, 3, 4, 5)));
    }
    @Test public void rollingWindowAndIndependentVoiceOrGuidanceRequireReset() {
        CodecHistory state = new CodecHistory(); state.accept(frames(1), frames(2, 3));
        assertEquals(-1, state.reusableFrames(frames(2, 3)));
        assertEquals(-1, state.reusableFrames(frames(9, 2, 3)));
        assertEquals(-1, state.reusableFrames(frames()));
        state.clear(); assertEquals(0, state.reusableFrames(frames(2, 3)));
    }
    @Test public void remembersValuesRatherThanMutableCallerArrays() {
        List<int[]> target = frames(2, 3);
        CodecHistory state = new CodecHistory(); state.accept(frames(1), target);
        target.get(0)[0] = 99;
        assertEquals(3, state.reusableFrames(frames(1, 2, 3)));
        List<int[]> changed = frames(1, 2, 3); changed.get(2)[15] = 99;
        assertEquals(-1, state.reusableFrames(changed));
    }
    @Test(expected = IllegalArgumentException.class) public void cannotRetainAnUnboundedSequence() {
        new CodecHistory().accept(Collections.nCopies(301, new int[16]), frames(1));
    }
}
