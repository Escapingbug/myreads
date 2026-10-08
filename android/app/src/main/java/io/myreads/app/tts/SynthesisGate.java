package io.myreads.app.tts;
import java.util.function.BooleanSupplier;

/** One generation's cooperative pause/cancel state, independent of the media player. */
final class SynthesisGate {
    private boolean paused, cancelled;

    synchronized void setPaused(boolean value) { paused = value; notifyAll(); }
    synchronized void cancel() { cancelled = true; notifyAll(); }
    synchronized boolean isPaused() { return paused; }
    synchronized void wakeForPriority() { notifyAll(); }
    synchronized void awaitModelReady(Runnable suspend, Runnable resume, BooleanSupplier priority) throws InterruptedException {
        checkCancelled();
        if (priority.getAsBoolean()) throw new SpeechModelRuntime.YieldNarration();
        if (!paused) return;
        suspend.run();
        while (paused && !cancelled && !priority.getAsBoolean()) wait();
        checkCancelled();
        if (priority.getAsBoolean()) throw new SpeechModelRuntime.YieldNarration();
        resume.run();
    }

    synchronized void awaitReady(Runnable suspend, Runnable resume) throws InterruptedException {
        checkCancelled();
        if (!paused) return;
        suspend.run();
        while (paused && !cancelled) wait();
        checkCancelled();
        resume.run();
    }

    private void checkCancelled() throws InterruptedException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException("听书已停止");
    }
}
