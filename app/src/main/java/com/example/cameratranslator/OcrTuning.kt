package com.example.cameratranslator

/**
 * OCR hattının çalışma anında açılıp kapatılabilen ayarları.
 * Ayarlar ekranından değişir, analyzer thread'i tarafından okunur.
 */
object OcrTuning {
    /** Beyaz altyazı filtresi: parlak/renksiz yazıyı bırakır, asfalt gibi dokulu arka planı siler. */
    @Volatile var textMaskEnabled: Boolean = false

    /** Sadece ortadaki altyazıyı oku: kenardaki HUD yazılarını (harita, telefon) atar. */
    @Volatile var centerOnlyEnabled: Boolean = true

    /**
     * OCR'a giden görüntüleri ve okunan metni telefona kaydeder (ayar yaparken teşhis için).
     * Geliştirici aracı: sürümde arayüzden kaldırıldı, gerektiğinde buradan true yapılır.
     */
    @Volatile var debugDumpEnabled: Boolean = false

    /**
     * OPUS "dikkatli çeviri": beam search (4 ışın). Çevirilerin bir kısmı belirgin şekilde daha doğal
     * ama çeviri ~3 kat uzun sürüyor; varsayılan kapalı (OPUS ayarlarının içinde).
     */
    @Volatile var opusCarefulEnabled: Boolean = false
}
