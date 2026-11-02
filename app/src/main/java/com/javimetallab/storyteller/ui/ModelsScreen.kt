package com.javimetallab.storyteller.ui

import com.javimetallab.storyteller.data.Languages
import com.javimetallab.storyteller.Screen
import com.javimetallab.storyteller.PickedFile
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javimetallab.storyteller.AppViewModel
import com.javimetallab.storyteller.R
import com.javimetallab.storyteller.data.Capability
import com.javimetallab.storyteller.data.Endpoint
import com.javimetallab.storyteller.data.Format
import com.javimetallab.storyteller.data.Prefs
import kotlinx.coroutines.launch

/** Just to fill in the address. Everything else is up to the user, and any other URL goes too. */
private data class Preset(val name: String, val url: String, val has: Set<Capability>)

private val ALL = setOf(Capability.NARRATION, Capability.TRANSCRIPTION, Capability.TRANSLATION)
private val PRESETS = listOf(
    Preset("OpenAI", "https://api.openai.com/v1", ALL),
    Preset("Groq", "https://api.groq.com/openai/v1", ALL),
    Preset("OpenRouter", "https://openrouter.ai/api/v1", setOf(Capability.TRANSLATION)),
)
private val FORMATS = listOf("wav", "mp3", "opus", "flac", "aac")

