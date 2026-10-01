package io.myreads.app.tts

import ai.onnxruntime.*
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.IntBuffer
import org.json.JSONObject

/** Decode each independent voice-clone utterance from the codec's initial state. */
internal class CodecStreamDecoder(private val env: OrtEnvironment, private val session: OrtSession, metadata: JSONObject) : Closeable {
    private data class State(val input: String, val shape: LongArray, val integer: Boolean, val initial: Int = 0)
    private val states = mutableListOf<State>()
    private val zeros = linkedMapOf<String, OnnxTensor>()
    init {
        val stream = metadata.getJSONObject("streaming_decode")
        val offsets = stream.getJSONArray("transformer_offsets")
        for (i in 0 until offsets.length()) {
            val spec = offsets.getJSONObject(i)
            states += State(spec.getString("input_name"), shape(spec, "shape"), true)
        }
        val caches = stream.getJSONArray("attention_caches")
        for (i in 0 until caches.length()) {
            val spec = caches.getJSONObject(i)
            states += State(spec.getString("offset_input_name"), shape(spec, "offset_shape"), true)
            states += State(spec.getString("cached_keys_input_name"), shape(spec, "cache_shape"), false)
            states += State(spec.getString("cached_values_input_name"), shape(spec, "cache_shape"), false)
            states += State(spec.getString("cached_positions_input_name"), shape(spec, "positions_shape"), true, -1)
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
    fun decode(codes: List<IntArray>, checkpoint: SynthesisCheckpoint): FloatArray {
        checkpoint.awaitReady()
        return run(codes).use { next ->
            val audio = next.get("audio").get() as OnnxTensor
            val channels = (audio.value as Array<*>)[0] as Array<*>
            val arrays = channels.map { it as FloatArray }
            val length = ((next.get("audio_lengths").get() as OnnxTensor).intBuffer).get(0)
            require(length == codes.size * 3840) { "语音解码长度不一致" }
            FloatArray(length) { index -> arrays.sumOf { it[index].toDouble() }.toFloat() / arrays.size }
        }
    }
    private fun run(codes: List<IntArray>): OrtSession.Result {
        require(codes.isNotEmpty())
        val flat = IntArray(codes.size * 16)
        codes.forEachIndexed { index, row -> System.arraycopy(row, 0, flat, index * 16, 16) }
        OnnxTensor.createTensor(env, IntBuffer.wrap(flat), longArrayOf(1, codes.size.toLong(), 16)).use { audioCodes ->
            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(codes.size)), longArrayOf(1)).use { lengths ->
                val feeds = linkedMapOf<String, OnnxTensorLike>("audio_codes" to audioCodes, "audio_code_lengths" to lengths)
                for (spec in states) feeds[spec.input] = initial(spec)
                return session.run(feeds)
            }
        }
    }
    override fun close() { zeros.values.forEach { it.close() }; zeros.clear() }
}
