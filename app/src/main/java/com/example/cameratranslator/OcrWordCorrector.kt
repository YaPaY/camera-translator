package com.example.cameratranslator

/**
 * OCR'ın bozduğu İngilizce kelimeleri çeviriden önce düzeltir; yapay zekâ modeli değil,
 * OPUS sözlüğüne (≈25 bin tam kelime + sıklık puanı) dayalı hızlı bir kural sistemi.
 *
 * Yalnızca sözlükte olmayan kelimelere dokunur, sırayla şunları dener:
 *  1. OCR'ın tipik karışıklıkları: rn→m, ri→n, y→v, rn→n, çift harf (gota→gotta) …
 *  2. Tek harf hatası (fust→just, meke→make)
 *  3. Birleşik okunan iki kelime (ofyou→of you, theonly→the only)
 *  4. Uzun kelimede harf hatası + karışıklık
 *
 * Sözlükte olmayan ama gerçek olan kelimeler (scotch, meds, motherfucking) [realWords] listesiyle korunur.
 * OCR'ın kaybettiği/yanlış okuduğu "I" ayrıca düzeltilir: ll -> I'll, fm -> I'm, l -> I, Tdon't -> I don't.
 *
 * Yanlış düzeltmeye karşı: isimler (sözlükte büyük harfle kayıtlı olanlar, cümle ortasında
 * büyük harfli kelimeler), tamamı büyük harf kısaltmalar ve 1-2 harfli kelimeler korunur;
 * cümle başındaki bilinmeyen büyük harfli kelime yalnızca yaygın bir kelimeye düzeltilir.
 * Gerçek OCR loglarındaki okumalarla bilgisayarda test edildi.
 */
