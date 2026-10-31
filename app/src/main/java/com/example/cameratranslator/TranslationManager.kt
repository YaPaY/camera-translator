package com.example.cameratranslator

import android.os.SystemClock
import android.text.Html
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.tasks.await
import com.example.cameratranslator.GoogleApiAuth.googleApiKey
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

enum class TranslationMode {
    CLOUD, FREE, OFFLINE
}

sealed class TranslationStatus {
    object Idle : TranslationStatus()
    object DownloadingModel : TranslationStatus()
    object Ready : TranslationStatus()
    data class Error(val message: String) : TranslationStatus()
}

interface ITranslationEngine {
    val status: StateFlow<TranslationStatus>
    val isBusy: StateFlow<Boolean>
    var mode: TranslationMode
    var isTrToEn: Boolean
    suspend fun translate(text: String): String
    suspend fun prepare()
    fun close()
    fun updateApiKey(key: String) {}
    /** Ağ bağlantısını önceden ısıtır (çevrimiçi motorlar için). */
    fun warmUp() {}
    /** OPUS modeli indirildi/değişti: gerekiyorsa yeniden yükle. */
    suspend fun reloadOfflineModel() {}
    /** OPUS modelini bellekten çıkar (dosyalar silinmeden önce). */
    suspend fun releaseOfflineModel() {}
    /** Kelime OPUS sözlüğünde tam kelime olarak var mı? (OCR okumasının temiz olup olmadığını anlamak için) */
    fun isKnownWord(word: String): Boolean = false
    /** Aktif motor yerel ve hızlı mı (OPUS)? Öyleyse olası okuma onaydan önce arka planda çevrilebilir. */
    val canPretranslate: Boolean get() = false
}

// Shared high-speed OkHttpClient with HTTP/2 connection pooling & keep-alive
private val sharedTranslationClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(4, TimeUnit.SECONDS)
    .connectionPool(ConnectionPool(8, 10, TimeUnit.MINUTES))
    .build()

class GoogleCloudTranslationEngine(
    private var apiKey: String
) : ITranslationEngine {

    override var isTrToEn: Boolean = false
    override var mode: TranslationMode = TranslationMode.CLOUD
    private val _status = MutableStateFlow<TranslationStatus>(TranslationStatus.Idle)
    override val status: StateFlow<TranslationStatus> = _status
    private val _isBusy = MutableStateFlow(false)
    override val isBusy: StateFlow<Boolean> = _isBusy

    override suspend fun prepare() {
        _status.value = if (apiKey.isNotBlank()) {
            Log.d(TAG, "Google Cloud Translation API ready")
            TranslationStatus.Ready
        } else {
            Log.w(TAG, "Google Cloud API key missing")
            TranslationStatus.Error("API anahtarınızı girin")
        }
    }

    override suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || text.isBlank()) return@withContext ""
        _isBusy.value = true
        try {
            val url = "https://translation.googleapis.com/language/translate/v2"
            
            val payload = JSONObject().apply {
                put("q", text.trim())
                put("source", if (isTrToEn) "tr" else "en")
                put("target", if (isTrToEn) "en" else "tr")
                put("format", "text")
            }.toString()

            val mediaType = "application/json; charset=UTF-8".toMediaType()
            val requestBody = payload.toRequestBody(mediaType)

            val request = Request.Builder()
                .url(url)
                .googleApiKey(apiKey)
                .post(requestBody)
                .build()

            sharedTranslationClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP ${response.code}: $body")
                    return@withContext ""
                }

                val translated = JSONObject(body)
                    .getJSONObject("data")
                    .getJSONArray("translations")
                    .getJSONObject(0)
                    .getString("translatedText")
                    .let { decodeHtmlEntities(it) }
                    .trim()

                translated
            }
        } catch (e: Exception) {
            Log.e(TAG, "Google Cloud translate error", e)
            ""
        } finally {
            _isBusy.value = false
        }
    }

    override fun close() = Unit

    override fun updateApiKey(key: String) {
        this.apiKey = key
    }

    private fun decodeHtmlEntities(text: String): String {
        return Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    companion object {
        private const val TAG = "TranslationGoogle"
    }
}

