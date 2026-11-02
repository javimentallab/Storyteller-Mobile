package com.javimetallab.storyteller.ui

import com.javimetallab.storyteller.data.RemoteBook
import com.javimetallab.storyteller.data.PronStore
import com.javimetallab.storyteller.data.Languages
import androidx.compose.material3.OutlinedTextField
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javimetallab.storyteller.AppViewModel
import com.javimetallab.storyteller.PickedFile
import com.javimetallab.storyteller.R
import com.javimetallab.storyteller.Screen
import com.javimetallab.storyteller.data.Capability
import com.javimetallab.storyteller.data.Format
import com.javimetallab.storyteller.data.TranslationItem
import com.javimetallab.storyteller.engine.Translation
import java.util.Locale

private val EPUB_TYPES = arrayOf("application/epub+zip", "application/octet-stream")
private val AUDIO_TYPES = arrayOf("audio/*", "video/mp4", "application/octet-stream")
private val DOC_TYPES = arrayOf("*/*")
private val TRANSCRIBE_LANGUAGES = listOf("auto", "es", "en", "fr", "de", "it", "pt", "ca", "gl", "eu")

/** When the model for this isn't set up yet: say so, and where. */
@Composable
private fun NeedsModel(vm: AppViewModel, capability: Capability): Boolean {
    if (vm.isConfigured(capability)) return false
    Text(stringResource(R.string.needs_model), color = Danger, modifier = Modifier.padding(vertical = 8.dp))
    PrimaryButton(stringResource(R.string.models), modifier = Modifier.fillMaxWidth()) { vm.go(Screen.Models) }
    return true
}

