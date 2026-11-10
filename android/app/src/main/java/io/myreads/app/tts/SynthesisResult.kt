package io.myreads.app.tts

import java.io.File

data class SynthesisResult @JvmOverloads constructor(
    val outputFile: File,
    val generatedFrames: Int,
    val sampleRate: Int,
    val durationMs: Long,
    val elapsedMs: Long,
    val audioCodes: List<IntArray> = emptyList(),
    val rawLeading: Int = 0,
    val rawTrailing: Int = 0,
    val retainedTail: Int = 0,
    val prefillMs: Long = 0,
    val generationMs: Long = 0,
    val codecMs: Long = 0,
    val vadMs: Long = 0,
    val codecWarmFrames: Int = 0,
    val retryMs: Long = 0,
    val randomDraws: Long = 0,
) {
    fun includeRetries(ms: Long): SynthesisResult = copy(elapsedMs = elapsedMs + ms, retryMs = ms)
}
