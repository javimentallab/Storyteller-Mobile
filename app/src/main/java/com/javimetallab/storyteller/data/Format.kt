package com.javimetallab.storyteller.data

import java.net.URI

/** Plain functions with no Android in them, covered by the unit tests. */
object Format {
    private val ENDPOINTS = listOf("/audio/speech", "/audio/transcriptions", "/chat/completions", "/models")

    /** "1:05", or "1:02:05" from one hour on: an imported audiobook is one very long chapter. */
    fun time(ms: Long): String {
        val s = (ms.coerceAtLeast(0)) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "mm:ss", "h:mm:ss" or plain seconds → milliseconds. Null if it isn't a position. */
    fun parsePosition(text: String): Long? {
        val parts = text.trim().split(":")
        if (parts.isEmpty() || parts.size > 3) return null
        if (parts.any { p -> p.isEmpty() || p.length > 7 || !p.all(Char::isDigit) }) return null
        val numbers = parts.map { it.toLong() }
        if (numbers.size > 1 && numbers.drop(1).any { it >= 60 }) return null
        return numbers.fold(0L) { acc, n -> acc * 60 + n } * 1000
    }

    /**
     * What the user typed → the base of an OpenAI-style API, or null if it can't be one.
     * Forgiving: "192.168.1.5:8000" gets http and /v1, and a pasted full endpoint loses its tail.
     */
    fun apiBase(text: String): String? {
        var t = text.trim().trimEnd('/')
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val hadScheme = t.startsWith("http://", true) || t.startsWith("https://", true)
        if (t.contains("://") && !hadScheme) return null
        if (!hadScheme) t = "http://$t"
        val uri = try {
            URI(t)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host ?: return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        var path = uri.path.orEmpty().trimEnd('/')
        for (tail in ENDPOINTS) path = path.removeSuffix(tail)
        path = path.trimEnd('/')
        if (path.isEmpty()) path = "/v1"
        val scheme = if (uri.scheme.equals("https", true)) "https" else "http"
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "$scheme://$host$port$path"
    }

    /** Plain HTTP beyond the home network would send the key and the book in the clear. */
    fun isUnsafeUrl(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return true
        }
        return uri.scheme != "https" && !isPrivateHost(uri.host.orEmpty())
    }

    /** An access key spends the user's money: it never goes in the clear beyond the home network. */
    fun mayCarryKey(url: String): Boolean = !isUnsafeUrl(url)

    /** The name to show for a service, also when the URL was typed without http://. */
    fun host(url: String): String = try {
        URI(apiBase(url) ?: url).host.orEmpty()
    } catch (_: Exception) {
        ""
    }

    private val PRIVATE_SUFFIXES = listOf(".localhost", ".local", ".lan", ".home", ".internal", ".intranet", ".home.arpa", ".ts.net")

    private fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.startsWith("[")) {
            val ip = h.removeSurrounding("[", "]")
            return ip == "::1" || ip.startsWith("fc") || ip.startsWith("fd") || Regex("fe[89ab].*").matches(ip)
        }
        // "nas", "mint": a name with no dot only exists at home. "134744072" is a public address in disguise.
        if (h.isNotEmpty() && '.' !in h && h.any { it.isLetter() } && !h.startsWith("0x")) return true
        if (h == "localhost" || PRIVATE_SUFFIXES.any { h.endsWith(it) }) return true
        val parts = h.split(".")
        if (parts.any { it.length > 1 && it.startsWith("0") }) return false     // read as octal: 010.0.0.1 is 8.0.0.1
        val p = parts.map { it.toIntOrNull() }
        if (p.size != 4 || p.any { it == null || it !in 0..255 }) return false
        val (a, b) = p[0]!! to p[1]!!
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) ||
            (a == 169 && b == 254) || (a == 100 && b in 64..127) // the last one: Tailscale and the like
    }

    enum class CueScroll { NONE, TOP, FOLLOW }

    /** What the list of sentences does: no list, nothing; no sentence playing, back to the top; a sentence
     *  playing, go to it, unless the user touched the list a moment ago and is reading elsewhere. */
    fun cueScroll(cues: Int, active: Int, sinceTouchMs: Long, pauseMs: Long): CueScroll = when {
        cues == 0 -> CueScroll.NONE
        active < 0 -> CueScroll.TOP
        sinceTouchMs > pauseMs -> CueScroll.FOLLOW
        else -> CueScroll.NONE
    }

    /** The cue that is playing at this moment: the last one that has started. -1 before the first.*/
    fun cueAt(cues: List<Cue>, seconds: Double): Int {
        var lo = 0
        var hi = cues.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].start <= seconds) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /** Nameless sections get "Capitulo N" from the engine: that is not a title worth showing. */
    fun chapterTitleIsGeneric(title: String): Boolean =
        title.isBlank() || Regex("Capitulo \\d+").matches(title.trim())

    fun size(bytes: Long): String {
        val gb = bytes / 1e9
        return when {
            gb >= 1 -> "%.1f GB".format(gb)
            bytes >= 1_000_000 -> "%d MB".format(bytes / 1_000_000)
            else -> "%d KB".format((bytes / 1000).coerceAtLeast(1))
        }
    }

    /** A folder name for a title: only what is safe in a path. */
    fun slug(name: String): String {
        val plain = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD)
            .replace(Regex("[^\\p{ASCII}]"), "")
        val s = plain.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').lowercase().take(120).trim('-')
        return s.ifEmpty { "book" }
    }
}