@Composable
private fun PickButton(label: String, chosen: String?, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        border = BorderStroke(1.dp, Line),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(chosen ?: label, color = if (chosen == null) Amber else Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FilePicker(vm: AppViewModel, label: String, types: Array<String>, file: PickedFile?, onPick: (PickedFile?) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPick(vm.describe(uri))
    }
    val chosen = file?.let { if (it.size > 0) "${it.name} · ${Format.size(it.size)}" else it.name }
    PickButton(label, chosen) { launcher.launch(types) }
}

@Composable
fun NarrateScreen(vm: AppViewModel) {
    val preview by vm.preview.collectAsStateWithLifecycle()
    val reading by vm.reading.collectAsStateWithLifecycle()
    var prepare by rememberSaveable { mutableStateOf(true) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickEpub(uri)
    }
    val endpoint = remember { vm.endpoint(Capability.NARRATION) }

    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.narrate_title), onBack = {
            vm.clearPreview()
            vm.back()
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            FormColumn {
                if (NeedsModel(vm, Capability.NARRATION)) return@FormColumn
                val p = preview
                PickButton(
                    stringResource(if (reading) R.string.reading_file else R.string.choose_epub),
                    p?.name,
                ) { launcher.launch(EPUB_TYPES) }
                if (p != null) {
                    Text(
                        p.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Ink,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        stringResource(
                            R.string.narrate_summary,
                            LocalContext.current.resources.getQuantityString(R.plurals.chapter_count, p.chapters, p.chapters),
                            p.sentences, p.chars,
                        ),
                        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
                    )
                    // Each character may cost money: better said before than after.
                    Text(
                        stringResource(R.string.narrate_goes_to, p.chars, Format.host(endpoint.url)),
                        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(stringResource(R.string.prepare_text), color = Ink)
                        Text(stringResource(R.string.prepare_text_help), color = Muted, fontSize = 12.sp)
                    }
                    Switch(
                        checked = prepare, onCheckedChange = { prepare = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Amber, checkedThumbColor = OnAmber),
                    )
                }
                PrimaryButton(
                    stringResource(R.string.start),
                    enabled = p != null && p.chapters > 0,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                ) { vm.narrate(p!!, prepare) }
                if (p != null && p.chapters == 0) {
                    Text(stringResource(R.string.no_chapters), color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
fun ImportScreen(vm: AppViewModel) {
    var file by remember { mutableStateOf<PickedFile?>(null) }
    var language by rememberSaveable { mutableStateOf(Locale.getDefault().language.takeIf { it in TRANSCRIBE_LANGUAGES } ?: "auto") }

    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.import_title), onBack = { vm.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            FormColumn {
                Text(stringResource(R.string.import_help), color = Muted, modifier = Modifier.padding(bottom = 10.dp))
                if (NeedsModel(vm, Capability.TRANSCRIPTION)) return@FormColumn
                FilePicker(vm, stringResource(R.string.choose_audio), AUDIO_TYPES, file) { file = it }
                val detect = stringResource(R.string.detect_language)
                Picker(
                    stringResource(R.string.audio_language), TRANSCRIBE_LANGUAGES, language,
                    { if (it == "auto") detect else languageName(it) },
                ) { language = it }
                PrimaryButton(
                    stringResource(R.string.start),
                    enabled = file != null,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                ) { vm.transcribe(file!!, language) }
            }
        }
    }
}

@Composable
fun TranslateScreen(vm: AppViewModel) {
    val items by vm.translations.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val queued by vm.queued.collectAsStateWithLifecycle()
    var file by remember { mutableStateOf<PickedFile?>(null) }
    val phone = Locale.getDefault().language
    var tgt by rememberSaveable { mutableStateOf(phone.takeIf { it in Translation.LANGUAGES } ?: "es") }
    var src by rememberSaveable { mutableStateOf(if (tgt != "en") "en" else "es") }
    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.translate_title), onBack = { vm.back() })
        job?.takeIf { it.kind == "translate" }?.let { JobPanel(vm, it, queued) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            FormColumn {
                Text(stringResource(R.string.translate_help), color = Muted, modifier = Modifier.padding(bottom = 10.dp))
                if (NeedsModel(vm, Capability.TRANSLATION)) return@FormColumn
                FilePicker(vm, stringResource(R.string.choose_document), DOC_TYPES, file) { file = it }
                Picker(stringResource(R.string.from_language), Translation.LANGUAGES, src, { languageName(it) }) { src = it }
                Picker(stringResource(R.string.to_language), Translation.LANGUAGES, tgt, { languageName(it) }) { tgt = it }
                if (src == tgt) Text(stringResource(R.string.same_language), color = Danger, fontSize = 13.sp)
                Text(
                    stringResource(R.string.translate_goes_to, Format.host(vm.endpoint(Capability.TRANSLATION).url)),
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
                )
                PrimaryButton(
                    stringResource(R.string.start),
                    enabled = file != null && src != tgt,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                ) {
                    vm.translate(file!!, src, tgt)
                    file = null
                }
                if (items.isNotEmpty()) {
                    Text(
                        stringResource(R.string.translations_list),
                        fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Ink,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                    for (t in items) TranslationCard(vm, t, job?.translationId == t.id)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranslationCard(vm: AppViewModel, t: TranslationItem, running: Boolean) {
    var confirm by remember { mutableStateOf(false) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.saveTranslation(t, uri)
    }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .background(Panel, RoundedCornerShape(12.dp))
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(t.name, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val state = when {
            running -> stringResource(R.string.translation_running)
            t.state == "done" -> stringResource(R.string.translation_done)
            t.total > 0 -> stringResource(R.string.translation_partial, t.done, t.total)
            else -> stringResource(R.string.translation_partial_short)
        }
        Text(
            "${languageName(t.src)} → ${languageName(t.tgt)} · $state",
            color = if (t.state == "done" && !running) Muted else Amber, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp),
        )
        if (!running) {
            // Wraps instead of cutting a word in two: with three buttons in some languages they don't fit in a row.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val tight = PaddingValues(horizontal = 8.dp)
                if (t.state != "done") {
                    TextButton(onClick = { vm.resumeTranslation(t) }, contentPadding = tight) {
                        Text(stringResource(R.string.resume), maxLines = 1, softWrap = false)
                    }
                }
                if (t.outputSize > 0) {
                    TextButton(onClick = { saver.launch(t.outputName) }, contentPadding = tight) {
                        Text(stringResource(R.string.save_to_phone), maxLines = 1, softWrap = false)
                    }
                }
                TextButton(onClick = { confirm = true }, contentPadding = tight) {
                    Text(stringResource(R.string.delete), color = Danger, maxLines = 1, softWrap = false)
                }
            }
        }
    }
    if (confirm) {
        ConfirmDialog(
            stringResource(R.string.confirm_delete_translation, t.name), stringResource(R.string.delete),
            { vm.deleteTranslation(t) }, { confirm = false },
        )
    }
}

/** The dictionary of the language being narrated. The same words mean something else in another language. */
@Composable
fun PronScreen(vm: AppViewModel) {
    val lang = remember { vm.endpoint(Capability.NARRATION).language }
    val start = remember { vm.pronTexts(lang) }
    var words by rememberSaveable { mutableStateOf(start.words) }
    var letters by rememberSaveable { mutableStateOf(start.letters) }
    var asWords by rememberSaveable { mutableStateOf(start.asWords) }
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.pron_title), onBack = { vm.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            FormColumn {
                Text(stringResource(R.string.pron_help, Languages.name(lang)), color = Muted, fontSize = 13.sp)
                OutlinedTextField(
                    value = words, onValueChange = { words = it },
                    label = { Text(stringResource(R.string.pron_words)) },
                    placeholder = { Text("software = sófwer") }, minLines = 6,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                OutlinedTextField(
                    value = letters, onValueChange = { letters = it },
                    label = { Text(stringResource(R.string.pron_letters)) }, placeholder = { Text("USB, CPU") },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                OutlinedTextField(
                    value = asWords, onValueChange = { asWords = it },
                    label = { Text(stringResource(R.string.pron_as_words)) }, placeholder = { Text("NASA, UNESCO") },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                PrimaryButton(stringResource(R.string.pron_save), modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
                    vm.savePron(lang, PronStore.Texts(words, letters, asWords))
                    vm.back()
                }
            }
        }
    }
}

/** Books narrated on the desktop, from the user's own server: its URL is the one for narration. */
@Composable
fun FetchScreen(vm: AppViewModel) {
    var books by remember { mutableStateOf<List<RemoteBook>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val here by vm.books.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        val (found, why) = vm.remoteBooks()
        books = found
        failure = why
        loading = false
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.fetch_title), onBack = { vm.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            FormColumn {
                if (NeedsModel(vm, Capability.NARRATION)) return@FormColumn
                Text(stringResource(R.string.fetch_help), color = Muted, fontSize = 13.sp)
                if (loading) Text(stringResource(R.string.fetch_loading), color = Muted, modifier = Modifier.padding(top = 12.dp))
                failure?.let { Text(it, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
                books?.let { list ->
                    if (list.isEmpty()) Text(stringResource(R.string.fetch_empty), color = Muted, modifier = Modifier.padding(top = 12.dp))
                    for (b in list) {
                        val mine = here.firstOrNull { it.id == vm.remoteFolder(b) }
                        val chapters = b.manifest.length()
                        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(b.title, color = Ink, fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.fetch_summary, chapters, b.total, Format.size(b.size)),
                                    color = Muted, fontSize = 13.sp,
                                )
                            }
                            if (mine != null && mine.done >= chapters) {
                                Text(stringResource(R.string.fetch_have_it), color = Muted, fontSize = 13.sp)
                            } else {
                                PrimaryButton(stringResource(R.string.fetch_bring)) {
                                    vm.fetchBook(b)
                                    vm.back()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
