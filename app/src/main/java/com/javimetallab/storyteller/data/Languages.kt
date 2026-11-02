package com.javimetallab.storyteller.data

import java.util.Locale

/** What the book is read in: the user's choice, like the voice. */
object Languages {
    /** The languages of XTTS v2, each written in itself: that's how whoever speaks it finds it. */
    val ALL = listOf(
        "es" to "Español", "en" to "English", "fr" to "Français", "de" to "Deutsch", "it" to "Italiano",
        "pt" to "Português", "nl" to "Nederlands", "pl" to "Polski", "cs" to "Čeština", "hu" to "Magyar",
        "tr" to "Türkçe", "ru" to "Русский", "ar" to "العربية", "hi" to "हिन्दी", "zh-cn" to "中文",
        "ja" to "日本語", "ko" to "한국어",
    )
    val CODES = ALL.map { it.first }

    fun name(code: String) = ALL.firstOrNull { it.first == code }?.second ?: code

    /** Until the user chooses: the phone's, if it is one of them. */
    fun default(): String {
        val phone = Locale.getDefault().language.let { if (it == "zh") "zh-cn" else it }
        return if (phone in CODES) phone else "en"
    }

    /** What Whisper understands: "zh", not "zh-cn". */
    fun forWhisper(code: String) = code.substringBefore('-')
}