/**
 * FREE Translation Engine: iki ücretsiz Google uç noktasını yarıştırır.
 *
 *  1. Chrome sözlük eklentisi uç noktası (clients5.google.com) — ana yol.
 *     Peş peşe isteklerde ölçülen süre ~90 ms ve çok kararlı.
 *  2. GTX (translate.googleapis.com) — yedek. Aynı çeviriyi verir ama peş peşe
 *     isteklerde 0,2–0,8 sn arasında dalgalanıyor (eskiden ana yoldu, yavaşlık buydu).
 *
 * Ana yol [HEDGE_DELAY_MS] içinde cevap vermezse yedek de gönderilir ve hangisi
 * önce dolu cevap verirse o kullanılır; yavaş kalan istek iptal edilir. Böylece
 * tek bir yavaş/takılan istek çeviriyi saniyelerce bekletmez.
 *
 * Eski mobil web yedeği (translate.google.com/m) kaldırıldı: bölgemizde onay
 * sayfasına yönlendirdiği için hep boş dönüp ~0,5 sn kaybettiriyordu.
 */
class FreeTranslationEngine : ITranslationEngine {
    override var isTrToEn: Boolean = false
    override var mode: TranslationMode = TranslationMode.FREE
    private val _status = MutableStateFlow<TranslationStatus>(TranslationStatus.Idle)
    override val status: StateFlow<TranslationStatus> = _status
    private val _isBusy = MutableStateFlow(false)
    override val isBusy: StateFlow<Boolean> = _isBusy

    override suspend fun prepare() {
        _status.value = TranslationStatus.Ready
        warmUp()
    }

    /**
     * İki sunucuya da küçük bir istek atarak TLS bağlantısını önceden açar.
     * İlk çeviride el sıkışma (~100-300 ms) beklenmez; bağlantı havuzda kalır.
     */
    override fun warmUp() {
        sharedTranslationClient.newCall(buildGtxRequest("hi", "en", "tr")).enqueue(IgnoreCallback)
        sharedTranslationClient.newCall(buildChromeRequest("hi", "en", "tr")).enqueue(IgnoreCallback)
    }

    override suspend fun translate(text: String): String {
        if (text.isBlank()) return ""
        _isBusy.value = true
        val startedAt = SystemClock.elapsedRealtime()
        val cleanText = text.trim()
        val sl = if (isTrToEn) "tr" else "en"
        val tl = if (isTrToEn) "en" else "tr"

        return try {
            val result = coroutineScope {
                val primary = async { requestChrome(cleanText, sl, tl) }
                // Ana yol hızlıysa yedeği hiç göndermeden bitir
                val early = withTimeoutOrNull(HEDGE_DELAY_MS) { primary.await() }
                val value = when {
                    !early.isNullOrBlank() -> early
                    early != null -> requestGtx(cleanText, sl, tl) // ana yol boş döndü
                    else -> firstNonBlank(primary, async { requestGtx(cleanText, sl, tl) })
                }
                coroutineContext.cancelChildren() // kaybeden isteği iptal et
                value
            }
            Log.d(TAG, "Translated in ${SystemClock.elapsedRealtime() - startedAt} ms")
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Free translate error", e)
            ""
        } finally {
            _isBusy.value = false
        }
    }

