package com.javimetallab.storyteller.data

import java.net.URL

/** The app's only fixed addresses. */
object Links {
    /** The privacy policy in the language the app is showing: [file] comes from the translated strings. */
    fun privacy(file: String) = "https://github.com/javimentallab/Storyteller-Mobile/blob/main/$file"

    /** The Google Form behind "Report content", which Play asks for in apps that generate content with AI. */
    const val REPORT_FORM = "https://docs.google.com/forms/d/e/1FAIpQLSeAp75uOtXTUgbvuwDpWg9llQR_SMirFGVH5SS8BQaXkChMVQ/viewform"

    /** A page opened inside the app stays in its own folder of its own site, on https. */
    fun staysInside(start: String, next: String): Boolean = try {
        val from = URL(start)
        val to = URL(next)
        to.protocol == "https" && to.host.equals(from.host, ignoreCase = true) &&
            to.path.startsWith(from.path.substringBeforeLast('/') + "/")
    } catch (_: Exception) {
        false
    }
}
