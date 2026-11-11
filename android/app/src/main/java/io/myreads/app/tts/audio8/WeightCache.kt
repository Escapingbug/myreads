// Adapted from litert-community/Audio8-TTS-Realistic-0.6b @ dcf477032785f2dda4af08c89a0de8509e511c6f (Android example, MIT).
package io.myreads.app.tts.audio8

import io.myreads.app.BuildConfig

import java.io.File
import java.security.MessageDigest

/**
 * The XNNPACK weight cache file of a CPU graph. Without a cache, XNNPACK packs a graph's weights again for every
 * signature when the graph is created (the slow AR's three signatures each hold the same 361 MB of FULLY_CONNECTED
 * weights); with a cache file the packed weights are written once, at the first create, and every later create maps
 * them from the file, one copy for all signatures.
 *
 * The cache is chosen by the model's bytes, never by its file name alone:
 * `<dir>/<model file name without .tflite>-<sha256 of the model file>-litert<runtime version>.xnnpack`
 * (the packed layout may change with the runtime). A marker `<cache>.done` is written after the graph has been
 * created and run with the cache; a cache without its marker (a load stopped while writing it) is deleted and written
 * again. The caches of the same model file name with other bytes or another runtime are deleted before the create.
 */
object WeightCache {
    /** The folder under the app's cache directory (Android may clear it when storage runs low: the next load writes it again). */
    const val DIR = "xnnpack"
    const val SUFFIX = ".xnnpack"
    const val DONE = ".done"

    class Plan(
        val model: File,
        val sha256: String,
        /** The path handed to the runtime. */
        val file: File,
        /** The cache and its marker were there before the create: the runtime reads it instead of packing. */
        val hit: Boolean,
        /** Files deleted by [prepare]: other caches of this model file name, or an unfinished one. */
        val deleted: List<String>,
        /** sha256 of the model file plus the clean-up, in ms. */
        val prepareMs: Double,
    ) {
        val marker: File get() = File(file.path + DONE)
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        f.inputStream().use { s ->
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun stem(model: File): String = model.name.removeSuffix(".tflite")

    fun name(model: File, sha256: String, runtime: String): String =
        "${stem(model)}-$sha256-litert${runtime.replace(Regex("[^A-Za-z0-9._]"), "_")}$SUFFIX"

    /** The caches (and markers) of [model]'s file name: `<stem>-<64 hex>-litert<any>.xnnpack[.done]`. */
    private fun sameModel(model: File): Regex =
        Regex("^" + Regex.escape(stem(model) + "-") + "[0-9a-f]{64}-litert.*" + Regex.escape(SUFFIX) + "(" + Regex.escape(DONE) + ")?$")

    /** Hashes [model], picks its cache in [dir], deletes the other caches of the same file name and an unfinished one. */
    fun prepare(dir: File, model: File, runtime: String): Plan {
        val t0 = System.nanoTime()
        dir.mkdirs()
        val sha = sha256(model)
        val target = File(dir, name(model, sha, runtime))
        val marker = File(target.path + DONE)
        val deleted = ArrayList<String>()
        val re = sameModel(model)
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (re.matches(f.name) && f.name != target.name && f.name != marker.name && f.delete()) deleted.add(f.name)
        }
        val complete = target.isFile && target.length() > 0 && marker.isFile
        if (!complete) {
            if (target.exists() && target.delete()) deleted.add(target.name)
            if (marker.exists() && marker.delete()) deleted.add(marker.name)
        }
        return Plan(model, sha, target, complete, deleted, (System.nanoTime() - t0) / 1e6)
    }

    /**
     * After the create and the first run with the cache: writes the marker when the runtime wrote a cache file
     * (a runtime that cannot use the path runs without a cache and writes nothing). Returns whether the marker is there.
     */
    fun finish(plan: Plan): Boolean {
        if (plan.hit) return plan.marker.isFile
        if (!plan.file.isFile || plan.file.length() == 0L) return false
        plan.marker.writeText("model ${plan.model.name} sha256 ${plan.sha256} cache_bytes ${plan.file.length()}\n")
        return true
    }

    /** A cache the runtime refused: the file and its marker deleted. */
    fun discard(plan: Plan) {
        plan.file.delete()
        plan.marker.delete()
    }
}
