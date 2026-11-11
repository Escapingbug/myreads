package io.myreads.app.tts

import io.myreads.app.tts.audio8.Wav
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Audio8AppEngineTest {
    private class Fake : Audio8AppEngine.Backend {
        val texts = arrayListOf<String>()
        var complete = true
        var invalid = false
        var hook: (() -> Unit)? = null
        override fun tokens(text: String) = text.codePointCount(0, text.length)
        override fun render(text: String, checkpoint: () -> Unit): Audio8AppEngine.Rendered {
            texts.add(text); checkpoint(); hook?.invoke(); checkpoint()
            return Audio8AppEngine.Rendered(if (invalid) floatArrayOf(Float.NaN) else FloatArray(4410) { .1f }, complete)
        }
        override fun close() {}
    }
    private fun output() = File.createTempFile("audio8-unit-", ".wav").apply { deleteOnExit() }
    @Test fun writes44100MonoAndSplitsWithoutLosingText() {
        val fake = Fake(); val out = output()
        val text = "窗外的雨已经停了，".repeat(20) + "Good evening."
        val result = Audio8AppEngine(fake).synthesize(text, out) {}
        assertTrue(fake.texts.all { fake.tokens(it) <= 49 })
        assertEquals(text, fake.texts.joinToString(""))
        assertEquals(44100, Wav.pcm16(out).sampleRate)
        assertEquals(1, result.generatedFrames)
        assertEquals(fake.texts.size * 100L, result.durationMs)
    }
    @Test fun preservesCaseAndPunctuation() {
        val fake = Fake(); Audio8AppEngine(fake).synthesize("hello，停一停。", output()) {}
        assertEquals(listOf("hello，停一停。"), fake.texts)
    }
    @Test fun cappedOutputCannotBePlayedOrCached() {
        val fake = Fake().apply { complete = false }; val out = output()
        try { Audio8AppEngine(fake).synthesize("不完整", out) {}; fail() } catch (_: Audio8AppEngine.TooLong) {}
        assertFalse(out.exists())
    }
    @Test fun guidancePreemptionDeletesOutputAndNextRequestWorks() {
        val fake = Fake(); val engine = Audio8AppEngine(fake); val out = output()
        fake.hook = { throw SpeechModelRuntime.YieldNarration() }
        try { engine.synthesize("取消", out) {}; fail() } catch (_: SpeechModelRuntime.YieldNarration) {}
        assertFalse(out.exists())
        fake.hook = null; engine.synthesize("恢复", out) {}; assertTrue(out.length() > 44)
    }
    @Test fun interruptDuringGenerationDeletesOutput() {
        val fake = Fake(); val out = output(); var calls = 0
        try { Audio8AppEngine(fake).synthesize("暂停", out) { if (++calls == 3) throw InterruptedException() }; fail() }
        catch (_: InterruptedException) {}
        assertFalse(out.exists())
    }
    @Test fun rejectsNonFiniteAudio() {
        val fake = Fake().apply { invalid = true }; val out = output()
        try { Audio8AppEngine(fake).synthesize("无效", out) {}; fail() } catch (_: IllegalArgumentException) {}
        assertFalse(out.exists())
    }
    @Test fun splitterKeepsSupplementaryCharacters() {
        val text = "生僻字𠀀，停顿。".repeat(20)
        val parts = Audio8Text.split(text, { it.codePointCount(0, it.length) }, 49)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.codePointCount(0, it.length) <= 49 })
    }
}
