package com.example.cameratranslator

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Android TV ana ekranı (kumanda/D-pad ile kullanılır). Çeviriyi başlatır/durdurur,
 * OCR alanını ve yazı boyutunu ayarlar, HDMI girişine geçer ve yakalama teşhisini gösterir.
 */
class TvActivity : ComponentActivity() {

    private lateinit var settingsManager: SettingsManager
    private var overlayGranted by mutableStateOf(false)
    private var translationMode by mutableStateOf(TranslationMode.OFFLINE)

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                TvTranslationService.start(this, result.resultCode, data)
                Toast.makeText(this, "Çeviri başladı. Şimdi oyunun HDMI girişine geçin.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Ekran yakalama izni verilmedi", Toast.LENGTH_LONG).show()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestProjection() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)
        TvSettings.init(this)
        OfflineModelDownloader.init(this)
        translationMode = settingsManager.getTranslationMode()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TvScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun startTranslation() {
        if (!overlayGranted) {
            Toast.makeText(this, "Önce ekran üstü gösterim (overlay) iznini verin", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Bildirim izni şart değil (servis yine çalışır); sonuç ne olursa olsun devam edilir
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Tek uygulama değil, tüm ekran: HDMI girişi başka bir uygulamada (TV girişi) açılacak
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        try {
            projectionLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Bu TV ekran yakalamayı desteklemiyor", Toast.LENGTH_LONG).show()
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Bu TV'de ayar ekranı yok; aşağıdaki adb komutunu kullanın", Toast.LENGTH_LONG).show()
        }
    }

    private fun hdmiInputs(): List<TvInputInfo> {
        val tim = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        return try {
            tim.tvInputList.filter { it.isPassthroughInput }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun openInput(input: TvInputInfo) {
        val intent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Giriş açılamadı; kumandanın Kaynak/Input tuşunu kullanın", Toast.LENGTH_LONG).show()
        }
    }

    private fun cycleTranslationMode() {
        val modes = buildList {
            add(TranslationMode.OFFLINE)
            add(TranslationMode.FREE)
            if (settingsManager.getCloudModeEnabled()) add(TranslationMode.CLOUD)
        }
        val next = modes[(modes.indexOf(translationMode) + 1) % modes.size]
        translationMode = next
        settingsManager.saveTranslationMode(next)
        if (TvCaptureStatus.state.value.running) {
            Toast.makeText(this, "Yeni motor bir sonraki başlatmada kullanılır", Toast.LENGTH_SHORT).show()
        }
    }

    private fun modeLabel(mode: TranslationMode): String = when (mode) {
        TranslationMode.OFFLINE -> "⚡ OPUS (internetsiz)"
        TranslationMode.FREE -> "🌐 Google Translate"
        TranslationMode.CLOUD -> "☁️ Google Cloud"
    }

    @Composable
    private fun TvScreen() {
        val status by TvCaptureStatus.state.collectAsStateWithLifecycle()
        val preview by TvCaptureStatus.preview.collectAsStateWithLifecycle()
        val config by TvSettings.config.collectAsStateWithLifecycle()
        val modelState by OfflineModelDownloader.state.collectAsStateWithLifecycle()
        val inputs = remember { hdmiInputs() }
        val firstFocus = remember { FocusRequester() }

        LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF020817))
                // TV güvenli alanı (overscan): kenarlardan ~%5
                .padding(horizontal = 48.dp, vertical = 27.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            // Sol: kontroller
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Camera Translator TV", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Ekranın altındaki İngilizce yazıyı okur, Türkçeye çevirir", fontSize = 14.sp, color = Color(0xFF94A3B8))
                Spacer(Modifier.height(4.dp))

                if (status.running) {
                    TvButton("■ Çeviriyi durdur", Modifier.focusRequester(firstFocus), accent = Color(0xFFDC2626)) {
                        TvTranslationService.stop(this@TvActivity)
                    }
                } else {
                    TvButton("▶ Çeviriyi başlat", Modifier.focusRequester(firstFocus), accent = Color(0xFF16A34A)) {
                        startTranslation()
                    }
                }

                inputs.forEach { input ->
                    val label = input.loadLabel(this@TvActivity)?.toString() ?: input.id
                    TvButton("📺 $label girişine geç") { openInput(input) }
                }

                if (!overlayGranted) {
                    TvButton("🔓 Ekran üstü gösterim izni ver", accent = Color(0xFFD97706)) { openOverlaySettings() }
                }

                TvStepper(
                    label = "OCR alanı (alttan): %${config.ocrHeightPercent}",
                    onMinus = { TvSettings.update { it.copy(ocrHeightPercent = it.ocrHeightPercent - 2) } },
                    onPlus = { TvSettings.update { it.copy(ocrHeightPercent = it.ocrHeightPercent + 2) } }
                )
                TvStepper(
                    label = "Yan boşluk: %${config.sideMarginPercent}",
                    onMinus = { TvSettings.update { it.copy(sideMarginPercent = it.sideMarginPercent - 2) } },
                    onPlus = { TvSettings.update { it.copy(sideMarginPercent = it.sideMarginPercent + 2) } }
                )
                TvStepper(
                    label = "Çeviri yazı boyutu: ${config.textSizeSp}",
                    onMinus = { TvSettings.update { it.copy(textSizeSp = it.textSizeSp - 2) } },
                    onPlus = { TvSettings.update { it.copy(textSizeSp = it.textSizeSp + 2) } }
                )
                TvButton("Çeviri motoru: ${modeLabel(translationMode)}") { cycleTranslationMode() }

                if (translationMode == TranslationMode.OFFLINE) {
                    when (val s = modelState) {
                        is OfflineModelDownloader.State.Installed -> Unit
                        is OfflineModelDownloader.State.Downloading ->
                            TvButton("OPUS indiriliyor %${(s.progress * 100).toInt()} — iptal") { OfflineModelDownloader.cancel() }
                        is OfflineModelDownloader.State.Verifying -> Text("OPUS doğrulanıyor…", color = Color.White)
                        else -> TvButton("⬇ OPUS modelini indir (355 MB)") { OfflineModelDownloader.start() }
                    }
                }

                TvButton(
                    if (config.captureMode == TvSettings.CaptureMode.CODEC) "Yakalama yöntemi: Codec (uyumluluk)"
                    else "Yakalama yöntemi: Normal"
                ) {
                    TvSettings.update {
                        it.copy(
                            captureMode = if (it.captureMode == TvSettings.CaptureMode.CODEC) TvSettings.CaptureMode.STANDARD
                            else TvSettings.CaptureMode.CODEC
                        )
                    }
                    if (TvCaptureStatus.state.value.running) {
                        Toast.makeText(this@TvActivity, "Çeviriyi durdurup yeniden başlatınca geçerli olur", Toast.LENGTH_LONG).show()
                    }
                }

                TvButton(if (config.diagnostics) "Teşhis satırı: açık" else "Teşhis satırı: kapalı") {
                    TvSettings.update { it.copy(diagnostics = !it.diagnostics) }
                }
            }

            // Sağ: durum ve teşhis
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val verdictColor = when (status.verdict) {
                    TvCaptureStatus.Verdict.OK -> Color(0xFF4ADE80)
                    TvCaptureStatus.Verdict.BLACK, TvCaptureStatus.Verdict.TRANSPARENT,
                    TvCaptureStatus.Verdict.NO_FRAMES -> Color(0xFFF87171)
                    else -> Color(0xFFCBD5E1)
                }
                Text("Durum: ${TvCaptureStatus.verdictText(status.verdict)}", color = verdictColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                status.error?.let { Text("Hata: $it", color = Color(0xFFF87171)) }
                if (status.running) {
                    Text(
                        "Yakalama (${if (status.captureMode == TvSettings.CaptureMode.CODEC) "Codec" else "Normal"}) ${status.captureWidth}x${status.captureHeight} • ${status.fps.toInt()} kare/sn • OCR alanı en parlak nokta: ${status.maxLuma}",
                        color = Color(0xFFCBD5E1), fontSize = 14.sp
                    )
                }

                Text("OCR alanının yakalanan görüntüsü:", color = Color(0xFF94A3B8), fontSize = 14.sp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 160.dp)
                        .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = preview
                    if (bmp != null && !bmp.isRecycled) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "OCR alanı",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("Henüz görüntü yok", color = Color(0xFF64748B))
                    }
                }

                if (status.lastOcr.isNotBlank()) Text("Son OCR: ${status.lastOcr}", color = Color.White)
                if (status.lastTranslation.isNotBlank()) Text("Son çeviri: ${status.lastTranslation}", color = Color(0xFF93C5FD))

                Spacer(Modifier.height(6.dp))
                Text("Nasıl kullanılır", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    "1. ▶ Çeviriyi başlat → ekran yakalama iznini onaylayın.\n" +
                        "2. Xbox'ın bağlı olduğu HDMI girişine geçin (yukarıdaki düğme ya da kumandadaki Input tuşu).\n" +
                        "3. Sol üstteki sarı teşhis satırı \"Yakalama çalışıyor\" diyorsa çeviriler altyazının hemen üstünde görünür.\n" +
                        "4. \"SİYAH\" ya da \"ŞEFFAF\" diyorsa: Çeviriyi durdurun, \"Yakalama yöntemi\"ni Codec yapıp yeniden başlatın. " +
                        "Bazı TV'ler HDMI görüntüsünü yalnızca bu yolda verir.\n" +
                        "5. Codec ile de siyahsa bu TV HDMI görüntüsünü uygulamalara vermiyor: HDMI yakalama kartı gerekir.",
                    color = Color(0xFFCBD5E1), fontSize = 14.sp
                )
                if (!overlayGranted) {
                    Text(
                        "Overlay izni ayar ekranı yoksa bilgisayardan:\n" +
                            "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow",
                        color = Color(0xFFFBBF24), fontSize = 14.sp
                    )
                }
            }
        }
    }

    @Composable
    private fun TvButton(
        text: String,
        modifier: Modifier = Modifier,
        accent: Color = Color(0xFF1E293B),
        onClick: () -> Unit
    ) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        Button(
            onClick = onClick,
            interactionSource = interaction,
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            border = if (focused) BorderStroke(3.dp, Color.White) else null,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (focused) accent.copy(alpha = 1f) else accent.copy(alpha = 0.75f),
                contentColor = Color.White
            )
        ) {
            Text(text, fontSize = if (focused) 17.sp else 16.sp)
        }
    }

    @Composable
    private fun TvStepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            TvButton("−", Modifier.width(64.dp), onClick = onMinus)
            TvButton("+", Modifier.width(64.dp), onClick = onPlus)
        }
    }
}
