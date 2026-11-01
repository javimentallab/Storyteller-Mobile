package com.javimetallab.storyteller.engine

/**
 * How a book's text becomes pieces for the voice. A port of engine/cc_engine.py of the desktop
 * app; tests/golden_text.json checks both give the same pieces.
 * No \w, \b or \s here: on the JVM they are ASCII and on Android Unicode.
 */
object TextPrep {
    private const val W = "[\\p{L}\\p{N}_]"
    private const val S = "[\\s\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]"
    private const val NB = "(?<!$W)"          // what \b means at the start of a word
    private const val NA = "(?!$W)"           // and at the end

    private val LETTER_OR_DIGIT = Regex("[A-Za-z\\u00C0-\\u024F0-9]")
    private val ANY_LETTER = Regex("\\p{L}")
    private val ABBR_END = Regex(
        "(?:$NB(?:[Ss]r|[Ss]ra|[Ss]rta|[Dd]r|[Dd]ra|[Mm]r|[Mm]rs|[Mm]s|[Ss]t|[Pp]rof|" +
            "[Ll]ic|[Ii]ng|EE|vs|[Pp]ág|[Nn]úm|aprox)|(?<![\\p{L}\\p{N}_.])[A-ZÁÉÍÓÚÑ])\\.$",
    )
    private val SENTENCE_END = Regex("(?<=[.!?…])$S+")
    private val CLAUSE_END = Regex("(?<=[,;:])$S+")

    /** One sentence a piece. Long ones are cut at commas, where the reader would pause anyway. */
    fun splitSentences(text: String, target: Int = 180, hard: Int = 240, soft: Int = 170): List<String> {
        val raw = ArrayList<String>()
        for (s in SENTENCE_END.split(text.replace("\n", " "))) {
            // "el Sr. Smith", "EE. UU.", "John F. Kennedy" are not the end of anything
            if (raw.isNotEmpty() && ABBR_END.containsMatchIn(raw.last())) {
                raw[raw.size - 1] = raw.last() + " " + s
            } else {
                raw += s
            }
        }
        val out = ArrayList<String>()
        for (sentence in raw) {
            val s = sentence.pyStrip()
            if (s.isEmpty()) continue
            if (s.length <= soft) {
                out += s
                continue
            }
            var buf = ""
            for (part in CLAUSE_END.split(s)) {
                var p = part.pyStrip()
                if (p.isEmpty()) continue
                while (p.length > hard) {               // a monstrous clause: hard cut
                    var cut = maxOf(p.lastIndexOf(", ", hard - 1), p.lastIndexOf("; ", hard - 1))
                    if (cut < 60) cut = hard
                    if (buf.isNotEmpty()) {
                        out += buf.pyStrip()
                        buf = ""
                    }
                    val end = minOf(cut + 1, p.length)
                    out += p.substring(0, end).pyStrip()
                    p = p.substring(end).pyStrip()
                }
                if (buf.length + p.length + 1 <= target) {
                    buf = "$buf $p".pyStrip()
                } else {
                    if (buf.isNotEmpty()) out += buf.pyStrip()
                    buf = p
                }
            }
            if (buf.pyStrip().isNotEmpty()) out += buf.pyStrip()
        }
        return out.filter { LETTER_OR_DIGIT.containsMatchIn(it) || ANY_LETTER.containsMatchIn(it) }
    }

    /** What Python's strip() takes away, which is more than trim() thinks of as blank in places. */
    private fun String.pyStrip() = trim { it.isWhitespace() || it == ' ' }

    private val MARKS = Regex("\\[\\[\\\\?/?[^\\]]{0,20}\\]\\]")
    private val STRAY_HYPHEN = Regex("(?<!$W)-+(?!$W)|(?<=$S)-|-(?=$S)")
    private val INNER_APOSTROPHE = Regex("(?<=$W)[’'](?=$W)")
    private val QUOTES = Regex("[“”«»\"‘’‚„‹›]")
    private val BRACKETS = Regex("[()\\[\\]{}]")
    private val COLON = Regex("(?<!\\d):|:(?!\\d)")
    private val SLASH = Regex("(?<!\\d)$S*/$S*(?!\\d)")
    private val SEMICOLON = Regex("$S*;$S*")
    private val DECIMAL_COMMA = Regex("(\\d),(\\d)")
    private val COMMA = Regex("$S*,$S*")
    private val SPACES = Regex("[ \\t]{2,}")
    private val COMMAS = Regex("(?:,$S*){2,}")
    private val COMMA_BEFORE_END = Regex("$S*,$S*([.?!])")
    private val COMMA_AFTER_END = Regex("([.?!])$S*,$S*")
    private val COMMA_AFTER_OPENING = Regex("([¿¡])$S*,$S*")
    private val LEADING = Regex("^[$S,.]+".replace("[$S,.]", "(?:$S|[,.])"))
    private val TRAILING = Regex("(?:$S|,)+$")

