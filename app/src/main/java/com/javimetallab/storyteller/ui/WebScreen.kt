package com.javimetallab.storyteller.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.javimetallab.storyteller.AppViewModel
import com.javimetallab.storyteller.R
import com.javimetallab.storyteller.Screen
import com.javimetallab.storyteller.data.Links

/** [file] comes from the screen, so it follows the language the app is showing right now. */
fun AppViewModel.openPrivacyPolicy(file: String) = go(Screen.Web(R.string.privacy_policy, Links.privacy(file)))

fun AppViewModel.openReportForm() = go(Screen.Web(R.string.report_content, Links.REPORT_FORM))

/** The report form has to work without leaving the app, and the privacy policy goes the same way. */
@Composable
fun WebScreen(vm: AppViewModel, page: Screen.Web) {
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(page.title), onBack = { vm.back() })
        WebPage(page.url, Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().imePadding())
    }
}

// Forms need JavaScript, and the page can't leave its own address.
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebPage(url: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Night.toArgb())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = StayInside(url)
                loadUrl(url)
            }
        },
        onRelease = { it.destroy() },
    )
}

/** Only the page itself is limited: what a form loads to work (scripts, a captcha frame) still comes from its hosts. */
private class StayInside(private val start: String) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.isForMainFrame && !Links.staysInside(start, request.url.toString())
}
