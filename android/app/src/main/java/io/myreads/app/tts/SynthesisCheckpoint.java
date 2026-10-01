package io.myreads.app.tts;

@FunctionalInterface
public interface SynthesisCheckpoint {
    void awaitReady() throws InterruptedException;
}
