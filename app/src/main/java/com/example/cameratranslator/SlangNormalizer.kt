package com.example.cameratranslator

/**
 * OPUS'un bilmediği oyun/sokak argosunu, çeviriden önce modelin anladığı sade
 * İngilizceye çevirir. Örn. "…tricked out, dog." -> "…modified, dude." ->
 * "…modifiye edilmiş, dostum." (önce "…kandırılmalı, köpek." çıkıyordu).
 *
 * Her kural bilgisayarda OPUS modeliyle önce/sonra test edilerek seçildi; OPUS'un
 * zaten doğru çevirdikleri (homie -> kanka, "Twenty Gs" -> yirmi bin dolar,
 * "holy shit" -> vay anasını, "my bad", "snitch") bilerek listede yok.
 * Hitap kuralları yalnızca virgülden sonra ve cümle/öbek sonunda eşleşir:
 * "The dog barked" gibi gerçek anlamlar etkilenmez.
 *
 * Sadece OPUS (İngilizce -> Türkçe) için kullanılır.
 */
object SlangNormalizer {

    /** Hitap sonu: ardından noktalama ya da metin sonu gelmeli. */
    private const val VOCATIVE_END = "(?=\\s*[.!?,]|\\s*$)"

    /**
     * Google modlarında da uygulanan dar kurallar: Google'ın da yanlış çevirdiği görülenler
     * ("tricked out, dog" -> "kandırılmış, köpek", "nigga" -> "zenci"). Hitaplar yalnızca virgülden
     * sonra ve öbek sonunda eşleşir; başka cümlelere dokunmaz. Google'ın zaten doğru çevirdiği
     * kalıplar (gonna, no shit, strapped…) burada bilerek yok.
     */
    private val SHARED_RULES: List<Pair<Regex, String>> = listOf(
        // Hitaplar (köpek, kuzen, zenci gibi düz çevrilmesin)
        "(?<=,\\s)(dog|dawg)$VOCATIVE_END" to "dude",
        "(?<=,\\s)(homes|holmes)$VOCATIVE_END" to "buddy",
        "(?<=,\\s)ese$VOCATIVE_END|^ese(?=,)" to "man",
        "(?<=,\\s)(cuz|fam|bruh|brah)$VOCATIVE_END" to "dude",
        "\\bmy (nigga|nigguh|niggah)\\b" to "my brother",
        "\\b(nigga|nigguh|niggah)s\\b" to "guys",
        "\\b(nigga|nigguh|niggah)\\b" to "man",
        "\\btricked[- ]out\\b" to "modified",
    ).map { (pattern, replacement) -> Regex(pattern, RegexOption.IGNORE_CASE) to replacement }

    /** Yalnızca OPUS için (Google bunları zaten doğru çeviriyor). */
    private val RULES: List<Pair<Regex, String>> = listOf(
        "\\bmy g\\b" to "my man",

        // Kalıplar
        "\\band shit\\b" to "and stuff",
        "\\bno shit\\b" to "no kidding",
        "\\bno cap\\b" to "no lie",
        "\\bfive-?o\\b|\\b5-0\\b" to "the cops",
        "\\brepo\\b" to "repossess",
        // "You're strapped in" (kemer/emniyet) "Silahlısın" oluyordu: in/down/into ile gelince dokunma
        "\\bstrapped\\b(?!\\s+(in|down|into|to)\\b)" to "armed",
        "\\blet's bounce\\b" to "let's go",
        "\\bgat\\b" to "gun",
        "\\bmy whip\\b" to "my car",
        "\\bnice ride\\b" to "nice car",
        "\\bthe hood\\b" to "the neighborhood",
        "\\blet's bail\\b" to "let's leave",
        // "ain't gonna be cool" OPUS'ta ters anlama dönüyordu ("hiç sorun olmaz")
        "\\b(ain't|aint|isn't|is not|not) (gonna|going to) be cool\\b" to "won't be happy",
        "\\bwon't be cool\\b" to "won't be happy",
        // GTA görev metni: "Lose the Cops." -> "Polisleri bırakın" çıkıyordu
        "\\blose the (cops|police)\\b" to "escape the cops",

        // Konuşma dili kısaltmaları
        "\\bgonna\\b" to "going to",
        "\\bwanna\\b" to "want to",
        "\\bgotta\\b" to "have to",
        "\\blemme\\b" to "let me",
        "\\bgimme\\b" to "give me",
        "\\bdunno\\b" to "don't know",
        "\\by'all\\b" to "you all",
        "\\bain't got\\b" to "don't have",
    ).map { (pattern, replacement) -> Regex(pattern, RegexOption.IGNORE_CASE) to replacement }

    /** Kekeleme: "p-p-pretend", "A-A-And", "M-M-M-Mrs." -> pretend, And, Mrs. (OPUS döngüye giriyordu) */
    private val STUTTER = Regex("\\b([A-Za-z])-(?:\\1-)*(?=\\1)", RegexOption.IGNORE_CASE)

    /** Google modları için: yalnızca ortak dar kurallar. */
    fun normalizeShared(text: String): String {
        var result = text
        for ((regex, replacement) in SHARED_RULES) {
            result = regex.replace(result) { match -> keepCase(match.value, replacement) }
        }
        return result
    }

    fun normalize(text: String): String {
        var result = normalizeShared(STUTTER.replace(text, ""))
        for ((regex, replacement) in RULES) {
            result = regex.replace(result) { match -> keepCase(match.value, replacement) }
        }
        return result
    }

    /** "Dog" -> "Dude", "dog" -> "dude": cümle başı büyük harf korunsun. */
    private fun keepCase(original: String, replacement: String): String =
        if (original.firstOrNull()?.isUpperCase() == true) replacement.replaceFirstChar { it.uppercaseChar() } else replacement
}
