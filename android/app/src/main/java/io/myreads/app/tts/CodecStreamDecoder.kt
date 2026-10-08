package io.myreads.app.tts

import ai.onnxruntime.*
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.IntBuffer
import org.json.JSONObject

/** Reuse state only when it represents the exact beginning of the next prefix. */
internal class CodecStreamDecoder(private val env: OrtEnvironment, private val session: OrtSession, metadata: JSONObject) : Closeable {
    private val batchFrames = 16
    private data class State(val input: String, val output: String, val shape: LongArray, val integer: Boolean, val initial: Int = 0)
    private val states = mutableListOf<State>()
    private val zeros = linkedMapOf<String, OnnxTensor>()
    private var previous: OrtSession.Result? = null
    private val history = CodecHistory()
    var warmedFrames: Int = 0
        private set
    init {
        val stream = metadata.getJSONObject("streaming_decode")
        val offsets = stream.getJSONArray("transformer_offsets")
        for (i in 0 until offsets.length()) {
            val spec = offsets.getJSONObject(i)
            states += State(spec.getString("input_name"), spec.getString("output_name"), shape(spec, "shape"), true)
        }
        val caches = stream.getJSONArray("attention_caches")
        for (i in 0 until caches.length()) {
            val spec = caches.getJSONObject(i)
            states += State(spec.getString("offset_input_name"), spec.getString("offset_output_name"), shape(spec, "offset_shape"), true)
            states += State(spec.getString("cached_keys_input_name"), spec.getString("cached_keys_output_name"), shape(spec, "cache_shape"), false)
            states += State(spec.getString("cached_values_input_name"), spec.getString("cached_values_output_name"), shape(spec, "cache_shape"), false)
            states += State(spec.getString("cached_positions_input_name"), spec.getString("cached_positions_output_name"), shape(spec, "positions_shape"), true, -1)
        }
    }
    private fun shape(json: JSONObject, key: String): LongArray {
        val values = json.getJSONArray(key)
        return LongArray(values.length()) { values.getLong(it) }
    }
    private fun initial(state: State): OnnxTensor = zeros.getOrPut(state.input) {
        val count = state.shape.fold(1L) { a, b -> a * b }.toInt()
        if (state.integer) OnnxTensor.createTensor(env, IntBuffer.wrap(IntArray(count) { state.initial }), state.shape)
        else OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(count)), state.shape)
    }
    fun decode(codes: List<IntArray>, checkpoint: SynthesisCheckpoint, prefix: List<IntArray> = emptyList()): FloatArray {
        val reusable = history.reusableFrames(prefix)
        if (reusable < 0) reset()
        warmedFrames = 0
        try {
            // Bound transient waveform allocations during prefix warm-up. State
            // stays continuous across these batches and the new target codes.
            for (chunk in prefix.drop(reusable.coerceAtLeast(0)).chunked(batchFrames)) {
                checkpoint.awaitReady(); run(chunk); warmedFrames += chunk.size
            }
            val mono = FloatArray(codes.size * 3840)
            var offset = 0
            // Keep the codec's causal state while bounding attention/waveform
            // allocations. This does not split text or change the TTS request.
            for (chunk in codes.chunked(batchFrames)) {
                checkpoint.awaitReady()
                val next = run(chunk)
                offset += copyMono(next, chunk.size * 3840, mono, offset)
            }
            require(offset == mono.size) { "语音解码长度不一致" }
            history.accept(prefix, codes)
            return mono
        } catch (failure: Throwable) {
            reset(); throw failure
        }
    }
    private fun copyMono(next: OrtSession.Result, expected: Int, mono: FloatArray, offset: Int): Int {
        val audio = next.get("audio").get() as OnnxTensor
        val length = ((next.get("audio_lengths").get() as OnnxTensor).intBuffer).get(0)
        require(length == expected) { "语音解码长度不一致" }
        val shape = audio.info.shape
        require(shape.size == 3 && shape[0] == 1L && shape[1] > 0 && shape[2] >= length) { "语音解码形状不一致" }
        // Read channel-major PCM directly, avoiding nested Java array copies
        // and a per-sample collection reduction for a whole utterance.
        val channels = shape[1].toInt(); val stride = shape[2].toInt()
        val data = audio.floatBuffer
        if (channels == 1) data.get(mono, offset, length)
        else for (i in 0 until length) {
            var sum = 0.0
            for (channel in 0 until channels) sum += data.get(channel * stride + i).toDouble()
            mono[offset + i] = sum.toFloat() / channels
        }
        return length
    }
    private fun run(codes: List<IntArray>): OrtSession.Result {
        require(codes.isNotEmpty())
        val flat = IntArray(codes.size * 16)
        codes.forEachIndexed { index, row -> System.arraycopy(row, 0, flat, index * 16, 16) }
        OnnxTensor.createTensor(env, IntBuffer.wrap(flat), longArrayOf(1, codes.size.toLong(), 16)).use { audioCodes ->
            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(codes.size)), longArrayOf(1)).use { lengths ->
                val feeds = linkedMapOf<String, OnnxTensorLike>("audio_codes" to audioCodes, "audio_code_lengths" to lengths)
                for (spec in states) feeds[spec.input] = previous?.get(spec.output)?.get() as? OnnxTensor ?: initial(spec)
                val next = session.run(feeds)
                previous?.close(); previous = next
                return next
            }
        }
    }
    private fun reset() { previous?.close(); previous = null; history.clear() }
    override fun close() { reset(); zeros.values.forEach { it.close() }; zeros.clear() }
}
