package com.javimetallab.storyteller.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.javimetallab.storyteller.AppViewModel
import com.javimetallab.storyteller.R
import com.javimetallab.storyteller.data.Format
import com.javimetallab.storyteller.engine.JobState
import com.javimetallab.storyteller.engine.Stage
import java.text.DateFormat
import java.util.Date

// The colours of the desktop app.
val Night = Color(0xFF0F1420)
val Panel = Color(0xFF18202E)
val Line = Color(0xFF263246)
val Ink = Color(0xFFE6ECF5)
val Muted = Color(0xFF8C9AB0)
val Accent = Color(0xFF5CC8E0)
val OnAccent = Color(0xFF08171C)
val Danger = Color(0xFFFF8A80)
val ActiveCue = Accent.copy(alpha = 0.17f)

@Composable
fun StorytellerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = OnAccent, secondary = Accent, onSecondary = OnAccent,
            background = Night, onBackground = Ink, surface = Night, onSurface = Ink,
            surfaceVariant = Panel, onSurfaceVariant = Muted, outline = Line, error = Danger,
            surfaceContainer = Panel, surfaceContainerHigh = Panel, surfaceContainerHighest = Panel,
            surfaceContainerLow = Panel, secondaryContainer = Line, onSecondaryContainer = Ink,
        ),
        content = content,
    )
}

/** Text for an error code. Unknown codes still say which one. */
@StringRes
private fun errorRes(code: String): Int? = when (code) {
    "model_auth" -> R.string.err_auth
    "model_not_found" -> R.string.err_not_found
    "model_rate_limit" -> R.string.err_rate_limit
    "model_bad_request" -> R.string.err_bad_request
    "model_server" -> R.string.err_server
    "model_bad_answer" -> R.string.err_bad_answer
    "network" -> R.string.err_network
    "bad_type" -> R.string.err_bad_type
    "cant_read_file" -> R.string.err_cant_read_file
    "no_text" -> R.string.err_no_text
    "no_text_pdf" -> R.string.err_no_text_pdf
    else -> null
}

/** The scaffold of every screen, with its one-off notices. */
@Composable
fun AppFrame(vm: AppViewModel, content: @Composable () -> Unit) {
    val snack = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    LaunchedEffect(vm) {
        vm.notices.collect { n ->
            val text = when (n) {
                is AppViewModel.Notice.Text -> ctx.getString(n.res)
                is AppViewModel.Notice.Code -> {
                    val base = errorRes(n.code)?.let { ctx.getString(it) } ?: ctx.getString(R.string.err_generic, n.code)
                    // What the service said is usually the part that explains it.
                    if (n.detail.isNullOrBlank()) base else base + "\n" + n.detail.take(200)
                }
            }
            snack.showSnackbar(text, withDismissAction = true)
        }
    }
    // Each screen pads itself around the system bars: its panels reach under them.
    Scaffold(
        containerColor = Night,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snack, Modifier.navigationBarsPadding()) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) { content() }
    }
}

/** Two lines, as some titles don't fit in one in every language. A book's title can be any length: pass 1 for it. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, titleLines: Int = 2, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(Panel).statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Ink)
            }
        }
        Text(
            title,
            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Ink,
            maxLines = titleLines, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = if (onBack == null) 12.dp else 4.dp, vertical = 12.dp),
        )
        actions()
    }
}

/** A labelled choice: a button that opens a list. */
@Composable
fun <T> Picker(
    label: String,
    options: List<T>,
    selected: T?,
    text: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
        Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Line),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    selected?.let { text(it) } ?: "—",
                    color = Ink, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Filled.ArrowDropDown, null, tint = Muted)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (o in options) {
                    DropdownMenuItem(
                        text = { Text(text(o), color = Ink) },
                        onClick = {
                            open = false
                            onSelect(o)
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent, contentColor = OnAccent, disabledContainerColor = Line, disabledContentColor = Muted,
        ),
        shape = RoundedCornerShape(10.dp),
    ) { Text(text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
}

@Composable
fun ConfirmDialog(text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, dismiss: String? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        text = { Text(text, color = Ink) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(confirm, color = Danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss ?: stringResource(R.string.cancel)) } },
    )
}

/** Where a job is, in words. */
@Composable
fun jobText(job: JobState): String {
    val base = when (job.stage) {
        Stage.QUEUED -> stringResource(R.string.job_queued)
        Stage.COPYING -> stringResource(R.string.job_copying)
        Stage.READING -> stringResource(R.string.job_reading)
        Stage.NARRATING -> stringResource(R.string.job_narrating, job.a, job.b, job.c)
        Stage.ENCODING -> stringResource(R.string.job_encoding)
        Stage.TRANSCRIBING -> stringResource(R.string.job_transcribing, Format.time(job.posMs), Format.time(job.durMs))
        Stage.TRANSLATING -> stringResource(R.string.job_translating, job.a, job.b)
        Stage.SAVING -> stringResource(R.string.job_saving)
    }
    val parts = mutableListOf(base)
    when {
        job.stopping -> parts += stringResource(R.string.job_stopping)
        job.waitingMs > 0 -> parts += stringResource(R.string.job_waiting, ((job.waitingMs + 999) / 1000).toInt())
        job.stage == Stage.NARRATING || job.stage == Stage.TRANSLATING -> {
            val eta = job.etaSec
            parts += if (eta != null) etaText(eta) else stringResource(R.string.eta_calculating)
        }
    }
    return parts.joinToString(" — ")
}

@Composable
private fun etaText(seconds: Double): String {
    val minutes = Math.round(seconds / 60).toInt()
    val left = when {
        minutes < 1 -> stringResource(R.string.eta_less_minute)
        minutes < 60 -> stringResource(R.string.eta_minutes, minutes)
        else -> stringResource(R.string.eta_hours, minutes / 60, minutes % 60)
    }
    if (minutes < 5) return left
    val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + (seconds * 1000).toLong()))
    return stringResource(R.string.eta_until, left, at)
}

/** The job in progress, with its two ways of stopping. */
@Composable
fun JobPanel(vm: AppViewModel, job: JobState, queued: Int) {
    var askCancel by remember { mutableStateOf(false) }
    val waiting by vm.waiting.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(job.title, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(jobText(job), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
        if (job.pct >= 0) {
            LinearProgressIndicator(
                progress = { job.pct / 100f }, modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Night,
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = Night)
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { vm.stopJob(save = true) }, enabled = !job.stopping) { Text(stringResource(R.string.stop_and_save)) }
            TextButton(onClick = { askCancel = true }, enabled = !job.stopping) { Text(stringResource(R.string.cancel), color = Muted) }
        }
        for (w in waiting) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.job_waiting_turn, w.title), color = Muted, fontSize = 13.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.switchToJob(w.id) }, enabled = !job.stopping) { Text(stringResource(R.string.job_switch)) }
            }
        }
    }
    if (askCancel) {
        ConfirmDialog(
            text = stringResource(R.string.confirm_cancel_job, job.title),
            confirm = stringResource(R.string.cancel),
            onConfirm = { vm.stopJob(save = false) },
            onDismiss = { askCancel = false },
            // Both buttons saying "Cancel" is how the wrong one gets pressed.
            dismiss = stringResource(R.string.keep_going),
        )
    }
}

@Composable
fun FormColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { content() }
}

/** A language code in the phone's language. */
fun languageName(code: String): String {
    val name = java.util.Locale.forLanguageTag(code).displayLanguage
    return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }.ifEmpty { code }
}