@Composable
fun ModelsScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.models), onBack = { vm.back() })
        Column(Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState())) {
            FormColumn {
                Text(stringResource(R.string.models_intro), color = Muted, modifier = Modifier.padding(bottom = 12.dp))
                ModelCard(vm, Capability.NARRATION, stringResource(R.string.section_narration), stringResource(R.string.section_narration_help))
                ModelCard(vm, Capability.TRANSCRIPTION, stringResource(R.string.section_transcription), stringResource(R.string.section_transcription_help))
                ModelCard(vm, Capability.TRANSLATION, stringResource(R.string.section_translation), stringResource(R.string.section_translation_help))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(vm: AppViewModel, c: Capability, title: String, help: String) {
    var e by remember { mutableStateOf(vm.endpoint(c)) }
    var showKey by remember { mutableStateOf(false) }
    val tests by vm.tests.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    fun change(next: Endpoint) {
        e = next
        vm.saveEndpoint(c, next)
    }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 14.dp)
            .background(Panel, RoundedCornerShape(12.dp))
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Ink)
        Text(help, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in PRESETS.filter { c in it.has }) {
                AssistChip(
                    onClick = { change(e.copy(url = p.url, model = "", voice = "")) },
                    label = { Text(p.name) },
                    colors = AssistChipDefaults.assistChipColors(labelColor = Ink),
                )
            }
        }
        OutlinedTextField(
            value = e.url,
            onValueChange = { change(e.copy(url = it.trim())) },
            label = { Text(stringResource(R.string.field_url)) },
            placeholder = { Text("https://…/v1") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            isError = e.url.isNotBlank() && Format.apiBase(e.url) == null,
            modifier = Modifier.fillMaxWidth(),
        )
        val base = Format.apiBase(e.url)
        if (base != null && Format.isUnsafeUrl(base)) {
            Text(stringResource(R.string.cleartext_warning), color = Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        OutlinedTextField(
            value = e.key,
            onValueChange = { change(e.copy(key = it.trim())) },
            label = { Text(stringResource(R.string.field_key)) },
            placeholder = { Text(stringResource(R.string.field_key_hint)) },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        stringResource(if (showKey) R.string.hide_key else R.string.show_key), tint = Muted,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        ListedField(
            label = stringResource(R.string.field_model), value = e.model,
            search = stringResource(R.string.search_models),
            load = { vm.listModels(e) },
            enabled = base != null,
        ) { change(e.copy(model = it)) }
        if (c == Capability.NARRATION) {
            Picker(stringResource(R.string.field_language), Languages.CODES, e.language, { Languages.name(it) }) { change(e.copy(language = it)) }
            Text(stringResource(R.string.field_language_help), color = Muted, fontSize = 12.sp)
            ListedField(
                label = stringResource(R.string.field_voice), value = e.voice,
                search = stringResource(R.string.search_voices),
                load = { vm.listVoices(e) },
                enabled = base != null,
            ) { change(e.copy(voice = it)) }
            VoiceUpload(vm, e, base != null) { change(e.copy(voice = it)) }
            Picker(stringResource(R.string.field_format), FORMATS, e.format, { it }) { change(e.copy(format = it)) }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.qc_title), color = Ink)
                    Text(stringResource(R.string.qc_help), color = Muted, fontSize = 12.sp)
                }
                Switch(
                    checked = e.qc, onCheckedChange = { change(e.copy(qc = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Amber, checkedThumbColor = OnAmber),
                )
            }
            TextButton(onClick = { vm.go(Screen.Pron) }) { Text(stringResource(R.string.pron_title), color = Amber) }
        }
        if (c != Capability.TRANSCRIPTION) {
            Text(stringResource(R.string.field_parallel), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in 1..Prefs.MAX_PARALLEL) {
                    FilterChip(
                        selected = k == e.parallel, onClick = { change(e.copy(parallel = k)) }, label = { Text("$k") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber, selectedLabelColor = OnAmber, labelColor = Ink,
                        ),
                    )
                }
            }
            Text(stringResource(R.string.field_parallel_help), color = Muted, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                stringResource(if (testing == c) R.string.testing else R.string.test),
                enabled = base != null && testing == null,
            ) { vm.testModel(c, e) }
        }
        tests[c]?.let { r ->
            Text(r.text, color = if (r.ok) Amber else Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private val NOT_IN_VOICE_NAME = Regex("[^A-Za-z0-9._-]+")

/** An audio file of the phone becomes a voice of the service. Only the user's own server takes it. */
@Composable
private fun VoiceUpload(vm: AppViewModel, e: Endpoint, enabled: Boolean, onUploaded: (String) -> Unit) {
    var picked by remember { mutableStateOf<PickedFile?>(null) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val f = uri?.let { vm.describe(it) } ?: return@rememberLauncherForActivityResult
        picked = f
        name = NOT_IN_VOICE_NAME.replace(f.name.substringBeforeLast('.'), "-").trim('-', '.').take(60)
        failure = null
    }
    TextButton(enabled = enabled && !busy, onClick = { launcher.launch(arrayOf("audio/*")) }) {
        Text(stringResource(if (busy) R.string.voice_uploading else R.string.voice_upload), color = if (enabled) Amber else Muted)
    }
    Text(stringResource(R.string.voice_upload_help), color = Muted, fontSize = 12.sp)
    failure?.let { Text(it, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
    val f = picked ?: return
    AlertDialog(
        onDismissRequest = { picked = null },
        title = { Text(stringResource(R.string.voice_upload)) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it.trim() },
                label = { Text(stringResource(R.string.voice_name)) }, singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val chosen = name
                picked = null
                busy = true
                scope.launch {
                    val why = vm.uploadVoice(e, f, chosen)
                    busy = false
                    failure = why
                    if (why == null) onUploaded(chosen)
                }
            }) { Text(stringResource(R.string.voice_upload_send)) }
        },
        dismissButton = { TextButton(onClick = { picked = null }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** A name typed by hand, or picked from what the service says it has. */
@Composable
private fun ListedField(
    label: String,
    value: String,
    search: String,
    load: suspend () -> List<String>?,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf<List<String>>(emptyList()) }
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.trim()) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    Box {
        TextButton(
            enabled = enabled && !busy,
            onClick = {
                busy = true
                scope.launch {
                    val got = load()
                    busy = false
                    if (!got.isNullOrEmpty()) {
                        options = got
                        open = true
                    }
                }
            },
        ) { Text(search, fontSize = 13.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (o in options) {
                DropdownMenuItem(
                    text = { Text(o, color = Ink) },
                    onClick = {
                        open = false
                        onChange(o)
                    },
                )
            }
        }
    }
}
