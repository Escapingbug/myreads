package io.myreads.app.tts

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The sole production synthesis backend. One instance, serialized by the runtime. */
class BertVitsEngine(root: File) : Closeable {
    class TooLong(message: String): IOException(message)
    private val frontend=BertFrontend(root)
    private var closed=false
    init { nativeLoad(root.absolutePath) }

    @Throws(IOException::class, InterruptedException::class)
    fun synthesize(text: String, output: File, checkpoint: SynthesisCheckpoint): SynthesisResult {
        check(!closed)
        val started=System.nanoTime();var waited=0L
        val gate=SynthesisCheckpoint {
            val before=System.nanoTime();checkpoint.awaitReady();waited+=System.nanoTime()-before
        }
        gate.awaitReady()
        val input=frontend.prepare(text)
        if(input.phones.size>512)throw TooLong("语音段落超出模型预算")
        val prepared=System.nanoTime()
        gate.awaitReady()
        val pcm=nativeSynthesize(input.phones,input.tones,input.languages,input.ids,input.word2ph,input.attention,gate)
            ?: throw IOException("这段声音未能生成，请重试")
        gate.awaitReady()
        // The audition only applied +20dB gain. Keep the same voice and pacing.
        // Bound a single constant gain for the whole utterance to avoid clipping;
        // do not normalize individual clauses, trim breaths or insert pauses.
        var peak=0f
        for(sample in pcm) {
            if(!sample.isFinite())throw IOException("语音生成出现异常，请重试")
            peak=maxOf(peak,kotlin.math.abs(sample))
        }
        if(pcm.isEmpty()||peak<1e-7f)throw IOException("语音生成为空，请重试")
        val gain=minOf(10f,.95f/peak)
        output.parentFile?.mkdirs()
        try {
            output.outputStream().buffered().use { out ->
                val bytes=pcm.size*2
                val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()).putInt(bytes+36).put("WAVEfmt ".toByteArray()).putInt(16)
                    .putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE*2).putShort(2).putShort(16)
                    .put("data".toByteArray()).putInt(bytes)
                out.write(header.array())
                val buffer=ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
                for(i in pcm.indices) {
                    if(i%4096==0)gate.awaitReady()
                    buffer.putShort((pcm[i]*gain*32767).toInt().coerceIn(-32768,32767).toShort())
                    if(!buffer.hasRemaining()){out.write(buffer.array());buffer.clear()}
                }
                if(buffer.position()>0)out.write(buffer.array(),0,buffer.position())
            }
            val elapsed=(System.nanoTime()-started-waited)/1000000
            return SynthesisResult(output,1,SAMPLE_RATE,pcm.size*1000L/SAMPLE_RATE,elapsed,
                prefillMs=(prepared-started)/1000000,generationMs=elapsed)
        } catch(error: Throwable){output.delete();throw error}
    }
    override fun close(){if(!closed){nativeClose();closed=true}}
    private external fun nativeLoad(root: String)
    private external fun nativeSynthesize(phones: IntArray,tones: IntArray,languages: IntArray,ids: IntArray,
                                         word2ph: IntArray,attention: IntArray,checkpoint: SynthesisCheckpoint): FloatArray?
    private external fun nativeClose()
    companion object {
        const val SAMPLE_RATE=22050
        const val VOICE="bili22"
        init {System.loadLibrary("zijian_bertvits")}
    }
}
