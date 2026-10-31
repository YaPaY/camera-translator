package com.example.cameratranslator

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import kotlin.math.pow

/**
 * Kameraya giden Camera2 ayarlarının tek kaynağı: odak, elle pozlama (ekstra karanlık) ve
 * sabitleme. setCaptureRequestOptions her çağrıda öncekileri sildiği için hepsi birlikte gönderilir.
 *
 * Ekstra karanlık: Parlaklık kaydırıcısı telefonun pozlama telafisi sınırının altına inince
 * otomatik pozlama kapatılır ve perde hızı/ISO elle verilir. Her adım bir öncekinin yarısı kadar
 * ışık alır; başlangıç, otomatik pozlamanın en karanlık halindeki gerçek değerlerdir (sıçrama olmaz).
 * TV kendi ışığını verdiği için beyaz altyazı parlak kalır, arka plan kararır.
 */
@OptIn(ExperimentalCamera2Interop::class)
object CameraTuning {

    private const val TAG = "CameraTuning"

    /** Parlaklık kaydırıcısında telefonun sınırının altına eklenen elle karartma adımı sayısı. */
    const val EXTRA_DARK_STEPS = 6

    /** Telefon kameralarının yakın odak sınırı ~10 cm = 10 diyoptri. */
    private const val MAX_FOCUS_DIOPTERS = 10f
    /** Elle pozlamada en uzun perde: TV'nin hareketli görüntüsünde bulanıklık olmasın. */
    private const val MAX_MANUAL_EXPOSURE_NS = 33_333_333L
    /** Otomatik değerler bilinmiyorsa başlangıç: 1/60 sn, ISO 100. */
    private const val FALLBACK_EXPOSURE_NS = 16_666_667L
    private const val FALLBACK_ISO = 100

    // Otomatik pozlamanın kameradan okunan son değerleri (elle pozlamaya geçerken başlangıç)
    @Volatile private var autoExposureNs = 0L
    @Volatile private var autoIso = 0
    // Elle pozlamaya geçerken dondurulan başlangıç (elle moddayken sonuçlar artık otomatik değil)
    @Volatile private var baseExposureNs = 0L
    @Volatile private var baseIso = 0

    /** Otomatik odağın son başarılı netlediği mercek konumu (diyoptri) */
    @Volatile private var lastGoodFocusDiopters: Float? = null

    @Volatile private var exposureRange: Range<Long>? = null
    @Volatile private var isoRange: Range<Int>? = null

    private var lastKey: String? = null
    private var lastCamera: Camera? = null

