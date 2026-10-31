package com.example.cameratranslator

import android.content.res.AssetManager
import java.nio.ByteBuffer
import kotlin.math.ln

/**
 * Film/dizi altyazılarından (OpenSubtitles, 12 milyon satır) sayılmış İngilizce kelime çifti sıklıkları.
 * Kelime düzeltici, bozuk bir kelimenin adaylarından hangisinin komşularıyla birlikte gerçekten
 * kullanıldığına bakar: "to very the" değil "to verify the", "my ife" değil "my life".
 *
 * assets/ocr_bigrams.bin: [adet:int][toplam kelime:long][FNV-1a 64 "w1 w2" özetleri, sıralı][sayılar]
 * assets/ocr_unigrams.tsv: kelime \t sayı
 */
class BigramModel private constructor(
    private val keys: LongArray,
    private val counts: IntArray,
    private val unigrams: Map<String, Int>,
    private val total: Long
) {
    fun bigram(a: String, b: String): Int {
        val index = keys.binarySearch(hash("$a $b"))
        return if (index >= 0) counts[index] else 0
    }

    private fun unigram(word: String): Int = unigrams[word] ?: 0

    /** P(b | a), tek kelime sıklığıyla yumuşatılmış. */
    private fun probability(a: String, b: String): Double {
        val prior = (unigram(b) + 1.0) / total
        return (bigram(a, b) + SMOOTHING * prior) / (unigram(a) + SMOOTHING)
    }

    /** prev → word → next zincirinin log olasılığı, yaygınlık için küçük bir ek puanla. */
    fun score(prev: String, word: String, next: String): Double =
        ln(probability(prev, word)) + ln(probability(word, next))

    fun commonness(word: String): Double = ln(maxOf(1, unigram(word)).toDouble())

    companion object {
        private const val SMOOTHING = 50.0

        fun load(assets: AssetManager): BigramModel {
            val buffer = ByteBuffer.wrap(assets.open("ocr_bigrams.bin").use { it.readBytes() })
            val size = buffer.int
            val total = buffer.long
            val keys = LongArray(size)
            buffer.asLongBuffer().get(keys)
            buffer.position(buffer.position() + size * 8)
            val counts = IntArray(size)
            buffer.asIntBuffer().get(counts)
            val unigrams = HashMap<String, Int>(70_000)
            assets.open("ocr_unigrams.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab > 0) unigrams[line.substring(0, tab)] = line.substring(tab + 1).toIntOrNull() ?: continue
                }
            }
            return BigramModel(keys, counts, unigrams, total)
        }

        /** FNV-1a 64 (Python tarafındaki özetle birebir aynı). */
        private fun hash(text: String): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (b in text.toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toLong() and 0xff)
                h *= 0x100000001b3L
            }
            return h
        }
    }
}
