// Adapted from litert-community/Audio8-TTS-Realistic-0.6b @ dcf477032785f2dda4af08c89a0de8509e511c6f (Android example, MIT).
package io.myreads.app.tts.audio8

import io.myreads.app.BuildConfig

import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import java.util.SplittableRandom
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Audio8-TTS (Realistic / Preview 0.6b) on LiteRT: a line-by-line Kotlin port of the reference host loop
 * `audio8_tts_litert.py` of the model repository (class Audio8LiteRT) on the CompiledModel API.
 *
 * Graphs: slow AR `slow_ar_int8.tflite` (prefill_256 + decode, and prefill_64 when the graph has it, KV cache as 48 f32 tensors
 * [1,2,cache,64]; the cache length is read from the graph, so one APK runs the KV 2048 and the KV 1024 exports), fast
 * AR `fast_ar_int8.tflite` (step, 10 calls per frame, KV [4,1,2,10,64]), codec decoder
 * (decode: codes i32 [1,10,T] -> wav f32 [1,1,T*2048]) and the codec encoder (encode: audio [1,1,442368] ->
 * codes [1,10,216]). Every buffer is addressed by tensor NAME through run(Map, Map, signature): the positional
 * lists follow the subgraph's input order, not the signature's.
 *
 * KV cache: two persistent sets of 48 TensorBuffers; in [kvMode] "inplace" (the default) one set is bound as both the
 * input and the output of every slow call (the graph's DYNAMIC_UPDATE_SLICE then writes in place instead of copying
 * the whole cache, 24 MiB at KV 1024; valid because no op of the graph reads a KV input after its KV output is
 * written); in "pingpong" each slow call reads one set and writes the other. Either way no cache bytes cross the JNI
 * boundary per step; both give the same codes, and in place was the faster one on both phones measured (model card).
 * A generate needs prompt + [MAX_NEW] positions inside the cache, and refuses a longer prompt. The fast
 * AR keeps its two small sets plus a write-once zero set for the first call of every frame.
 *
 * Codec decoder (the Realistic files): one fp16 graph per window (T128, T192) that runs on the GPU and, unchanged, on
 * the CPU. The files are a rewrite of the published fp16 decoders (codec_gpu_rewrite.py in hf-to-litertlm) that the LiteRT GPU computes correctly
 * at fp16 (the published graphs give a wrong output on the GPU at fp16 and fp32: model card); their CPU
 * output equals the published graphs' byte for byte. Codec mode "auto" = the GPU when its output on the check codes
 * matches a CPU reference bundled with the app ([CheckReference]: the CPU output of the T128 file on the same codes;
 * corr >= 0.99), else the CPU (fp16, T192 windows with 128 frames of left context, as the reference host loop decodes).
 * On the GPU both windows are created and warmed at load, so no sentence pays for a decoder's creation.
 */
