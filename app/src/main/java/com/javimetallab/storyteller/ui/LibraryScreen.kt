package com.javimetallab.storyteller.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javimetallab.storyteller.AppViewModel
import com.javimetallab.storyteller.R
import com.javimetallab.storyteller.Screen
import com.javimetallab.storyteller.data.BookSummary
import com.javimetallab.storyteller.data.Format
import kotlinx.coroutines.delay

@Composable
fun LibraryScreen(vm: AppViewModel) {
    val books by vm.books.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val queued by vm.queued.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.library), onBack = null) {
            IconButton(onClick = { vm.go(Screen.Models) }) {
                Icon(Icons.Filled.Tune, stringResource(R.string.models), tint = Muted)
            }
        }
        job?.let { JobPanel(vm, it, queued) }
        ActionRow(vm)
        Box(Modifier.weight(1f)) {
            if (books.isEmpty()) {
                if (loaded) {
                    Text(
                        stringResource(R.string.library_empty),
                        color = Muted, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(14.dp),
                ) {
                    items(books, key = { it.id }) { BookCard(vm, it, job?.bookId == it.id) }
                }
            }
        }
        MiniPlayer(vm)
    }
}

@Composable
private fun ActionRow(vm: AppViewModel) {
    TextButton(
        onClick = { vm.go(Screen.Fetch) },
        modifier = Modifier.padding(start = 6.dp, top = 4.dp),
    ) { Text(stringResource(R.string.fetch_title), color = Muted, fontSize = 13.sp) }
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PrimaryButton(stringResource(R.string.narrate_epub), modifier = Modifier.weight(1f)) { vm.go(Screen.Narrate) }
        OutlinedButton(
            onClick = { vm.go(Screen.Import) },
            modifier = Modifier.weight(1f),
            border = BorderStroke(1.dp, Line),
            shape = RoundedCornerShape(10.dp),
        ) { Text(stringResource(R.string.import_audiobook), color = Ink, maxLines = 2, textAlign = TextAlign.Center, fontSize = 13.sp) }
        OutlinedButton(
            onClick = { vm.go(Screen.Translate) },
            border = BorderStroke(1.dp, Line),
            shape = RoundedCornerShape(10.dp),
        ) { Text(stringResource(R.string.translate), color = Ink, maxLines = 1, fontSize = 13.sp) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookCard(vm: AppViewModel, b: BookSummary, active: Boolean) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val running by vm.job.collectAsStateWithLifecycle()
    val waiting by vm.waiting.collectAsStateWithLifecycle()
    val inLine = waiting.firstOrNull { it.bookId == b.id }

    Row(
        Modifier.fillMaxWidth()
            .background(Panel, RoundedCornerShape(12.dp))
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .clickable(enabled = b.chapters > 0) { vm.openBook(b.id, b.title) }
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                b.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                LocalContext.current.resources.getQuantityString(
                    R.plurals.book_chapters, b.chapters, b.chapters, Format.time((b.duration * 1000).toLong()),
                ),
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
            )
            when {
                active -> Text(stringResource(R.string.book_working), color = Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                inLine != null -> Text(stringResource(R.string.book_in_line), color = Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                !b.complete -> Text(stringResource(R.string.book_partial, b.progress), color = Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            // Wraps: three buttons need about 300 dp, and a 360 dp phone leaves 268 here.
            FlowRow {
                // What is narrated can be heard while the rest is being narrated or waits its turn.
                if (b.chapters > 0) {
                    TextButton(onClick = { vm.openBook(b.id, b.title) }, modifier = Modifier.padding(top = 2.dp)) {
                        Text(stringResource(R.string.listen))
                    }
                }
                when {
                    active -> {}
                    inLine != null -> TextButton(onClick = { vm.switchToJob(inLine.id) }, modifier = Modifier.padding(top = 2.dp)) {
                        Text(stringResource(R.string.job_switch))
                    }
                    b.resumable && running != null -> {
                        TextButton(onClick = { vm.switchToBook(b) }, modifier = Modifier.padding(top = 2.dp)) { Text(stringResource(R.string.job_switch)) }
                        TextButton(onClick = { vm.resumeBook(b) }, modifier = Modifier.padding(top = 2.dp)) { Text(stringResource(R.string.job_add_to_line)) }
                    }
                    b.resumable -> TextButton(onClick = { vm.resumeBook(b) }, modifier = Modifier.padding(top = 2.dp)) {
                        Text(stringResource(R.string.resume))
                    }
                }
            }
        }
        if (!active && inLine == null) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options), tint = Muted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (b.hasSource) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.regenerate)) },
                            onClick = {
                                menu = false
                                confirm = "regenerate"
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = Danger) },
                        onClick = {
                            menu = false
                            confirm = "delete"
                        },
                    )
                }
            }
        }
    }
    when (confirm) {
        "delete" -> ConfirmDialog(
            stringResource(R.string.confirm_delete, b.title), stringResource(R.string.delete),
            { vm.deleteBook(b.id) }, { confirm = null },
        )
        "regenerate" -> ConfirmDialog(
            stringResource(R.string.confirm_regenerate, b.title), stringResource(R.string.regenerate),
            { vm.regenerateBook(b) }, { confirm = null },
        )
    }
}

/** What is playing, on the way back to the library. */
@Composable
private fun MiniPlayer(vm: AppViewModel) {
    val ui by vm.player.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        while (true) {
            vm.player.tick()
            delay(500)
        }
    }
    if (ui.bookId == null || ui.chapters.isEmpty()) {
        Box(Modifier.navigationBarsPadding())
        return
    }
    Column(Modifier.fillMaxWidth().background(Panel).navigationBarsPadding()) {
        if (ui.durationMs > 0) {
            LinearProgressIndicator(
                progress = { (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(), color = Amber, trackColor = Line,
            )
        }
        Row(
            Modifier.fillMaxWidth().clickable { vm.openPlayer() }.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ui.title, color = Ink, fontFamily = FontFamily.Serif, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = vm.player::playPause) {
                Icon(
                    if (ui.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    stringResource(if (ui.playing) R.string.pause else R.string.play), tint = Amber,
                )
            }
        }
    }
}
