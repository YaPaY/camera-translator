package com.example.cameratranslator

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * OCR metin düzeltmesi, çeviri motorundan bağımsız: OPUS, Google Translate, Google Cloud ve ML Kit
 * aynı düzeltilmiş metni alır ("|am" -> "I am", "Im" -> "I'm", "sorne" -> "some").
 *
 * Sözlük uygulamanın içinde gelir (OPUS modeli indirilmeden de çalışır):
 *  - assets/ocr_vocab.tsv: OPUS-MT sözlüğündeki ~24 bin tam kelime ve sıklık puanları
 *  - assets/en_words.txt: OPUS sözlüğünde olmayan gerçek kelimeler (FrequencyWords)
 *  - assets/ocr_bigrams.bin + ocr_unigrams.tsv: altyazılardaki kelime çifti sıklıkları (bağlam düzeltmesi)
 */
object OcrTextFixer {
    private const val TAG = "OcrTextFixer"

    @Volatile private var corrector: OcrWordCorrector? = null
    @Volatile private var bigrams: BigramModel? = null
    @Volatile private var lowerWords: Set<String> = emptySet()
    @Volatile private var casedWords: Set<String> = emptySet()

    /** Uygulama açılışında bir kez çağrılır; sözlük arka planda yüklenir (~50 ms). */
    fun init(context: Context) {
        if (corrector != null) return
        val assets = context.applicationContext.assets
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val scores = HashMap<String, Float>()
                val cased = HashSet<String>()
                assets.open("ocr_vocab.tsv").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val tab = line.indexOf('\t')
                        if (tab <= 0) continue
                        val word = line.substring(0, tab)
                        if (word[0].isUpperCase()) cased += word
                        else scores[word] = line.substring(tab + 1).toFloatOrNull() ?: continue
                    }
                }
                val realWords = assets.open("en_words.txt").bufferedReader().useLines { lines ->
                    lines.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
                }
                lowerWords = scores.keys
                casedWords = cased
                corrector = OcrWordCorrector(scores, cased, realWords)
                Log.d(TAG, "Loaded ${scores.size} words, ${cased.size} names, ${realWords.size} extra words")
                // Kelime çifti tablosu (~9 MB) ayrı yüklenir; gelmezse düzeltici bağlamsız çalışmaya devam eder
                bigrams = BigramModel.load(assets)
                Log.d(TAG, "Context model loaded")
            } catch (e: Exception) {
                Log.e(TAG, "OCR vocabulary could not be loaded", e)
            }
        }
    }

    /** "|" -> I ve kelime düzeltici. Sözlük henüz yüklenmediyse yalnızca "|" düzeltilir. */
    fun fix(text: String): String {
        val piped = OcrWordCorrector.fixPipes(text)
        val fixer = corrector ?: return piped
        val corrected = fixer.correct(piped)
        // Komşu kelimelere bakan ikinci tur (ör. "to very the" -> "to verify the")
        val model = bigrams ?: return corrected
        return fixer.correctInContext(piped, corrected, model)
    }

    /** Kelime sözlükte tek parça olarak var mı (stabilizer'ın "temiz okuma" kontrolü için). */
    fun isKnownWord(word: String): Boolean = word in lowerWords || word in casedWords
}
