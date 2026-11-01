package com.example.cameratranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android TV çeviri motoru: ekranı MediaProjection ile yakalar, alttaki altyazı alanını
 * (TvSettings.ocrHeightPercent) telefon sürümüyle aynı OCR → stabilizer → çeviri hattından geçirir
 * ve çeviriyi her şeyin üstündeki bir şeritte (TvOverlay) gösterir.
 *
 * Önemli sınır: Birçok TV, HDMI girişini Android'in çizdiği ekranın altında ayrı bir donanım
 * katmanında oynatır. O zaman yakalanan karede HDMI bölgesi siyah ya da şeffaf gelir. Servis
 * bunu algılayıp ekranda ve TV uygulamasında açıkça bildirir (Verdict.BLACK / TRANSPARENT).
 */
class TvTranslationService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var codecCapture: TvCodecCapture? = null

    private lateinit var overlay: TvOverlay
    private lateinit var settingsManager: SettingsManager
    private var translationManager: TranslationManager? = null
    private var speechManager: SpeechManager? = null
    private var analyzer: TextRecognitionAnalyzer? = null
    private var stabilizer: OcrStabilizer? = null

    @Volatile private var ocrArea: RectF = TvSettings.Config().ocrArea()

    // Yakalama iş parçacığında yazılır, ana iş parçacığındaki gözetleyici okur
    @Volatile private var startedAt = 0L
    @Volatile private var lastFrameAt = 0L
    @Volatile private var blankSince = 0L
    @Volatile private var lastTransparent = false
    @Volatile private var lastMaxLuma = 0
    @Volatile private var framesInWindow = 0
    private var lastProcessedAt = 0L
    private var lastPreviewAt = 0L

    private var translationJob: Job? = null
    private var hideJob: Job? = null
    private var lastConfirmed: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = TvOverlay(this)
        settingsManager = SettingsManager(this)
        TvSettings.init(this)
    }

    override fun onStartCommand(startIntent: Intent?, flags: Int, startId: Int): Int {
        val intent = startIntent ?: return START_NOT_STICKY
        when (intent.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // Android 14+: MediaProjection alınmadan önce mediaProjection tipli ön plan servisi şart
                startInForeground()
                if (projection != null) return START_NOT_STICKY

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                if (data == null) {
                    fail("Ekran yakalama izni alınamadı")
                    return START_NOT_STICKY
                }
                try {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val mp = mpm.getMediaProjection(resultCode, data)
                    if (mp == null) {
                        fail("Ekran yakalama başlatılamadı")
                        return START_NOT_STICKY
                    }
                    projection = mp
                    // Android 14+: createVirtualDisplay'den önce callback kaydı zorunlu
                    mp.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.d(TAG, "MediaProjection durduruldu")
                            stopSelf()
                        }
                    }, mainHandler)
                    startPipeline()
                    startCapture(mp)
                } catch (e: Exception) {
                    Log.e(TAG, "Ekran yakalama hatası", e)
                    fail("Ekran yakalama hatası: ${e.message}")
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun fail(message: String) {
        TvCaptureStatus.update { it.copy(running = false, verdict = TvCaptureStatus.Verdict.IDLE, error = message) }
        stopSelf()
    }

    // ---------------------------------------------------------------- Çeviri hattı

    private fun startPipeline() {
        GoogleApiAuth.init(this)
        OcrDebugDumper.init(this)
        OfflineModelDownloader.init(this)
        OcrTextFixer.init(this)
        OcrTuning.textMaskEnabled = settingsManager.getOcrTextMaskEnabled()
        OcrTuning.opusCarefulEnabled = settingsManager.getOpusCarefulEnabled()
        OcrTuning.centerOnlyEnabled = settingsManager.getOcrCenterOnlyEnabled()

        val tm = TranslationManager(CompositeTranslationEngine(offlinePlus = OpusTranslationEngine(applicationContext)))
        tm.updateApiKey(settingsManager.getApiKey())
        tm.isTrToEn = false // TV modu: oyundaki İngilizce → Türkçe
        tm.mode = settingsManager.getTranslationMode()
        scope.launch { tm.prepare() }
        translationManager = tm

        val speech = SpeechManager(this).apply {
            setEnabled(settingsManager.getTtsEnabled())
            setSpeechRate(settingsManager.getSpeechRate())
            setCloudSpeechRate(settingsManager.getCloudSpeechRate())
            setPitch(settingsManager.getSpeechPitch())
            setVoiceGender(settingsManager.getVoiceGender())
            engineType = settingsManager.getTtsEngineType()
            ttsApiKey = settingsManager.getTtsApiKey()
            trCloudVoice = settingsManager.getTrCloudVoice()
            enCloudVoice = settingsManager.getEnCloudVoice()
        }
        speechManager = speech

        val stab = OcrStabilizer(
            isCleanReading = { CleanReading.isClean(it, tm::isKnownWord) },
            isKnownWord = tm::isKnownWord,
            onLikelyCandidate = { candidate ->
                if (tm.canPretranslate && !tm.isBusy.value) scope.launch { tm.translate(candidate) }
            }
        ) { confirmed -> mainHandler.post { onSubtitleConfirmed(confirmed) } }
        stabilizer = stab

        val an = TextRecognitionAnalyzer(
            onTextDetected = { text, confidence ->
                TvCaptureStatus.update { it.copy(lastOcr = text) }
                // Kendi çeviri şeridimiz yanlışlıkla OCR alanına girerse tekrar çevirme
                if (!isOwnOverlayText(text)) stab.submit(text, confidence)
            },
            onEmptyDetected = { onSubtitleGone() }
        )
        an.setPaused(false)
        analyzer = an

        val config = TvSettings.config.value
        ocrArea = config.ocrArea()
        overlay.show(config)
        scope.launch {
            TvSettings.config.collect { c ->
                ocrArea = c.ocrArea()
                overlay.applyConfig(c)
            }
        }
        // OPUS indirilip kurulunca modeli yükle
        scope.launch {
            var wasInstalled = OfflineModelDownloader.state.value is OfflineModelDownloader.State.Installed
            OfflineModelDownloader.state.collect { state ->
                val installed = state is OfflineModelDownloader.State.Installed
                if (installed && !wasInstalled) tm.reloadOfflineModel()
                wasInstalled = installed
            }
        }
        startWatchdog()
    }

    private fun onSubtitleConfirmed(text: String) {
        val tm = translationManager ?: return
        if (lastConfirmed != null && normalize(lastConfirmed!!) == normalize(text)) return
        lastConfirmed = text
        hideJob?.cancel()
        translationJob?.cancel()
        translationJob = scope.launch {
            try {
                val translated = withContext(Dispatchers.IO) { tm.translate(text) }
                if (translated.isBlank() || translated.equals(text, ignoreCase = true)) return@launch
                overlay.showTranslation(translated)
                TvCaptureStatus.update { it.copy(lastTranslation = translated) }
                speechManager?.speak(translated, false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Çeviri hatası", e)
            }
        }
    }

    private fun onSubtitleGone() {
        stabilizer?.notifyEmpty()
        lastConfirmed = null
        if (hideJob?.isActive == true) return
        hideJob = scope.launch {
            delay(HIDE_AFTER_EMPTY_MS)
            overlay.clearTranslation()
        }
    }

    private fun isOwnOverlayText(text: String): Boolean {
        val shown = overlay.currentTranslation()
        if (shown.isBlank()) return false
        val a = normalize(text)
        val b = normalize(shown)
        return a.isNotEmpty() && (a == b || (a.length > 8 && b.contains(a)))
    }

    private fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.replace(Regex("\\s+"), " ").trim()

    // ---------------------------------------------------------------- Ekran yakalama

    private fun startCapture(mp: MediaProjection) {
        val (width, height) = captureSize()
        val density = resources.displayMetrics.densityDpi
        val thread = HandlerThread("TvCapture").also { it.start() }
        captureThread = thread
        val handler = Handler(thread.looper)
        val mode = TvSettings.config.value.captureMode

        val surface = when (mode) {
            TvSettings.CaptureMode.STANDARD -> {
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                reader.setOnImageAvailableListener({ r -> onImageAvailable(r) }, handler)
                imageReader = reader
                reader.surface
            }
            TvSettings.CaptureMode.CODEC -> {
                val codec = TvCodecCapture(
                    width, height, handler,
                    onFrame = { image -> onCodecFrame(image, width, height) },
                    onError = { message -> mainHandler.post { TvCaptureStatus.update { it.copy(error = message) } } }
                )
                codec.start()
                codecCapture = codec
                codec.inputSurface
            }
        }

        virtualDisplay = mp.createVirtualDisplay(
            "CameraTranslatorTv",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, handler
        )
        startedAt = SystemClock.elapsedRealtime()
        TvCaptureStatus.update {
            it.copy(
                running = true, verdict = TvCaptureStatus.Verdict.STARTING,
                captureWidth = width, captureHeight = height, captureMode = mode, error = null
            )
        }
        Log.d(TAG, "Ekran yakalama başladı ($mode) ${width}x$height @$density dpi")
    }

    /** Ekranın gerçek boyutu, genişlik en fazla 1920 (4K panelde OCR için gereksiz büyük kare olmasın) */
    private fun captureSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
        val scale = min(1f, MAX_CAPTURE_WIDTH / max(w, 1).toFloat())
        // ImageReader için çift sayı
        val sw = ((w * scale).roundToInt() / 2) * 2
        val sh = ((h * scale).roundToInt() / 2) * 2
        return max(sw, 2) to max(sh, 2)
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            lastFrameAt = now
            if (now - lastProcessedAt < FRAME_INTERVAL_MS) return
            lastProcessedAt = now
            val bitmap = image.toBitmap() ?: return
            processFrame(bitmap, now)
        } catch (e: Exception) {
            Log.e(TAG, "Kare işlenemedi", e)
        } finally {
            image.close()
        }
    }

    /** Codec yolu: çözülmüş YUV karesi (her kare gelir; OCR hızına göre seyreltilir) */
    private fun onCodecFrame(image: Image, width: Int, height: Int) {
        try {
            val now = SystemClock.elapsedRealtime()
            lastFrameAt = now
            if (now - lastProcessedAt < FRAME_INTERVAL_MS) return
            lastProcessedAt = now
            processFrame(TvCodecCapture.lumaToBitmap(image, width, height), now)
        } catch (e: Exception) {
            Log.e(TAG, "Codec karesi işlenemedi", e)
        }
    }

    /** Bitmap'in sahipliğini alır: OCR'a verilir ya da geri dönüştürülür. */
    private fun processFrame(bitmap: Bitmap, now: Long) {
        framesInWindow++
        val area = ocrArea
        val sample = sampleArea(bitmap, area)
        lastMaxLuma = sample.maxLuma
        lastTransparent = sample.transparent
        if (sample.blank) {
            if (blankSince == 0L) blankSince = now
        } else {
            blankSince = 0L
        }

        if (now - lastPreviewAt > PREVIEW_INTERVAL_MS) {
            lastPreviewAt = now
            publishPreview(bitmap, area)
        }

        val an = analyzer
        if (sample.blank || an == null || !an.canAcceptFrame() || !an.submitBitmap(bitmap, area)) {
            bitmap.recycle()
        }
    }

    private fun Image.toBitmap(): Bitmap? {
        val plane = planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = rowStride / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        if (cropped !== padded) padded.recycle()
        return cropped
    }

    private data class AreaSample(val maxLuma: Int, val blank: Boolean, val transparent: Boolean)

    /** OCR alanından seyrek örnek: tamamen siyah mı, şeffaf mı (donanım katmanı deliği)? */
    private fun sampleArea(bitmap: Bitmap, area: RectF): AreaSample {
        val rect = PreviewCoordinateMapper.mapNormalizedToBitmapRect(area, bitmap.width, bitmap.height)
        val cols = 48
        val rows = 12
        var maxLuma = 0
        var transparentCount = 0
        var total = 0
        for (r in 0 until rows) {
            val y = rect.top + (rect.height() - 1) * r / (rows - 1)
            for (c in 0 until cols) {
                val x = rect.left + (rect.width() - 1) * c / (cols - 1)
                val p = bitmap.getPixel(x, y)
                total++
                if (Color.alpha(p) < 8) transparentCount++
                val luma = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                if (luma > maxLuma) maxLuma = luma
            }
        }
        val transparent = total > 0 && transparentCount > total * 0.9f
        return AreaSample(maxLuma, blank = transparent || maxLuma <= BLANK_MAX_LUMA, transparent = transparent)
    }

    private fun publishPreview(bitmap: Bitmap, area: RectF) {
        val rect = PreviewCoordinateMapper.mapNormalizedToBitmapRect(area, bitmap.width, bitmap.height)
        val scale = min(1f, 640f / rect.width())
        val w = max(1, (rect.width() * scale).roundToInt())
        val h = max(1, (rect.height() * scale).roundToInt())
        val region = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        val small = Bitmap.createScaledBitmap(region, w, h, true)
        if (region !== small && region !== bitmap) region.recycle()
        // Eski önizleme geri dönüştürülmez: Compose onu hâlâ çiziyor olabilir (GC toplar)
        TvCaptureStatus.preview.value = small
    }

    /** Saniyede bir: fps, siyah/şeffaf görüntü teşhisi, ekrandaki teşhis satırı */
    private fun startWatchdog() {
        scope.launch {
            while (isActive) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                val fps = framesInWindow.toFloat()
                framesInWindow = 0
                val verdict = when {
                    lastFrameAt == 0L && now - startedAt > NO_FRAME_MS -> TvCaptureStatus.Verdict.NO_FRAMES
                    lastFrameAt == 0L -> TvCaptureStatus.Verdict.STARTING
                    blankSince != 0L && now - blankSince > BLANK_VERDICT_MS ->
                        if (lastTransparent) TvCaptureStatus.Verdict.TRANSPARENT else TvCaptureStatus.Verdict.BLACK
                    else -> TvCaptureStatus.Verdict.OK
                }
                if (blankSince != 0L && now - blankSince > HIDE_AFTER_EMPTY_MS) onSubtitleGone()
                TvCaptureStatus.update { it.copy(verdict = verdict, fps = fps, maxLuma = lastMaxLuma) }
                val s = TvCaptureStatus.state.value
                overlay.showDiagnostics(
                    "CT [${if (TvSettings.config.value.captureMode == TvSettings.CaptureMode.CODEC) "Codec" else "Normal"}] • ${TvCaptureStatus.verdictText(verdict)} • ${fps.roundToInt()} kare/sn • parlaklık ${s.maxLuma}" +
                        if (s.lastOcr.isNotBlank()) "\nOCR: ${s.lastOcr.take(80)}" else ""
                )
            }
        }
    }

    // ---------------------------------------------------------------- Ön plan bildirimi

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "TV çevirisi", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TvTranslationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 2, Intent(this, TvActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Camera Translator TV")
            .setContentText("Ekrandaki İngilizce altyazılar çevriliyor")
            .setContentIntent(openIntent)
            .addAction(0, "Durdur", stopIntent)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    override fun onDestroy() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { codecCapture?.release() }
        codecCapture = null
        runCatching { projection?.stop() }
        captureThread?.quitSafely()
        virtualDisplay = null
        imageReader = null
        projection = null
        analyzer?.close()
        speechManager?.shutdown()
        translationManager?.close()
        overlay.hide()
        scope.cancel()
        TvCaptureStatus.update {
            it.copy(running = false, verdict = TvCaptureStatus.Verdict.IDLE, fps = 0f)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TV_CAPTURE"
        private const val CHANNEL_ID = "tv_translation"
        private const val NOTIFICATION_ID = 4711
        const val ACTION_START = "com.example.cameratranslator.tv.START"
        const val ACTION_STOP = "com.example.cameratranslator.tv.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        private const val MAX_CAPTURE_WIDTH = 1920f
        /** ~5 OCR karesi/sn; telefon kamerasındaki hızla aynı mertebe */
        private const val FRAME_INTERVAL_MS = 200L
        private const val PREVIEW_INTERVAL_MS = 1000L
        /** OCR alanındaki en parlak nokta bu değerin altındaysa kare "boş" (siyah) sayılır */
        private const val BLANK_MAX_LUMA = 10
        private const val BLANK_VERDICT_MS = 4000L
        private const val NO_FRAME_MS = 4000L
        private const val HIDE_AFTER_EMPTY_MS = 1500L

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, TvTranslationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TvTranslationService::class.java).setAction(ACTION_STOP))
        }
    }
}
