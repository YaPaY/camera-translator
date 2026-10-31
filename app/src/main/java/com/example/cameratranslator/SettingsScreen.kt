package com.example.cameratranslator

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import com.example.cameratranslator.ui.theme.SectionCardStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
private fun SettingsBlockCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = SectionCardStyle.cardColor(darkAlpha = 0.45f),
        border = SectionCardStyle.cardBorder(),
        shadowElevation = SectionCardStyle.cardShadow()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

/**
 * Açılır kapanır ayar bölümü başlığı: başlık, sağda ok ve (isteğe bağlı) altında kısa açıklama.
 * Ayarlar ekranı kalabalık görünmesin diye bazı bölümler kapalı başlar.
 */
@Composable
private fun CollapsibleHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
    badge: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (badge != null) {
            Text(
                badge,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Gizle" else "Göster",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OfflineModelSection(
    state: OfflineModelDownloader.State,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val totalMb = OfflineModelDownloader.totalBytes / 1_000_000
    var showMeteredDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val startDownload: () -> Unit = {
        if (OfflineModelDownloader.isOnMeteredNetwork(context)) {
            showMeteredDialog = true
        } else {
            onDownload()
        }
    }

    // Model yüklüyse açıklama gizli başlar (ayarlar ekranında yer kaplamasın), oka basınca açılır
    val isInstalled = state is OfflineModelDownloader.State.Installed
    var isInfoExpanded by remember { mutableStateOf(!isInstalled) }
    CollapsibleHeader(
        title = "⚡ OPUS Çeviri Motoru",
        expanded = isInfoExpanded,
        onToggle = { isInfoExpanded = !isInfoExpanded },
        badge = if (isInstalled) "İndirildi" else null
    )
    if (isInfoExpanded) {
        Text(
            "Bir kez indirin ($totalMb MB), sonra internet olmadan ücretsiz İngilizce → Türkçe çeviri yapın. API anahtarı, kota veya ücret yok. Argo ve deyimlerde Google Translate kadar doğru olmayabilir. Türkçe → İngilizce yönünde standart çevrimdışı motor kullanılır.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    when (state) {
        is OfflineModelDownloader.State.NotInstalled -> {
            Button(onClick = startDownload, modifier = Modifier.fillMaxWidth()) {
                Text("İndir ($totalMb MB)")
            }
        }
        is OfflineModelDownloader.State.Downloading -> {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "%${(state.progress * 100).toInt()} • ${state.downloadedBytes / 1_000_000} / $totalMb MB",
                    fontSize = 13.sp
                )
                OutlinedButton(onClick = onCancel) { Text("İptal") }
            }
        }
        is OfflineModelDownloader.State.Verifying -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Dosyalar doğrulanıyor...", fontSize = 13.sp)
        }
        is OfflineModelDownloader.State.Installed -> Unit // durum başlıkta ("İndirildi"); Sil açılınca
        is OfflineModelDownloader.State.Failed -> {
            Text("⚠️ ${state.message}", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            Button(onClick = startDownload, modifier = Modifier.fillMaxWidth()) {
                Text("Tekrar dene (kaldığı yerden devam eder)")
            }
        }
    }

    if (isInfoExpanded && isInstalled) {
        // Dikkatli çeviri (beam search): daha doğal ama biraz daha yavaş; bilerek bu açılır bölümde
        var careful by remember { mutableStateOf(OcrTuning.opusCarefulEnabled) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Açıklama metni anahtara değmesin
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Dikkatli çeviri", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Birkaç çeviri seçeneğini deneyip en iyisini seçer. Çeviriler daha doğal olur, ancak biraz daha geç gelir.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = careful, onCheckedChange = {
                careful = it
                OcrTuning.opusCarefulEnabled = it
                SettingsManager(context).saveOpusCarefulEnabled(it)
            })
        }
    }

    if (isInfoExpanded) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Model: Helsinki-NLP OPUS-MT (opus-mt-tc-big-en-tr), CC-BY 4.0",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (isInstalled) {
                TextButton(onClick = { showDeleteDialog = true }) { Text("Modeli sil") }
            }
        }
    }

    if (showMeteredDialog) {
        AlertDialog(
            onDismissRequest = { showMeteredDialog = false },
            title = { Text("Mobil veri") },
            text = { Text("Wi-Fi'a bağlı değilsiniz. Model $totalMb MB; mobil veriyle indirmek istiyor musunuz?") },
            confirmButton = {
                TextButton(onClick = {
                    showMeteredDialog = false
                    onDownload()
                }) { Text("İndir") }
            },
            dismissButton = { TextButton(onClick = { showMeteredDialog = false }) { Text("Vazgeç") } }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Modeli sil") },
            text = { Text("OPUS modeli silinsin mi? Tekrar kullanmak için yeniden indirmeniz gerekir ($totalMb MB).") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) { Text("Sil") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Vazgeç") } }
        )
    }
}

