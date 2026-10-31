package com.example.cameratranslator

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer

/**
 * OPUS çeviri motoru: Helsinki-NLP OPUS-MT (opus-mt-tc-big-en-tr), ONNX int8.
 * Tamamen telefonda çalışır; internet, API anahtarı veya kota gerekmez.
 *
 * Model dosyaları OfflineModelDownloader ile uygulamanın özel alanına indirilir
 * (filesDir/opus_en_tr/): encoder_model.onnx, decoder_model_merged.onnx,
 * source_pieces.tsv, vocab.json
 *
 * Sadece İngilizce -> Türkçe. Ters yön için CompositeTranslationEngine ML Kit'e düşer.
 */
class OpusTranslationEngine(context: Context) : ITranslationEngine {

    override var isTrToEn: Boolean = false
    override var mode: TranslationMode = TranslationMode.OFFLINE
    private val _status = MutableStateFlow<TranslationStatus>(TranslationStatus.Idle)
    override val status: StateFlow<TranslationStatus> = _status
    private val _isBusy = MutableStateFlow(false)
    override val isBusy: StateFlow<Boolean> = _isBusy

    val modelDir: File? = OfflineModelDownloader.modelDirectory(context)

    private val loadMutex = Mutex()
    private val runMutex = Mutex()
    private var env: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var tokenizer: MarianTokenizer? = null

    val isReady: Boolean get() = _status.value == TranslationStatus.Ready

    override suspend fun prepare() {
        withContext(Dispatchers.IO) { loadModel() }
    }

    private suspend fun loadModel() {
        loadMutex.withLock {
            if (encoder != null) return@withLock
            val dir = modelDir
            val missing = REQUIRED_FILES.filter { dir == null || !File(dir, it).isFile }
            if (missing.isNotEmpty()) {
                Log.w(TAG, "Model files missing in $dir: $missing")
                _status.value = TranslationStatus.Error("OPUS modeli indirilmedi (Ayarlar)")
                return@withLock
            }
            _status.value = TranslationStatus.DownloadingModel
            try {
                val startedAt = SystemClock.elapsedRealtime()
                val environment = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(NUM_THREADS)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                tokenizer = MarianTokenizer.load(File(dir, "source_pieces.tsv"), File(dir, "vocab.json"))
                encoder = environment.createSession(File(dir, "encoder_model.onnx").path, options)
                decoder = environment.createSession(File(dir, "decoder_model_merged.onnx").path, options)
                env = environment
                _status.value = TranslationStatus.Ready
                Log.d(TAG, "Loaded in ${SystemClock.elapsedRealtime() - startedAt} ms")
            } catch (e: Exception) {
                Log.e(TAG, "Model load failed", e)
                releaseSessions()
                _status.value = TranslationStatus.Error("OPUS modeli yüklenemedi")
            }
        }
    }

    override fun isKnownWord(word: String): Boolean = tokenizer?.isWholeWord(word) == true

    override suspend fun translate(text: String): String = withContext(Dispatchers.Default) {
        if (text.isBlank() || isTrToEn || !isReady) return@withContext ""
        runMutex.withLock {
            _isBusy.value = true
            val startedAt = SystemClock.elapsedRealtime()
            try {
                // OCR'ın eklediği şapkalı harfler (fúcker, Vēspucci) modelde <unk> oluyordu;
                // ardından OPUS'un bilmediği argo sade İngilizceye çevrilir (dog -> dude vb.)
                val cleaned = tokenizer?.cleanSource(OcrWordCorrector.fixPipes(text)).orEmpty()
                // OCR'ın bozduğu kelimeleri düzelt (Alriaht -> Alright, ofyou -> of you); tüm modlarda ortak
                val corrected = OcrTextFixer.fix(cleaned)
                if (corrected != cleaned) Log.d(TAG, "OCR fix: \"$cleaned\" -> \"$corrected\"")
                val source = SlangNormalizer.normalize(corrected)
                OcrDebugDumper.logPipeline("OPUS'a giden", source)
                // Cümle cümle çevirmek, modelin çok cümleli altyazılarda ilk cümleyi
                // atlamasını önlüyor (testte "Siktir, kanka." kayboluyordu).
                val result = SENTENCE_SPLIT.split(source)
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { translateSentence(it) }
                    .replace(LANGUAGE_NOTE, "")
                    .trim()
                Log.d(TAG, "Translated in ${SystemClock.elapsedRealtime() - startedAt} ms")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Translate error", e)
                ""
            } finally {
                _isBusy.value = false
            }
        }
    }