    /** Önizleme isteklerinin sonuçlarından otomatik pozlama değerlerini okur. */
    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            // Otomatik odak netlediğinde mercek konumunu sakla: sonraki netleme (karanlıkta) başarısız olursa geri dönülür
            if (result.get(CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) {
                result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastGoodFocusDiopters = it }
            }
            if (result.get(CaptureResult.CONTROL_AE_MODE) == CaptureRequest.CONTROL_AE_MODE_OFF) return
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { autoExposureNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { autoIso = it }
        }
    }

    /** Kamera bağlanınca elle pozlama sınırlarını okur. */
    fun readCapabilities(camera: Camera) {
        try {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            exposureRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            Log.d(TAG, "Manual exposure range: $exposureRange ns, ISO $isoRange")
        } catch (e: Exception) {
            Log.w(TAG, "Could not read sensor ranges", e)
        }
    }

    /** Kamera elle pozlamayı destekliyor mu? (Desteklemiyorsa ekstra karanlık adımları etkisiz kalır.) */
    val supportsManualExposure: Boolean get() = exposureRange != null && isoRange != null

    /**
     * Odak, ekstra karanlık ve sabitleme ayarlarını birlikte uygular.
     * @param focusValue odak kaydırıcısı (0..1); [AUTO_FOCUS_ZONE] altı otomatik odak
     * @param darkStep 0 = otomatik pozlama, 1..[EXTRA_DARK_STEPS] = elle karartma adımı
     */
    /**
     * Otomatik netleme başarısız olduysa (çok karanlık görüntüde kontrast bulunamıyor) merceği son
     * başarılı konuma kilitler; yoksa mercek yanlış yerde kalıp altyazı bulanıklaşıyordu.
     * Bir sonraki periyodik netlemede otomatik odak yeniden denenir. Kaydedilmiş konum yoksa false.
     */
    fun restoreLastGoodFocus(camera: Camera, darkStep: Int): Boolean {
        val diopters = lastGoodFocusDiopters ?: return false
        apply(camera, AUTO_FOCUS_ZONE, darkStep, force = true, lockedDiopters = diopters)
        return true
    }

    fun apply(camera: Camera, focusValue: Float, darkStep: Int, force: Boolean = false, lockedDiopters: Float? = null) {
        val autoFocus = focusValue < AUTO_FOCUS_ZONE && lockedDiopters == null
        val step = if (supportsManualExposure) darkStep.coerceIn(0, EXTRA_DARK_STEPS) else 0
        val focusKey = when {
            lockedDiopters != null -> "lock%.3f".format(lockedDiopters)
            autoFocus -> "auto"
            else -> "%.3f".format(focusValue)
        }
        val key = "$focusKey|$step"
        if (!force && camera === lastCamera && key == lastKey) return

        // Otomatik pozlamadan elle pozlamaya geçerken başlangıcı dondur
        val wasManual = lastKey?.substringAfter('|')?.toIntOrNull()?.let { it > 0 } == true && camera === lastCamera
        if (step > 0 && !wasManual) {
            baseExposureNs = autoExposureNs.takeIf { it > 0 } ?: FALLBACK_EXPOSURE_NS
            baseIso = autoIso.takeIf { it > 0 } ?: FALLBACK_ISO
        }
        if (key != lastKey) {
            val focusText = when {
                lockedDiopters != null -> "son iyi konuma kilitlendi"
                autoFocus -> "otomatik"
                else -> "elle %.2f".format(focusValue)
            }
            OcrDebugDumper.logPipeline("Kamera ayarı", "odak $focusText, ekstra karartma $step", endOfEntry = true)
        }
        lastKey = key
        lastCamera = camera

        try {
            val builder = CaptureRequestOptions.Builder()
                // Tripodda görüntü kaymasın: optik ve dijital sabitleme kapalı
                .setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)

            if (autoFocus) {
                // Sürekli takip eden odak yerine bir kez odaklanıp sabit kalan odak (AUTO + tetikleme):
                // tripodda görüntü sürekli odak arayıp kaymasın. Tetikleme zoom sonrası, açılışta ve dokununca yapılır.
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            } else {
                // Karesel eğri: kaydırıcının büyük kısmı uzak mesafelere (TV: 2-4 m = 0.25-0.5 diyoptri) ayrılır
                val t = ((focusValue - AUTO_FOCUS_ZONE) / (1f - AUTO_FOCUS_ZONE)).coerceIn(0f, 1f)
                builder
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, lockedDiopters ?: (MAX_FOCUS_DIOPTERS * t * t))
            }

            if (step > 0) {
                val (exposureNs, iso) = manualExposure(step)
                builder
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                Log.d(TAG, "Extra dark step $step: ${exposureNs / 1000} µs, ISO $iso")
            }

            Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Applying camera options failed", e)
        }
    }

    /** Adım başına ışık yarıya iner: önce ISO en düşüğe, sonra perde kısalır. */
    private fun manualExposure(step: Int): Pair<Long, Int> {
        val exposures = exposureRange ?: return FALLBACK_EXPOSURE_NS to FALLBACK_ISO
        val isos = isoRange ?: return FALLBACK_EXPOSURE_NS to FALLBACK_ISO
        val totalLight = baseExposureNs.toDouble() * baseIso / 2.0.pow(step)
        val iso = isos.lower
        val exposureNs = (totalLight / iso).toLong()
            .coerceIn(exposures.lower, minOf(exposures.upper, MAX_MANUAL_EXPOSURE_NS))
        return exposureNs to iso
    }
}