    private suspend fun requestGtx(text: String, sl: String, tl: String): String {
        val body = sharedTranslationClient.newCall(buildGtxRequest(text, sl, tl)).awaitBody() ?: return ""
        return try {
            val firstPart = JSONArray(body).optJSONArray(0) ?: return ""
            val sb = StringBuilder()
            for (i in 0 until firstPart.length()) {
                val part = firstPart.optJSONArray(i)?.optString(0, "") ?: ""
                if (part != "null") sb.append(part)
            }
            sb.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "GTX parse error", e)
            ""
        }
    }

    private suspend fun requestChrome(text: String, sl: String, tl: String): String {
        val body = sharedTranslationClient.newCall(buildChromeRequest(text, sl, tl)).awaitBody() ?: return ""
        return try {
            // Biçim: ["çeviri"] ya da ["çeviri","kaynak dil"] dizileri içeren bir dizi
            val root = JSONArray(body)
            when (val first = root.opt(0)) {
                is String -> first
                is JSONArray -> first.optString(0, "")
                else -> ""
            }.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Chrome endpoint parse error", e)
            ""
        }
    }

    private fun buildGtxRequest(text: String, sl: String, tl: String): Request {
        val encoded = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        return Request.Builder()
            .url("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t&q=$encoded")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .build()
    }

    private fun buildChromeRequest(text: String, sl: String, tl: String): Request {
        val encoded = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        return Request.Builder()
            .url("https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=$sl&tl=$tl&q=$encoded")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .build()
    }

    /** Sıradaki ilk dolu sonucu döndürür; hepsi boşsa "". */
    private suspend fun firstNonBlank(vararg requests: Deferred<String>): String {
        val pending = requests.toMutableList()
        while (pending.isNotEmpty()) {
            val (finished, value) = select<Pair<Deferred<String>, String>> {
                pending.forEach { request -> request.onAwait { request to it } }
            }
            pending.remove(finished)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    override fun close() = Unit

    private object IgnoreCallback : Callback {
        override fun onFailure(call: Call, e: IOException) = Unit
        override fun onResponse(call: Call, response: Response) = response.close()
    }

    companion object {
        private const val TAG = "FreeEngine"
        // Ana yol normalde ~90 ms; 300 ms'yi geçtiyse takılmış say, yedeği de gönder
        private const val HEDGE_DELAY_MS = 300L
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
    }
}

/**
 * OkHttp isteğini askıya alınabilir şekilde çalıştırır. Coroutine iptal edilirse
 * HTTP isteği de iptal edilir (bloklayan execute() bunu yapamıyordu).
 * Başarılı cevapta gövdeyi, aksi halde null döner.
 */
private suspend fun Call.awaitBody(): String? = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resume(null)
        }

        override fun onResponse(call: Call, response: Response) {
            val body = response.use { if (it.isSuccessful) it.body?.string() else null }
            if (continuation.isActive) continuation.resume(body)
        }
    })
}

class MLKitTranslationEngine : ITranslationEngine {
    override var isTrToEn: Boolean = false
    override var mode: TranslationMode = TranslationMode.OFFLINE
    private val _status = MutableStateFlow<TranslationStatus>(TranslationStatus.Idle)
    override val status: StateFlow<TranslationStatus> = _status
    private val _isBusy = MutableStateFlow(false)
    override val isBusy: StateFlow<Boolean> = _isBusy

    private var enTrTranslator: Translator? = null
    private var trEnTranslator: Translator? = null

    override suspend fun prepare() {
        prepareDirection(false)
        prepareDirection(true)
    }

    private suspend fun prepareDirection(trToEn: Boolean) {
        val source = if (trToEn) TranslateLanguage.TURKISH else TranslateLanguage.ENGLISH
        val target = if (trToEn) TranslateLanguage.ENGLISH else TranslateLanguage.TURKISH
        
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        
        val client = Translation.getClient(options)
        _status.value = TranslationStatus.DownloadingModel
        
        try {
            val conditions = DownloadConditions.Builder().build()
            client.downloadModelIfNeeded(conditions).await()
            if (trToEn) trEnTranslator = client else enTrTranslator = client
            _status.value = TranslationStatus.Ready
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit model download error", e)
            _status.value = TranslationStatus.Error("Model indirilemedi")
        }
    }

