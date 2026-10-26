package io.myreads.app.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Assert.*
import org.junit.Test

/** Opt-in host test using real pinned weights; never launches an Android device. */
class OfficialModelParityTest {
    @Test fun migratedEngineMatchesOfficialCodesAndPcmAcrossTwoLongformVoices() {
        Assume.assumeTrue(System.getProperty("ttsModelRoot") != null)
        val root = File(System.getProperty("ttsModelRoot"))
        val reference = File(System.getProperty("ttsReferenceRoot"))
        val output = File(System.getProperty("ttsParityOutput"))
        output.mkdirs()
        val tokenizer = NativeTokenizer(File(root, "MOSS-TTS-Nano-100M-ONNX/tokenizer.model"))
        val normalizer = NativeWeText(File("src/main/assets/tts/normalizer"), "zh")
        try {
            val original = File(reference, "original.txt").readText().trim()
            val paragraphs = original.split('\n').mapIndexed { i, text -> OfficialNarration.Paragraph(text, i) }
            val chunks = OfficialNarration.chapter(paragraphs,
                { text -> OfficialText.prepare(text, "Yuewen", normalizer::normalize) },
                { text -> tokenizer.tokenize(text).size })
            assertEquals(13, chunks.size)
            val expectedInputs = JSONObject(File(reference, "inputs.json").readText()).getJSONArray("official")
            chunks.forEachIndexed { i, chunk -> assertEquals(expectedInputs.getJSONObject(i).getString("text"), chunk.text) }
            val runs = JSONArray()
            MossOnnxDemoEngine(root, output, 2).use { engine ->
                for (voice in listOf("Yuewen", "Junhao")) {
                    var random = OfficialRandom()
                    val plans = File(output, "plans")
                    val plan = NarrationPlan.create(plans, "pinned-official-model", voice, "longform-reference", 0, chunks)
                    // Exercise the same transaction used by the service when
                    // narration yields to a UI prompt at a model checkpoint.
                    if (voice == "Yuewen") {
                        val initial = random.key()
                        var checkpoints = 0
                        try {
                            random.transaction().use {
                                engine.synthesize(tokenizer.tokenize(chunks[0].text), File(output, "interrupted.wav"), voice,
                                    375, random, SynthesisCheckpoint { if (++checkpoints == 25) throw InterruptedException("priority prompt") }, 0)
                            }
                            fail("Expected cooperative interruption")
                        } catch (expected: InterruptedException) { assertEquals(initial, random.key()) }
                    }
                    val files = mutableListOf<File>()
                    chunks.forEachIndexed { i, chunk ->
                        if (i == 6) {
                            // Resume with only durable plan/position data, as if
                            // the process and previously cached WAVs were gone.
                            val saved = JSONObject().put("pipeline", NarrationCache.REVISION).put("bookId", "test-book")
                                .put("chapter", 0).put("paragraph", chunk.paragraph).put("voice", voice)
                                .put("plan", plan.id).put("unit", i).put("rng", random.key()).put("rngDraws", random.draws())
                            val resumed = requireNotNull(NarrationPlan.Resume.read(JSONObject(saved.toString()), "test-book", 0, chunk.paragraph, voice))
                            val restored = requireNotNull(NarrationPlan.load(plans, resumed.plan, "pinned-official-model", voice, "longform-reference"))
                            assertEquals(chunk.text, restored.chunks[resumed.unit].text)
                            random = resumed.random
                        }
                        val file = File(output, "$voice-${i.toString().padStart(2, '0')}.wav")
                        val before = random.copy()
                        val result = engine.synthesize(tokenizer.tokenize(chunk.text), file, voice, 375, random,
                            SynthesisCheckpoint {}, chunk.pauseSamples)
                        assertTrue(result.generatedFrames in 1..374)
                        val expectedCodes = JSONArray(File(reference, "$voice-official-${i.toString().padStart(2, '0')}.codes.json").readText())
                        assertEquals(expectedCodes.length(), result.generatedFrames)
                        result.audioCodes.forEachIndexed { frame, codes ->
                            val row = expectedCodes.getJSONArray(frame)
                            assertArrayEquals("$voice chunk $i frame $frame", IntArray(row.length()) { row.getInt(it) }, codes)
                        }
                        assertEquals((result.generatedFrames + 1L) * 17, result.randomDraws)
                        val bytes = file.readBytes()
                        val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        val raw = ByteBuffer.wrap(File(reference, "$voice-official-mono-${i.toString().padStart(2, '0')}.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                        assertEquals(44 + (result.generatedFrames * 3840 + chunk.pauseSamples) * 2, bytes.size)
                        var maxPcmError = 0
                        for (sample in 0 until result.generatedFrames * 3840) {
                            val expected = Math.rint((raw.float.coerceIn(-1f, 1f) * 32767f).toDouble()).toInt()
                            maxPcmError = maxOf(maxPcmError, abs(pcm.getShort(44 + sample * 2).toInt() - expected))
                        }
                        assertTrue("$voice chunk $i PCM error $maxPcmError", maxPcmError <= 2)
                        for (sample in result.generatedFrames * 3840 until (bytes.size - 44) / 2)
                            assertEquals(0, pcm.getShort(44 + sample * 2).toInt())
                        val unit = NarrationPlanner.Unit(chunk.text, NarrationPlanner.Boundary.SENTENCE, false)
                        val sidecar = File(output, "$voice-$i.codes")
                        val clip = NarrationCache.Clip(file, unit, result.audioCodes, result.durationMs, result.elapsedMs,
                            0, 0, 0, result.randomDraws)
                        NarrationCache.write(sidecar, clip)
                        val hit = requireNotNull(NarrationCache.read(file, sidecar, unit))
                        before.advance(hit.randomDraws)
                        assertEquals(random.key(), before.key())
                        runs.put(JSONObject().put("voice", voice).put("chunk", i).put("frames", result.generatedFrames)
                            .put("codesExact", true).put("maxPcmError16bit", maxPcmError).put("randomDraws", result.randomDraws)
                            .put("restoredFromSavedPosition", i == 6))
                        File(output, "parity.json").writeText(runs.toString(2))
                        files += file
                    }
                    join(files, File(output, "$voice-migrated.wav"))
                }
            }
        } finally { normalizer.close(); tokenizer.close() }
    }
    private fun join(files: List<File>, output: File) {
        val payload = files.sumOf { it.length() - 44 }.toInt()
        val header = files.first().readBytes().copyOf(44)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(4, payload + 36).putInt(40, payload)
        output.outputStream().use { out ->
            out.write(header)
            files.forEach { file -> file.inputStream().use { input -> input.skip(44); input.copyTo(out) } }
        }
    }
}
