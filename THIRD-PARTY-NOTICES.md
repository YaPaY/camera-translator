# Üçüncü Taraf Bildirimleri (Third-Party Notices)

Camera Translator'ın kaynak kodu [MIT lisansı](LICENSE.md) ile yayımlanır. Uygulama aşağıdaki üçüncü taraf bileşenleri kullanır; bu bileşenler **kendi lisanslarına** tabidir ve MIT lisansının kapsamında değildir.

---

## ⚡ OPUS çeviri modeli — OPUS-MT (Helsinki-NLP)

- **Model:** [Helsinki-NLP/opus-mt-tc-big-en-tr](https://huggingface.co/Helsinki-NLP/opus-mt-tc-big-en-tr)
- **Geliştiren:** Language Technology Research Group, University of Helsinki — OPUS-MT / Tatoeba Translation Challenge
- **Lisans:** [Creative Commons Attribution 4.0 International (CC-BY 4.0)](https://creativecommons.org/licenses/by/4.0/)
- **Yapılan değişiklikler:** Uygulamanın indirdiği model dosyaları, orijinal modelin
  - ONNX formatına dönüştürülmüş,
  - 8-bit (int8) dinamik kuantizasyonla sıkıştırılmış,
  - tokenizer sözlüğü (`source_pieces.tsv`) düz metin olarak çıkarılmış

  halidir. Model ağırlıkları yeniden eğitilmemiştir. Dönüştürme betikleri: [tools/offline_model](tools/offline_model).
- **Uygulamanın içindeki sözlük:** `app/src/main/assets/ocr_vocab.tsv`, bu modelin kaynak dil sözlüğündeki tam kelimelerin ve sıklık puanlarının listesidir. OCR kelime düzelticisi bunu kullanır; böylece düzeltme OPUS indirilmeden ve tüm çeviri modlarında çalışır. Lisansı modelle aynıdır (CC-BY 4.0).
- **Dağıtım:** Dönüştürülmüş dosyalar bu reponun [`offline-model-v1`](https://github.com/crosswindfighter/camera-translator/releases/tag/offline-model-v1) sürümünde yayımlanır ve uygulama içinden indirilir.
- **Atıf:**
  > Jörg Tiedemann and Santhosh Thottingal. 2020. *OPUS-MT — Building open translation services for the World.* In Proceedings of the 22nd Annual Conference of the European Association for Machine Translation (EAMT).
  >
  > Jörg Tiedemann. 2020. *The Tatoeba Translation Challenge — Realistic Data Sets for Low Resource and Multilingual MT.* In Proceedings of the Fifth Conference on Machine Translation (WMT).

---

## 📖 İngilizce kelime listesi — FrequencyWords

- **Dosya:** `app/src/main/assets/en_words.txt` (OCR kelime düzelticisinin gerçek kelimeleri tanıması için)
- **Kaynak:** [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords) — `content/2018/en/en_50k.txt` (OpenSubtitles 2018 altyazılarından derlenmiş sıklık listesi)
- **Lisans:** [Creative Commons Attribution-ShareAlike 4.0 International (CC-BY-SA 4.0)](https://creativecommons.org/licenses/by-sa/4.0/)
- **Yapılan değişiklikler:** Sıklık sayıları çıkarılmış; OPUS sözlüğünde zaten olan kelimeler, 4 harften kısa kelimeler ve listede bulunan OCR hataları (ör. `lt`, `dont`, `ofthe`) ayıklanmıştır. Ortaya çıkan liste de CC-BY-SA 4.0 lisansıyla paylaşılır.

## 🔗 Kelime çifti sıklıkları — OpenSubtitles (OPUS)

- **Dosyalar:** `app/src/main/assets/ocr_bigrams.bin`, `ocr_unigrams.tsv` (OCR düzelticisinin bağlam kontrolü için)
- **Kaynak:** [OPUS OpenSubtitles v2018](https://opus.nlpl.eu/OpenSubtitles/corpus/version/OpenSubtitles) İngilizce altyazılarının ilk 12 milyon satırı
- **İçerik:** Yalnızca kelime ve kelime çifti sayıları vardır (çiftler 64 bitlik özet olarak saklanır); altyazı metinlerinin kendisi yoktur ve bu sayılardan geri elde edilemez.
- **Atıf:** P. Lison and J. Tiedemann. 2016. *OpenSubtitles2016: Extracting Large Parallel Corpora from Movie and TV Subtitles.* In Proceedings of the 10th International Conference on Language Resources and Evaluation (LREC 2016). — http://www.opensubtitles.org/

---

## Yazılım kütüphaneleri

| Bileşen | Kullanım | Lisans |
|---|---|---|
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) (Microsoft) | OPUS modelini telefonda çalıştırma | MIT |
| [Google ML Kit](https://developers.google.com/ml-kit) — Text Recognition, Translation | Metin okuma (OCR) ve yedek çevrimdışı çeviri | [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) |
| [AndroidX / Jetpack Compose / CameraX](https://developer.android.com/jetpack/androidx) | Arayüz ve kamera | Apache License 2.0 |
| [Material Icons](https://fonts.google.com/icons) | Arayüz ikonları | Apache License 2.0 |
| [Accompanist](https://github.com/google/accompanist) | İzin yönetimi | Apache License 2.0 |
| [Kotlin](https://kotlinlang.org/) ve [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Programlama dili ve eşzamanlılık | Apache License 2.0 |
| [OkHttp](https://square.github.io/okhttp/) | Ağ istekleri | Apache License 2.0 |
| [Guava](https://github.com/google/guava) | Yardımcı kütüphane | Apache License 2.0 |

Apache License 2.0 metni: https://www.apache.org/licenses/LICENSE-2.0

---

## Çevrimiçi servisler

Google Translate, Google Cloud Translation ve Google Cloud Text-to-Speech modlarında çeviri ve seslendirme Google servisleri üzerinden yapılır; bu servisler Google'ın kendi kullanım koşullarına tabidir. Google Cloud servisleri kullanıcının kendi API anahtarıyla çalışır ve ücretlendirmesi Google Cloud'a bağlıdır.

Camera Translator bağımsız bir açık kaynak projedir; Google, Microsoft veya University of Helsinki ile bir bağlantısı yoktur ve bu kuruluşlar tarafından desteklenmemektedir.
