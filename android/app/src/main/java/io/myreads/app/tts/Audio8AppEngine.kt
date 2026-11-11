package io.myreads.app.tts

import android.content.Context
import android.os.Build
import io.myreads.app.tts.audio8.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Serialized by SpeechModelRuntime; a failed or interrupted request never leaves playable PCM. */
class Audio8AppEngine internal constructor(private val backend: Backend) : Closeable {
    constructor(context: Context, root: File) : this(NativeBackend(context, root))

    internal interface Backend : Closeable {
        fun tokens(text: String): Int
        fun render(text: String, checkpoint: () -> Unit): Rendered
    }
    internal data class Rendered(val samples: FloatArray, val complete: Boolean,
                                 val prefillMs: Long = 0, val generationMs: Long = 0, val codecMs: Long = 0)
    class TooLong : IOException("这段声音未能完整生成")

    @Throws(TooLong::class, InterruptedException::class)
    fun synthesize(text: String, output: File, checkpoint: SynthesisCheckpoint): SynthesisResult {
        val start = System.nanoTime()
        var waiting = 0L
        val gate = {
            val before = System.nanoTime()
            checkpoint.awaitReady()
            waiting += System.nanoTime() - before
            Unit
        }
        try {
            gate()
            val parts = Audio8Text.split(text, { backend.tokens(it) }, MAX_TEXT_TOKENS)
            require(parts.isNotEmpty()) { "没有可以朗读的文字" }
            val pcm = ByteArrayOutputStream()
            var prefill = 0L; var generation = 0L; var codec = 0L
            for (part in parts) {
                gate()
                require(backend.tokens(part) <= MAX_TEXT_TOKENS) { "听书文本过长" }
                val rendered = backend.render(part, gate)
                gate()
                if (!rendered.complete) throw TooLong()
                require(rendered.samples.isNotEmpty() && rendered.samples.all { it.isFinite() }) { "听书生成了无效音频" }
                // Keep the auditioned model's pace and pauses; no stretching or extra silence.
                pcm.write(Wav.floatToPcm16(rendered.samples))
                prefill += rendered.prefillMs; generation += rendered.generationMs; codec += rendered.codecMs
            }
            gate()
            output.parentFile?.mkdirs()
            Wav.writeMono16(output, pcm.toByteArray(), SAMPLE_RATE)
            gate()
            // 375 remains the service's legacy retry sentinel, not Audio8's actual frame count.
            return SynthesisResult(output, 1, SAMPLE_RATE, pcm.size().toLong() * 1000 / (SAMPLE_RATE * 2),
                (System.nanoTime() - start - waiting) / 1_000_000,
                prefillMs = prefill, generationMs = generation, codecMs = codec)
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }
    override fun close() = backend.close()

    private class NativeBackend(context: Context, root: File) : Backend {
        private val transcript = JSONObject(File(root, "voice/meta.json").readText()).getString("reference_text")
        private val codes = Npy.loadInts(File(root, "voice/codes.npy")).let { npy ->
            require(npy.shape.size == 2 && npy.shape[0] == Audio8Engine.NUM_CB && npy.shape[1] in 1..216)
            Array(npy.shape[0]) { q -> IntArray(npy.shape[1]) { npy.data[q * npy.shape[1] + it] } }
        }
        private val preferences = context.getSharedPreferences("audio8", Context.MODE_PRIVATE)
        private val gpuKey = "failed|${Build.FINGERPRINT}|${io.myreads.app.BuildConfig.LITERT_VERSION}|${root.name}"
        private val engine: Audio8Engine
        init {
            val assets = context.assets
            val constants = PromptConstants(assets.open("tts/audio8/prompt_constants.json").bufferedReader().use { it.readText() })
            val reference = JSONObject(assets.open("tts/audio8/codec_check_T128.json").bufferedReader().use { it.readText() })
            val rows = reference.getJSONArray("codes")
            val checkCodes = Array(Audio8Engine.NUM_CB) { q -> rows.getJSONArray(q).let { row -> IntArray(row.length()) { row.getInt(it) } } }
            val bytes = assets.open("tts/audio8/" + reference.getString("wav_file")).use { it.readBytes() }
            val wav = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(wav)
            require(wav.size == minOf(checkCodes[0].size, 128) * Audio8Engine.FRAME)
            val gpuCache = File(context.cacheDir, "audio8/${root.name}/gpu").apply { check(isDirectory || mkdirs()) }
            val weightCache = File(context.cacheDir, "audio8/${root.name}/xnnpack").apply { check(isDirectory || mkdirs()) }
            engine = Audio8Engine(root, gpuCache, constants, weightCacheDir = weightCache,
                codecMode = if (preferences.contains(gpuKey)) "cpu" else "auto",
                checkReference = Audio8Engine.CheckReference(checkCodes, wav, reference.getString("decoder_sha256")))
            try {
                for (vector in constants.vectors)
                    check(engine.tokenizer.encode(vector.text).contentEquals(vector.ids)) { "听书分词器校验失败" }
            } catch (error: Throwable) { engine.close(); throw error }
            if (engine.codecMode == "auto" && engine.codecBackend == "CPU")
                preferences.edit().putString(gpuKey, engine.gpuError ?: "GPU check failed").apply()
        }
        override fun tokens(text: String) = engine.tokenizer.encode(text).size
        override fun render(text: String, checkpoint: () -> Unit): Rendered {
            engine.checkpoint = checkpoint
            try {
                val start = System.nanoTime()
                val generated = engine.generate(text, transcript, codes, Sampler(false, 42L))
                if (generated.stoppedBy != "eos" || generated.frames.isEmpty()) return Rendered(FloatArray(0), false)
                checkpoint()
                val codecStart = System.nanoTime()
                val samples = engine.decodeAudio(generated.frames, linkedMapOf(), arrayListOf())
                checkpoint()
                return Rendered(samples, true,
                    (generated.stats["prefill_ms"] as? Number)?.toLong() ?: 0,
                    (codecStart - start) / 1_000_000, (System.nanoTime() - codecStart) / 1_000_000)
            } finally { engine.checkpoint = null }
        }
        override fun close() = engine.close()
    }
    companion object {
        const val VOICE = "audio8-zh"
        const val SAMPLE_RATE = 44100
        const val MAX_TEXT_TOKENS = 49
    }
}
