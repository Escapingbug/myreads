// Adapted from the pinned Audio8 Android MIT example; see assets/tts/audio8/ANDROID-MIT-LICENSE.txt.
package io.myreads.app.tts.audio8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The weight cache is chosen by the model's bytes; stale and unfinished caches are deleted before the create. */
class WeightCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun model(name: String, bytes: ByteArray): File = File(tmp.root, name).apply { writeBytes(bytes) }

    /** What the runtime does at the first create with a cache path: it writes the packed weights there. */
    private fun runtimeWrites(plan: WeightCache.Plan) = plan.file.writeBytes(ByteArray(64) { 7 })

    @Test
    fun theNameComesFromTheBytes() {
        val m = model("slow_ar_int8.tflite", "abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", WeightCache.sha256(m))
        val p = WeightCache.prepare(File(tmp.root, "xnnpack"), m, "2.2.0")
        assertEquals("slow_ar_int8-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad-litert2.2.0.xnnpack",
            p.file.name)
        assertFalse(p.hit)
    }

    @Test
    fun theSecondLoadReadsTheCacheOnlyAfterTheMarker() {
        val dir = File(tmp.root, "xnnpack")
        val m = model("slow_ar_int8.tflite", ByteArray(1000) { it.toByte() })
        val p1 = WeightCache.prepare(dir, m, "2.2.0")
        assertFalse(p1.hit)
        runtimeWrites(p1)
        assertTrue(WeightCache.finish(p1))
        val p2 = WeightCache.prepare(dir, m, "2.2.0")
        assertTrue(p2.hit)
        assertEquals(p1.file, p2.file)
        assertTrue(p2.deleted.isEmpty())
    }

    @Test
    fun anUnfinishedCacheIsDeleted() {
        val dir = File(tmp.root, "xnnpack")
        val m = model("slow_ar_int8.tflite", ByteArray(1000) { 1 })
        val p1 = WeightCache.prepare(dir, m, "2.2.0")
        runtimeWrites(p1)                       // the load stopped before the marker
        val p2 = WeightCache.prepare(dir, m, "2.2.0")
        assertFalse(p2.hit)
        assertFalse(p2.file.exists())
        assertEquals(listOf(p1.file.name), p2.deleted)
    }

    @Test
    fun otherBytesUnderTheSameNameGetANewCacheAndTheOldOneGoes() {
        val dir = File(tmp.root, "xnnpack")
        val m = model("slow_ar_int8.tflite", ByteArray(1000) { 1 })
        val p1 = WeightCache.prepare(dir, m, "2.2.0")
        runtimeWrites(p1)
        WeightCache.finish(p1)
        m.writeBytes(ByteArray(1000) { 2 })     // the same file name, other bytes
        val p2 = WeightCache.prepare(dir, m, "2.2.0")
        assertNotEquals(p1.file.name, p2.file.name)
        assertFalse(p2.hit)
        assertFalse(p1.file.exists())
        assertFalse(p1.marker.exists())
        assertEquals(setOf(p1.file.name, p1.marker.name), p2.deleted.toSet())
    }

    @Test
    fun anotherRuntimeVersionGetsANewCache() {
        val dir = File(tmp.root, "xnnpack")
        val m = model("slow_ar_int8.tflite", ByteArray(1000) { 3 })
        val p1 = WeightCache.prepare(dir, m, "2.2.0")
        runtimeWrites(p1)
        WeightCache.finish(p1)
        val p2 = WeightCache.prepare(dir, m, "2.3.0")
        assertFalse(p2.hit)
        assertFalse(p1.file.exists())
    }

    @Test
    fun anotherModelsCacheStays() {
        val dir = File(tmp.root, "xnnpack")
        val q = model("slow_ar_int8_q1k.tflite", ByteArray(1000) { 4 })
        val pq = WeightCache.prepare(dir, q, "2.2.0")
        runtimeWrites(pq)
        WeightCache.finish(pq)
        val m = model("slow_ar_int8.tflite", ByteArray(1000) { 5 })
        val pm = WeightCache.prepare(dir, m, "2.2.0")
        assertTrue(pq.file.exists())
        assertTrue(pq.marker.exists())
        assertTrue(pm.deleted.isEmpty())
        assertTrue(WeightCache.prepare(dir, q, "2.2.0").hit)
    }

    @Test
    fun noMarkerWhenTheRuntimeWroteNothing() {
        val dir = File(tmp.root, "xnnpack")
        val m = model("slow_ar_int8.tflite", ByteArray(1000) { 6 })
        val p = WeightCache.prepare(dir, m, "2.2.0")
        assertFalse(WeightCache.finish(p))     // a runtime that could not use the path writes no file
        assertFalse(p.marker.exists())
    }
}
