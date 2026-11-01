package com.example.cameratranslator

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Android TV'de (HDMI girişi dahil) her şeyin üstünde duran çeviri şeridi + teşhis satırı.
 *
 * Şerit OCR alanının hemen ÜSTÜNE konur: ekran yakalama kendi penceremizi de görür, şerit
 * orijinal altyazının üstüne binseydi Türkçe çeviri tekrar OCR'a girerdi.
 * Pencereler dokunulamaz/odaklanamaz: kumanda tuşları oyuna/TV girişine gitmeye devam eder.
 */
class TvOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var translationView: TextView? = null
    private var diagnosticsView: TextView? = null
    private var config = TvSettings.Config()

    fun canShow(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun show(initial: TvSettings.Config) {
        config = initial
        if (!canShow()) {
            Log.w(TAG, "Overlay izni yok; çeviri ekranda gösterilemeyecek")
            return
        }
        if (translationView != null) return
        try {
            val tv = TextView(context).apply {
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 4
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
                background = GradientDrawable().apply {
                    setColor(0xD9000000.toInt())
                    cornerRadius = dp(10).toFloat()
                }
                setPadding(dp(24), dp(10), dp(24), dp(12))
                visibility = View.GONE
            }
            windowManager.addView(tv, translationParams())
            translationView = tv

            val diag = TextView(context).apply {
                setTextColor(0xFFFFEB3B.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setBackgroundColor(0x99000000.toInt())
                setPadding(dp(8), dp(4), dp(8), dp(4))
                visibility = if (config.diagnostics) View.VISIBLE else View.GONE
            }
            windowManager.addView(diag, diagnosticsParams())
            diagnosticsView = diag
            applyConfig(config)
        } catch (e: Exception) {
            Log.e(TAG, "Overlay eklenemedi", e)
            hide()
        }
    }

    fun applyConfig(newConfig: TvSettings.Config) {
        config = newConfig
        translationView?.let { view ->
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, newConfig.textSizeSp.toFloat())
            runCatching { windowManager.updateViewLayout(view, translationParams()) }
        }
        diagnosticsView?.visibility = if (newConfig.diagnostics) View.VISIBLE else View.GONE
    }

    fun showTranslation(text: String) {
        val view = translationView ?: return
        view.text = text
        view.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    fun clearTranslation() = showTranslation("")

    fun currentTranslation(): String = translationView?.text?.toString().orEmpty()

    fun showDiagnostics(text: String) {
        val view = diagnosticsView ?: return
        if (view.text?.toString() != text) view.text = text
    }

    fun hide() {
        translationView?.let { runCatching { windowManager.removeView(it) } }
        diagnosticsView?.let { runCatching { windowManager.removeView(it) } }
        translationView = null
        diagnosticsView = null
    }

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    private fun baseFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun translationParams(): WindowManager.LayoutParams {
        val screenHeight = context.resources.displayMetrics.heightPixels
        val screenWidth = context.resources.displayMetrics.widthPixels
        return WindowManager.LayoutParams(
            (screenWidth * 0.84f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            baseFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // OCR alanının üst kenarının biraz üstü
            y = (screenHeight * config.ocrHeightPercent / 100f).toInt() + dp(8)
        }
    }

    private fun diagnosticsParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            baseFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(16)
        }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "TV_OVERLAY"
    }
}