class OcrWordCorrector(
    /** Küçük harfli tam kelimeler ve OPUS sıklık puanları (büyük = daha yaygın). */
    private val scores: Map<String, Float>,
    /** Sözlükte büyük harfle kayıtlı kelimeler (Wade, Trevor, Michael …): isim, düzeltilmez. */
    private val casedWords: Set<String>,
    /** OPUS sözlüğünde olmayan gerçek kelimeler (assets/en_words.txt): düzeltilmez. */
    private val realWords: Set<String> = emptySet()
) {
    private val rank: Map<String, Int> =
        scores.entries.sortedByDescending { it.value }.withIndex().associate { (index, entry) -> entry.key to index }

    fun correct(text: String): String {
        val tokens = fixDigitI(lowerMidSentenceIt(text.split(SPACES).filter { it.isNotEmpty() }))
        var sentenceStart = true
        val out = ArrayList<String>()
        for ((i, token) in tokens.withIndex()) {
            out += correctToken(token, sentenceStart, tokens.getOrNull(i + 1))
            sentenceStart = token.trimEnd('"', '\'', ')').lastOrNull()?.let { it in ".!?" } == true
        }
        return out.joinToString(" ")
    }

    /** Cümle ortasında büyük harfle okunan It/If/In/Is: "the way It was" -> "the way it was". */
    private fun lowerMidSentenceIt(tokens: List<String>): List<String> {
        val out = tokens.toMutableList()
        for (i in 1 until out.size) {
            val core = out[i].trimEnd('.', ',', '!', '?', ';', ':')
            val previous = out[i - 1].lastOrNull() ?: continue
            if (core in MID_SENTENCE_CAPS && previous.isLetter() && previous.isLowerCase()) {
                out[i] = core.lowercase() + out[i].substring(core.length)
            }
        }
        return out
    }

    /** Rakam 1 yerine okunan I: "Did 1, cupcake?", "What if 1 lose my job?" */
    private fun fixDigitI(tokens: List<String>): List<String> {
        val out = tokens.toMutableList()
        for (i in out.indices) {
            val core = out[i].trimEnd(',', '.', '?', '!')
            if (core != "1") continue
            val previous = out.getOrNull(i - 1)?.lowercase()?.trim(',', '.', '?', '!').orEmpty()
            val next = out.getOrNull(i + 1)?.lowercase()?.trim(',', '.', '?', '!').orEmpty()
            if (previous in AUX_BEFORE_I || next in I_VERBS || next == "lose") {
                out[i] = "I" + out[i].substring(core.length)
            }
        }
        return out
    }

    private fun correctToken(token: String, sentenceStart: Boolean, nextToken: String?): String {
        val match = TOKEN.matchEntire(token) ?: return token
        val (pre, rawWord, post) = match.destructured
        var word = rawWord
        fixIGlyph(word, sentenceStart, nextToken)?.let { return pre + it + post }
        // Kesmesi düşmüş kısaltma, cümle ortasında büyük harfle de olsa: Youll -> You'll
        if ('\'' !in word && word.length >= 3 && word !in casedWords) {
            CONTRACTIONS[word.lowercase()]?.let { fixed ->
                return pre + (if (word[0].isUpperCase()) fixed.replaceFirstChar { it.uppercaseChar() } else fixed) + post
            }
        }
        val stem = word.substringBefore('\'')
        val letters = word.filter { it.isLetter() }
        val allCaps = letters.isNotEmpty() && letters.all { it.isUpperCase() }
        val capitalized = word[0].isUpperCase() && word.drop(1).all { it.isLowerCase() }

        // Bağırarak yazılmış gerçek kelime: "SWITCH to Franklin" OPUS'ta bozuluyordu (FIB, IAA gibi kısaltmalar kalır)
        if (allCaps && word.length >= 4 && '\'' !in word && word.lowercase() in scores) {
            val lower = word.lowercase()
            return pre + (if (sentenceStart) lower.replaceFirstChar { it.uppercaseChar() } else lower) + post
        }
        if (stem.isEmpty() || isKnown(stem.lowercase()) || stem.length <= 2 || allCaps) return token
        // "I" kelimeye yapışmış: Ihate -> I hate, Iwonder -> I wonder (cümle ortasında da; Ivan gibi isimler hariç)
        if (word.length >= 4 && word[0] == 'I' && word.drop(1).all { it.isLowerCase() } && word !in casedWords &&
            isKnown(word.substring(1)) && rankOf(word.substring(1)) < COMMON_RANK
        ) {
            return pre + "I " + word.substring(1) + post
        }
        // Bozuk okunan isim: Frankin -> Franklin, Michiael -> Michael, Jrevor -> Trevor
        if (capitalized && word.length >= 5 && word !in casedWords) {
            correctName(word)?.let { return pre + it + post }
        }
        if ((capitalized && !sentenceStart) || word in casedWords) return token
        // Uzatılmış ünlem: Nooo, Ahhh, Yesss ("Nooo" -> "Noon" oluyordu)
        if (hasTripleLetter(word.lowercase())) return token
        // Virgülle biten büyük harfli kelime çoğunlukla hitap/isimdir ("Tracey, go home!"):
        // yalnızca harf ekleyen düzeltmeye izin ver (Alrigh, -> Alright, ama Tracey, -/-> Trace,)
        val vocative = capitalized && post.startsWith(",")

        if (word.drop(1).any { it.isUpperCase() }) {
            // "factI", "thiSpenis": büyük harften bölmek kelimeleri (ve "I"yı) korur
            for (k in 1 until word.length) {
                if (!word[k].isUpperCase()) continue
                val left = word.substring(0, k)
                val right = word.substring(k)
                val leftOk = (left.length >= 2 || left.lowercase() in FUNCTION_WORDS) &&
                    (left.lowercase() in scores || left.lowercase() in FUNCTION_WORDS)
                if (leftOk && (right == "I" || right.lowercase() in scores)) {
                    return pre + left + " " + (if (right == "I") right else right.lowercase()) + post
                }
            }
            // Arada tek büyük harf (knoW, abouL): küçük harfle düzeltmeyi dene
            if (word[0].isUpperCase() || word.count { it.isUpperCase() } > 1) return token
            word = word.lowercase()
        }
        if ('\'' in word) return token

        val lower = word.lowercase()
        if (!lower.all { it in 'a'..'z' || it.isDigit() }) return token
        val normalized = lower.replace('0', 'o').replace('9', 'g').replace('1', 'l')
        // Cümle başındaki büyük harfli bilinmeyen kelime isim olabilir: yalnızca yaygın kelimeye düzelt
        var fixed = correctWord(normalized, commonOnly = capitalized, noShortening = vocative) ?: return token
        if (word[0].isUpperCase()) fixed = fixed.replaceFirstChar { it.uppercaseChar() }
        return pre + fixed + post
    }

    private fun correctWord(word: String, commonOnly: Boolean, noShortening: Boolean): String? {
        fun acceptable(candidate: String?): Boolean {
            if (candidate == null) return false
            if (commonOnly && rankOf(candidate.substringBefore(' ')) >= COMMON_RANK) return false
            return !noShortening || candidate.length >= word.length
        }

        CONTRACTIONS[word]?.let { return it }
        // Kısa kelimede yanlış düzeltme riski yüksek: yalnızca OCR karışıklığıyla ve çok yaygın kelimeye
        if (word.length <= 3) {
            val candidate = best(confusionVariants(word))
            return candidate?.takeIf { rankOf(it) < SHORT_WORD_RANK }
        }

        // Argo "-in" sonu (trippin, nothin) bölünmesin
        val split = if (commonOnly || word.endsWith("in")) null else bestSplit(word)

        best(confusionVariants(word)).let { if (acceptable(it)) return it }
        // Kısaltmanın bozuk hali: hadrt -> hadn't, navent -> haven't, doht -> don't
        // (iki yaygın kelimeye bölünebiliyorsa bölme kazanır: haveno -> have no, haven't değil)
        if (split == null || !isCommonSplit(split)) (confusionVariants(word) + edits1(word))
            .filter { it in CONTRACTIONS }
            .minWithOrNull(compareBy<String>({ -it.length }, { it }))
            ?.let { return CONTRACTIONS.getValue(it) }
        best(edits1(word)).let { candidate ->
            if (acceptable(candidate)) {
                // "saton": iki çok yaygın kelimeye bölünebiliyorsa nadir bir kelimeye düzeltme ("salon")
                // Harf silen düzeltme de bölmeye yenilir: haveno -> have no ("haven" değil)
                val shortens = candidate!!.length < word.length && split != null && split.all { it.length >= 2 }
                if (split != null && isCommonSplit(split) && (rankOf(candidate) >= SPLIT_PART_RANK || shortens)) {
                    return split.joinToString(" ")
                }
                return candidate
            }
        }
        split?.let { return it.joinToString(" ") }

        if (word.length >= 6) {
            val candidates = HashSet<String>()
            for (edit in edits1(word)) candidates += confusionVariants(edit)
            for (variant in confusionVariants(word)) candidates += edits1(variant)
            best(candidates).let { if (acceptable(it)) return it }
        }
        return null
    }

    /** Sözlükte ya da çekimli hali sözlükte mi? relapsed, racer, jacking, daddies, stopped … */
    private fun isKnown(word: String): Boolean {
        if (word in scores || word in EXTRA_WORDS || word in realWords) return true
        if (word.endsWith("in") && (word + "g") in scores) return true // trippin, nothin
        for ((suffix, replacement) in SUFFIXES) {
            if (!word.endsWith(suffix) || word.length - suffix.length < 2) continue
            val stem = word.dropLast(suffix.length) + replacement
            if (stem in scores || stem in realWords) return true
            // Çift ünsüz: stopped -> stop, jogging -> jog
            if (replacement.isEmpty() && stem.length >= 3 && stem[stem.length - 1] == stem[stem.length - 2] &&
                stem.dropLast(1) in scores
            ) return true
        }
        return false
    }

    /** Tek harf/karışıklık farkıyla sözlükteki tek bir isme uyuyorsa o isim; birden çoksa harf eklenmiş olan. */
    private fun correctName(word: String): String? {
        val lower = word.lowercase()
        var names = (confusionVariants(lower) + edits1(lower))
            .map { it.replaceFirstChar { c -> c.uppercaseChar() } }
            .filter { it in casedWords }
            .toSet()
        // OCR'ın en sık hatası harf düşürmek (Frankin -> Franklin, Frankie değil)
        if (names.size > 1) names = names.filter { it.length == word.length + 1 }.toSet()
        return names.singleOrNull()
    }

    /**
     * OCR'ın kaybettiği ya da benzer harfle okuduğu "I" (TV fontunda ince bir çizgi):
     * "ll go" / "Tll go" -> I'll go, "fm with you" -> I'm, "l can't" -> I can't, "Tdon't" -> I don't,
     * "Jhave" -> I have, "Jike" -> like, "ls" -> is. Uygun değilse null.
     */
    private fun fixIGlyph(word: String, sentenceStart: Boolean, nextToken: String?): String? {
        val nextIsWord = nextToken?.firstOrNull()?.isLetter() == true
        if (word in LONE_I && nextIsWord) return "I"
        SHORT_FIXES[word]?.let { return it }
        if (word in I_LL && nextIsWord) return "I'll"
        // Baştaki I tamamen kaybolmuş: "ll go", "'m on my way", "ld say", "ve never done"
        I_FORMS[word]?.let { fixed ->
            if (!nextIsWord) return null
            val safeAnywhere = word.startsWith('\'') || word == "ll"
            return if (safeAnywhere || sentenceStart) fixed else null
        }
        if (word == "ld") return if (nextIsWord) "I'd" else null
        if (word.length < 2 || word[0] !in I_LOOKALIKES) return null
        val rest = word.substring(1)
        // Tll, IIl, Il, fm, lm, Im, l'd, J'll
        for (form in listOf(rest, rest.trimStart('I', 'l'))) {
            val fixed = I_FORMS[form] ?: continue
            if (form == "d" || (word[0] == 'f' && form != "m")) continue
            return fixed
        }
        when (word) {
            "Ive", "lve" -> return "I've"
            "Id" -> return if (nextIsWord) "I'd" else null
        }
        if (word in casedWords || isKnown(word.lowercase())) return null
        // Tdon't, Tknow, Jhave, ldid
        if (word[0] in "TJl|1I" && rest in I_VERBS) return "I $rest"
        // Jike -> like
        if (word[0] == 'J' && rest.all { it.isLowerCase() }) {
            val candidate = "l$rest"
            if (candidate in scores && rankOf(candidate) < SHORT_WORD_RANK) return candidate
        }
        return null
    }

    private fun rankOf(word: String): Int = rank[word] ?: Int.MAX_VALUE

    /** İki kelimeye en olası bölme ("ofyou" -> of + you); kısa parça yalnızca gerçek kısa kelime olabilir. */
    private fun bestSplit(word: String): List<String>? {
        var result: List<String>? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in 1 until word.length) {
            val left = word.substring(0, i)
            val right = word.substring(i)
            val leftScore = scores[left] ?: continue
            val rightScore = scores[right] ?: continue
            if ((left.length < 3 && left !in FUNCTION_WORDS) || (right.length < 3 && right !in FUNCTION_WORDS)) continue
            if (leftScore + rightScore > bestScore) {
                bestScore = leftScore + rightScore
                result = listOf(left, right)
            }
        }
        return result
    }

    private fun isCommonSplit(parts: List<String>): Boolean =
        parts.all { it in FUNCTION_WORDS || rankOf(it) < SPLIT_PART_RANK }

    private fun hasTripleLetter(word: String): Boolean =
        (2 until word.length).any { word[it] == word[it - 1] && word[it] == word[it - 2] }

    private fun best(candidates: Collection<String>): String? =
        candidates.filter { it in scores }.maxByOrNull { scores.getValue(it) }

    private fun confusionVariants(word: String): Set<String> {
        val out = HashSet<String>()
        for ((right, read) in CONFUSIONS) {
            var index = word.indexOf(read)
            while (index >= 0) {
                out += word.substring(0, index) + right + word.substring(index + read.length)
                index = word.indexOf(read, index + 1)
            }
        }
        return out
    }

    private fun edits1(word: String): Set<String> {
        val out = HashSet<String>()
        for (i in 0..word.length) {
            val a = word.substring(0, i)
            val b = word.substring(i)
            if (b.isNotEmpty()) {
                out += a + b.substring(1)
                for (c in 'a'..'z') out += a + c + b.substring(1)
            }
            if (b.length > 1) out += a + b[1] + b[0] + b.substring(2)
            for (c in 'a'..'z') out += a + c + b
        }
        return out
    }

    companion object {
        /**
         * TV fontundaki "I" sık sık "|" okunur: "| hate", "|'m", "|am" (boşluk da kaybolmuş).
         * Sözlük gerektirmez; her çeviri modunda (Google dahil) kullanılır.
         */
        fun fixPipes(text: String): String {
            if ('|' !in text) return text
            return text
                .replace(PIPE_APOSTROPHE, "I")
                .replace(PIPE_WORD) { "I " + it.groupValues[1] }
                .replace(STANDALONE_PIPE, "I")
        }

        // "|'m", "|-| demand" (kekeleme: I-I demand)
        private val PIPE_APOSTROPHE = Regex("(?<![\\p{L}\\d])\\|(?=['’-])|(?<=-)\\|(?![\\p{L}\\d])")
        private val PIPE_WORD = Regex("(?<![\\p{L}\\d])\\|(\\p{Ll}+)")
        private val STANDALONE_PIPE = Regex("(?<!\\S)\\|(?!\\S)")
        private val SPACES = Regex("\\s+")
        private val TOKEN = Regex("^([^A-Za-z0-9']*)([A-Za-z0-9']+)([^A-Za-z0-9']*)$")
        private const val COMMON_RANK = 12_000
        private const val SHORT_WORD_RANK = 3_500
        /** Bölünmüş kelimenin iki parçası da bu kadar yaygınsa nadir bir tek kelimeye tercih edilir. */
        private const val SPLIT_PART_RANK = 3_000

        /** Çekim ekleri (ek, köke eklenecek): relapsed -> relapse, racer -> race, daddies -> daddy. */
        private val SUFFIXES = listOf(
            "ies" to "y", "ied" to "y", "ier" to "y", "iest" to "y", "ing" to "", "ing" to "e", "ed" to "", "ed" to "e",
            "er" to "", "er" to "e", "ers" to "", "ers" to "e", "est" to "", "ly" to "", "es" to "", "s" to "",
            "d" to "", "r" to ""
        )

        /** OPUS sözlüğünde tek parça olmayan ama altyazılarda sık geçen kelimeler (düzeltilmez). */
        private val EXTRA_WORDS = setOf(
            "oh", "ohh", "uh", "uhh", "um", "hmm", "ah", "ahh", "huh", "whoa", "wow", "ok", "okay",
            "nah", "yep", "yup", "hey", "shh", "ugh", "yo", "ya", "homie", "homies", "dawg", "bro", "cuz", "fam",
            "argh", "aargh", "ew", "eww", "yikes", "nope", "gee", "jeez", "damn", "dammit", "goddamn", "puke",
            "kinda", "sorta", "gotcha", "wassup", "sup", "lol", "anus", "rappel", "doke", "okey"
        )
        private val FUNCTION_WORDS = setOf(
            "a", "i", "of", "to", "in", "on", "at", "is", "it", "be", "we", "he", "me", "my", "do", "so",
            "no", "up", "by", "or", "an", "as", "if", "us", "go", "you", "the", "and", "for"
        )
        private val CONTRACTIONS = mapOf(
            "dont" to "don't", "didnt" to "didn't", "doesnt" to "doesn't", "isnt" to "isn't", "wasnt" to "wasn't",
            "cant" to "can't", "wont" to "won't", "couldnt" to "couldn't", "wouldnt" to "wouldn't",
            "shouldnt" to "shouldn't", "youre" to "you're", "theyre" to "they're", "im" to "I'm", "ive" to "I've",
            "youve" to "you've", "weve" to "we've", "hes" to "he's", "shes" to "she's", "thats" to "that's",
            "whats" to "what's", "theres" to "there's", "therell" to "there'll", "youll" to "you'll",
            "itll" to "it'll", "aint" to "ain't", "havent" to "haven't",
            "hadnt" to "hadn't", "hasnt" to "hasn't", "arent" to "aren't", "werent" to "weren't", "theyll" to "they'll",
            "theyve" to "they've", "wouldve" to "would've", "couldve" to "could've", "shouldve" to "should've",
            "whos" to "who's", "whatll" to "what'll", "thatll" to "that'll", "wheres" to "where's", "hows" to "how's",
            "itd" to "it'd", "youd" to "you'd"
        )
        /** I yerine okunan tek karakter ("l can't", "J did", "| hate") */
        private val LONE_I = setOf("l", "J", "|")
        /** I'sı kaybolmuş kısaltmalar */
        private val I_FORMS = mapOf(
            "ll" to "I'll", "'ll" to "I'll", "m" to "I'm", "'m" to "I'm", "d" to "I'd", "'d" to "I'd",
            "ve" to "I've", "'ve" to "I've"
        )
        /** OCR'ın I yerine okuduğu harfler */
        private const val I_LOOKALIKES = "IlTJ|1f"
        private val SHORT_FIXES = mapOf("ls" to "is", "lt" to "it", "lf" to "if", "ln" to "in", "lts" to "its", "Lts" to "Its")
        /** "I"dan sonra sık gelen kelimeler: Tdon't, Jhave gibi yapışık okumaları ayırmak için */
        private val I_VERBS = setOf(
            "know", "don't", "didn't", "did", "do", "have", "had", "got", "think", "thought", "was", "said", "can",
            "can't", "could", "will", "won't", "just", "need", "want", "mean", "guess", "wonder", "hate", "love",
            "like", "told", "saw", "am", "never", "ever", "really", "swear", "promise", "bet", "gotta", "gonna",
            "wanna", "should", "would"
        )
        private val MID_SENTENCE_CAPS = setOf("It", "If", "In", "Is", "It's")
        /** I'll'ın okunuşları ("Next Il have", "lll just"); küçük harfli "ill" (hasta) değil */
        private val I_LL = setOf("Il", "Ill", "IIl", "lll")
        /** Bunlardan sonra gelen "1" aslında "I" ("Did 1", "What if 1") */
        private val AUX_BEFORE_I = setOf(
            "did", "do", "does", "can", "could", "would", "should", "will", "am", "was", "have", "had",
            "if", "and", "but", "so", "when", "then", "because"
        )
        /** (doğru, OCR'ın okuduğu) */
        private val CONFUSIONS = listOf(
            "m" to "rn", "m" to "rm", "m" to "ri", "n" to "ri", "h" to "li", "d" to "cl", "w" to "vv",
            "u" to "ii", "n" to "rn", "d" to "c",
            "tt" to "t", "ll" to "l", "ss" to "s", "ee" to "e", "oo" to "o", "pp" to "p", "ff" to "f", "rr" to "r",
            "v" to "y", "y" to "v", "i" to "l", "l" to "i", "o" to "0", "g" to "9",
            "e" to "c", "c" to "e", "h" to "b", "b" to "h", "n" to "h", "i" to "j", "t" to "f",
            "h" to "n"
        )
    }
}
