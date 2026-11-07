package com.javimetallab.storyteller.engine

import org.json.JSONObject

/** The user's dictionary for one language. */
class Pron(words: Map<String, String>, val asWords: Set<String>, val asLetters: Set<String>) {
    private val said = words.mapKeys { it.key.lowercase() }
    private val rx = if (said.isEmpty()) {
        null
    } else {
        val alternatives = said.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        Regex("(?<![\\p{L}\\p{N}_])(?:$alternatives)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
    }

    fun apply(text: String) = rx?.replace(text) { said[it.value.lowercase()] ?: it.value } ?: text

    companion object {
        val NONE = Pron(emptyMap(), emptySet(), emptySet())

        /** The part of pron.json for a language: the top is for Spanish, the rest goes in "langs". */
        fun entry(root: JSONObject, lang: String): JSONObject? =
            if (lang == "es") root else root.optJSONObject("langs")?.optJSONObject(lang)

        fun of(root: JSONObject, lang: String): Pron {
            val e = entry(root, lang) ?: return NONE
            val words = e.optJSONObject("pron")
            fun list(key: String) = e.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it).uppercase() }.toSet() }.orEmpty()
            return Pron(
                words?.keys()?.asSequence()?.associateWith { words.optString(it) }.orEmpty(),
                list("say_word"), list("say_letters"),
            )
        }
    }
}