class Audio8Engine(
    private val dir: File,
    private val gpuCacheDir: File,
    val constants: PromptConstants,
    val threads: Int = 4,
    /** "output": KV sets from createOutputBuffer (the default); "input": from createInputBuffer. */
    val kvOwner: String = "output",
    /**
     * The folder of the slow graph's XNNPACK weight cache ([WeightCache]: the file is named by the model's sha256;
     * null = no cache, the weights are packed again for every signature at each load). CPU only.
     */
    val weightCacheDir: File? = null,
    /** "auto": GPU codec if it passes the output check, else CPU; "gpu": GPU without the check; "cpu"; "none" (AR only). */
    val codecMode: String = "auto",
    /** The GPU codec output check's codes and the CPU output it is compared with (null = no check possible). */
    private val checkReference: CheckReference? = null,
    /** The graph file names in [dir]. */
    val files: Files = Files(),
    /** "inplace" or "pingpong" (see the class comment); may be switched between calls, every generate starts from zero. */
    kvMode: String = "inplace",
    /** Where the slow / fast AR graphs run: "cpu" (the app), "gpu" (GPU + CPU fallback, default precision), "gpu32". */
    val slowAccel: String = "cpu",
    val fastAccel: String = "cpu",
    /**
     * What the GPU codec output check compares with: "reference" (the app: the bundled CPU reference), "device_cpu" (the
     * same file decoded on this phone's CPU at load, the check of the first published app; kept for comparison runs) or
     * "both" (decided by the reference, the phone's CPU measured beside it).
     */
    val codecCheck: String = "reference",
    /** Create and warm the T192 GPU decoder at load (the app); false = at the first sentence over 128 frames. */
    val t192AtLoad: Boolean = true,
) : AutoCloseable {

    /**
     * The check's codes [10][n] (n >= 1; the first 128 frames are used) and the CPU output of the T128 decoder on them,
     * wav [min(n, 128) * FRAME] (empty for the "device_cpu" check, which decodes the codes on the phone instead).
     */
    class CheckReference(val codes: Array<IntArray>, val wav: FloatArray, val source: String)

    /** The graph files the engine reads. The CPU codec is the GPU codec's own file unless named otherwise. */
    data class Files(
        val slow: String = SLOW,
        val fast: String = FAST,
        val gpu128: String = CODEC_GPU_128,
        val gpu192: String = CODEC_GPU_192,
        val cpu128: String = CODEC_CPU_128,
        val cpu192: String = CODEC_CPU_192,
        val encoder: String = ENCODER,
    )

    companion object {
        const val TAG = "Audio8Demo"
        const val SEM_BEGIN = 151678
        const val SEM_END = 155773
        const val EOS = 151645
        const val NUM_CB = 10
        const val ROWS = NUM_CB + 1
        const val CB_SIZE = 4096
        const val SEM_LOGITS = 4097          // lm_head rows: semantic range (4096) + EOS
        const val DIM = 896
        const val FAST_KV_FLOATS = 4 * 1 * 2 * NUM_CB * 64
        const val PREFILL_T = 256
        /** The short prefill signature (prefill_64): a prompt's last chunk of at most this many rows runs it, if present. */
        const val PREFILL_SHORT = 64
        const val SR = 44100
        const val FRAME = 2048
        const val NEG = -1e9f
        const val ENC_SAMPLES = 442368
        const val ENC_FRAMES = 216
        const val MAX_NEW = 512
        const val CODEC_CTX = 128
        const val SLOW = "slow_ar_int8.tflite"
        const val FAST = "fast_ar_int8.tflite"
        const val CODEC_GPU_128 = "codec_decoder_fp16_T128.tflite"
        const val CODEC_GPU_192 = "codec_decoder_fp16_T192.tflite"
        /** The CPU decoder = the same fp16 file (its CPU output equals the published decoder's byte for byte). */
        const val CODEC_CPU_128 = CODEC_GPU_128
        const val CODEC_CPU_192 = CODEC_GPU_192
        const val ENCODER = "codec_encoder_fp16_10s.tflite"
        val KV_NAMES: List<String> = (0 until 24).flatMap { listOf("k_$it", "v_$it") }
        val AR_ACCELS = setOf("cpu", "gpu", "gpu32")

        fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()
        fun ms(ns: Long): Double = ns / 1e6

        /** Python's str.isspace() closely enough for clean(): Unicode whitespace incl. U+3000 and U+00A0. */
        private fun isPySpace(c: Char) = Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085'

        /** clean(t) = " ".join(str(t).strip().split()) */
        fun clean(t: String): String {
            val words = ArrayList<String>()
            val sb = StringBuilder()
            for (c in t) {
                if (isPySpace(c)) {
                    if (sb.isNotEmpty()) { words.add(sb.toString()); sb.setLength(0) }
                } else sb.append(c)
            }
            if (sb.isNotEmpty()) words.add(sb.toString())
            return words.joinToString(" ")
        }

        fun top2(x: FloatArray): Triple<Int, Int, Float> {
            var i1 = 0
            for (i in 1 until x.size) if (x[i] > x[i1]) i1 = i
            var i2 = if (i1 == 0) 1 else 0
            for (i in 0 until x.size) if (i != i1 && x[i] > x[i2]) i2 = i
            return Triple(i1, i2, x[i1] - x[i2])
        }

        fun allowed(idx: Int): Int = if (idx < CB_SIZE) SEM_BEGIN + idx else EOS

        /**
         * build_prompt of the reference host loop: rows [11][P], row 0 the text tokens (and the reference voice's
         * semantic ids), rows 1..10 the reference voice's codes at the same positions, zeros elsewhere.
         */
        fun buildPrompt(constants: PromptConstants, tokenizer: QwenBpeTokenizer, text: String, refText: String?,
                        refCodes: Array<IntArray>?): Array<IntArray> {
            val f = constants.fragments
            val target = clean(text)
            if (refCodes == null) {
                val row0 = f.getValue("sys_open") + f.getValue("sys_noref") + f.getValue("im_end_nl") +
                    f.getValue("user_open") + tokenizer.encode(target) + f.getValue("im_end_nl") + f.getValue("asst_voice")
                return Array(ROWS) { r -> if (r == 0) row0 else IntArray(row0.size) }
            }
            var rt = clean(refText ?: "")
            if (!rt.contains("<|speaker:")) rt = "<|speaker:0|>$rt"
            val prefix = f.getValue("sys_open") + f.getValue("sys_ref") + tokenizer.encode(rt) + f.getValue("speech")
            val suffix = f.getValue("im_end_nl") + f.getValue("user_open") + tokenizer.encode(target) +
                f.getValue("im_end_nl") + f.getValue("asst_voice")
            val l = refCodes[0].size
            val row0 = prefix + IntArray(l) { refCodes[0][it] + SEM_BEGIN } + suffix
            val prompt = Array(ROWS) { IntArray(row0.size) }
            prompt[0] = row0
            for (r in 1 until ROWS) System.arraycopy(refCodes[r - 1], 0, prompt[r], prefix.size, l)
            return prompt
        }
    }

    val tokenizer: QwenBpeTokenizer
    val tokenizerLoadMs: Double
    val createMs = LinkedHashMap<String, Double>()
    val warmupMs = LinkedHashMap<String, Double>()
    var codecBackend = "none"
        private set
    var gpuError: String? = null
        private set
    /** GPU codec output check: correlation with the CPU output on the same codes ([codecCheck]), and GPU(codes) vs GPU(zeros). */
    var gpuCheck: Map<String, Any>? = null
        private set

    /** "pingpong" or "inplace"; read at every slow call, so switch it only between calls. */
    var kvMode: String = kvMode
        set(v) {
            require(v == "pingpong" || v == "inplace") { "kvMode must be pingpong or inplace, got $v" }
            field = v
        }

    init {
        require(kvMode == "pingpong" || kvMode == "inplace") { "kvMode must be pingpong or inplace, got $kvMode" }
        require(codecMode in setOf("auto", "gpu", "cpu", "none")) { "codecMode must be auto, gpu, cpu or none, got $codecMode" }
        require(slowAccel in AR_ACCELS && fastAccel in AR_ACCELS) { "AR accelerators: $AR_ACCELS, got $slowAccel / $fastAccel" }
        require(codecCheck in setOf("reference", "device_cpu", "both")) { "codecCheck must be reference, device_cpu or both, got $codecCheck" }
    }

    /** An AR graph on its accelerator (the GPU arms are measurement runs; the app runs both AR graphs on the CPU). */
    private fun arModel(name: String, accel: String, weightCache: String? = null): CompiledModel = when (accel) {
        "gpu" -> gpuModel(name, null, null, "${name}_gpu_default")
        "gpu32" -> gpuModel(name, CompiledModel.GpuOptions.Precision.FP32, null, "${name}_gpu_fp32")
        else -> cpuModel(name, weightCache)
    }

    // ---------------- model creation ----------------

    private fun file(name: String): File = File(dir, name).also { check(it.isFile) { "model missing: ${it.path}" } }

    private fun cpuModel(name: String, weightCache: String? = null): CompiledModel {
        val o = CompiledModel.Options(Accelerator.CPU)
        o.cpuOptions = CompiledModel.CpuOptions(threads, null, weightCache)
        return CompiledModel.create(file(name).absolutePath, o, null)
    }

    /**
     * GPU (OpenCL) with the CPU accelerator as the fallback for the ops the GPU delegate rejects (the codec's fp16
     * DEQUANTIZE + EMBEDDING_LOOKUP). Options(Accelerator.GPU) alone fails at create ("Failed to compile model").
     */
    private fun gpuModel(
        name: String,
        precision: CompiledModel.GpuOptions.Precision? = null,
        storage: CompiledModel.GpuOptions.BufferStorageType? = null,
        cacheKey: String = name,
    ): CompiledModel {
        val o = CompiledModel.Options(Accelerator.GPU, Accelerator.CPU)
        o.cpuOptions = CompiledModel.CpuOptions(threads, null, null)
        o.gpuOptions = CompiledModel.GpuOptions(precision = precision, bufferStorageType = storage,
            serializationDir = gpuCacheDir.absolutePath, modelCacheKey = cacheKey)
        return CompiledModel.create(file(name).absolutePath, o, null)
    }

    // ---------------- codec probe (diagnosis of the GPU codec output) ----------------

    class ProbeRow(val variant: String, val createMs: Double, val runMs: List<Double>, val corrVsCpu: Double,
                   val corrJaVsZeros: Double, val rms: Double, val inTypes: String, val outTypes: String, val error: String?)

    fun pearson(a: FloatArray, b: FloatArray): Double {
        val n = min(a.size, b.size)
        var ma = 0.0; var mb = 0.0
        for (i in 0 until n) { ma += a[i]; mb += b[i] }
        ma /= n; mb /= n
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in 0 until n) { val x = a[i] - ma; val y = b[i] - mb; sab += x * y; saa += x * x; sbb += y * y }
        return sab / kotlin.math.sqrt(saa * sbb)
    }

    /**
     * Runs the T128 codec on [codes] ([10][n], n <= 128) on the CPU (reference) and on GPU variants, and reports each
     * variant's correlation with the reference and whether its output depends on the input at all (codes vs zeros).
     */
    fun codecProbe(codes: Array<IntArray>): List<ProbeRow> {
        val n = codes[0].size
        val buf = IntArray(NUM_CB * 128)
        for (q in 0 until NUM_CB) System.arraycopy(codes[q], 0, buf, q * 128, n)
        val zeros = IntArray(NUM_CB * 128)
        val rows = ArrayList<ProbeRow>()
        val ref = codec128?.let { c -> if (c.backend == "CPU") c.run(buf) else null } ?: run {
            val m = cpuModel(files.cpu128)
            Codec(files.cpu128, m, 128, "CPU").use { it.run(buf) }
        }
        fun types(m: CompiledModel, input: Boolean): String = runCatching {
            val r = if (input) m.getInputBufferRequirements("codes", "decode") else m.getOutputBufferRequirements("wav", "decode")
            "${r.supportedTypes} size=${r.bufferSize}"
        }.getOrElse { "error: $it" }
        data class V(val name: String, val precision: CompiledModel.GpuOptions.Precision?,
                     val storage: CompiledModel.GpuOptions.BufferStorageType?, val warmZeros: Boolean, val freshInput: Boolean)
        val variants = listOf(
            V("gpu_default_warm_zeros", null, null, true, false),
            V("gpu_default_cold", null, null, false, false),
            V("gpu_default_fresh_input_per_call", null, null, false, true),
            V("gpu_fp32", CompiledModel.GpuOptions.Precision.FP32, null, false, false),
            V("gpu_buffer_storage", null, CompiledModel.GpuOptions.BufferStorageType.BUFFER, false, false),
        )
        for (v in variants) {
            var m: CompiledModel? = null
            try {
                val t0 = nowNs()
                m = gpuModel(files.gpu128, v.precision, v.storage, "${files.gpu128}_${v.name}")
                val createMs = ms(nowNs() - t0)
                val inT = types(m, true)
                val outT = types(m, false)
                val times = ArrayList<Double>()
                fun runOnce(x: IntArray): FloatArray {
                    val inB = m.createInputBuffer("codes", "decode")
                    val outB = m.createOutputBuffer("wav", "decode")
                    inB.writeInt(x)
                    val r0 = nowNs()
                    m.run(mapOf("codes" to inB), mapOf("wav" to outB), "decode")
                    times.add(ms(nowNs() - r0))
                    val o = outB.readFloat()
                    inB.close(); outB.close()
                    return o
                }
                val outJa: FloatArray
                val outZero: FloatArray
                if (v.freshInput) {
                    outJa = runOnce(buf)
                    outZero = runOnce(zeros)
                } else {
                    val inB = m.createInputBuffer("codes", "decode")
                    val outB = m.createOutputBuffer("wav", "decode")
                    fun go(x: IntArray): FloatArray {
                        inB.writeInt(x)
                        val r0 = nowNs()
                        m.run(mapOf("codes" to inB), mapOf("wav" to outB), "decode")
                        times.add(ms(nowNs() - r0))
                        return outB.readFloat()
                    }
                    if (v.warmZeros) go(zeros)
                    outJa = go(buf)
                    outZero = go(zeros)
                    inB.close(); outB.close()
                }
                var sq = 0.0
                for (i in 0 until n * FRAME) sq += outJa[i] * outJa[i]
                val row = ProbeRow(v.name, createMs, times, pearson(outJa.copyOf(n * FRAME), ref.copyOf(n * FRAME)),
                    pearson(outJa, outZero), kotlin.math.sqrt(sq / (n * FRAME)), inT, outT, null)
                rows.add(row)
                Log.i(TAG, "CODEC_PROBE ${v.name} create_ms=$createMs run_ms=$times corr_vs_cpu=${row.corrVsCpu} " +
                    "corr_ja_vs_zeros=${row.corrJaVsZeros} rms=${row.rms} in=$inT out=$outT")
            } catch (e: Exception) {
                Log.e(TAG, "CODEC_PROBE ${v.name} failed: $e", e)
                rows.add(ProbeRow(v.name, 0.0, emptyList(), Double.NaN, Double.NaN, Double.NaN, "", "", e.toString()))
            } finally {
                m?.close()
            }
        }
        return rows
    }

    /**
     * The slow graph's KV cache length: the third dimension of the decode signature's `k_0` input [1, 2, cache, 64],
     * checked against the decode mask [1, 1, 1, cache] and the prefill mask [1, 1, 256, cache].
     */
    private fun kvLength(m: CompiledModel): Int {
        fun dims(name: String, sig: String): List<Int> =
            m.getInputTensorType(name, sig).layout?.dimensions ?: error("slow graph $sig/$name: no shape")
        val k = dims("k_0", "decode")
        check(k.size == 4 && k[0] == 1 && k[1] == 2 && k[2] > 0 && k[3] == 64) {
            "slow graph decode/k_0: shape $k, expected [1, 2, cache, 64]"
        }
        val c = k[2]
        val dm = dims("mask", "decode")
        check(dm == listOf(1, 1, 1, c)) { "slow graph decode/mask: shape $dm, expected [1, 1, 1, $c]" }
        val pm = dims("mask", "prefill_256")
        check(pm == listOf(1, 1, PREFILL_T, c)) { "slow graph prefill_256/mask: shape $pm, expected [1, 1, $PREFILL_T, $c]" }
        return c
    }

    /**
     * prefill_64 if the slow graph has that signature, its input shapes checked against the graph (codes [1, 11, 64],
     * input_pos [64], mask [1, 1, 64, cache]); null for a graph without it (the CompiledModel API lists no signatures:
     * the lookup of a missing one throws).
     */
    private fun shortPrefillOrNull(): ShortPrefill? {
        val sig = "prefill_$PREFILL_SHORT"
        val probe = runCatching { slow.getInputBufferRequirements("mask", sig) }
        if (probe.isFailure) {
            Log.i(TAG, "PREFILL no $sig in ${files.slow}: every chunk runs prefill_$PREFILL_T (${probe.exceptionOrNull()})")
            return null
        }
        fun dims(name: String): List<Int> =
            slow.getInputTensorType(name, sig).layout?.dimensions ?: error("slow graph $sig/$name: no shape")
        val codes = dims("codes")
        check(codes == listOf(1, ROWS, PREFILL_SHORT)) { "slow graph $sig/codes: shape $codes, expected [1, $ROWS, $PREFILL_SHORT]" }
        val pos = dims("input_pos")
        check(pos == listOf(PREFILL_SHORT)) { "slow graph $sig/input_pos: shape $pos, expected [$PREFILL_SHORT]" }
        val mask = dims("mask")
        check(mask == listOf(1, 1, PREFILL_SHORT, cache)) { "slow graph $sig/mask: shape $mask, expected [1, 1, $PREFILL_SHORT, $cache]" }
        return ShortPrefill(PREFILL_SHORT)
    }

    private inline fun <T> timed(key: String, map: MutableMap<String, Double>, block: () -> T): T {
        val t0 = nowNs()
        val r = block()
        map[key] = ms(nowNs() - t0)
        return r
    }

    // ---------------- slow AR ----------------
    private val slow: CompiledModel
    /** The weight cache file the slow graph was created with (null = none). */
    val slowWeightCache: String?
    /** The weight cache's record for the load report (enabled, file, model sha256, hit, prepare ms, bytes, marker, error). */
    val weightCacheStats: Map<String, Any?>
    /** The slow graph's KV cache length (positions), read from the graph at load: 2048 or 1024 for the Realistic exports. */
    val cache: Int
    private val kvA: Map<String, TensorBuffer>
    private val kvB: Map<String, TensorBuffer>
    private var cur: Map<String, TensorBuffer>
    private val decCodes: TensorBuffer
    private val decPos: TensorBuffer
    private val decMaskBuf: TensorBuffer
    private val decLogits: TensorBuffer
    private val decHidden: TensorBuffer
    private val preCodes: TensorBuffer
    private val prePos: TensorBuffer
    private val preMaskBuf: TensorBuffer
    private val preLogits: TensorBuffer
    private val preHidden: TensorBuffer
    private val decMask: FloatArray
    private val preMask: FloatArray
    /** prefill_64 when the slow graph has it (null: every chunk runs prefill_256, right-padded). */
    private val shortPrefill: ShortPrefill?
    /** The prefill signatures this engine calls, short first: [64, 256] or [256]. */
    val prefillBuckets: List<Int> get() = listOfNotNull(shortPrefill?.t, PREFILL_T)
    private val zeroKv: FloatArray
    private val decInA: Map<String, TensorBuffer>   // inputs reading set A
    private val decOutB: Map<String, TensorBuffer>  // outputs writing set B
    private val decInB: Map<String, TensorBuffer>
    private val decOutA: Map<String, TensorBuffer>  // outputs writing set A (also the in-place outputs)

    // ---------------- fast AR ----------------
    private val fast: CompiledModel
    private val fHidden: TensorBuffer
    private val fToken: TensorBuffer
    private val fLogits: TensorBuffer
    private val fIns: Array<Map<String, TensorBuffer>>
    private val fOuts: Array<Map<String, TensorBuffer>>
    private val fOwned = ArrayList<TensorBuffer>()
    private val tokenArr = IntArray(1)

    // ---------------- codec ----------------
    inner class Codec(val fileName: String, val model: CompiledModel, val t: Int, val backend: String) : AutoCloseable {
        val input: TensorBuffer = model.createInputBuffer("codes", "decode")
        val output: TensorBuffer = model.createOutputBuffer("wav", "decode")
        private val ins = mapOf("codes" to input)
        private val outs = mapOf("wav" to output)
        fun run(codes: IntArray): FloatArray {
            input.writeInt(codes)
            model.run(ins, outs, "decode")
            return output.readFloat()
        }
        override fun close() {
            input.close(); output.close(); model.close()
        }
    }

    private var codec128: Codec? = null
    private var codec192: Codec? = null
    private var codec192Failed = false
    private var t192Error: String? = null

    private fun cpuCodec(): Codec {
        val m = timed("codec_fp16_T128_cpu", createMs) { cpuModel(files.cpu128) }
        val c = Codec(files.cpu128, m, 128, "CPU")
        timed("codec_fp16_T128_cpu", warmupMs) { c.run(IntArray(NUM_CB * 128)) }
        codecBackend = "CPU"
        return c
    }

    private fun selectCodec(): Codec? {
        if (codecMode == "none") return null
        if (codecMode == "cpu") return cpuCodec()
        var gpu: Codec? = null
        try {
            val m = timed("codec_fp16_T128_gpu", createMs) { gpuModel(files.gpu128) }
            gpu = Codec(files.gpu128, m, 128, "GPU")
            timed("codec_fp16_T128_gpu", warmupMs) { gpu.run(IntArray(NUM_CB * 128)) }
        } catch (e: Exception) {
            gpu?.close()
            gpuError = e.toString()
            Log.e(TAG, "GPU codec failed, falling back to the CPU fp16 codec: $e", e)
            return cpuCodec()
        }
        val g = gpu!!
        val ref = checkReference
        if (codecMode == "gpu" || ref == null) {
            codecBackend = "GPU"
            return g
        }
        val n = min(ref.codes[0].size, 128)
        val buf = IntArray(NUM_CB * 128)
        for (q in 0 until NUM_CB) System.arraycopy(ref.codes[q], 0, buf, q * 128, n)
        val t0 = nowNs()
        val outG = g.run(buf).copyOf(n * FRAME)
        val outGz = g.run(IntArray(NUM_CB * 128)).copyOf(n * FRAME)
        val t1 = nowNs()
        val dep = pearson(outG, outGz)
        val refWav = if (codecCheck == "device_cpu") null else ref.wav.copyOf(n * FRAME)
        val check = linkedMapOf<String, Any>("form" to codecCheck, "frames" to n)
        // "reference" and "both": the GPU output against the bundled CPU reference; no CPU decoder at load.
        val corrRef = refWav?.let { pearson(outG, it) }
        // "device_cpu" and "both": the same file decoded on this phone's CPU, as the first published app did.
        var cpu: Codec? = null
        var corrCpu: Double? = null
        if (codecCheck != "reference") {
            val c = cpuCodec()
            cpu = c
            val t2 = nowNs()
            val outC = c.run(buf).copyOf(n * FRAME)
            val t3 = nowNs()
            corrCpu = pearson(outG, outC)
            check["corr_gpu_vs_cpu_fp16"] = corrCpu
            check["cpu_file"] = files.cpu128
            check["cpu_create_warmup_ms"] = ms(t2 - t1)
            check["cpu_run_ms"] = ms(t3 - t2)
            if (codecCheck == "both") {
                val rw = refWav!!
                var maxAbs = 0.0
                var same = 0
                for (i in 0 until n * FRAME) {
                    maxAbs = max(maxAbs, kotlin.math.abs(outC[i] - rw[i]).toDouble())
                    if (outC[i].toRawBits() == rw[i].toRawBits()) same++
                }
                check["corr_cpu_vs_reference"] = pearson(outC, rw)
                check["max_abs_cpu_vs_reference"] = maxAbs
                check["cpu_samples_equal_to_reference"] = same
                check["samples"] = n * FRAME
                check["corr_diff_reference_vs_cpu"] = kotlin.math.abs(corrRef!! - corrCpu)
            }
        }
        val corr = if (codecCheck == "device_cpu") corrCpu!! else corrRef!!
        val pass = corr >= 0.99
        if (corrRef != null) {
            check["corr_gpu_vs_reference"] = corrRef
            check["reference"] = ref.source
        }
        check["corr_gpu_codes_vs_gpu_zeros"] = dep
        check["pass"] = pass
        check["threshold"] = 0.99
        check["gpu_file"] = files.gpu128
        check["gpu_runs_ms"] = ms(t1 - t0)
        gpuCheck = check
        Log.i(TAG, "GPU_CODEC_CHECK $gpuCheck")
        return if (pass) {
            if (cpu != null) {
                cpu.close()
                createMs.remove("codec_fp16_T128_cpu"); warmupMs.remove("codec_fp16_T128_cpu")
            }
            codecBackend = "GPU"
            g
        } else {
            g.close()
            gpuError = "output check failed: corr vs CPU fp16 (${if (corrRef != null) "bundled reference" else "this phone"}) " +
                "= $corr, corr vs zero input = $dep"
            codecBackend = "CPU"
            cpu ?: cpuCodec()
        }
    }

    /** The T192 GPU decoder created and warmed with one call at load, after the T128 GPU decoder passed its check. */
    private fun warmCodec192() {
        val info = LinkedHashMap<String, Any>()
        val c = codec192OrNull(info) ?: return
        try {
            timed("codec_fp16_T192_gpu", warmupMs) { c.run(IntArray(NUM_CB * 192)) }
        } catch (e: Exception) {
            Log.e(TAG, "codec T192 (GPU) failed its first run: $e", e)
            c.close()
            codec192 = null
            codec192Failed = true
            t192Error = e.toString()
        }
    }

    init {
        val t0 = nowNs()
        tokenizer = QwenBpeTokenizer(file("tokenizer.json"))
        tokenizerLoadMs = ms(nowNs() - t0)
        Log.i(TAG, "TOKENIZER vocab=${tokenizer.vocabSize} merges=${tokenizer.mergeCount} added=${tokenizer.addedCount} load_ms=$tokenizerLoadMs")

        // The weight cache: named by the slow file's sha256 (the hash is part of the load time); a create that fails
        // with it is repeated without it.
        val wcDir: File? = weightCacheDir
        val wc = if (wcDir != null && slowAccel == "cpu") {
            runCatching { timed("slow_ar_weight_cache_prepare", createMs) { WeightCache.prepare(wcDir, file(files.slow), BuildConfig.LITERT_VERSION) } }
                .onFailure { Log.e(TAG, "weight cache: prepare failed, the slow graph goes without it: $it", it) }.getOrNull()
        } else null
        var wcError: String? = null
        slow = timed("slow_ar", createMs) {
            if (wc == null) arModel(files.slow, slowAccel)
            else try {
                arModel(files.slow, slowAccel, wc.file.path)
            } catch (e: Exception) {
                Log.e(TAG, "weight cache: the slow graph failed with ${wc.file.name}, created without it: $e", e)
                wcError = e.toString()
                WeightCache.discard(wc)
                arModel(files.slow, slowAccel)
            }
        }
        slowWeightCache = if (wc != null && wcError == null) wc.file.path else null
        val wcBytesAfterCreate = wc?.file?.let { if (it.isFile) it.length() else 0L }
        cache = kvLength(slow)
        decMask = FloatArray(cache)
        preMask = FloatArray(PREFILL_T * cache)
        zeroKv = FloatArray(2 * cache * 64)
        fun kvSet(): Map<String, TensorBuffer> = KV_NAMES.associateWith {
            if (kvOwner == "input") slow.createInputBuffer(it, "decode") else slow.createOutputBuffer(it, "decode")
        }
        kvA = kvSet()
        kvB = kvSet()
        cur = kvB
        decCodes = slow.createInputBuffer("codes", "decode")
        decPos = slow.createInputBuffer("input_pos", "decode")
        decMaskBuf = slow.createInputBuffer("mask", "decode")
        decLogits = slow.createOutputBuffer("logits", "decode")
        decHidden = slow.createOutputBuffer("hidden", "decode")
        preCodes = slow.createInputBuffer("codes", "prefill_256")
        prePos = slow.createInputBuffer("input_pos", "prefill_256")
        preMaskBuf = slow.createInputBuffer("mask", "prefill_256")
        preLogits = slow.createOutputBuffer("logits", "prefill_256")
        preHidden = slow.createOutputBuffer("hidden", "prefill_256")
        shortPrefill = shortPrefillOrNull()
        fun decIn(set: Map<String, TensorBuffer>) = HashMap<String, TensorBuffer>(64).apply {
            put("codes", decCodes); put("input_pos", decPos); put("mask", decMaskBuf); putAll(set)
        }
        fun decOut(set: Map<String, TensorBuffer>) = HashMap<String, TensorBuffer>(64).apply {
            put("logits", decLogits); put("hidden", decHidden); putAll(set)
        }
        decInA = decIn(kvA); decOutB = decOut(kvB); decInB = decIn(kvB); decOutA = decOut(kvA)

        fast = timed("fast_ar", createMs) { arModel(files.fast, fastAccel) }
        fHidden = fast.createInputBuffer("hidden", "step")
        fToken = fast.createInputBuffer("token", "step")
        fLogits = fast.createOutputBuffer("logits", "step")
        val use1 = fast.createInputBuffer("use_hidden", "step").also { it.writeFloat(floatArrayOf(1f)) }
        val use0 = fast.createInputBuffer("use_hidden", "step").also { it.writeFloat(floatArrayOf(0f)) }
        // fast_masks[p] = 0 where j <= p else -1e9, shape [1,1,1,10]; pos p; both constant per call index.
        val posBufs = Array(NUM_CB) { p -> fast.createInputBuffer("pos", "step").also { it.writeInt(intArrayOf(p)) } }
        val maskBufs = Array(NUM_CB) { p ->
            fast.createInputBuffer("mask", "step").also { b -> b.writeFloat(FloatArray(NUM_CB) { j -> if (j <= p) 0f else NEG }) }
        }
        val zero = FloatArray(FAST_KV_FLOATS)
        val kZ = fast.createInputBuffer("k_all", "step").also { it.writeFloat(zero) }
        val vZ = fast.createInputBuffer("v_all", "step").also { it.writeFloat(zero) }
        val kA = fast.createOutputBuffer("k_all", "step")
        val vA = fast.createOutputBuffer("v_all", "step")
        val kB = fast.createOutputBuffer("k_all", "step")
        val vB = fast.createOutputBuffer("v_all", "step")
        fOwned.addAll(listOf(use1, use0, kZ, vZ, kA, vA, kB, vB) + posBufs + maskBufs)
        // Call 0 reads the zero set and writes A; call p >= 1 reads A (p odd) or B (p even) and writes the other.
        fIns = Array(NUM_CB) { p ->
            val (k, v) = when {
                p == 0 -> kZ to vZ
                p % 2 == 1 -> kA to vA
                else -> kB to vB
            }
            mapOf("hidden" to fHidden, "token" to fToken, "use_hidden" to (if (p == 0) use1 else use0),
                "pos" to posBufs[p], "mask" to maskBufs[p], "k_all" to k, "v_all" to v)
        }
        fOuts = Array(NUM_CB) { p ->
            val (k, v) = if (p % 2 == 0) kA to vA else kB to vB
            mapOf("logits" to fLogits, "k_all" to k, "v_all" to v)
        }

        // Codec: the fp16 T128 graph on the GPU when it both builds and passes an output check against the bundled CPU
        // reference on the same codes; otherwise (or with codecMode "cpu") the CPU. A GPU graph can build and
        // run without an exception while returning the same wav for every input (the published Preview decoder on the
        // Galaxy S26), so an exception alone is not a usable fallback signal.
        codec128 = selectCodec()
        // The T192 GPU decoder too, so that the first sentence over 128 frames does not create it.
        if (t192AtLoad && codecBackend == "GPU") warmCodec192()

        // First invokes of the AR graphs (one decode, one fast step), timed apart from generation.
        timed("slow_decode", warmupMs) {
            zeroCache()
            decode(IntArray(ROWS), 0)
        }
        timed("fast_step", warmupMs) {
            fHidden.writeFloat(FloatArray(DIM))
            fToken.writeInt(intArrayOf(0))
            fast.run(fIns[0], fOuts[0], "step")
        }
        // The marker after the slow graph's first run with the cache (a cache without it is written again next load).
        weightCacheStats = if (wc == null) linkedMapOf<String, Any?>("enabled" to false) else {
            val marker = if (wcError == null) runCatching { WeightCache.finish(wc) }.getOrDefault(false) else false
            linkedMapOf<String, Any?>("enabled" to true, "dir" to wc.file.parent, "file" to wc.file.name, "model" to wc.model.name,
                "model_sha256" to wc.sha256, "hit" to (wc.hit && wcError == null), "prepare_ms" to wc.prepareMs,
                "deleted" to wc.deleted, "bytes_after_create" to wcBytesAfterCreate,
                "bytes_after_warmup" to (if (wc.file.isFile) wc.file.length() else 0L), "marker" to marker, "error" to wcError)
        }
        Log.i(TAG, "ENGINE files=$files slow=$slowAccel fast=$fastAccel kv_cache=$cache prefill=$prefillBuckets create_ms=$createMs " +
            "warmup_ms=$warmupMs codec_backend=$codecBackend kv_mode=${this.kvMode} gpu_error=$gpuError")
        Log.i(TAG, "WEIGHT_CACHE ${weightCacheStats.entries.joinToString(" ") { "${it.key}=${it.value}" }}")
    }

    // ---------------- prompt (build_prompt) ----------------

    fun buildPrompt(text: String, refText: String?, refCodes: Array<IntArray>?): Array<IntArray> =
        buildPrompt(constants, tokenizer, text, refText, refCodes)

    // ---------------- slow AR (_prefill / _decode) ----------------

    private fun zeroCache() {
        if (kvMode == "inplace") {
            for (n in KV_NAMES) kvA.getValue(n).writeFloat(zeroKv)
            cur = kvA
        } else {
            for (n in KV_NAMES) kvB.getValue(n).writeFloat(zeroKv)
            cur = kvB
        }
    }

    class Step(val logits: FloatArray, val hidden: FloatArray)

    /**
     * prefill_[t] (t = 64) for a prompt's last chunk of at most t rows: one call in place of a right-padded prefill_256
     * call, the same chunk code with its own buffers. The KV cache it writes equals prefill_256's on the valid rows
     * (dynamic int8 quantizes each row on its own; bit-identical on the Mac CPU, followup ROUND2.md).
     */
    private inner class ShortPrefill(val t: Int) : AutoCloseable {
        private val sig = "prefill_$t"
        private val codesBuf: TensorBuffer = slow.createInputBuffer("codes", sig)
        private val posBuf: TensorBuffer = slow.createInputBuffer("input_pos", sig)
        private val maskBuf: TensorBuffer = slow.createInputBuffer("mask", sig)
        private val logits: TensorBuffer = slow.createOutputBuffer("logits", sig)
        private val hidden: TensorBuffer = slow.createOutputBuffer("hidden", sig)
        private val codes = IntArray(ROWS * t)
        private val pos = IntArray(t)
        private val mask = FloatArray(t * cache)

        /** Prefills prompt positions [s, s + n), n <= t, right-padded to t rows. */
        fun run(prompt: Array<IntArray>, s: Int, n: Int) {
            codes.fill(0)
            for (r in 0 until ROWS) System.arraycopy(prompt[r], s, codes, r * t, n)
            for (i in 0 until t) pos[i] = s + i
            mask.fill(NEG)
            for (i in 0 until t) {
                val allowed = min(s + i + 1, cache)
                java.util.Arrays.fill(mask, i * cache, i * cache + allowed, 0f)
            }
            codesBuf.writeInt(codes)
            posBuf.writeInt(pos)
            maskBuf.writeFloat(mask)
            val next = if (kvMode == "inplace") cur else if (cur === kvA) kvB else kvA
            val ins = HashMap<String, TensorBuffer>(64).apply {
                put("codes", codesBuf); put("input_pos", posBuf); put("mask", maskBuf); putAll(cur)
            }
            val outs = HashMap<String, TensorBuffer>(64).apply {
                put("logits", logits); put("hidden", hidden); putAll(next)
            }
            slow.run(ins, outs, sig)
            cur = next
        }

        override fun close() {
            codesBuf.close(); posBuf.close(); maskBuf.close(); logits.close(); hidden.close()
        }
    }

    /**
     * Prefill prompt[:-1] in right-padded chunks of 256, the last chunk in one prefill_64 call instead when the graph has
     * it and the chunk has at most 64 rows; the last prompt token goes through decode.
     */
    fun prefill(prompt: Array<IntArray>, stats: MutableMap<String, Any>? = null): Step {
        val p = prompt[0].size
        val t0 = nowNs()
        zeroCache()
        val t1 = nowNs()
        val codes = IntArray(ROWS * PREFILL_T)
        val pos = IntArray(PREFILL_T)
        var s = 0
        var chunks = 0
        var shortCalls = 0
        val inplace = kvMode == "inplace"
        while (s < p - 1) {
            val rem = p - 1 - s
            val sp = shortPrefill
            if (sp != null && rem <= sp.t) {
                sp.run(prompt, s, rem)
                s += rem
                chunks++
                shortCalls++
                continue
            }
            val n = min(rem, PREFILL_T)
            codes.fill(0)
            for (r in 0 until ROWS) System.arraycopy(prompt[r], s, codes, r * PREFILL_T, n)
            for (i in 0 until PREFILL_T) pos[i] = s + i
            preMask.fill(NEG)
            for (i in 0 until PREFILL_T) {
                val allowed = min(s + i + 1, cache)
                java.util.Arrays.fill(preMask, i * cache, i * cache + allowed, 0f)
            }
            preCodes.writeInt(codes)
            prePos.writeInt(pos)
            preMaskBuf.writeFloat(preMask)
            val next = if (inplace) cur else if (cur === kvA) kvB else kvA
            val ins = HashMap<String, TensorBuffer>(64).apply {
                put("codes", preCodes); put("input_pos", prePos); put("mask", preMaskBuf); putAll(cur)
            }
            val outs = HashMap<String, TensorBuffer>(64).apply {
                put("logits", preLogits); put("hidden", preHidden); putAll(next)
            }
            slow.run(ins, outs, "prefill_256")
            cur = next
            s += n
            chunks++
        }
        val t2 = nowNs()
        val step = decode(IntArray(ROWS) { prompt[it][p - 1] }, p - 1)
        val t3 = nowNs()
        stats?.apply {
            put("kv_zero_ms", ms(t1 - t0)); put("prefill_chunks_ms", ms(t2 - t1)); put("prefill_chunks", chunks)
            put("prefill_calls", (List(chunks - shortCalls) { PREFILL_T } + List(shortCalls) { PREFILL_SHORT }).joinToString("+"))
            put("prefill_last_token_decode_ms", ms(t3 - t2)); put("kv_mode", kvMode)
        }
        return step
    }

    /** One slow decode step: pingpong reads the current KV set and writes the other one; inplace reads and writes set A. */
    fun decode(column: IntArray, pos: Int, runNs: LongArray? = null): Step {
        require(pos in 0 until cache) { "decode position $pos is outside the slow graph's KV cache of $cache positions" }
        decCodes.writeInt(column)
        decPos.writeInt(intArrayOf(pos))
        decMask.fill(NEG)
        java.util.Arrays.fill(decMask, 0, pos + 1, 0f)
        decMaskBuf.writeFloat(decMask)
        if (kvMode == "inplace") {
            check(cur === kvA) { "in-place decode needs the cache in set A (a generate starts from zeroCache)" }
            val r0 = nowNs()
            slow.run(decInA, decOutA, "decode")
            if (runNs != null) runNs[0] += nowNs() - r0
        } else {
            val fromA = cur === kvA
            val r0 = nowNs()
            slow.run(if (fromA) decInA else decInB, if (fromA) decOutB else decOutA, "decode")
            if (runNs != null) runNs[0] += nowNs() - r0
            cur = if (fromA) kvB else kvA
        }
        return Step(decLogits.readFloat(), decHidden.readFloat())
    }

    // ---------------- fast AR (_fast_frame) ----------------

    /** 10 step calls: pos 0 = hidden (use_hidden 1, token 0), pos p = previous code; codes[0] = semantic index. */
    fun fastFrame(hidden: FloatArray, semantic: Int, pick: (FloatArray, Int) -> Int, runNs: LongArray? = null): IntArray {
        fHidden.writeFloat(hidden)
        tokenArr[0] = 0
        fToken.writeInt(tokenArr)
        var r0 = nowNs()
        fast.run(fIns[0], fOuts[0], "step")
        if (runNs != null) runNs[0] += nowNs() - r0
        var c = (semantic - SEM_BEGIN).coerceIn(0, CB_SIZE - 1)
        val codes = IntArray(NUM_CB)
        codes[0] = c
        for (p in 1 until NUM_CB) {
            checkpoint?.invoke()
            tokenArr[0] = c
            fToken.writeInt(tokenArr)
            r0 = nowNs()
            fast.run(fIns[p], fOuts[p], "step")
            if (runNs != null) runNs[0] += nowNs() - r0
            c = pick(fLogits.readFloat(), p)
            codes[p] = c
        }
        return codes
    }

    // ---------------- generation (generate_codes) ----------------

    /** Forced sequence for the teacher-forced parity check: the semantic ids + codes [10][N]. */
    class Teacher(val semantic: IntArray, val codes: Array<IntArray>) {
        val frames: Int get() = semantic.size
    }

    class Gen(
        val prompt: Array<IntArray>,
        val semantic: IntArray,
        val frames: List<IntArray>,
        val stoppedBy: String,
        val stats: MutableMap<String, Any>,
        val decodeMs: DoubleArray,      // per frame: the slow decode after the frame
        val fastMs: DoubleArray,        // per frame: the 10 fast steps incl. sampling
        val semMargin: FloatArray?,
        val semTop2: Array<IntArray>?,
        val codeMargin: Array<FloatArray>?,
        val ownSemantic: IntArray?,     // teacher mode: the app's own greedy pick at each position
        val ownCodes: Array<IntArray>?,
        val semLogitsDump: ArrayList<FloatArray>?,
        val fastLogitsDump: ArrayList<FloatArray>?,
        /** dumpHidden: the slow hidden state each frame's fast AR read (frame i = the decode before it). */
        val hiddenDump: ArrayList<FloatArray>? = null,
    )

    /**
     * [cancel] is read once per frame: when it is set the loop stops before the next frame and the result carries
     * stoppedBy = "cancelled" (the next call starts from a zeroed KV cache, so nothing has to be reset).
     * [fastDumpFrames] limits the fast logits dump to the first frames (9 x 4096 floats per frame).
     * [maxNew] caps the frames (1..[MAX_NEW]); a generation that reaches it carries stoppedBy = "limit".
     */
    var checkpoint: (() -> Unit)? = null

    fun generate(
        text: String, refText: String?, refCodes: Array<IntArray>?, sampler: Sampler,
        record: Boolean = false, teacher: Teacher? = null, dumpLogits: Boolean = false,
        cancel: AtomicBoolean? = null,
        dumpHidden: Boolean = false, fastDumpFrames: Int = Int.MAX_VALUE,
        maxNew: Int = MAX_NEW,
        onFrame: ((Int, Double) -> Unit)? = null,
    ): Gen {
        require(maxNew in 1..MAX_NEW) { "maxNew must be in 1..$MAX_NEW, got $maxNew" }
        val stats = LinkedHashMap<String, Any>()
        val tStart = nowNs()
        val prompt = buildPrompt(text, refText, refCodes)
        val p = prompt[0].size
        // The last decode of a full generation writes position p + MAX_NEW - 1. The app's own texts stay far below the
        // KV 1024 limit (at most 49 text tokens and a 10 s voice: 308 positions + 512 frames = 820).
        require(p + MAX_NEW <= cache) {
            "prompt length $p + $MAX_NEW new frames is more than the slow graph's KV cache of $cache positions"
        }
        val tPrompt = nowNs()
        checkpoint?.invoke()
        var step = prefill(prompt, stats)
        checkpoint?.invoke()
        val tPrefill = nowNs()
        stats["prompt_len"] = p
        stats["prompt_build_ms"] = ms(tPrompt - tStart)
        stats["prefill_ms"] = ms(tPrefill - tPrompt)

        val previous = ArrayDeque<Int>()
        val frames = ArrayList<IntArray>()
        val semantic = ArrayList<Int>()
        val decodeMs = ArrayList<Double>()
        val fastMs = ArrayList<Double>()
        val semMargin = if (record) ArrayList<Float>() else null
        val semTop2 = if (record) ArrayList<IntArray>() else null
        val codeMargin = if (record) ArrayList<FloatArray>() else null
        val ownSem = if (teacher != null) ArrayList<Int>() else null
        val ownCodes = if (teacher != null) ArrayList<IntArray>() else null
        val semDump = if (dumpLogits) ArrayList<FloatArray>() else null
        val fastDump = if (dumpLogits) ArrayList<FloatArray>() else null
        val hidDump = if (dumpHidden) ArrayList<FloatArray>() else null
        var stoppedBy = "limit"
        val decRun = LongArray(1)
        val fastRun = LongArray(1)
        var samplerNs = 0L
        val limit = min(maxNew, cache - p)
        stats["frame_cap"] = limit
        for (i in 0 until limit) {
            checkpoint?.invoke()
            if (cancel?.get() == true) {
                stoppedBy = "cancelled"
                break
            }
            if (record) {
                val (i1, i2, m) = top2(step.logits)
                semMargin!!.add(m); semTop2!!.add(intArrayOf(allowed(i1), allowed(i2)))
            }
            semDump?.add(step.logits)
            val s0 = nowNs()
            var sem = sampler.semantic(step.logits, previous)
            samplerNs += nowNs() - s0
            if (teacher != null) {
                ownSem!!.add(sem)
                sem = if (i < teacher.frames) teacher.semantic[i] else EOS
            }
            if (sem == EOS) {
                stoppedBy = "eos"
                break
            }
            hidDump?.add(step.hidden)
            val dumpThis = fastDump != null && i < fastDumpFrames
            val margins = if (record) FloatArray(NUM_CB - 1) else null
            val own = if (teacher != null) IntArray(NUM_CB) else null
            val f0 = nowNs()
            val cb = fastFrame(step.hidden, sem, { logits, pIdx ->
                if (margins != null) margins[pIdx - 1] = top2(logits).third
                if (dumpThis) fastDump!!.add(logits)
                val q0 = nowNs()
                val c = sampler.code(logits)
                samplerNs += nowNs() - q0
                if (own != null) {
                    own[pIdx] = c
                    teacher!!.codes[pIdx][i]
                } else c
            }, fastRun)
            val f1 = nowNs()
            if (own != null) { own[0] = cb[0]; ownCodes!!.add(own) }
            if (margins != null) codeMargin!!.add(margins)
            frames.add(cb)
            semantic.add(sem)
            previous.addLast(sem)
            if (previous.size > 10) previous.removeFirst()
            onFrame?.invoke(frames.size, ms(nowNs() - tStart))
            val column = IntArray(ROWS)
            column[0] = sem
            System.arraycopy(cb, 0, column, 1, NUM_CB)
            val d0 = nowNs()
            step = decode(column, p + i, decRun)
            val d1 = nowNs()
            fastMs.add(ms(f1 - f0))
            decodeMs.add(ms(d1 - d0))
        }
        val tEnd = nowNs()
        stats["frames"] = frames.size
        stats["loop_ms"] = ms(tEnd - tPrefill)
        stats["generate_ms"] = ms(tEnd - tStart)
        stats["slow_decode_run_only_ms_total"] = ms(decRun[0])
        stats["fast_run_only_ms_total"] = ms(fastRun[0])
        stats["sampler_ms_total"] = ms(samplerNs)
        stats["kv_mode"] = kvMode
        stats["kv_cache"] = cache
        return Gen(prompt, semantic.toIntArray(), frames, stoppedBy, stats, decodeMs.toDoubleArray(), fastMs.toDoubleArray(),
            semMargin?.toFloatArray(), semTop2?.toTypedArray(), codeMargin?.toTypedArray(),
            ownSem?.toIntArray(), ownCodes?.toTypedArray(), semDump, fastDump, hidDump)
    }

    // ---------------- codec (decode_audio) ----------------

    class CodecCall(val t: Int, val start: Int, val n: Int, val ms: Double)

    private fun codec192OrNull(calls: MutableMap<String, Any>): Codec? {
        if (codec192 != null || codec192Failed) {
            t192Error?.let { calls["t192_error"] = it }
            return codec192
        }
        val name = if (codecBackend == "GPU") files.gpu192 else files.cpu192
        if (!File(dir, name).isFile) {
            t192Error = "missing $name"
            calls["t192_error"] = "missing $name"
            codec192Failed = true
            return null
        }
        codec192 = try {
            if (codecBackend == "GPU") {
                val m = timed("codec_fp16_T192_gpu", createMs) { gpuModel(name) }
                Codec(name, m, 192, "GPU")
            } else {
                val m = timed("codec_fp16_T192_cpu", createMs) { cpuModel(name) }
                Codec(name, m, 192, "CPU")
            }
        } catch (e: Exception) {
            Log.e(TAG, "codec T192 ($codecBackend) failed: $e", e)
            t192Error = e.toString()
            calls["t192_error"] = e.toString()
            codec192Failed = true
            null
        }
        return codec192
    }

    /**
     * Causal codec: T128 if N <= 128, T192 if N <= 192, else T192 windows with 128 frames of left context, each window
     * right-padded (the reference host loop's decode_audio). Without a T192 graph longer inputs use T128 windows with 64
     * frames of context. [cancel] is read before every window; when it is set the decode stops with a
     * [CancellationException].
     */
    fun decodeAudio(
        frames: List<IntArray>, info: MutableMap<String, Any>, calls: MutableList<CodecCall>,
        cancel: AtomicBoolean? = null,
    ): FloatArray {
        val n = frames.size
        if (n == 0) return FloatArray(0)
        val c128 = checkNotNull(codec128) { "no codec (codec mode $codecMode)" }
        val c = if (n <= 128) c128 else (codec192OrNull(info) ?: c128)
        val t = c.t
        val ctx = if (t > CODEC_CTX) CODEC_CTX else t / 2
        info["codec_file"] = c.fileName
        info["codec_backend"] = c.backend
        info["codec_T"] = t
        info["codec_ctx"] = ctx
        val wav = FloatArray(n * FRAME)
        var done = 0
        val buf = IntArray(NUM_CB * t)
        while (done < n) {
            checkpoint?.invoke()
            if (cancel?.get() == true) throw CancellationException("cancelled during the codec decode")
            val s = if (done == 0) 0 else max(0, done - ctx)
            val k = min(t, n - s)
            buf.fill(0)
            for (q in 0 until NUM_CB) for (j in 0 until k) buf[q * t + j] = frames[s + j][q]
            val t0 = nowNs()
            val out = c.run(buf)
            calls.add(CodecCall(t, s, k, ms(nowNs() - t0)))
            System.arraycopy(out, (done - s) * FRAME, wav, done * FRAME, (s + k - done) * FRAME)
            done = s + k
        }
        return wav
    }

    // ---------------- registration (encode_reference) ----------------

    class Registration(val codes: Array<IntArray>, val createMs: Double, val encodeMs: Double, val closeMs: Double,
                       val samples: Int)

    /** Codec encoder created for this call and closed right after (2 GB footprint on the S26). */
    fun encodeReference(wav44k: FloatArray): Registration {
        val t0 = nowNs()
        val m = cpuModel(files.encoder)
        val t1 = nowNs()
        val inB = m.createInputBuffer("audio", "encode")
        val outB = m.createOutputBuffer("codes", "encode")
        val nIn = min(wav44k.size, ENC_SAMPLES)
        val a = FloatArray(ENC_SAMPLES)
        System.arraycopy(wav44k, 0, a, 0, nIn)
        inB.writeFloat(a)
        m.run(mapOf("audio" to inB), mapOf("codes" to outB), "encode")
        val raw = outB.readInt()
        val t2 = nowNs()
        inB.close(); outB.close(); m.close()
        val t3 = nowNs()
        val keep = ceil(nIn / FRAME.toDouble()).toInt().coerceAtMost(ENC_FRAMES)
        val codes = Array(NUM_CB) { q -> IntArray(keep) { raw[q * ENC_FRAMES + it] } }
        return Registration(codes, ms(t1 - t0), ms(t2 - t1), ms(t3 - t2), nIn)
    }

    // ---------------- kv_probe ----------------

    class ProbeResult(val decodeTotal: DoubleArray, val decodeRun: DoubleArray, val fastTotal: DoubleArray, val fastRun: DoubleArray)

    /** [steps] dummy slow decode steps (current kvMode, pos 0..steps-1) and [steps] fast step calls (p = i % 10). */
    fun kvProbe(steps: Int): ProbeResult {
        val (dt, dr) = slowProbe(steps)
        val ft = DoubleArray(steps)
        val fr = DoubleArray(steps)
        fHidden.writeFloat(FloatArray(DIM) { ((it % 7) - 3) * 0.1f })
        for (i in 0 until steps) {
            val p = i % NUM_CB
            val t0 = nowNs()
            tokenArr[0] = (i * 13) % CB_SIZE
            fToken.writeInt(tokenArr)
            val r0 = nowNs()
            fast.run(fIns[p], fOuts[p], "step")
            val r1 = nowNs()
            fLogits.readFloat()
            ft[i] = ms(nowNs() - t0)
            fr[i] = ms(r1 - r0)
        }
        return ProbeResult(dt, dr, ft, fr)
    }

    /**
     * [steps] slow decode steps from a zeroed cache in the current [kvMode] (pos 0..steps-1, a fixed column pattern):
     * per step the whole call (write inputs + run + read logits and hidden) and the run alone, in ms.
     */
    fun slowProbe(steps: Int): Pair<DoubleArray, DoubleArray> {
        require(steps <= cache) { "slow probe of $steps steps is longer than the KV cache of $cache positions" }
        zeroCache()
        val dt = DoubleArray(steps)
        val dr = DoubleArray(steps)
        val col = IntArray(ROWS)
        for (i in 0 until steps) {
            col[0] = SEM_BEGIN + (i * 37) % CB_SIZE
            for (r in 1 until ROWS) col[r] = (i * 11 + r) % CB_SIZE
            val run = LongArray(1)
            val t0 = nowNs()
            decode(col, i, run)
            dt[i] = ms(nowNs() - t0)
            dr[i] = ms(run[0])
        }
        return dt to dr
    }

    private var closed = false

    /** Releases every buffer and graph once; a second call does nothing (a TensorBuffer must not be closed twice). */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        codec128?.close(); codec192?.close()
        codec128 = null; codec192 = null
        for (b in kvA.values) b.close()
        for (b in kvB.values) b.close()
        for (b in listOf(decCodes, decPos, decMaskBuf, decLogits, decHidden, preCodes, prePos, preMaskBuf, preLogits, preHidden)) b.close()
        shortPrefill?.close()
        slow.close()
        for (b in fOwned) b.close()
        fHidden.close(); fToken.close(); fLogits.close()
        fast.close()
    }
}

