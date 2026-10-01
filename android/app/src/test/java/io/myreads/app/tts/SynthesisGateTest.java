package io.myreads.app.tts;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SynthesisGateTest {
    @Test public void pauseSleepsAndResumeKeepsTheSameWork() throws Exception {
        SynthesisGate gate = new SynthesisGate(); gate.setPaused(true);
        CountDownLatch suspended = new CountDownLatch(1);
        AtomicInteger resumed = new AtomicInteger(), work = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = worker.submit(() -> {
                try { gate.awaitReady(suspended::countDown, resumed::incrementAndGet); work.incrementAndGet(); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            });
            assertTrue(suspended.await(2, TimeUnit.SECONDS));
            assertFalse(task.isDone()); assertEquals(0, work.get());
            gate.setPaused(false); task.get(2, TimeUnit.SECONDS);
            assertEquals(1, resumed.get()); assertEquals(1, work.get());
        } finally { gate.cancel(); worker.shutdownNow(); }
    }
    @Test public void stopUnblocksPausedWorkerWithoutResumingOrContinuing() throws Exception {
        SynthesisGate gate = new SynthesisGate(); gate.setPaused(true);
        CountDownLatch suspended = new CountDownLatch(1);
        AtomicInteger resumed = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> task = worker.submit(() -> {
                try { gate.awaitReady(suspended::countDown, resumed::incrementAndGet); return false; }
                catch (InterruptedException expected) { return true; }
            });
            assertTrue(suspended.await(2, TimeUnit.SECONDS));
            gate.cancel(); assertTrue(task.get(2, TimeUnit.SECONDS)); assertEquals(0, resumed.get());
            gate.setPaused(false);
            assertThrows(InterruptedException.class, () -> gate.awaitReady(() -> {}, () -> {}));
            // A replacement book owns a new gate and cannot revive the stopped producer.
            new SynthesisGate().awaitReady(() -> fail("unexpected pause"), () -> fail("unexpected resume"));
        } finally { gate.cancel(); worker.shutdownNow(); }
    }
    @Test public void interruptUnblocksPausedWorker() throws Exception {
        SynthesisGate gate = new SynthesisGate(); gate.setPaused(true);
        CountDownLatch suspended = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try { gate.awaitReady(suspended::countDown, () -> fail("unexpected resume")); }
            catch (InterruptedException expected) { interrupted.countDown(); }
        });
        try {
            worker.start(); assertTrue(suspended.await(2, TimeUnit.SECONDS));
            worker.interrupt(); assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        } finally { gate.cancel(); worker.interrupt(); worker.join(2000); }
    }
}
