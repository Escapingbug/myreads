package io.myreads.app.tts;

import java.util.*;

/** Nano continuation: the transcript is in user text, matching spoken codes are in assistant. */
public final class NarrationPrompt {
    public interface Encoder { int[] encode(String value); }
    static int[] continuation(Encoder encoder, String previous, String target, int start, int end, int audioStart) {
        List<int[]> parts = Arrays.asList(new int[]{start}, encoder.encode("user\n"),
            encoder.encode("<user_inst>\n- Reference(s):\n"), encoder.encode("None"),
            encoder.encode("\n- Instruction:\nNone\n- Tokens:\nNone\n- Quality:\nNone\n"
                + "- Sound Event:\nNone\n- Ambient Sound:\nNone\n- Language:\nNone\n- Text:\n"),
            encoder.encode(previous + target), encoder.encode("\n</user_inst>"), new int[]{end},
            encoder.encode("\n"), new int[]{start}, encoder.encode("assistant\n"), new int[]{audioStart});
        int[] result = new int[parts.stream().mapToInt(p -> p.length).sum()];
        int offset = 0;
        for (int[] part : parts) { System.arraycopy(part, 0, result, offset, part.length); offset += part.length; }
        return result;
    }
}