/**
 * The vendor sampler (audio8_tts_litert.py _processed/_sample/_sample_semantic): top-k 50 / top-p 0.9 /
 * temperature 0.7, RAS re-draw at temperature 1.0 / top-p 0.9 when the semantic pick repeats one of the last 10,
 * exponential-race sampling argmax(p / -ln u). The RNG is SplittableRandom(seed); with [greedy] every u is 0.5, which
 * reduces each draw to argmax (the Python twin greedy_ref.py does the same).
 */
class Sampler(val greedy: Boolean, val seed: Long, val temperature: Double = 0.7, val topP: Double = 0.9, val topK: Int = 50) {
    private val rng = SplittableRandom(seed)
    private val idx = IntArray(64)
    private val kept = IntArray(64)
    private val vals = DoubleArray(64)

    private fun u(): Double = if (greedy) 0.5 else 1.0 - rng.nextDouble()   // (0, 1]

    /**
     * _sample(_processed(scores, k, p, t), u): keep the top-k by score (stable order), drop those whose cumulative
     * softmax exceeds top_p (the first always stays), scale by 1/t, draw argmax(p_i / -ln u_i) over the kept ones in
     * ascending index order (numpy argmax takes the first maximum).
     */
    fun draw(scores: FloatArray, k: Int, pTop: Double, t: Double): Int {
        val kk = min(k, scores.size)
        // top-k indices, descending score, ties -> lower index first (np.argsort(-scores, kind="stable"))
        var cnt = 0
        for (i in scores.indices) {
            val v = scores[i]
            if (cnt == kk && v <= scores[idx[cnt - 1]]) continue
            var j = if (cnt < kk) cnt++ else kk - 1
            while (j > 0 && scores[idx[j - 1]] < v) { idx[j] = idx[j - 1]; j-- }
            idx[j] = i
        }
        val mx = scores[idx[0]].toDouble()
        var sum = 0.0
        for (v in scores) sum += exp(v - mx)
        var cum = 0.0
        var nk = 0
        for (j in 0 until cnt) {
            cum += exp(scores[idx[j]] - mx) / sum
            if (j == 0 || cum <= pTop) kept[nk++] = idx[j]
        }
        java.util.Arrays.sort(kept, 0, nk)
        val tt = max(t, 1e-5)
        var m2 = Double.NEGATIVE_INFINITY
        for (j in 0 until nk) { vals[j] = scores[kept[j]] / tt; if (vals[j] > m2) m2 = vals[j] }
        var s2 = 0.0
        for (j in 0 until nk) { vals[j] = exp(vals[j] - m2); s2 += vals[j] }
        var best = -1
        var bestV = Double.NEGATIVE_INFINITY
        for (j in 0 until nk) {
            val r = (vals[j] / s2) / (-ln(u()))
            if (r > bestV) { bestV = r; best = kept[j] }
        }
        return best
    }

    /** _sample_semantic over the 4097 slow logits (semantic range + EOS). */
    fun semantic(logits: FloatArray, previous: Collection<Int>): Int {
        val normal = draw(logits, topK, topP, temperature)
        val high = draw(logits, topK, 0.9, 1.0)
        val normalId = Audio8Engine.allowed(normal)
        val highId = Audio8Engine.allowed(high)
        return if (previous.isNotEmpty() && normalId in previous &&
            normalId >= Audio8Engine.SEM_BEGIN && normalId <= Audio8Engine.SEM_END) highId else normalId
    }

    /** One fast codebook draw over 4096 logits. */
    fun code(logits: FloatArray): Int = draw(logits, topK, topP, temperature)
}