/**
 * How a book's text becomes pieces for the voice. A port of engine/cc_engine.py of the desktop
 * app; app/src/test/resources/golden.json checks both give the same pieces.
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

    /** What closes a piece of narration, and the silence after it: the book's punctuation sets it, not the model. */
    enum class Close(val pause: Double) { CLAUSE(0.22), SENTENCE(0.55), PARAGRAPH(0.90) }

    data class Chunk(val text: String, val close: Close)

    // The end of a sentence: a sign, maybe a closing quote or bracket, and a space. Not before a
    // lower case letter ("¿Qué hora es? —dijo él", "etc. y", "Bueno… quizá"). Chinese and Japanese
    // have no space after the sign: without cutting there, a whole paragraph would go in one piece.
    private val CHUNK_SENTENCE_END = Regex(
        "(?:(?<=[.!?…।؟])|(?<=[.!?…।؟][»”\"’)\\]]))$S+(?![—–-]?$S*[a-zà-öø-ÿа-яёα-ω])" +
            "|(?:(?<=[。！？])(?![」』”）])|(?<=[。！？][」』”）]))$S*",
    )
    private val ENDS_SENTENCE = Regex("[.!?…。！？।؟][»”\"’)\\]」』）]*$")
    private val CHUNK_CLAUSE_END = Regex("(?<=[,;:،؛])$S+|(?<=[，、；：])$S*")

    // Most characters per piece: XTTS cuts the audio past its limit (about 92 % of it here, because
    // numbers are spelled out later). With a whole sentence a piece, the limit is reached.
    private val HARD = mapOf(
        "zh-cn" to 75, "ja" to 65, "ko" to 85, "hi" to 140, "ar" to 155, "ru" to 170, "cs" to 175, "pt" to 190,
        "it" to 200, "pl" to 210, "hu" to 210, "tr" to 210, "es" to 220, "en" to 230, "de" to 230, "nl" to 230,
        "fr" to 250,
    )
    private const val HARD_DEFAULT = 230

    /**
     * A sentence longer than [hard] is cut at the comma (or ; :) nearest the limit, so that the
     * piece is as long as it can be; at the one before if that leaves less than [short] on either
     * side. With no comma that will do, at a space.
     */
    private fun splitLong(sentence: String, hard: Int, short: Int): List<String> {
        val out = ArrayList<String>()
        var s = sentence
        while (s.length > hard) {
            var ok: MatchResult? = null
            var fallback: MatchResult? = null
            for (m in CHUNK_CLAUSE_END.findAll(s)) {
                val end = m.range.first                  // just after the sign
                if (end > hard) break
                if (end < short) continue
                fallback = m
                if (s.length - (m.range.last + 1) >= short) ok = m
            }
            val m = ok ?: fallback
            if (m != null) {
                out += s.substring(0, m.range.first).pyStrip()
                s = s.substring(m.range.last + 1).pyStrip()
                continue
            }
            var cut = s.lastIndexOf(' ', hard - 1)
            if (cut < minOf(60, hard / 2)) cut = hard
            out += s.substring(0, cut).pyStrip()
            s = s.substring(cut).pyStrip()
        }
        out += s
        return out
    }

    /**
     * One piece per whole sentence: XTTS reads it in one go and makes its own commas, and between
     * sentences and paragraphs the pause is the book's ([Close]). Until 7 Nov it cut at every comma,
     * and it sounded read line by line.
     */
    private fun splitParagraph(text: String, hard: Int, short: Int = 20): List<String> {
        val raw = ArrayList<String>()
        for (s in CHUNK_SENTENCE_END.split(text)) {
            if (raw.isNotEmpty() && ABBR_END.containsMatchIn(raw.last())) {
                raw[raw.size - 1] = raw.last() + " " + s
            } else {
                raw += s
            }
        }
        val out = ArrayList<String>()
        for (sentence in raw) {
            val s = sentence.pyStrip()
            if (s.isNotEmpty()) out += splitLong(s, hard, short)
        }
        return out.filter { LETTER_OR_DIGIT.containsMatchIn(it) || ANY_LETTER.containsMatchIn(it) }
    }

    /** The pieces for the voice, like split_chunks on the desktop. Each line of the text is a paragraph. */
    fun splitChunks(text: String, lang: String = "es"): List<Chunk> {
        val hard = HARD[lang] ?: HARD_DEFAULT
        val out = ArrayList<Chunk>()
        for (paragraph in text.split("\n")) {
            val parts = splitParagraph(paragraph, hard)
            for ((i, c) in parts.withIndex()) {
                val close = when {
                    i == parts.lastIndex -> Close.PARAGRAPH
                    ENDS_SENTENCE.containsMatchIn(c) -> Close.SENTENCE
                    else -> Close.CLAUSE
                }
                out += Chunk(c, close)
            }
        }
        return out
    }

    /**
     * One sentence a piece, for the subtitles of a transcribed audiobook: there a line per comma
     * would be too short. Long ones are cut at commas, where the reader would pause anyway.
     */
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
    private val DOTS = Regex("\\.{2,}")
    private val END_THEN_DOTS = Regex("([.?!])(?:$S*\\.)+")
    private val LEADING = Regex("^[$S,.]+".replace("[$S,.]", "(?:$S|[,.])"))
    private val TRAILING = Regex("(?:$S|,)+$")

    /** Odd punctuation → the standard kind, so that pauses are read as pauses. */
    fun normalizePunct(input: String, lang: String = "es"): String {
        var t = MARKS.replace(input, "")
        t = t.replace("­", "")
        t = Regex("_{2,}").replace(t, ", ")
        t = t.replace("—", ", ").replace("–", ", ").replace("―", ", ")
        t = STRAY_HYPHEN.replace(t, ", ")
        t = DOTS.replace(t, "…").replace("…", ". ")      // "y..." typed by hand: XTTS made things up
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
        t = END_THEN_DOTS.replace(t, "$1")               // 'y...".' -> "y. ." without quotes -> "y."
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

    /** PREFACIO reads as a word, CPU letter by letter. Only the user's lists go beyond Spanish. */
    private fun fixCaps(token: String, lang: String, pron: Pron): String {
        val core = NOT_WORD.replace(token, "")
        if (core.length < 2 || !core.all { it.isLetter() } || core != core.uppercase() || core == core.lowercase()) return token
        val es = lang == "es"
        val up = core.uppercase()
        val word = core.lowercase().replaceFirstChar { it.uppercase() }
        val letters = if (es) core.lowercase().map { LETTERS_ES[it] ?: it.toString() }.joinToString(" ") else core.lowercase().toList().joinToString(" ")
        val spoken = when {
            up in pron.asLetters -> letters
            up in pron.asWords || (es && up in SAY_WORD) -> word
            !es -> return token
            pronounceable(core) -> word
            else -> letters
        }
        return token.replace(core, spoken)
    }

    /** XTTS and others read a capital at the start of a piece oddly, and it sounds the same without. */
    private fun lowerInitial(text: String, lang: String): String {
        for ((i, ch) in text.withIndex()) {
            if (ch.isLetter()) {
                if (lang != "es" && text.getOrNull(i + 1)?.isUpperCase() == true) return text   // an acronym: in Spanish, fixCaps did it
                return text.substring(0, i) + ch.lowercase() + text.substring(i + 1)
            }
            if (ch.isDigit()) break
        }
        return text
    }

    private val MONEY_CENTS = Regex("\\$$S?(\\d+)\\.(\\d{2})$NA")
    private val MONEY = Regex("\\$$S?(\\d[\\d.,]*\\d|\\d)")
    private val THOUSANDS_SPACE = Regex("(?<=\\d)[ \\u00A0\\u202F](?=\\d{3}$NA)")
    private val THOUSANDS_DOT = Regex("(?<=\\d)\\.(?=\\d{3}$NA)")
    private val THOUSANDS_COMMA = Regex("$NB\\d{1,3}(?:,\\d{3})+$NA(?![,.]\\d)")
    private val NUMBER = Regex("\\d+(?:,\\d+)?")
    private val NUMBER_POINT = Regex("\\d+(?:\\.\\d+)?")
    private val TWENTY_ONE = Regex("${NB}veintiuno (mil|millones)$NA")
    private val ONE_THOUSAND = Regex("${NB}uno (mil|millones)$NA")

    private fun numbers(input: String, lang: String, spell: (Long) -> String): String {
        var t = input
        val es = lang == "es"
        val point = lang in DECIMAL_POINT
        if (es) {
            t = MONEY_CENTS.replace(t, "$1 dólares con $2")
            t = MONEY.replace(t) { it.groupValues[1] + " dólares" }
        }
        t = THOUSANDS_SPACE.replace(t, "")
        if (!point) t = THOUSANDS_DOT.replace(t, "")
        if (es || point) t = THOUSANDS_COMMA.replace(t) { it.value.replace(",", "") }   // "1,500" is a decimal in German
        t = (if (point) NUMBER_POINT else NUMBER).replace(t) { m ->
            val parts = m.value.split(if (point) '.' else ',')
            val whole = parts[0].toLongOrNull() ?: return@replace m.value
            if (parts.size == 1) {
                spell(whole)
            } else {
                val word = DECIMAL_WORD[lang] ?: return@replace m.value
                spell(whole) + " " + word + " " + parts[1].map { spell(it.digitToInt().toLong()) }.joinToString(" ")
            }
        }
        if (es) {                                                  // 21000: veintiún mil, not "veintiuno mil"
            t = TWENTY_ONE.replace(t, "veintiún $1")
            t = ONE_THOUSAND.replace(t, "un $1")
        }
        return t
    }

    private val DECIMAL_WORD = mapOf(
        "es" to "coma", "en" to "point", "fr" to "virgule", "de" to "Komma", "it" to "virgola", "pt" to "vírgula",
        "ca" to "coma", "gl" to "coma", "pl" to "przecinek", "tr" to "virgül", "ru" to "запятая", "nl" to "komma",
        "cs" to "celá", "hu" to "egész", "ar" to "فاصلة", "zh-cn" to "点", "ja" to "点", "ko" to "점", "hi" to "दशमलव",
    )
    private val DECIMAL_POINT = setOf("en", "zh-cn", "ja", "ko", "hi", "ar")         // written 3.5; 3,5 elsewhere

    /**
     * The whole path to the audio. Good voices don't need it, but many open ones read digits,
     * capitals and odd punctuation badly, and it does no harm to the rest.
     */
    fun forVoice(input: String, lang: String, spell: (Long) -> String, pron: Pron = Pron.NONE): String {
        var t = input
        if (lang == "es") for ((rx, rep) in ABBREVIATIONS_ES) t = rx.replace(t, rep)
        t = CLOCK.replace(t, "$1 $2")
        t = normalizePunct(t, lang)
        t = t.split(" ").joinToString(" ") { fixCaps(it, lang, pron) }
        t = lowerInitial(t, lang)
        t = pron.apply(t)
        return numbers(t, lang, spell)
    }
}