    /** Odd punctuation → the standard kind, so that pauses are read as pauses. */
    fun normalizePunct(input: String, lang: String = "es"): String {
        var t = MARKS.replace(input, "")
        t = t.replace("­", "")
        t = Regex("_{2,}").replace(t, ", ")
        t = t.replace("—", ", ").replace("–", ", ").replace("―", ", ")
        t = STRAY_HYPHEN.replace(t, ", ")
        t = t.replace("…", ". ")
        t = INNER_APOSTROPHE.replace(t, "'")
        t = QUOTES.replace(t, "")
        t = BRACKETS.replace(t, ", ")
        t = COLON.replace(t, ", ")
        t = SLASH.replace(t, ", ")
        if (lang == "es") {
            t = Regex("$S*=$S*").replace(t, " igual a ")
            t = Regex("(?<=[A-Za-z])\\+").replace(t, " más")
        }
        t = t.replace("©", "").replace("®", "").replace("™", "").replace("*", " ")
        t = t.replace("；", ";").replace("，", ",")
        t = SEMICOLON.replace(t, ", ")
        t = DECIMAL_COMMA.replace(t, "$1\u0000$2")
        t = COMMA.replace(t, ", ")
        t = t.replace("\u0000", ",")
        t = SPACES.replace(t, " ")
        t = COMMAS.replace(t, ", ")
        t = COMMA_BEFORE_END.replace(t, "$1")
        t = COMMA_AFTER_END.replace(t, "$1 ")
        t = COMMA_AFTER_OPENING.replace(t, "$1")
        t = LEADING.replace(t, "")
        t = TRAILING.replace(t, "")
        return t.pyStrip()
    }

