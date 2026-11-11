package io.myreads.app.tts

import com.example.textpreprocess.zh.BV2Preprocess
import com.example.textpreprocess.zh.zhSymbolsMap
import com.example.textpreprocess.zh.normal_symbols
import com.example.textpreprocess.en.CmuLexiconTxt
import com.example.textpreprocess.en.EnglishG2P
import java.io.File
import org.json.JSONObject

/** CPU text front-end for the pinned 22k bilingual Android model. */
class BertFrontend(root: File) {
    private val prep = File(root, "preprocess")
    private val dictionary = File(prep, "zh/dict")
    private val chinese = BV2Preprocess(
        File(dictionary,"jieba.dict.utf8").path, File(dictionary,"hmm_model.utf8").path,
        File(dictionary,"user.dict.utf8").path, File(dictionary,"idf.utf8").path,
        File(dictionary,"stop_words.utf8").path, File(prep,"zh/opencpop-strict.txt").path)
    private val vocab = JSONObject(File(root,"bert/zh/tokenizer.json").readText())
        .getJSONObject("model").getJSONObject("vocab")
    // No asynchronous dictionary initialization: a first mixed utterance must
    // never synthesize using an empty lexicon.
    private val english by lazy {
        EnglishG2P(normal_symbols.toSet(), lexicon=CmuLexiconTxt.fromLocalFile(File(prep,"en/cmudict.rep").path))
    }
    data class Input(val phones: IntArray, val tones: IntArray, val languages: IntArray,
                     val ids: IntArray, val word2ph: IntArray, val attention: IntArray)

    fun prepare(text: String): Input {
        // Normalize whitespace without converting spaces within English into
        // commas; the upstream multilingual normalizer otherwise does that.
        val normalized = chinese.normalizeText(text.replace(Regex("\\s+"), " ").trim())
        require(normalized.any { it.isLetterOrDigit() }) { "没有可朗读的文字" }
        if (!normalized.any { it in 'a'..'z' || it in 'A'..'Z' }) {
            val result=chinese.preprocessWithNormalizedText(normalized)
            val ids=intArrayOf(101)+normalized.map { vocab.optInt(it.toString(),100) }.toIntArray()+intArrayOf(102)
            require(ids.size==result.word2ph.size) { "中文音素与文字未对齐" }
            val words=result.word2ph.map { it*2 }.toIntArray();words[0]++
            return Input(blank(result.phones.map(::phone)),blank(result.tones),IntArray(result.phones.size*2+1),
                ids,words,IntArray(ids.size) {1})
        }
        // Preserve spaces and punctuation for English, with punctuation attached
        // to the preceding language segment, matching the Android mix path.
        val segments=mutableListOf<Pair<Boolean,StringBuilder>>()
        for (c in text.trim()) {
            val en=if(c in 'a'..'z'||c in 'A'..'Z') true else if(c in '\u4e00'..'\u9fff'||c.isDigit()) false
                else segments.lastOrNull()?.first ?: false
            if(segments.isEmpty()||segments.last().first!=en) segments.add(en to StringBuilder())
            segments.last().second.append(c)
        }
        val phones=mutableListOf<Int>();val tones=mutableListOf<Int>();val languages=mutableListOf<Int>()
        for ((en,part) in segments) {
            if(en) {
                val r=english.g2p(english.textNormalize(part.toString()))
                phones.addAll(r.phones.map(::phone));tones.addAll(r.tones.map {it+8});languages.addAll(List(r.phones.size){2})
            } else {
                val n=chinese.normalizeText(part.toString())
                if(n.isEmpty())continue
                val r=chinese.preprocessWithNormalizedText(n)
                phones.addAll(r.phones.map(::phone));tones.addAll(r.tones);languages.addAll(List(r.phones.size){0})
            }
        }
        // The 22k model's bilingual mode intentionally takes zero BERT features.
        return Input(blank(phones),blank(tones),blank(languages),intArrayOf(),intArrayOf(),intArrayOf())
    }
    private fun phone(value: String): Int = zhSymbolsMap[value] ?: error("不支持的音素：$value")
    private fun blank(values: List<Int>): IntArray = IntArray(values.size*2+1).also { result ->
        values.forEachIndexed { index,value -> result[index*2+1]=value }
    }
}