    private fun translateSentence(sentence: String): String {
        // Tek başına "Hey!" OPUS'ta "Merhaba!" oluyordu (oyunda hep seslenme)
        if (STANDALONE_HEY.matches(sentence)) return sentence
        return if (OcrTuning.opusCarefulEnabled) beamSentence(sentence) else greedySentence(sentence)
    }

    /**
     * Dikkatli çeviri (beam search): her adımda en olası [BEAM_SIZE] yol birlikte ilerletilir, sonunda
     * uzunluğa göre normalize edilmiş en yüksek puanlı cümle seçilir. Helsinki modeli bu şekilde
     * (num_beams=4) değerlendirmiş. 100 oyun altyazısında ~16'sı belirgin şekilde daha iyi, ~5'i daha
     * kötü çıktı; süre ~3,5 katı. Işınlar decoder'a tek grup (batch) olarak verilir.
     */
    private fun beamSentence(sentence: String): String {
        val environment = env ?: return ""
        val enc = encoder ?: return ""
        val dec = decoder ?: return ""
        val tok = tokenizer ?: return ""
        val k = BEAM_SIZE

        val inputIds = tok.encode(sentence)
        val length = inputIds.size
        val outputNames = dec.outputNames.toList()
        val closeables = mutableListOf<AutoCloseable>()
        try {
            val idsTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(inputIds), longArrayOf(1, length.toLong()))
            val oneMask = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(length) { 1L }), longArrayOf(1, length.toLong()))
            closeables += idsTensor
            closeables += oneMask
            val encoderResult = enc.run(mapOf("input_ids" to idsTensor, "attention_mask" to oneMask))
            closeables += encoderResult
            val hiddenOne = (encoderResult.get(0) as OnnxTensor)
            val hiddenShape = hiddenOne.info.shape
            val hiddenData = hiddenOne.floatBuffer
            val perBeam = hiddenData.remaining()
            val repeated = FloatBuffer.allocate(perBeam * k)
            repeat(k) { repeated.put(hiddenData.duplicate()) }
            repeated.rewind()
            val hidden = OnnxTensor.createTensor(environment, repeated, longArrayOf(k.toLong(), hiddenShape[1], hiddenShape[2]))
            val mask = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(length * k) { 1L }), longArrayOf(k.toLong(), length.toLong()))
            closeables += hidden
            closeables += mask

            val past = HashMap<String, OnnxTensor>()
            for (layer in 0 until DECODER_LAYERS) for (kind in KV_KINDS) for (part in KV_PARTS) {
                val empty = OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), longArrayOf(k.toLong(), HEADS.toLong(), 0, HEAD_DIM.toLong()))
                closeables += empty
                past["past_key_values.$layer.$kind.$part"] = empty
            }

            var tokens = IntArray(k) { PAD_ID }
            var scores = DoubleArray(k) { if (it == 0) 0.0 else Double.NEGATIVE_INFINITY }
            var sequences = List(k) { emptyList<Int>() }
            val finished = ArrayList<Pair<Double, List<Int>>>()
            var gathered = emptyList<OnnxTensor>()
            val maxTokens = minOf(MAX_NEW_TOKENS, length * 2 + 8)

            for (step in 0 until maxTokens) {
                val tokenTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(k) { tokens[it].toLong() }), longArrayOf(k.toLong(), 1))
                val cacheTensor = OnnxTensor.createTensor(environment, booleanArrayOf(step > 0))
                val feeds = HashMap<String, OnnxTensor>(past)
                feeds["encoder_attention_mask"] = mask
                feeds["input_ids"] = tokenTensor
                feeds["encoder_hidden_states"] = hidden
                feeds["use_cache_branch"] = cacheTensor
                val result = dec.run(feeds)
                tokenTensor.close()
                cacheTensor.close()
                gathered.forEach { it.close() } // önceki adımın sıralanmış decoder K/V'si kullanıldı

                // Her ışının en iyi 2k devamı (log-softmax puanıyla)
                val logits = (result.get(0) as OnnxTensor).floatBuffer
                val vocab = logits.remaining() / k
                val candidates = ArrayList<Triple<Int, Int, Double>>(k * 2 * k)
                for (b in 0 until k) {
                    if (scores[b] == Double.NEGATIVE_INFINITY) continue
                    val base = b * vocab
                    var max = Float.NEGATIVE_INFINITY
                    for (i in 0 until vocab) if (i != PAD_ID && i != UNK_ID) max = maxOf(max, logits.get(base + i))
                    var sum = 0.0
                    for (i in 0 until vocab) if (i != PAD_ID && i != UNK_ID) sum += Math.exp((logits.get(base + i) - max).toDouble())
                    val logZ = max + Math.log(sum)
                    val top = topIndices(logits, base, vocab, 2 * k)
                    for (t in top) candidates += Triple(b, t, scores[b] + logits.get(base + t) - logZ)
                }
                candidates.sortByDescending { it.third }

                val next = ArrayList<Triple<Int, Int, Double>>(k)
                for (c in candidates) {
                    if (c.second == EOS_ID) {
                        val seq = sequences[c.first]
                        finished += c.third / maxOf(1, seq.size) to seq
                    } else {
                        next += c
                    }
                    if (next.size == k) break
                }
                if (finished.size >= k || next.isEmpty()) {
                    if (step == 0) closeables += result else result.close()
                    break
                }
                while (next.size < k) next += Triple(next[0].first, next[0].second, Double.NEGATIVE_INFINITY)

                // Decoder K/V'yi seçilen ışınların sırasına göre yeniden diz; encoder K/V ilk adımdan kalır
                val order = IntArray(k) { next[it].first }
                val newGathered = ArrayList<OnnxTensor>()
                for (i in 1 until outputNames.size) {
                    val name = outputNames[i]
                    val key = name.replace("present", "past_key_values")
                    val tensor = result.get(i) as OnnxTensor
                    if (".decoder." in name) {
                        val reordered = gatherBeams(environment, tensor, order)
                        newGathered += reordered
                        past[key] = reordered
                    } else if (step == 0) {
                        past[key] = tensor
                    }
                }
                gathered = newGathered
                if (step == 0) closeables += result else result.close()

                sequences = List(k) { sequences[next[it].first] + next[it].second }
                tokens = IntArray(k) { next[it].second }
                scores = DoubleArray(k) { next[it].third }
            }
            gathered.forEach { runCatching { it.close() } }

            if (finished.isEmpty()) {
                for (b in 0 until k) if (scores[b] > Double.NEGATIVE_INFINITY) finished += scores[b] / maxOf(1, sequences[b].size) to sequences[b]
            }
            val best = finished.maxByOrNull { it.first }?.second ?: return ""
            val output = best.toMutableList()
            trimRepetition(output)
            return tok.decode(output)
        } finally {
            closeables.asReversed().forEach { runCatching { it.close() } }
        }
    }

    /** Bir ışının logitlerinden en büyük [count] token (pad/unk hariç). */
    private fun topIndices(logits: FloatBuffer, base: Int, vocab: Int, count: Int): IntArray {
        val best = IntArray(count) { -1 }
        val values = FloatArray(count) { Float.NEGATIVE_INFINITY }
        for (i in 0 until vocab) {
            if (i == PAD_ID || i == UNK_ID) continue
            val v = logits.get(base + i)
            if (v <= values[count - 1]) continue
            var j = count - 1
            while (j > 0 && values[j - 1] < v) {
                values[j] = values[j - 1]
                best[j] = best[j - 1]
                j--
            }
            values[j] = v
            best[j] = i
        }
        return best.filter { it >= 0 }.toIntArray()
    }

    /** [k, heads, seq, dim] tensörünün ışın satırlarını [order] sırasına göre kopyalar. */
    private fun gatherBeams(environment: OrtEnvironment, tensor: OnnxTensor, order: IntArray): OnnxTensor {
        val shape = tensor.info.shape
        val source = tensor.floatBuffer
        val block = source.remaining() / order.size
        val target = FloatBuffer.allocate(source.remaining())
        for (b in order) {
            val slice = source.duplicate()
            slice.position(b * block)
            slice.limit(b * block + block)
            target.put(slice)
        }
        target.rewind()
        return OnnxTensor.createTensor(environment, target, shape)
    }

    /** Açgözlü (greedy) çözümleme, KV önbellekli birleşik decoder ile. */
    private fun greedySentence(sentence: String): String {
        val environment = env ?: return ""
        val enc = encoder ?: return ""
        val dec = decoder ?: return ""
        val tok = tokenizer ?: return ""

        val inputIds = tok.encode(sentence)
        val inputShape = longArrayOf(1, inputIds.size.toLong())
        val outputNames = dec.outputNames.toList()

        val closeables = mutableListOf<AutoCloseable>()
        try {
            val idsTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(inputIds), inputShape)
            val maskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(inputIds.size) { 1L }), inputShape)
            closeables += idsTensor
            closeables += maskTensor

            val encoderResult = enc.run(mapOf("input_ids" to idsTensor, "attention_mask" to maskTensor))
            closeables += encoderResult
            val hidden = encoderResult.get(0) as OnnxTensor

            // İlk adımda geçmiş yok: sıfır uzunluklu tensörler (use_cache_branch=false iken kullanılmaz)
            val past = HashMap<String, OnnxTensor>()
            for (layer in 0 until DECODER_LAYERS) for (kind in KV_KINDS) for (part in KV_PARTS) {
                val empty = OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), longArrayOf(1, HEADS.toLong(), 0, HEAD_DIM.toLong()))
                closeables += empty
                past["past_key_values.$layer.$kind.$part"] = empty
            }

            val output = ArrayList<Int>()
            var token = PAD_ID
            var previous: OrtSession.Result? = null
            // Çeviri kaynaktan çok daha uzun olamaz: döngüye giren çeviri 128 adım (≈2 sn) sürüyordu
            val maxTokens = minOf(MAX_NEW_TOKENS, inputIds.size * 2 + 8)
            for (step in 0 until maxTokens) {
                val tokenTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(token.toLong())), longArrayOf(1, 1))
                val cacheTensor = OnnxTensor.createTensor(environment, booleanArrayOf(step > 0))
                val feeds = HashMap<String, OnnxTensor>(past)
                feeds["encoder_attention_mask"] = maskTensor
                feeds["input_ids"] = tokenTensor
                feeds["encoder_hidden_states"] = hidden
                feeds["use_cache_branch"] = cacheTensor

                val result = dec.run(feeds)
                tokenTensor.close()
                cacheTensor.close()

                token = argmax((result.get(0) as OnnxTensor).floatBuffer)

                // Önbellek kolu encoder K/V'yi geri vermiyor (sahte şekil döner):
                // encoder K/V ilk adımdan kalır, her adımda sadece decoder K/V güncellenir.
                for (i in 1 until outputNames.size) {
                    val name = outputNames[i]
                    if (step == 0 || ".decoder." in name) {
                        past[name.replace("present", "past_key_values")] = result.get(i) as OnnxTensor
                    }
                }
                if (step == 0) {
                    closeables += result // encoder K/V bu sonuçta, cümle bitene kadar açık kalmalı
                } else {
                    previous?.close()
                    previous = result
                }

                if (token == EOS_ID) break
                output += token
                if (trimRepetition(output)) break
            }
            previous?.close()
            return tok.decode(output)
        } finally {
            closeables.asReversed().forEach { runCatching { it.close() } }
        }
    }

    /**
     * Model döngüye girdiyse ("p-p-p-p-p-p…") tekrarları atıp true döner. Sonda 1-3 tokenlık bir
     * kalıp [MAX_REPEATS] kez art arda tekrarlanıyorsa döngü sayılır; kalıbın bir tekrarı bırakılır.
     */
    private fun trimRepetition(output: MutableList<Int>): Boolean {
        for (size in 1..3) {
            val span = size * MAX_REPEATS
            if (output.size < span) continue
            val start = output.size - span
            val looping = (start + size until output.size).all { output[it] == output[it - size] }
            if (looping) {
                repeat(span - size) { output.removeAt(output.size - 1) }
                return true
            }
        }
        return false
    }

    private fun argmax(logits: FloatBuffer): Int {
        var best = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in 0 until logits.remaining()) {
            // pad: HF generation_config bad_words_ids; unk: ekrana "<unk>" basılmasın
            if (i == PAD_ID || i == UNK_ID) continue
            val v = logits.get(i)
            if (v > bestValue) {
                bestValue = v
                best = i
            }
        }
        return best
    }

    /** Modeli bellekten çıkarır; süren bir çevirinin bitmesini bekler (dosya silinmeden önce çağrılır). */
    suspend fun unload() {
        loadMutex.withLock {
            runMutex.withLock { releaseSessions() }
        }
        _status.value = TranslationStatus.Idle
    }

    private fun releaseSessions() {
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
        encoder = null
        decoder = null
        tokenizer = null
    }

    override fun close() {
        releaseSessions()
        _status.value = TranslationStatus.Idle
    }

    companion object {
        private const val TAG = "OpusEngine"
        const val MODEL_DIR_NAME = "opus_en_tr"
        private val REQUIRED_FILES = listOf("encoder_model.onnx", "decoder_model_merged.onnx", "source_pieces.tsv", "vocab.json")

        /** OPUS'un bazen kendiliğinden eklediği dil notu: "El Burro Heights (İngilizce)" */
        private val LANGUAGE_NOTE = Regex("\\s*\\((İngilizce|ingilizce|İspanyolca)\\)")

        private const val NUM_THREADS = 4
        private const val MAX_NEW_TOKENS = 128
        /** Dikkatli çeviride aynı anda denenen yol sayısı (Helsinki'nin num_beams değeri) */
        private const val BEAM_SIZE = 4
        /** Aynı kalıp bu kadar art arda gelirse döngü sayılır ("No, no, no, no, no" -> 5 "hayır" kalabilir) */
        private const val MAX_REPEATS = 6
        private const val DECODER_LAYERS = 6
        private const val HEADS = 16
        private const val HEAD_DIM = 64
        private val KV_KINDS = listOf("decoder", "encoder")
        private val KV_PARTS = listOf("key", "value")

        private const val PAD_ID = 57059
        private const val EOS_ID = 43741
        private const val UNK_ID = 52508

        private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+")
        private val STANDALONE_HEY = Regex("(?i)hey[.!]*")
    }
}

