package com.example.cameratranslator

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.RectF
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Uygulama Android TV'de mi çalışıyor? (TV'de kamera yerine ekran yakalama modu açılır) */
fun Context.isTelevision(): Boolean {
    val uiMode = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    return packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

/**
 * Android TV ekran yakalama modunun ayarları. Servis [config] akışını dinler; TV ekranında
 * yapılan değişiklik (OCR alanı, yazı boyutu) çeviri çalışırken anında uygulanır.
 */
object TvSettings {

    data class Config(
        /** Ekranın altından itibaren OCR alanının yüksekliği (% ekran yüksekliği) */
        val ocrHeightPercent: Int = 22,
        /** OCR alanının sağ ve soldan boşluğu (% ekran genişliği) */
        val sideMarginPercent: Int = 8,
        /** Çeviri yazısının boyutu (sp) */
        val textSizeSp: Int = 30,
        /** Ekranın sol üstünde teşhis satırı (fps, siyah görüntü uyarısı, son OCR) */
        val diagnostics: Boolean = true
    ) {
        /** OCR alanı, ekran karesine göre normalize (0..1) */
        fun ocrArea(): RectF {
            val h = ocrHeightPercent / 100f
            val m = sideMarginPercent / 100f
            return RectF(m, 1f - h, 1f - m, 1f)
        }
    }

    const val MIN_HEIGHT = 8
    const val MAX_HEIGHT = 50
    const val MIN_TEXT = 18
    const val MAX_TEXT = 60

    private var prefs: SharedPreferences? = null
    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("tv_settings", Context.MODE_PRIVATE)
        prefs = p
        val d = Config()
        _config.value = Config(
            ocrHeightPercent = p.getInt("ocr_height", d.ocrHeightPercent).coerceIn(MIN_HEIGHT, MAX_HEIGHT),
            sideMarginPercent = p.getInt("side_margin", d.sideMarginPercent).coerceIn(0, 30),
            textSizeSp = p.getInt("text_size", d.textSizeSp).coerceIn(MIN_TEXT, MAX_TEXT),
            diagnostics = p.getBoolean("diagnostics", d.diagnostics)
        )
    }

    fun update(transform: (Config) -> Config) {
        val c = transform(_config.value).let {
            it.copy(
                ocrHeightPercent = it.ocrHeightPercent.coerceIn(MIN_HEIGHT, MAX_HEIGHT),
                sideMarginPercent = it.sideMarginPercent.coerceIn(0, 30),
                textSizeSp = it.textSizeSp.coerceIn(MIN_TEXT, MAX_TEXT)
            )
        }
        _config.value = c
        prefs?.edit()
            ?.putInt("ocr_height", c.ocrHeightPercent)
            ?.putInt("side_margin", c.sideMarginPercent)
            ?.putInt("text_size", c.textSizeSp)
            ?.putBoolean("diagnostics", c.diagnostics)
            ?.apply()
    }
}

/**
 * Servis ↔ TV ekranı arasındaki durum (aynı süreç). TV ekranı bunu gösterir; servis yazar.
 */
object TvCaptureStatus {

    enum class Verdict {
        /** Servis çalışmıyor */
        IDLE,
        /** Yakalama başladı, henüz yeterli kare yok */
        STARTING,
        /** Kareler geliyor ve OCR alanında görüntü var */
        OK,
        /** OCR alanı uzun süredir tamamen siyah: HDMI içeriği korumalı/yakalanamıyor olabilir */
        BLACK,
        /** OCR alanı şeffaf: görüntü donanım katmanında, ekran yakalamaya hiç girmiyor */
        TRANSPARENT,
        /** Ekran yakalamadan hiç kare gelmiyor */
        NO_FRAMES
    }

    data class Snapshot(
        val running: Boolean = false,
        val verdict: Verdict = Verdict.IDLE,
        val captureWidth: Int = 0,
        val captureHeight: Int = 0,
        val fps: Float = 0f,
        /** OCR alanındaki en parlak örnek (0..255) */
        val maxLuma: Int = 0,
        val lastOcr: String = "",
        val lastTranslation: String = "",
        val error: String? = null
    )

    val state = MutableStateFlow(Snapshot())

    /** OCR alanının son küçük önizlemesi (TV ekranında "yakalama gerçekten ne görüyor" kontrolü için) */
    val preview = MutableStateFlow<android.graphics.Bitmap?>(null)

    fun update(transform: (Snapshot) -> Snapshot) {
        state.value = transform(state.value)
    }

    fun verdictText(verdict: Verdict): String = when (verdict) {
        Verdict.IDLE -> "Kapalı"
        Verdict.STARTING -> "Başlatılıyor…"
        Verdict.OK -> "Yakalama çalışıyor"
        Verdict.BLACK -> "Görüntü SİYAH geliyor: HDMI içeriği korumalı olabilir (aşağıya bakın)"
        Verdict.TRANSPARENT -> "Görüntü ŞEFFAF geliyor: HDMI donanım katmanında, ekran yakalamaya girmiyor"
        Verdict.NO_FRAMES -> "Kare gelmiyor: ekran yakalama engellenmiş olabilir"
    }
}