private sealed class UpdateCheckState {
    object Idle : UpdateCheckState()
    object Checking : UpdateCheckState()
    object UpToDate : UpdateCheckState()
    data class Available(val release: UpdateChecker.ReleaseInfo) : UpdateCheckState()
    object Failed : UpdateCheckState()
}

@Composable
private fun UpdatesSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }

    fun openPage(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Log.e("SettingsScreen", "Error opening GitHub releases link", e)
        }
    }

    Text("Güncellemeler", fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Text(
        text = "Yüklü sürüm: v${UpdateChecker.currentVersion}. Yeni bir sürüm yayınlandığında uygulama açılışta sizi bir kez bilgilendirir; dilediğiniz zaman buradan da kontrol edebilirsiniz.",
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    when (val s = state) {
        is UpdateCheckState.Available -> {
            Text(
                "🎉 Yeni sürüm mevcut: v${s.release.version}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Button(
                onClick = { openPage(s.release.pageUrl) },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Yeni sürümü indir", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        is UpdateCheckState.UpToDate ->
            Text("✅ En güncel sürümü kullanıyorsunuz.", fontSize = 14.sp)
        is UpdateCheckState.Failed ->
            Text(
                "⚠️ Kontrol edilemedi. İnternet bağlantınızı kontrol edip tekrar deneyin.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error
            )
        else -> Unit
    }

    if (state !is UpdateCheckState.Available) {
        OutlinedButton(
            onClick = {
                state = UpdateCheckState.Checking
                scope.launch {
                    state = try {
                        val latest = UpdateChecker.fetchLatest()
                        if (UpdateChecker.isNewer(latest.version, UpdateChecker.currentVersion)) {
                            UpdateCheckState.Available(latest)
                        } else {
                            UpdateCheckState.UpToDate
                        }
                    } catch (e: Exception) {
                        Log.w("SettingsScreen", "Update check failed: ${e.message}")
                        UpdateCheckState.Failed
                    }
                }
            },
            enabled = state !is UpdateCheckState.Checking,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (state is UpdateCheckState.Checking) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Kontrol ediliyor...", fontSize = 14.sp)
            } else {
                Text("Güncellemeleri kontrol et 🔄", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    TextButton(onClick = { openPage(UpdateChecker.RELEASES_PAGE) }, modifier = Modifier.fillMaxWidth()) {
        Text("Tüm sürümleri GitHub'da gör", fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentTheme: AppTheme,
    currentApiKey: String,
    currentTtsApiKey: String = "",
    subtitleFontSize: Float = 38f,
    currentDisplayMode: DisplayMode = DisplayMode.DEFAULT,
    showDisplayModeQuickSwitch: Boolean = false,
    isOcrTextMaskEnabled: Boolean = false,
    isOcrCenterOnlyEnabled: Boolean = true,
    onOcrCenterOnlyEnabledChange: (Boolean) -> Unit = {},
    offlineModelState: OfflineModelDownloader.State = OfflineModelDownloader.State.NotInstalled,
    onDownloadOfflineModel: () -> Unit = {},
    onCancelOfflineModelDownload: () -> Unit = {},
    onDeleteOfflineModel: () -> Unit = {},
    isTtsEnabled: Boolean = false,
    currentTtsEngineType: TtsEngineType = TtsEngineType.NATIVE,
    speechRate: Float = 1.15f,
    cloudSpeechRate: Float = 1.0f,
    speechPitch: Float = 1.0f,
    currentVoiceGender: VoiceGender = VoiceGender.FEMALE,
    isTrToEn: Boolean = false,
    currentTrCloudVoice: String = "tr-TR-Chirp3-HD-Kore",
    currentEnCloudVoice: String = "en-US-Chirp3-HD-Kore",
    onThemeChange: (AppTheme) -> Unit,
    onSubtitleFontSizeChange: (Float) -> Unit = {},
    onDisplayModeChange: (DisplayMode) -> Unit = {},
    onShowDisplayModeQuickSwitchChange: (Boolean) -> Unit = {},
    onOcrTextMaskEnabledChange: (Boolean) -> Unit = {},
    onApiKeySave: (String) -> Unit,
    isCloudModeEnabled: Boolean = false,
    onCloudModeEnabledChange: (Boolean) -> Unit = {},
    onTtsApiKeySave: (String) -> Unit = {},
    onTtsEnabledChange: (Boolean) -> Unit = {},
    onTtsEngineTypeChange: (TtsEngineType) -> Unit = {},
    onSpeechRateChange: (Float) -> Unit = {},
    onCloudSpeechRateChange: (Float) -> Unit = {},
    onSpeechPitchChange: (Float) -> Unit = {},
    onVoiceGenderChange: (VoiceGender) -> Unit = {},
    onTrCloudVoiceSave: (String) -> Unit = {},
    onEnCloudVoiceSave: (String) -> Unit = {},
    onTestCustomSpeech: (String) -> Unit = {},
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(currentApiKey) }
    var savedApiKey by remember(currentApiKey) { mutableStateOf(currentApiKey) }

    var ttsApiKey by remember { mutableStateOf(currentTtsApiKey) }
    var savedTtsApiKey by remember(currentTtsApiKey) { mutableStateOf(currentTtsApiKey) }

    var isApiKeyVisible by remember { mutableStateOf(false) }
    var isTtsApiKeyVisible by remember { mutableStateOf(false) }

    var customTestText by remember { mutableStateOf("") }
    val zoomSettings = remember { SettingsManager(context) }
    var isDisplaySectionExpanded by rememberSaveable { mutableStateOf(false) }
    var isOcrSectionExpanded by rememberSaveable { mutableStateOf(false) }
    var useZoomSlider by remember { mutableStateOf(zoomSettings.getZoomSliderEnabled()) }

    val isTtsKeySaved = ttsApiKey.isNotBlank() && ttsApiKey == savedTtsApiKey
    val isApiKeySaved = apiKey.isNotBlank() && apiKey == savedApiKey

    fun formatVoiceDisplayLabel(voiceId: String): String {
        val cleanName = voiceId
            .removePrefix("tr-TR-")
            .removePrefix("en-US-")
            .removePrefix("Chirp3-HD-")
            .removePrefix("Chirp-HD-")
        val voiceObj = CloudVoiceRegistry.getVoiceById(voiceId)
        val genderStr = if (voiceObj?.gender == VoiceGender.FEMALE) "Kadın" else "Erkek"
        return "Chirp 3: HD • $cleanName • $genderStr"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ayarlar", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SectionCardStyle.screenBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = SectionCardStyle.screenBackground(),
        modifier = Modifier.imePadding()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // BLOCK: OPUS Çeviri Modeli
            SettingsBlockCard {
                OfflineModelSection(
                    state = offlineModelState,
                    onDownload = onDownloadOfflineModel,
                    onCancel = onCancelOfflineModelDownload,
                    onDelete = onDeleteOfflineModel
                )
            }

            // BLOCK 3: Görünüm Modu (Display Mode)
            SettingsBlockCard {
                CollapsibleHeader(
                    title = "Görünüm Modu",
                    subtitle = "Altyazının ekranda nasıl gösterileceği, çeviri yazı boyutu ve yakınlaştırma kontrolü",
                    expanded = isDisplaySectionExpanded,
                    onToggle = { isDisplaySectionExpanded = !isDisplaySectionExpanded }
                )
                if (isDisplaySectionExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SectionCardStyle.segmentBackground(), RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DisplayMode.entries.forEach { mode ->
                        val isSelected = mode == currentDisplayMode
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable { onDisplayModeChange(mode) },
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = when (mode) {
                                        DisplayMode.DEFAULT -> "Sinematik Altyazı"
                                        DisplayMode.SCREEN_BETA -> "Ekran Çevirisi"
                                    },
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                // Show Quick Switcher on Main Screen Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Görünüm modu butonunu ana ekranda göster", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Switch(
                        checked = showDisplayModeQuickSwitch,
                        onCheckedChange = { onShowDisplayModeQuickSwitchChange(it) }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // Çeviri yazı boyutu (seçili görünüm moduna ayrı kaydedilir)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Çeviri yazı boyutu", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = "${subtitleFontSize.toInt()} sp",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = subtitleFontSize,
                    onValueChange = { onSubtitleFontSizeChange(it) },
                    // 2 sp adımlarla 16–80 (24 sp bazılarına büyük geliyordu)
                    valueRange = 16f..80f,
                    steps = 31,
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // Yakınlaştırma kontrolü: kaydırıcı ya da sabit düğmeler
                Text("Yakınlaştırma kontrolü", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SectionCardStyle.segmentBackground(), RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(true to "Kaydırıcı", false to "Düğmeler (1x–5x)").forEach { (slider, label) ->
                        val isSelected = slider == useZoomSlider
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable {
                                    useZoomSlider = slider
                                    zoomSettings.saveZoomSliderEnabled(slider)
                                },
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = label,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
                Text(
                    if (useZoomSlider) {
                        "Parmağınızla yukarı/aşağı kaydırın: 1x'in altı geniş açı, en üstü telefonun kamerasının izin verdiği en yüksek yakınlaştırma. Kaydırıcı gerçek lenslere (1x, 3x, 10x) hafifçe yapışır; görüntü en net bu değerlerdedir."
                    } else {
                        "Sol tarafta 1x–5x arası sabit yakınlaştırma düğmeleri."
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                }
            }

            // BLOCK 4: Sesli Okuma (Speech Reading)
            SettingsBlockCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Sesli Okuma", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Çevrilen altyazıları sesli okur; ekrana bakmadan oyunu takip edebilirsiniz.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isTtsEnabled,
                        onCheckedChange = { onTtsEnabledChange(it) }
                    )
                }

                if (isTtsEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // TTS Engine Selection
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Konuşma Motoru", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SectionCardStyle.segmentBackground(), RoundedCornerShape(12.dp))
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                TtsEngineType.entries.forEach { engine ->
                                    val isSelected = engine == currentTtsEngineType
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(40.dp)
                                            .clickable { onTtsEngineTypeChange(engine) },
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = when (engine) {
                                                    TtsEngineType.NATIVE -> "Google Translate"
                                                    TtsEngineType.CLOUD_TTS -> "Cloud Text-to-Speech"
                                                },
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Cloud TTS API Key Input Field (Only visible when CLOUD_TTS is selected)
                        if (currentTtsEngineType == TtsEngineType.CLOUD_TTS) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Cloud Text-to-Speech API", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                OutlinedTextField(
                                    value = ttsApiKey,
                                    onValueChange = { ttsApiKey = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    visualTransformation = if (isTtsApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation('•'),
                                    trailingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .padding(end = 4.dp)
                                                .size(48.dp)
                                                .pointerInput(Unit) {
                                                    awaitPointerEventScope {
                                                        while (true) {
                                                            val event = awaitPointerEvent()
                                                            if (event.type == PointerEventType.Press) {
                                                                isTtsApiKeyVisible = true
                                                            } else if (event.type == PointerEventType.Release || 
                                                                       event.type == PointerEventType.Exit) {
                                                                isTtsApiKeyVisible = false
                                                            }
                                                            event.changes.forEach { it.consume() }
                                                        }
                                                    }
                                                }
                                        ) {
                                            Icon(
                                                imageVector = if (isTtsApiKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                                contentDescription = "Göster/Gizle",
                                                modifier = Modifier.align(Alignment.Center)
                                            )
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                )

                                if (!isTtsKeySaved) {
                                    Button(
                                        onClick = {
                                            keyboardController?.hide()
                                            focusManager.clearFocus()

                                            val keyToSave = ttsApiKey.trim()
                                            if (keyToSave.isNotBlank()) {
                                                onTtsApiKeySave(keyToSave)
                                                savedTtsApiKey = keyToSave
                                                ttsApiKey = keyToSave
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(46.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Text(
                                            text = "TTS Anahtarını Kaydet",
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            fontSize = 14.sp
                                        )
                                    }
                                } else {
                                    // Side-by-Side equal height buttons for Saved state
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // Vibrant Light Green Saved Button
                                        Button(
                                            onClick = {},
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(46.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF4CAF50) // Vibrant Light Green
                                            ),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Kaydedildi",
                                                    tint = Color.White,
                                                    modifier = Modifier
                                                        .size(16.dp)
                                                        .padding(end = 4.dp)
                                                )
                                                Text(
                                                    text = "Cloud TTS Anahtarı Kaydedildi",
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    fontSize = 11.sp,
                                                    maxLines = 1
                                                )
                                            }
                                        }

                                        // Red Delete Button
                                        Button(
                                            onClick = {
                                                keyboardController?.hide()
                                                focusManager.clearFocus()

                                                onTtsApiKeySave("")
                                                savedTtsApiKey = ""
                                                ttsApiKey = ""
                                            },
                                            modifier = Modifier.height(46.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFFE53935)
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                        ) {
                                            Text(
                                                text = "Anahtarı Sil",
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }

                                Text(
                                    "API anahtarınız cihazınızda güvenli bir şekilde saklanır.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Voice Gender Selection
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Ses Cinsiyeti", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SectionCardStyle.segmentBackground(), RoundedCornerShape(12.dp))
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                VoiceGender.entries.forEach { gender ->
                                    val isSelected = gender == currentVoiceGender
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(40.dp)
                                            .clickable { onVoiceGenderChange(gender) },
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = when (gender) {
                                                    VoiceGender.FEMALE -> "Kadın"
                                                    VoiceGender.MALE -> "Erkek"
                                                },
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 14.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Voice Model Selector (Filtered strictly by selected gender)
                        if (currentTtsEngineType == TtsEngineType.CLOUD_TTS && isTtsKeySaved) {
                            var isVoiceDialogVisible by remember { mutableStateOf(false) }

                            val availableVoices = remember(isTrToEn, currentVoiceGender) {
                                val baseList = if (isTrToEn) CloudVoiceRegistry.englishVoices else CloudVoiceRegistry.turkishVoices
                                baseList.filter { it.gender == currentVoiceGender }
                            }

                            val selectedVoiceId = if (isTrToEn) currentEnCloudVoice else currentTrCloudVoice
                            val selectedVoiceObj = remember(selectedVoiceId, availableVoices) {
                                availableVoices.firstOrNull { it.id == selectedVoiceId }
                                    ?: availableVoices.firstOrNull()
                                    ?: CloudVoiceRegistry.turkishVoices.first()
                            }

                            val displayLabel = formatVoiceDisplayLabel(selectedVoiceObj.id)

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Ses Modeli (${if (isTrToEn) "İngilizce" else "Türkçe"})",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(SectionCardStyle.segmentBackground(), RoundedCornerShape(12.dp))
                                        .clickable { isVoiceDialogVisible = true }
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = displayLabel,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (isVoiceDialogVisible) {
                                AlertDialog(
                                    onDismissRequest = { isVoiceDialogVisible = false },
                                    title = {
                                        Text(
                                            text = "Ses Modeli Seçin (${if (currentVoiceGender == VoiceGender.FEMALE) "Kadın" else "Erkek"})",
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    text = {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 350.dp)
                                                .verticalScroll(rememberScrollState()),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            availableVoices.forEach { voice ->
                                                val isSelected = voice.id == selectedVoiceObj.id
                                                Surface(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            if (isTrToEn) {
                                                                onEnCloudVoiceSave(voice.id)
                                                            } else {
                                                                onTrCloudVoiceSave(voice.id)
                                                            }
                                                            isVoiceDialogVisible = false
                                                        },
                                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(12.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = formatVoiceDisplayLabel(voice.id),
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                            fontSize = 13.sp,
                                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                                        )
                                                        if (isSelected) {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = "Seçili",
                                                                tint = MaterialTheme.colorScheme.primary
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = { isVoiceDialogVisible = false }) {
                                            Text("Kapat")
                                        }
                                    }
                                )
                            }
                        }

                        // Bulut Ses Hızı Slider (Visible ONLY when Cloud TTS engine is selected)
                        if (currentTtsEngineType == TtsEngineType.CLOUD_TTS) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Bulut ses hızı", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        text = String.format(Locale.US, "%.2f", cloudSpeechRate),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = cloudSpeechRate,
                                    onValueChange = { onCloudSpeechRateChange(it) },
                                    valueRange = 0.8f..1.6f,
                                    steps = 15,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // Sliders are visible ONLY when Google Translate (NATIVE) engine is selected
                        if (currentTtsEngineType == TtsEngineType.NATIVE) {
                            // Ses Hızı Slider
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Ses hızı", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        text = String.format(Locale.US, "%.2f", speechRate),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = speechRate,
                                    onValueChange = { onSpeechRateChange(it) },
                                    valueRange = 0.5f..2.0f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // Ses Perdesi Slider
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Ses perdesi", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        text = String.format(Locale.US, "%.2f", speechPitch),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = speechPitch,
                                    onValueChange = { onSpeechPitchChange(it) },
                                    valueRange = 0.5f..2.0f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // Customizable Test Text Box
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .bringIntoViewRequester(bringIntoViewRequester)
                                .onFocusEvent { focusState ->
                                    if (focusState.isFocused) {
                                        coroutineScope.launch {
                                            bringIntoViewRequester.bringIntoView()
                                        }
                                    }
                                }
                        ) {
                            Text("Test Metni", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            OutlinedTextField(
                                value = customTestText,
                                onValueChange = { customTestText = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 80.dp, max = 140.dp),
                                placeholder = {
                                    Text(
                                        text = if (isTrToEn) "Hello, this is a voice test." else "Merhaba, sesli okuma testi yapılıyor.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                },
                                maxLines = 4,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                onTestCustomSpeech(customTestText)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Sesi Test Et", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // BLOCK: Metin Okuma (OCR)
            SettingsBlockCard {
                CollapsibleHeader(
                    title = "Metin Okuma (OCR)",
                    subtitle = "Kameranın ekrandaki altyazıyı nasıl okuyacağı: arka plan filtresi ve oyun arayüzü yazılarını eleme",
                    expanded = isOcrSectionExpanded,
                    onToggle = { isOcrSectionExpanded = !isOcrSectionExpanded }
                )
                if (isOcrSectionExpanded) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Beyaz altyazı filtresi", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Arka planı (siyah kutusu) olmayan beyaz altyazılarda kelimeler bozuk veya hatalı okunuyorsa açmanız önerilir. Yalnızca beyaz altyazılarda etkilidir.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isOcrTextMaskEnabled,
                        onCheckedChange = { onOcrTextMaskEnabledChange(it) }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Sadece ortadaki altyazıyı oku", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "OCR alanının kenarlarındaki harita, telefon ve görev yazıları gibi oyun arayüzü metinlerini yok sayar. OCR alanını altyazıya ortalayın. Altyazısı sola/sağa dayalı oyunlarda kapatın.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isOcrCenterOnlyEnabled,
                        onCheckedChange = { onOcrCenterOnlyEnabledChange(it) }
                    )
                }
                }
            }

            // BLOCK 5: Google Cloud Translation (isteğe bağlı, ücretli; açılınca API alanı görünür)
            SettingsBlockCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("☁️ Google Cloud Çeviri", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Ücretli Google servisi; kendi Cloud Translation API anahtarınızı gerektirir. Açtığınızda çeviri yöntemleri arasına eklenir.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isCloudModeEnabled,
                        onCheckedChange = { onCloudModeEnabledChange(it) }
                    )
                }

                if (isCloudModeEnabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation('•'),
                    trailingIcon = {
                        Box(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(48.dp)
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.type == PointerEventType.Press) {
                                                isApiKeyVisible = true
                                            } else if (event.type == PointerEventType.Release || 
                                                       event.type == PointerEventType.Exit) {
                                                isApiKeyVisible = false
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                }
                        ) {
                            Icon(
                                imageVector = if (isApiKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Göster/Gizle",
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                )

                if (!isApiKeySaved) {
                    Button(
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            val keyToSave = apiKey.trim()
                            if (keyToSave.isNotBlank()) {
                                onApiKeySave(keyToSave)
                                savedApiKey = keyToSave
                                apiKey = keyToSave
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            text = "Cloud Translation Anahtarını Kaydet",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                } else {
                    // Side-by-Side equal height buttons for Saved state
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Vibrant Light Green Saved Button
                        Button(
                            onClick = {},
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4CAF50) // Vibrant Light Green
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Kaydedildi",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .padding(end = 4.dp)
                                )
                                Text(
                                    text = "Cloud Translation Anahtarı Kaydedildi",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    maxLines = 1
                                )
                            }
                        }

                        // Red Delete Button
                        Button(
                            onClick = {
                                keyboardController?.hide()
                                focusManager.clearFocus()

                                onApiKeySave("")
                                savedApiKey = ""
                                apiKey = ""
                            },
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE53935)
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = "Anahtarı Sil",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
                
                Text(
                    "API anahtarınız cihazınızda güvenli bir şekilde saklanır.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                }
            }

            // BLOCK 1: Tema (Theme)
            SettingsBlockCard {
                Text("Tema", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SectionCardStyle.segmentBackground(), RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AppTheme.entries.forEach { theme ->
                        val isSelected = theme == currentTheme
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable { onThemeChange(theme) },
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = when (theme) {
                                        AppTheme.LIGHT -> "Açık"
                                        AppTheme.DARK -> "Koyu"
                                        AppTheme.SYSTEM -> "Sistem"
                                    },
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            // BLOCK 6: Güncellemeler (Updates)
            SettingsBlockCard {
                UpdatesSection()
            }
        }
    }
}
