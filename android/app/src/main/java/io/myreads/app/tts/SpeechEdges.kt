package io.myreads.app.tts

import ai.onnxruntime.*
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Silero's recurrent 16kHz ONNX wrapper. Only its outer speech bounds are used; internal pauses stay intact. */
internal class SpeechEdges(private val env: OrtEnvironment, private val session: OrtSession) {
    fun detect(audio: FloatArray, checkpoint: SynthesisCheckpoint): IntArray {
        val context = FloatArray(64)
        var state = FloatArray(256)
        var first = -1
        var last = -1
        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000)), longArrayOf()).use { rate ->
            var offset = 0
            while (offset < audio.size) {
                checkpoint.awaitReady()
                val window = FloatArray(576)
                System.arraycopy(context, 0, window, 0, 64)
                for (i in 0 until 512) {
                    val index = offset + i * 3
                    if (index + 2 < audio.size) window[i + 64] = (audio[index] + audio[index + 1] + audio[index + 2]) / 3
                }
                OnnxTensor.createTensor(env, FloatBuffer.wrap(window), longArrayOf(1, 576)).use { input ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { memory ->
                        session.run(mapOf("input" to input, "state" to memory, "sr" to rate)).use { result ->
                            val confidence = (result.get("output").get() as OnnxTensor).floatBuffer.get(0)
                            val nextState = (result.get("stateN").get() as OnnxTensor).floatBuffer
                            state = FloatArray(256) { nextState.get(it) }
                            if (confidence >= 0.25f) {
                                if (first < 0) first = offset
                                last = minOf(audio.size, offset + 1536)
                            }
                        }
                    }
                }
                System.arraycopy(window, 512, context, 0, 64)
                offset += 1536
            }
        }
        return intArrayOf(first, last)
    }
}
