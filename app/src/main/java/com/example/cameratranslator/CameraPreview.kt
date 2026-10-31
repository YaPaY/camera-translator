package com.example.cameratranslator

import android.util.Size
import android.util.Log
import android.view.ViewGroup
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.content.Context
import android.os.Build
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import android.os.SystemClock
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    analyzer: ImageAnalysis.Analyzer,
    zoomLevel: Float = 1f,
    exposureIndex: Int = 0,
    manualFocusValue: Float = 0f,
    onCameraReady: (Camera) -> Unit = {},
    onPreviewViewReady: (PreviewView) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    // OCR analyzer'ına dokunmadan: her kamera karesini iletmek PreviewView.getBitmap()
    // çağrılarını main thread'de kuyruklar ve TextureView preview'sini kilitler.
    val gatedAnalyzer = remember(analyzer) {
        MainThreadSafeAnalyzerGate(analyzer)
    }
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var cameraInstance by remember { mutableStateOf<Camera?>(null) }

    // Granular rotation takibi (özellikle 180 derece flip için)
    var displayRotation by remember { mutableIntStateOf(Surface.ROTATION_0) }

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.display.rotation
                } else {
                    @Suppress("DEPRECATION")
                    (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
                }
                if (rotation != displayRotation) {
                    displayRotation = rotation
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    LaunchedEffect(previewView) {
        onPreviewViewReady(previewView)
    }

    // Kamera kurulumu ve UseCase bağlama (Başlangıçta ve her 90/180 derece dönüşte çalışır)
    LaunchedEffect(displayRotation) {
        // Bloklamadan bekle: kamera servisi yavaş açılırsa (ör. bellek sıkışıkken) ana iş parçacığı donup ANR veriyordu
        val cameraProvider = ProcessCameraProvider.awaitInstance(context)
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        val rotation = displayRotation

        while (previewView.width == 0 || previewView.height == 0 || previewView.viewPort == null) {
            delay(16)
        }

        val previewBuilder = Preview.Builder().setTargetRotation(rotation)
        // Otomatik pozlamanın gerçek değerlerini oku (ekstra karanlığa geçişte sıçrama olmasın)
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(CameraTuning.captureCallback)
        val preview = previewBuilder
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetRotation(rotation)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        // 1080p: zoom yapılmış TV altyazısında harf kenarları 720p'ye göre belirgin şekilde daha net.
                        // 4K denendi: S23 Ultra önizlemeyle birlikte en fazla 1920x1440 veriyor (genişlik aynı), fayda yok.
                        ResolutionStrategy(
                            Size(1920, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            // RGBA çıktısı: YUV -> JPEG -> Bitmap dönüşümündeki sıkıştırma bozulmaları ortadan kalkar.
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, gatedAnalyzer) }

        try {
            cameraProvider.unbindAll()
            val viewPort = previewView.viewPort!!
            val useCaseGroup = UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(preview)
                .addUseCase(imageAnalysis)
                .build()
            
            val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroup)
            Log.d(TAG, "Analysis resolution: ${imageAnalysis.resolutionInfo?.resolution}")

            // İlk değerleri uygula
            CameraTuning.readCapabilities(camera)
            camera.cameraControl.setZoomRatio(zoomLevel)
            applyExposure(camera, exposureIndex)
            
            cameraInstance = camera
            onCameraReady(camera)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    // Zoom ayrı uygulanır: kaydırıcı sürüklenirken pozlama/odak her seferinde yeniden
    // gönderilince (ve odak 200 ms sonra zorla tekrar uygulanınca) görüntü takılıyordu
    val currentZoomLevel by rememberUpdatedState(zoomLevel)
    val currentFocusValue by rememberUpdatedState(manualFocusValue)
    val currentExposureIndex by rememberUpdatedState(exposureIndex)
    LaunchedEffect(cameraInstance, zoomLevel) {
        val camera = cameraInstance ?: return@LaunchedEffect
        camera.cameraControl.setZoomRatio(zoomLevel)
        // Zoom değişince telefon başka bir lense geçebiliyor (S23 Ultra: 1x/3x/10x) ve sürekli
        // otomatik odak bazen takılı kalıp yazılar bulanık kalıyordu (elle "Oto"ya alınca düzeliyordu).
        // Kaydırma bitince (her değişimde efekt yeniden başladığı için ancak durunca buraya gelinir)
        // odak Oto'daysa odağı bir kez yeniden tetikle. Elle odak ayarlıysa dokunma.
        delay(REFOCUS_AFTER_ZOOM_MS)
        OcrDebugDumper.logPipeline("Zoom", "%.2fx".format(zoomLevel), endOfEntry = true)
        if (currentFocusValue < AUTO_FOCUS_ZONE) refocusCenter(camera, darkStepFor(camera, currentExposureIndex))
    }

    // Kamera Ayarlarını (Pozlama ve Manuel Odak) Senkronize Et: değer değişince yalnızca bir kez gönder
    LaunchedEffect(cameraInstance, exposureIndex, manualFocusValue) {
        val camera = cameraInstance ?: return@LaunchedEffect
        applyExposure(camera, exposureIndex)
        CameraTuning.apply(camera, manualFocusValue, darkStepFor(camera, exposureIndex))
    }

    // Uygulamaya her dönüşte (Resume) ayarları tazele. Ayrı efekt: eskiden yukarıdaki efektin içindeydi
    // ve kaydırıcının her değişiminde 200 ms sonra her şey zorla tekrar gönderilip odak yeniden
    // tetikleniyordu; mor (ekstra karanlık) bölgede ayar yaparken görüntü bir an aydınlanıp kararıyordu.
    LaunchedEffect(cameraInstance) {
        val camera = cameraInstance ?: return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(200)
            camera.cameraControl.setZoomRatio(currentZoomLevel)
            applyExposure(camera, currentExposureIndex)
            val darkStep = darkStepFor(camera, currentExposureIndex)
            CameraTuning.apply(camera, currentFocusValue, darkStep, force = true)
        }
    }

    // Otomatik odak sürekli değil (bir kez odaklanıp sabit kalır). Oto'ya geçince hemen, sonra da
    // belirli aralıklarla yeniden odakla: elle odaktan Oto'ya dönünce tetikleme yapılmadığı için
    // lens eski yerinde kalıyor ve görüntü bulanık kalıyordu. Uygulamaya dönünce de baştan başlar.
    val autoFocus = manualFocusValue < AUTO_FOCUS_ZONE
    LaunchedEffect(cameraInstance, autoFocus) {
        val camera = cameraInstance ?: return@LaunchedEffect
        if (!autoFocus) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(AUTO_REFOCUS_DELAY_MS)
            refocusCenter(camera, darkStepFor(camera, currentExposureIndex))
            while (true) {
                delay(PERIODIC_REFOCUS_MS)
                // Ekstra karartmada periyodik netleme yok: karanlık görüntüde odak bazen "netlendi" deyip
                // yanlış yere netliyor ve altyazı 30 sn okunamıyordu (testte görüldü). Mercek yerinde kalır.
                val darkStep = darkStepFor(camera, currentExposureIndex)
                if (darkStep == 0) refocusCenter(camera, darkStep)
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

private const val REFOCUS_AFTER_ZOOM_MS = 400L
/** Oto odağa geçtikten / uygulamaya döndükten sonra ilk odaklama (ayarların kameraya ulaşması için kısa bekleme). */
private const val AUTO_REFOCUS_DELAY_MS = 300L
/** Oto odakta arada bir yeniden odaklama: zoom ya da ortam değişse de görüntü bulanık kalmasın. */
private const val PERIODIC_REFOCUS_MS = 20_000L
/** Karanlıktan normal parlaklığa geçince otomatik pozlamanın oturması için netlemeden önce bekleme */
private const val DARK_REFOCUS_EXPOSURE_MS = 500L

/**
 * Ortadaki noktaya bir kez odaklanır ve odağı orada sabitler (tripodda görüntü odak arayıp
 * kaymasın). Zoom bittikten sonra, uygulamaya dönünce ve açılışta çağrılır.
 */
private suspend fun refocusCenter(camera: Camera, darkStep: Int) {
    try {
        // Ekstra karartmadaysa netleme bir anlığına normal parlaklıkta yapılır (karanlıkta odak yanılıyor),
        // bitince karanlığa dönülür. Yalnızca Oto'ya geçince, zoom sonrası ve uygulamaya dönünce olur.
        CameraTuning.apply(camera, 0f, 0, force = true)
        if (darkStep > 0) delay(DARK_REFOCUS_EXPOSURE_MS)
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .disableAutoCancel()
            .build()
        val future = camera.cameraControl.startFocusAndMetering(action)
        // Geliştirici modu logu: bulanık görüntüde odağın denenip denenmediği / tutup tutmadığı görülsün
        future.addListener({
            val success = try { future.get().isFocusSuccessful } catch (e: Exception) { null }
            OcrDebugDumper.logPipeline("Odak", when (success) { true -> "netlendi"; false -> "NETLENEMEDİ"; null -> "iptal" }, endOfEntry = true)
            // Netleme tutmazsa mercek yanlış yerde kalmasın: son başarılı konuma dön (karartma da geri gelir)
            if (success == false && CameraTuning.restoreLastGoodFocus(camera, darkStep)) {
                OcrDebugDumper.logPipeline("Odak", "son iyi konuma geri dönüldü", endOfEntry = true)
            } else if (darkStep > 0) {
                CameraTuning.apply(camera, 0f, darkStep, force = true)
            }
        }, Runnable::run)
    } catch (e: Exception) {
        Log.e("CameraPreview", "Refocus failed", e)
    }
}

/** Parlaklık kaydırıcısı telefonun pozlama telafisi sınırının altındaysa elle karartma adımı (1..), değilse 0. */
private fun darkStepFor(camera: Camera, exposureIndex: Int): Int {
    val lower = camera.cameraInfo.exposureState.exposureCompensationRange.lower
    return (lower - exposureIndex).coerceIn(0, CameraTuning.EXTRA_DARK_STEPS)
}

/**
 * Pozlama telafisini telefonun aralığında uygular (altındaki adımlar elle pozlamayla yapılır).
 * Değer zaten aynıysa gönderilmez: mor bölgede kaydırırken hep en alt değer kalır, her seferinde
 * yeniden göndermek kameraya gereksiz istek yolluyordu.
 */
private fun applyExposure(camera: Camera, exposureIndex: Int) {
    val state = camera.cameraInfo.exposureState
    val target = exposureIndex.coerceIn(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper)
    if (state.exposureCompensationIndex != target) camera.cameraControl.setExposureCompensationIndex(target)
}

/** Odak kaydırıcısının alt kısmı: bu değerin altı otomatik odak (AF). */
const val AUTO_FOCUS_ZONE = 0.08f

/**
 * OCR sınıfını değiştirmeden ImageAnalysis karelerini seyreltir.
 * Çeviri açıkken PreviewView.getBitmap() her frame'de main thread'e basılmaz.
 */
private class MainThreadSafeAnalyzerGate(
    private val delegate: ImageAnalysis.Analyzer
) : ImageAnalysis.Analyzer {
    private val lastForwardedAt = AtomicLong(0)

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastForwardedAt.get()
        if (now - previous < MIN_FORWARD_INTERVAL_MS) {
            image.close()
            return
        }
        if (!lastForwardedAt.compareAndSet(previous, now)) {
            image.close()
            return
        }
        delegate.analyze(image)
    }

    companion object {
        private const val MIN_FORWARD_INTERVAL_MS = 150L
    }
}

private const val TAG = "CameraPreview"