    // ---- ahead of the voice: only what is sent to the model, never the subtitle
    private val LETTERS_ES = mapOf(
        'a' to "a", 'b' to "be", 'c' to "ce", 'd' to "de", 'e' to "e", 'f' to "efe", 'g' to "ge", 'h' to "hache",
        'i' to "i", 'j' to "jota", 'k' to "ka", 'l' to "ele", 'm' to "eme", 'n' to "ene", 'ñ' to "eñe", 'o' to "o",
        'p' to "pe", 'q' to "cu", 'r' to "erre", 's' to "ese", 't' to "te", 'u' to "u", 'v' to "uve",
        'w' to "uve doble", 'x' to "equis", 'y' to "i griega", 'z' to "zeta",
    )
    private val SAY_WORD = setOf(
        "OTAN", "ONU", "OVNI", "OPEP", "UNESCO", "UNICEF", "FIFA", "OMS", "RAE", "UVI", "UCI", "PIB", "IVA", "AVE",
        "RENFE", "PYME", "MIR", "ESO", "LOGSE", "SIDA", "LASER", "RADAR", "OTI", "NASA", "FMI",
    )
    private val SHORT_WORDS = setOf(
        "A", "Y", "O", "U", "E", "DE", "LA", "EL", "EN", "NO", "YO", "TU", "TÚ", "ÉL", "MI", "MÍ", "SU", "SE", "SÉ", "LO",
        "UN", "AL", "ES", "YA", "SI", "SÍ", "NI", "VA", "ME", "TE", "LE", "OS", "HA", "HE", "DA", "VE", "OH", "AY", "EH",
        "AH", "IR", "TÉ", "AÚN", "OÍR",
    )
    private const val V = "AEIOUÁÉÍÓÚÜ"
    private val SYLLABLE = Regex("[^$V][$V]{1,2}[^$V]?|[$V][^$V][$V]")
    private val ABBREVIATIONS_ES = listOf(
        Regex("$NB(?!(?:El|EL|Al|AL|Del|DEL|Un|UN|Y|Pero|Que|Dijo|Con|Para|Por)$NA)([A-ZÁÉÍÓÚÑ][A-Za-zÁÉÍÓÚÑáéíóúñ']+) [Ss][Rr]\\.?$NA") to "$1 sénior",
        Regex("${NB}EE\\.?$S?UU\\.?$NA") to "Estados Unidos",
        Regex("$NB[Ss][Rr][Aa]\\.$NA") to "señora",
        Regex("$NB[Ss][Rr][Tt][Aa]\\.$NA") to "señorita",
        Regex("$NB[Ss][Rr]\\.$NA") to "señor",
        Regex("$NB[Dd][Rr][Aa]\\.$NA") to "doctora",
        Regex("$NB[Dd][Rr]\\.$NA") to "doctor",
        Regex("$NB[Jj][Rr]\\.?$NA") to "júnior",
    )
    private val CLOCK = Regex("$NB(\\d{1,2}):(\\d{2})$NA")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}_]")

    private fun pronounceable(w: String): Boolean {
        val up = w.uppercase()
        val low = w.lowercase()
        val vowels = low.count { it in "aeiouáéíóúü" }
        if (vowels == 0) return false
        if (up in SHORT_WORDS) return true
        if (up.length <= 2) return false
        if (up.length == 3) return SYLLABLE.matches(up)
        return vowels >= maxOf(1, low.length / 4)
    }

    /** PREFACIO reads as a word, CPU letter by letter. */
    private fun fixCaps(token: String): String {
        val core = NOT_WORD.replace(token, "")
        if (core.length < 2 || !core.all { it.isLetter() } || core != core.uppercase() || core == core.lowercase()) return token
        val spoken = when {
            core.uppercase() in SAY_WORD || pronounceable(core) -> core.lowercase().replaceFirstChar { it.uppercase() }
            else -> core.lowercase().map { LETTERS_ES[it] ?: it.toString() }.joinToString(" ")
        }
        return token.replace(core, spoken)
    }

    private val MONEY_CENTS = Regex("\\$$S?(\\d+)\\.(\\d{2})$NA")
    private val MONEY = Regex("\\$$S?(\\d[\\d.,]*\\d|\\d)")
    private val THOUSANDS_SPACE = Regex("(?<=\\d)[ \\u00A0\\u202F](?=\\d{3}$NA)")
    private val THOUSANDS_DOT = Regex("(?<=\\d)\\.(?=\\d{3}$NA)")
    private val THOUSANDS_COMMA = Regex("$NB\\d{1,3}(?:,\\d{3})+$NA(?![,.]\\d)")
    private val NUMBER = Regex("\\d+(?:,\\d+)?")

    private fun numbers(input: String, lang: String, spell: (Long) -> String): String {
        var t = input
        if (lang == "es") {
            t = MONEY_CENTS.replace(t, "$1 dólares con $2")
            t = MONEY.replace(t) { it.groupValues[1] + " dólares" }
        }
        t = THOUSANDS_SPACE.replace(t, "")
        t = THOUSANDS_DOT.replace(t, "")
        t = THOUSANDS_COMMA.replace(t) { it.value.replace(",", "") }
        return NUMBER.replace(t) { m ->
            val parts = m.value.split(",")
            val whole = parts[0].toLongOrNull() ?: return@replace m.value
            if (parts.size == 1) {
                spell(whole)
            } else {
                val decimals = parts[1].map { spell(it.digitToInt().toLong()) }.joinToString(" ")
                spell(whole) + " " + (DECIMAL_WORD[lang] ?: "coma") + " " + decimals
            }
        }
    }

    private val DECIMAL_WORD = mapOf(
        "es" to "coma", "en" to "point", "fr" to "virgule", "de" to "Komma", "it" to "virgola", "pt" to "vírgula",
        "ca" to "coma", "gl" to "coma",
    )

    /**
     * The whole path to the audio. Good voices don't need it, but many open ones read digits,
     * capitals and odd punctuation badly, and it does no harm to the rest.
     */
    fun forVoice(input: String, lang: String, spell: (Long) -> String): String {
        var t = input
        if (lang == "es") for ((rx, rep) in ABBREVIATIONS_ES) t = rx.replace(t, rep)
        t = CLOCK.replace(t, "$1 $2")
        t = normalizePunct(t, lang)
        if (lang == "es") t = t.split(" ").joinToString(" ") { fixCaps(it) }
        return numbers(t, lang, spell)
    }
}
