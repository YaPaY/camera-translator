package com.example.cameratranslator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OCR kaynağı: ImageAnalysis frame'i değil, PreviewView bitmap'i.
 * Kullanıcının ekranda gördüğü görüntü = OCR'a giden görüntü (WYSIWYG).
 */
class TextRecognitionAnalyzer(
    /** (metin, ML Kit güveni 0..1) */
    private val onTextDetected: (String, Float) -> Unit,
    private val onEmptyDetected: () -> Unit = {}
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var stabilizationJob: Job? = null
    private var emptyFrameCount = 0

    private val ocrArea = AtomicReference<RectF?>(null)
    private val previewViewRef = AtomicReference<PreviewView?>(null)
    private val isPaused = AtomicBoolean(true)
    private val activeOcrTasks = AtomicInteger(0)
    private val frameCounter = AtomicLong(0)

    fun attachPreviewView(view: PreviewView) {
        previewViewRef.set(view)
        Log.d(TAG, "PreviewView attached ${view.width}x${view.height}")
    }

    fun setOcrArea(rect: RectF?, @Suppress("UNUSED_PARAMETER") viewWidth: Float, @Suppress("UNUSED_PARAMETER") viewHeight: Float) {
        ocrArea.set(rect)
    }

    fun setPaused(paused: Boolean) {
        val wasPaused = isPaused.getAndSet(paused)
        if (paused && !wasPaused) resetStabilization("pause")
        if (!paused && wasPaused) resetStabilization("resume")
    }

    private fun resetStabilization(reason: String) {
        stabilizationJob?.cancel()
        stabilizationJob = null
        emptyFrameCount = 0
        Log.d(TAG, "Stabilization reset ($reason)")
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val frameId = frameCounter.incrementAndGet()
        // En fazla 2 eşzamanlı OCR işlemine izin ver
        if (isPaused.get() || activeOcrTasks.get() >= 2) {
            imageProxy.close()
            return
        }

        val area = ocrArea.get()
        if (area == null) {
            imageProxy.close()
            return
        }

        // ImageProxy'den ViewPort uyumlu (crop + rotation) bitmap al
        val previewBitmap = imageProxy.toBitmapSafe()
        imageProxy.close()

        if (previewBitmap == null) return

        activeOcrTasks.incrementAndGet()
        scope.launch {
            runOcr(previewBitmap, area, frameId)
        }
    }

    private suspend fun runOcr(previewBitmap: Bitmap, area: RectF, frameId: Long) {
        var upscaled: Bitmap? = null
        var masked: Bitmap? = null
        val frameSize = "${previewBitmap.width}x${previewBitmap.height}"
        try {
            val cropRect = PreviewCoordinateMapper.mapNormalizedToBitmapRect(
                area,
                previewBitmap.width,
                previewBitmap.height
            )

            if (cropRect.width() < 30 || cropRect.height() < 12) return

            val cropped = Bitmap.createBitmap(
                previewBitmap,
                cropRect.left,
                cropRect.top,
                cropRect.width(),
                cropRect.height()
            )
            // OCR alanı tüm kareyi kapsıyorsa createBitmap aynı nesneyi döndürür
            if (cropped !== previewBitmap) previewBitmap.recycle()

            val original = upscaleForOcr(cropped)
            if (original !== cropped) cropped.recycle()
            upscaled = original

            // Önce filtresiz oku. Beyaz altyazı filtresi yalnızca okuma zayıfsa denenir (açık renk asfalt/kum
            // üstündeki altyazı): eskiden her kare iki kez OCR'dan geçiyordu, filtre ise karelerin ancak ~%1,5'inde
            // belirgin fark yaratıyordu. Hiç yazı bulunmayan karede de denenmez (1310 karede yalnızca 3 kez işe yaradı).
            val originalResult = recognize(original, frameId)
            val originalReading = originalResult?.first
            val needsFilter = OcrTuning.textMaskEnabled && originalReading != null &&
                originalReading.text.isNotBlank() && originalReading.confidence < FILTER_BELOW_CONFIDENCE
            // Görüntü uygun değilse null döner
            val filtered = if (needsFilter) SubtitleTextMask.apply(original) else null
            masked = filtered
            OcrDebugDumper.maybeSaveFrames(frameId, original, filtered)
            val filteredResult = filtered?.let { recognize(it, frameId) }
            val useFiltered = filteredResult != null && isBetter(filteredResult.first, originalResult?.first) &&
                !originalHasExtraWords(originalResult?.first, filteredResult.first)
            val best = if (useFiltered) filteredResult else originalResult

            val source = buildString {
                if (filteredResult != null) {
                    append("filtre ").append(formatConfidence(filteredResult.first.confidence)).append(" | ")
                }
                append("orijinal ").append(formatConfidence(originalResult?.first?.confidence ?: 0f))
                append(if (useFiltered) " → filtre" else " → orijinal")
                append(if (OcrTuning.centerOnlyEnabled) " | orta: açık" else " | orta: kapalı")
                append(" | ").append(frameSize)
            }

            withContext(Dispatchers.Main) {
                val reading = best?.first ?: OcrReading("", 0f)
                val text = reading.text
                OcrDebugDumper.logResult(frameId, best?.second.orEmpty(), text, reading.confidence, source)
                if (frameId % 10 == 0L) Log.d(TAG, "Frame #$frameId OCR Result ($source): \"$text\"")
                if (text.isNotBlank()) handleOcrResult(text, reading.confidence, frameId)
                else handleEmptyResult(frameId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Frame #$frameId error", e)
        } finally {
            activeOcrTasks.decrementAndGet()
            if (!previewBitmap.isRecycled) previewBitmap.recycle()
            upscaled?.let { if (!it.isRecycled) it.recycle() }
            masked?.recycle()
        }
    }

    /** ML Kit'i çalıştırır; (ayıklanmış okuma, ML Kit ham metni) döner, hata olursa null. */
    private suspend fun recognize(bitmap: Bitmap, frameId: Long): Pair<OcrReading, String>? {
        return try {
            val visionText = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            extractSubtitleText(visionText, bitmap.width) to visionText.text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Frame #$frameId ML Kit error", e)
            null
        }
    }

    private fun isBetter(candidate: OcrReading, other: OcrReading?): Boolean {
        if (other == null || other.text.isBlank()) return candidate.text.isNotBlank()
        if (candidate.text.isBlank()) return false
        return candidate.confidence > other.confidence
    }

    /**
     * GTA görev yazılarında hedef kelime renklidir ("Go to the <sarı>warehouse</sarı>."): beyaz filtre
     * o kelimeyi siler ve yazı "Go to the" diye yarım okunur. Orijinal okuma filtrelinin kelimelerini
     * içerip fazladan kelime de buluyorsa (ve yeterince güvenliyse) orijinal tercih edilir.
     */
    private fun originalHasExtraWords(original: OcrReading?, filtered: OcrReading): Boolean {
        if (original == null || original.confidence < MIN_ORIGINAL_CONFIDENCE) return false
        val filteredWords = wordKeys(filtered.text)
        if (filteredWords.isEmpty()) return false
        // Satır satır bakılır: orijinaldeki ayrı bir HUD satırı (mesafe, sokak adı) "fazla kelime" sayılmasın
        return original.text.split('\n').any { line ->
            val lineWords = wordKeys(line)
            lineWords.size > filteredWords.size && filteredWords.count { it in lineWords } >= filteredWords.size * 0.8f
        }
    }

    private fun wordKeys(text: String): List<String> =
        text.lowercase().split(' ', '\n').map { word -> word.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }

    private fun formatConfidence(value: Float): String = String.format(java.util.Locale.US, "%.2f", value)

    private fun upscaleForOcr(source: Bitmap): Bitmap {
        val scaleWidth = 800f / source.width.toFloat()
        val scaleHeight = 200f / source.height.toFloat()
        // 1080p'de alan zaten büyük; genişliği sınırlamak OCR süresini sabit tutar,
        // harfler bu boyutta ML Kit için fazlasıyla yeterli yükseklikte kalır.
        val maxScale = maxOf(1f, MAX_OCR_WIDTH / source.width.toFloat())
        val scale = minOf(maxOf(2.5f, scaleWidth, scaleHeight), maxScale)
        if (scale <= 1.1f) return source

        val width = (source.width * scale).roundToInt()
        val height = (source.height * scale).roundToInt()
        val scaled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        val paint = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
        }
        canvas.drawBitmap(source, Matrix().apply { postScale(scale, scale) }, paint)
        return scaled
    }

    private data class OcrReading(val text: String, val confidence: Float)

    /**
     * ML Kit sonucunu kendi satır sırasıyla birleştirir ve okumanın ortalama
     * güvenini hesaplar. Güven satırları atmak için değil, aynı altyazının
     * birkaç okuması arasından en temizini seçmek için kullanılır
     * (satır atmak cümleleri yarım bırakıyordu).
     *
     * "Sadece ortadaki altyazı" açıksa kenardaki HUD yazıları (harita, telefon)
     * [SubtitleRegionFilter] ile atılır.
     */
    private fun extractSubtitleText(visionText: Text, imageWidth: Int): OcrReading {
        val centerOnly = OcrTuning.centerOnlyEnabled
        val lines = visionText.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val text = if (centerOnly) centeredText(line, imageWidth) else line.text.trim()
                text?.takeIf { t -> t.count { it.isLetter() } >= MIN_LINE_LETTERS && !isCapsDecal(t) }?.let { line to it }
            }
        if (lines.isEmpty()) return OcrReading("", 0f)

        var weightedConfidence = 0f
        var totalLength = 0
        for ((line, text) in lines) {
            weightedConfidence += line.confidence.coerceIn(0f, 1f) * text.length
            totalLength += text.length
        }
        val confidence = if (totalLength > 0) weightedConfidence / totalLength else 0f
        val text = lines.joinToString("\n") { it.second }.trim()
        return OcrReading(text, confidence)
    }

    /**
     * Araç/tabela yazısı: tamamı büyük harf ve cümle noktalaması yok ("LOS SANTOS FIRE DEPARTMENT KEEP BACK",
     * "TEMP"). Bağırılan altyazılar ünlem/soru/noktayla biter ("FREEZE!", "GET DOWN!") ve kalır.
     * Son testlerdeki 1000+ altyazının hiçbiri bu kurala takılmadı.
     */
    private fun isCapsDecal(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 4) return false
        val upperRatio = letters.count { it.isUpperCase() }.toFloat() / letters.length
        return upperRatio > 0.8f && !SENTENCE_END_PUNCTUATION.containsMatchIn(text)
    }

    /** Satırın yalnızca ortadaki kelime gruplarını döndürür; konum bilgisi yoksa satırı aynen bırakır. */
    private fun centeredText(line: Text.Line, imageWidth: Int): String? {
        val words = line.elements.mapNotNull { element ->
            val box = element.boundingBox ?: return@mapNotNull null
            SubtitleRegionFilter.Word(element.text, box.left, box.right, box.height())
        }
        if (words.size != line.elements.size) return line.text.trim()
        return SubtitleRegionFilter.keepCentered(words, imageWidth)
    }

    private fun handleEmptyResult(frameId: Long) {
        emptyFrameCount++
        if (emptyFrameCount >= EMPTY_RESET_THRESHOLD) {
            stabilizationJob?.cancel()
            stabilizationJob = null
            onEmptyDetected()
        }
    }

    private fun isGibberishWord(word: String): Boolean {
        val cleanWord = word.lowercase().filter { it.isLetter() }
        if (cleanWord.length < 3) return false

        // Eşikler OPUS sözlüğündeki ~25 bin İngilizce kelimeyle ayarlandı. Eski eşikler (3 aynı harf,
        // 3 sesli, "y" dahil 4 sessiz) 860'tan fazla gerçek kelimeyi siliyordu: Johnny, everything,
        // country, quickly, background, beautiful, quiet, Mmm, Shhh …

        // 1. 4+ aynı harf art arda ("iiii", "llll"); "Mmm", "Shhh" gibi ünlemler kalır
        if (GIBBERISH_REPEAT_REGEX.containsMatchIn(cleanWord)) return true

        // 2. 4+ sesli harf art arda (yalnızca queue, Hawaiian gibi birkaç kelime etkilenir)
        if (GIBBERISH_VOWELS_REGEX.containsMatchIn(cleanWord)) return true

        // 3. "y" hariç 5+ sessiz harf art arda ("qwrtz", "hjklm"); yalnızca birkaç nadir kelime etkilenir
        if (GIBBERISH_CONSONANTS_REGEX.containsMatchIn(cleanWord)) return true

        return false
    }

    private fun filterOcrNoise(text: String): String {
        val words = text.replace(SPACES_REGEX, " ").trim().split(SPACES_REGEX)
        val filteredWords = words.filter { word ->
            // Not: büyük harfli kelimeleri (MENU, PAUSE) atan filtre kaldırıldı. HUD yazılarını artık
            // "Sadece ortadaki altyazıyı oku" eliyor; o filtre bağırarak yazılmış altyazıları
            // ("NO!", "GET DOWN!") ve kısaltmaları (FIB, LSPD, MC) da siliyordu.

            // 1. Filter out alphanumeric noise words where letters and numbers are directly concatenated (e.g. A10, A1, A2, A5000, 100X, B12)
            // Sıra sayıları, onyıllar ve saatler gerçek kelimedir: 1st, 24th, 90s, 10pm, 5k
            val core = word.trim { !it.isLetterOrDigit() }
            val hasAlphanumericNoise = !NUMBER_WITH_SUFFIX.matches(core) && ALPHANUMERIC_NOISE_REGEX.containsMatchIn(word)

            // 2. Filter out unpronounceable OCR artifacts and gibberish noise words
            val isGibberish = isGibberishWord(word)

            !hasAlphanumericNoise && !isGibberish
        }
        return filteredWords.joinToString(" ").trim()
    }

    private fun handleOcrResult(text: String, confidence: Float, frameId: Long) {
        if (isPaused.get()) {
            Log.d(TAG, "Frame #$frameId ignored because analyzer is paused.")
            return
        }
        emptyFrameCount = 0

        // Filter out ALL-CAPS words (2+ caps), Alphanumeric noise tokens (e.g. A10, A1), and Gibberish OCR artifacts
        val cleaned = filterOcrNoise(text)

        if (!isPlausibleSubtitle(cleaned)) {
            // Bozuk okuma "altyazı yok" sayılmaz: asfalt üstündeki altyazı birkaç kare
            // bozuk okununca stabilizer sıfırlanıyor ve cümle kaçıyordu. Gerçek boşluk
            // ML Kit'in hiç metin bulmamasıyla (handleEmptyResult) algılanır.
            Log.d(TAG, "Frame #$frameId rejected: \"$cleaned\" (original: \"$text\")")
            return
        }

        // Forward each plausible OCR sample. OcrStabilizer needs repeated frame
        // evidence to distinguish a real subtitle from transient road texture.
        if (frameId % 10 == 0L) Log.d(TAG, "Frame #$frameId sampling: \"$cleaned\"")
        onTextDetected(cleaned, confidence)
    }

    private fun isPlausibleSubtitle(text: String): Boolean {
        if (text.length < 2) return false

        val letters = text.count { it.isLetter() }
        // Oran noktalama/boşluk sayılmadan hesaplanır: "Uh..." (2 harf / 5 karakter) reddediliyordu
        val meaningful = text.count { it.isLetterOrDigit() || it in "$%&@#*+=/\\|~^" }
        if (letters < meaningful * 0.45f) return false
        // %15 sınırı "It's 20000 dollars." gibi sayılı altyazıları tamamen reddediyordu
        if (text.count { it.isDigit() } > text.length * 0.4f) return false
        if (REJECT_PATTERN.containsMatchIn(text)) return false

        val words = text.split(SPACES_REGEX).filter { it.isNotBlank() }
        if (text.length > 15 && words.size < 2) return false
        // Harita/menü tuş ipuçları: "Zoom LT RT Select Blip", "Browse LB RB"
        val cores = words.map { it.trim { c -> !c.isLetter() } }
        if (cores.count { it in BUTTON_HINTS } >= 2) return false
        // Tuşlardan biri yanlış okunsa da ("Zoom LT AT Select Blip") harita ipucu kelimesiyle birlikteyse at
        if (cores.any { it in BUTTON_HINTS } && cores.any { it.lowercase() in MAP_HINT_WORDS }) return false

        var validWords = 0
        for (word in words) {
            val wordLetters = word.filter { it.isLetter() }
            if (wordLetters.length < 2) continue
            // Sesli harfi olmayan ünlemler ("Shh!", "Hmm.", "Psst!") de gerçek altyazıdır
            if (wordLetters.lowercase() in INTERJECTIONS) {
                validWords++
                continue
            }

            val vowels = wordLetters.count { it.lowercaseChar() in "aeiouıöü" }
            val vowelRatio = vowels.toFloat() / wordLetters.length
            if (vowelRatio !in 0.15f..0.75f) continue

            // Not: büyük harfli uzun kelimeleri ("FREEZE!") geçersiz sayan kural kaldırıldı;
            // bağırarak yazılmış tek kelimelik altyazılar reddediliyordu. HUD'u orta filtre eliyor.
            if (CAMEL_CASE_PATTERN.containsMatchIn(word)) continue

            validWords++
        }

        return validWords >= 1
    }

    companion object {
        private const val TAG = "OCR_ANALYZER"
        private const val EMPTY_RESET_THRESHOLD = 3
        private const val MAX_OCR_WIDTH = 2400f
        // 3 iken "No!", "Go!", "Hi.", "Ok." gibi kısa altyazılar siliniyordu
        private const val MIN_LINE_LETTERS = 2
        /** Renkli kelimeyi bulan orijinal okumanın filtreli okumaya tercih edilmesi için en düşük güven */
        private const val MIN_ORIGINAL_CONFIDENCE = 0.6f
        /** Filtresiz okumanın güveni bunun altındaysa beyaz filtre de denenir (iyi karelerde ~0.8) */
        private const val FILTER_BELOW_CONFIDENCE = 0.75f
        /** Oyun kumandası tuş adları (büyük harfle, tek başına) */
        private val BUTTON_HINTS = setOf("LT", "RT", "LB", "RB", "LS", "RS")
        private val MAP_HINT_WORDS = setOf("zoom", "blip", "blp", "waypoint", "legend", "browse")
        private val SENTENCE_END_PUNCTUATION = Regex("[.!?…][\"')]*\\s*$")

        private val SPACES_REGEX = Regex("\\s+")
        private val NUMBER_WITH_SUFFIX = Regex("\\d+(st|nd|rd|th|s|k|m|am|pm|x)", RegexOption.IGNORE_CASE)
        private val INTERJECTIONS = setOf("shh", "shhh", "hmm", "hmmm", "hm", "mm", "mmm", "mhm", "psst", "pst", "pfft", "tsk", "brr", "grr", "sh")
        private val REJECT_PATTERN = Regex("[<>]+|_{2,}|={2,}")
        private val CAMEL_CASE_PATTERN = Regex("[a-z][A-Z]{2,}[a-z]")


        // Regex matching alphanumeric noise tokens where letters and digits are directly concatenated (e.g. A10, A1, A2, A5000, 100X, B12)
        private val ALPHANUMERIC_NOISE_REGEX = Regex("(?=[A-Za-zÇĞİÖŞÜçğıöşü]*\\d)(?=\\d*[A-Za-zÇĞİÖŞÜçğıöşü])[A-Za-zÇĞİÖŞÜçğıöşü0-9-]+")

        // Gibberish artifact regexes
        private val GIBBERISH_REPEAT_REGEX = Regex("(.)\\1{3,}")
        private val GIBBERISH_VOWELS_REGEX = Regex("[aeiouıöü]{4,}")
        private val GIBBERISH_CONSONANTS_REGEX = Regex("[bcdfghjklmnpqrstvwxzçğş]{5,}")
    }
}
