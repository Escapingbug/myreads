package io.myreads.app.tts;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;

public class SpeechPriorityTest {
    @Test public void promptYieldsNarratorUntilPriorityRequestEnds() {
        SynthesisGate gate = new SynthesisGate();
        SpeechModelRuntime.checkNarration(gate);
        SpeechModelRuntime.beginGuidance();
        try {
            assertThrows(SpeechModelRuntime.YieldNarration.class, () -> SpeechModelRuntime.checkNarration(gate));
        } finally { SpeechModelRuntime.endGuidance(); }
        SpeechModelRuntime.checkNarration(gate);
    }
    @Test public void newGuidanceWakesAnAlreadyPausedModelCheckpoint() throws Exception {
        SynthesisGate gate = new SynthesisGate(); gate.setPaused(true);
        ExecutorService worker = Executors.newSingleThreadExecutor(); CountDownLatch suspended = new CountDownLatch(1);
        Future<?> task = worker.submit(() -> {
            try { gate.awaitModelReady(suspended::countDown, () -> {}, SpeechModelRuntime::guidancePending); }
            catch (InterruptedException failure) { throw new RuntimeException(failure); }
        });
        boolean requested = false;
        try {
            assertTrue(suspended.await(1, TimeUnit.SECONDS));
            SpeechModelRuntime.beginGuidance(); requested = true; gate.wakeForPriority();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> task.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof SpeechModelRuntime.YieldNarration);
        } finally { if (requested) SpeechModelRuntime.endGuidance(); gate.cancel(); worker.shutdownNow(); }
    }
}