    override suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        val engine = if (isTrToEn) trEnTranslator else enTrTranslator
        if (engine == null) return@withContext ""
        _isBusy.value = true
        try {
            engine.translate(text).await()
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit translate error", e)
            ""
        } finally {
            _isBusy.value = false
        }
    }

    override fun close() {
        enTrTranslator?.close()
        trEnTranslator?.close()
        enTrTranslator = null
        trEnTranslator = null
    }

    override fun updateApiKey(key: String) {}

    companion object {
        private const val TAG = "TranslationMLKit"
    }
}

class CompositeTranslationEngine(
    apiKey: String = "",
    private val google: GoogleCloudTranslationEngine = GoogleCloudTranslationEngine(apiKey),
    private val free: ITranslationEngine = FreeTranslationEngine(),
    private val offline: ITranslationEngine = MLKitTranslationEngine(),
    /** OPUS (OPUS-MT). Context gerektirdiği için dışarıdan verilir; yoksa ML Kit kullanılır. */
    private val offlinePlus: OpusTranslationEngine? = null
) : ITranslationEngine {

    override var isTrToEn: Boolean = false
        set(value) {
            field = value
            google.isTrToEn = value
            free.isTrToEn = value
            offline.isTrToEn = value
            offlinePlus?.isTrToEn = value
        }

    override var mode: TranslationMode = TranslationMode.FREE
        set(value) {
            val switchedToFree = value == TranslationMode.FREE && field != value
            field = value
            syncStatus()
            if (switchedToFree) free.warmUp()
            // ~350 MB'lık OPUS modeli sadece Offline modu seçilince belleğe yüklenir
            // (model indirilmemişse prepare sessizce hata durumuna geçer, ML Kit kullanılır)
            if (value == TranslationMode.OFFLINE) scope.launch { offlinePlus?.prepare() }
        }

    /**
     * Offline modunda hangi motor? Model hazırsa ve yön İngilizce -> Türkçe ise OPUS
     * (OPUS-MT), aksi halde ML Kit. Yön değişince otomatik ve sessizce geçiş yapar.
     */
    private fun useOfflinePlus(): Boolean =
        mode == TranslationMode.OFFLINE && !isTrToEn && offlinePlus?.isReady == true

    private val _isBusy = MutableStateFlow(false)
    override val isBusy: StateFlow<Boolean> = _isBusy

    private val _status = MutableStateFlow<TranslationStatus>(TranslationStatus.Idle)
    override val status: StateFlow<TranslationStatus> = _status

    private var keepWarmJob: Job? = null

    private val cacheLock = Any()
    private val translationCache = object : LinkedHashMap<String, String>(40, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 40
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun cacheKey(source: String): String {
        // Mod ve yön anahtarın parçası: başka motorun çevirisi bu moda sızmasın
        return "${mode.name}|$isTrToEn|${useOfflinePlus()}|${OcrTuning.opusCarefulEnabled}|" + source.lowercase()
            .replace(Regex("[^a-z0-9çğıöşü\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    override suspend fun prepare() {
        scope.launch { google.prepare() }
        scope.launch { free.prepare() }
        scope.launch { offline.prepare() }

        offline.status.onEach { syncStatus() }.launchIn(scope)
        google.status.onEach { syncStatus() }.launchIn(scope)
        free.status.onEach { syncStatus() }.launchIn(scope)
        offlinePlus?.status?.onEach { syncStatus() }?.launchIn(scope)

        merge(offline.isBusy, google.isBusy, free.isBusy, offlinePlus?.isBusy ?: MutableStateFlow(false))
            .onEach { _isBusy.value = it }
            .launchIn(scope)

        // Ücretsiz modda bağlantıyı sıcak tut: altyazılar arası sessizlikte bağlantı
        // kapanırsa sonraki çeviri yeniden TLS el sıkışması bekliyordu.
        if (keepWarmJob?.isActive != true) {
            keepWarmJob = scope.launch {
                while (isActive) {
                    delay(KEEP_WARM_INTERVAL_MS)
                    if (mode == TranslationMode.FREE) free.warmUp()
                }
            }
        }
    }

    override fun warmUp() {
        if (mode == TranslationMode.FREE) free.warmUp()
    }

    override suspend fun reloadOfflineModel() {
        val plus = offlinePlus ?: return
        plus.unload()
        if (mode == TranslationMode.OFFLINE) plus.prepare()
        syncStatus()
    }

    override suspend fun releaseOfflineModel() {
        offlinePlus?.unload()
        syncStatus()
    }

    private fun syncStatus() {
        val currentEngine = when (mode) {
            TranslationMode.CLOUD -> google
            TranslationMode.FREE -> free
            TranslationMode.OFFLINE -> if (useOfflinePlus()) offlinePlus ?: offline else offline
        }
        val newStatus = currentEngine.status.value
        if (_status.value != newStatus) {
            _status.value = newStatus
        }
    }

    override suspend fun translate(text: String): String {
        if (text.isBlank()) return ""

        val key = cacheKey(text)
        val cached = synchronized(cacheLock) {
            if (key.isNotEmpty()) translationCache[key] else null
        }
        if (cached != null) return cached

        // Önden çeviri isteği hâlâ yoldaysa ikinci istek atma, onun sonucunu bekle
        // (onay geldiğinde Google cevabı çoğu zaman zaten gelmiş/gelmek üzere olur)
        val pending = synchronized(cacheLock) {
            if (key.isEmpty()) null
            else inFlight.getOrPut(key) { scope.async { translateUncached(text) } }
        }
        if (pending != null) {
            return try {
                pending.await()
            } finally {
                synchronized(cacheLock) { if (inFlight[key] === pending) inFlight.remove(key) }
            }
        }
        return translateUncached(text)
    }

    private val inFlight = HashMap<String, Deferred<String>>()

    private suspend fun translateUncached(text: String): String {
        val key = cacheKey(text)
        // Google modlarında da OCR hataları düzeltilsin ("|am" -> "I am", "Im" -> "I'm"); OPUS kendi içinde yapıyor
        val ocrFixed = if (isTrToEn) text else OcrTextFixer.fix(text)
        // Google'ın da yanlış çevirdiği birkaç argo (hitap "dog", "nigga", "tricked out"); geri kalanını Google iyi biliyor
        val forGoogle = if (isTrToEn) ocrFixed else SlangNormalizer.normalizeShared(ocrFixed)
        val result = when (mode) {
            TranslationMode.CLOUD -> {
                google.translate(forGoogle)
            }
            TranslationMode.FREE -> {
                free.translate(forGoogle)
            }
            TranslationMode.OFFLINE -> {
                val plus = offlinePlus
                if (plus != null && useOfflinePlus()) {
                    plus.translate(text).ifBlank { offline.translate(ocrFixed) }
                } else {
                    offline.translate(ocrFixed)
                }
            }
        }

        if (result.isNotBlank()) {
            synchronized(cacheLock) {
                if (key.isNotEmpty()) translationCache[key] = result
            }
            return result
        }

        return ""
    }

    override fun isKnownWord(word: String): Boolean = OcrTextFixer.isKnownWord(word)

    // OPUS ve ücretsiz Google: altyazı onaylanmadan çeviri başlar, onayda sonuç hazırdır.
    // Google Cloud hariç: önden istekler kullanıcının ücretli kotasını harcardı.
    override val canPretranslate: Boolean get() = useOfflinePlus() || mode == TranslationMode.FREE

    override fun close() {
        google.close()
        free.close()
        offline.close()
        offlinePlus?.close()
        scope.cancel()
    }

    override fun updateApiKey(key: String) {
        google.updateApiKey(key)
        scope.launch {
            google.prepare()
            syncStatus()
        }
    }

    companion object {
        private const val KEEP_WARM_INTERVAL_MS = 25_000L
    }
}

class TranslationManager(
    engine: ITranslationEngine = CompositeTranslationEngine()
) : ITranslationEngine by engine