/**
 * MarianTokenizer'ın Kotlin karşılığı (SentencePiece unigram + ortak sözlük).
 * Bilgisayarda resmi tokenizer ile birebir aynı sonucu verdiği doğrulandı.
 */
private class MarianTokenizer(
    private val pieceScores: HashMap<String, Float>,
    private val pieceToId: HashMap<String, Int>,
    private val idToPiece: Array<String?>
) {
    private val maxPieceLength = pieceScores.keys.maxOf { it.length }
    private val unknownScore = pieceScores.values.min() - 10f
    private val knownChars: Set<Char> = buildSet { pieceScores.keys.forEach { piece -> piece.forEach { add(it) } } }

    /**
     * OCR çıktısını modele uygun hale getirir: OCR'ın uydurduğu şapkalı harfler
     * (fúcker, Vēspucci, thát) düz harfe çevrilir, tek başına "|" (yanlış okunmuş "I")
     * düzeltilir, modelin hiç tanımadığı karakterler atılır. Aksi halde bunlar <unk>
     * oluyor ve model ya "<unk>" basıyor ya da cümlenin bir kısmını atlıyordu.
     */
    fun cleanSource(text: String): String {
        var t = text
        for ((from, to) in CHAR_REPLACEMENTS) t = t.replace(from, to)
        t = STANDALONE_PIPE.replace(t, "I")
        t = COMBINING_MARKS.replace(Normalizer.normalize(t, Normalizer.Form.NFD), "")
        val sb = StringBuilder(t.length)
        for (c in t) sb.append(if (c in knownChars || c.isWhitespace()) c else ' ')
        return sb.toString().trim().split(WHITESPACE).joinToString(" ")
    }

    /** Kelime sözlükte tek parça ("▁word") olarak var mı? OCR bozukluğu olan kelimeler ("sorne") yok. */
    fun isWholeWord(word: String): Boolean = pieceScores.containsKey(SPACE_MARK + word)

    fun encode(text: String): LongArray {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .trim()
            .split(WHITESPACE)
            .joinToString(SPACE_MARK)
        val s = SPACE_MARK + normalized
        val n = s.length

        // Viterbi: en yüksek toplam skorlu parçalama
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val backStart = IntArray(n + 1)
        val backPiece = arrayOfNulls<String>(n + 1)
        best[0] = 0f
        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            val limit = minOf(maxPieceLength, n - i)
            for (len in 1..limit) {
                val piece = s.substring(i, i + len)
                val score = pieceScores[piece] ?: continue
                if (best[i] + score > best[i + len]) {
                    best[i + len] = best[i] + score
                    backStart[i + len] = i
                    backPiece[i + len] = piece
                }
            }
            if (best[i + 1] == Float.NEGATIVE_INFINITY) {
                // Kaynak sözlükte olmayan karakter olduğu gibi kalır, ortak sözlükte aranır
                best[i + 1] = best[i] + unknownScore
                backStart[i + 1] = i
                backPiece[i + 1] = s.substring(i, i + 1)
            }
        }

        val ids = ArrayList<Long>()
        var j = n
        while (j > 0) {
            ids += (backPiece[j]?.let { pieceToId[it] } ?: UNK_ID).toLong()
            j = backStart[j]
        }
        ids.reverse()
        ids += EOS_ID.toLong()
        return ids.toLongArray()
    }

    fun decode(ids: List<Int>): String = buildString {
        for (id in ids) {
            if (id == EOS_ID || id == PAD_ID || id == UNK_ID) continue
            append(idToPiece.getOrNull(id) ?: continue)
        }
    }.replace(SPACE_MARK, " ").trim()

    companion object {
        private const val SPACE_MARK = "▁"
        private val WHITESPACE = Regex("\\s+")
        private val COMBINING_MARKS = Regex("\\p{Mn}+")
        private val STANDALONE_PIPE = Regex("(?<!\\S)\\|(?!\\S)")
        // Model sözlüğünde olmayan, ama İngilizce karşılığı belli karakterler
        private val CHAR_REPLACEMENTS = listOf(
            "…" to "...", "ı" to "i", "ß" to "ss", "ø" to "o", "æ" to "ae", "ł" to "l", "đ" to "d"
        )
        private const val UNK_ID = 52508
        private const val EOS_ID = 43741
        private const val PAD_ID = 57059

        fun load(piecesFile: File, vocabFile: File): MarianTokenizer {
            val scores = HashMap<String, Float>(40_000)
            piecesFile.forEachLine(Charsets.UTF_8) { line ->
                val tab = line.lastIndexOf('\t')
                if (tab > 0) scores[line.substring(0, tab)] = line.substring(tab + 1).toFloat()
            }
            val json = JSONObject(vocabFile.readText(Charsets.UTF_8))
            val toId = HashMap<String, Int>(json.length() * 2)
            var maxId = 0
            for (key in json.keys()) {
                val id = json.getInt(key)
                toId[key] = id
                if (id > maxId) maxId = id
            }
            val toPiece = arrayOfNulls<String>(maxId + 1)
            for ((piece, id) in toId) toPiece[id] = piece
            return MarianTokenizer(scores, toId, toPiece)
        }
    }
}
