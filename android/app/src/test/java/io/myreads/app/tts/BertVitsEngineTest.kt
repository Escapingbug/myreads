package io.myreads.app.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Runs the actual production Kotlin frontend/JNI/MNN model on the host. */
class BertVitsEngineTest {
    @Test fun producesBilingualPcmAndCanRestartAfterCancellation() {
        val model=System.getProperty("bertModelRoot")
        assumeTrue("Set bertModelRoot and ttsNativePath for real-model host verification",model!=null)
        val output=File(System.getProperty("bertTestOutput",System.getProperty("java.io.tmpdir")),"bertvits-test")
        output.mkdirs()
        val sentences=listOf("傍晚的雨终于停了。","林舟沿着石板路往旧书店走，屋檐还在滴水，街边的灯一盏接着一盏亮起来。",
            "他轻声说，Good evening，外面的雨已经停了。","这本书的名字叫 The Secret Garden，我想再读一遍。")
        val frontend=BertFrontend(File(model!!))
        for(text in sentences){
            val input=frontend.prepare(text)
            assertEquals(input.phones.size,input.tones.size)
            assertEquals(input.phones.size,input.languages.size)
            assertTrue(input.phones.size in 3..512)
            if(text.contains("Good"))assertTrue(input.ids.isEmpty())
            else if(!text.contains("Garden"))assertEquals(input.phones.size,input.word2ph.sum())
        }
        BertVitsEngine(File(model)).use { engine ->
            for((i,text) in sentences.withIndex()) {
                val file=File(output,"sample-$i.wav")
                val result=engine.synthesize(text,file,SynthesisCheckpoint {})
                val data=file.readBytes();val wav=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(22050,wav.getInt(24));assertEquals(16,wav.getShort(34).toInt())
                assertTrue(result.durationMs in 500..30000)
                assertEquals(data.size-44,wav.getInt(40))
                assertTrue((44 until data.size step 2).any {wav.getShort(it).toInt()!=0})
                println("BERT_RESULT $i: ${result.durationMs}ms audio in ${result.elapsedMs}ms")
            }
            val file=File(output,"cancelled.wav");val ticks=AtomicInteger()
            try {
                engine.synthesize(sentences[1],file,SynthesisCheckpoint {
                    if(ticks.incrementAndGet()==5)throw InterruptedException("test cancellation")
                })
                fail("Cancellation must escape JNI")
            }catch(expected: InterruptedException){assertFalse(file.exists())}
            val after=engine.synthesize(sentences[0],file,SynthesisCheckpoint {})
            assertTrue(after.durationMs>0)
        }
    }
}
