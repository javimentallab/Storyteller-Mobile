package com.javimetallab.storyteller.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javimetallab.storyteller.AppViewModel
import com.javimetallab.storyteller.R
import com.javimetallab.storyteller.data.Chapter
import com.javimetallab.storyteller.data.Format
import com.javimetallab.storyteller.player.PlayerUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** "23 · 18. Find the Sweet Spot". The number is the section's in the EPUB, not its place in the list. */
@Composable
private fun chapterLabel(c: Chapter): String {
    val title = if (Format.chapterTitleIsGeneric(c.title)) stringResource(R.string.section_n, c.id) else c.title.trim()
    return "${c.id} · $title"
}

@Composable
fun PlayerScreen(vm: AppViewModel) {
    val ui by vm.player.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        while (true) {
            vm.player.tick()
            delay(200)
        }
    }
    var menu by remember { mutableStateOf(false) }
    var askChapter by remember { mutableStateOf(false) }
    var askPosition by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar(ui.title, onBack = { vm.back() }, titleLines = 1) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options), tint = Muted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.go_to_position)) },
                        onClick = {
                            menu = false
                            askPosition = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.report_content)) },
                        onClick = {
                            menu = false
                            vm.openReportForm()
                        },
                    )
                }
            }
        }
        when {
            ui.loading -> Box(Modifier.weight(1f).fillMaxWidth()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
            }
            ui.failed || ui.chapters.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth()) {
                Text(stringResource(R.string.cant_open_book), color = Muted, modifier = Modifier.align(Alignment.Center).padding(32.dp))
            }
            else -> {
                ChapterBar(ui) { askChapter = true }
                CueList(ui, vm, Modifier.weight(1f))
                Controls(ui, vm)
            }
        }
    }
    if (askChapter) {
        ChapterDialog(ui, onPick = {
            askChapter = false
            vm.player.goToChapter(it)
        }, onDismiss = { askChapter = false })
    }
    if (askPosition) {
        PositionDialog(onGo = {
            askPosition = false
            vm.player.seekTo(it)
        }, onDismiss = { askPosition = false })
    }
}

@Composable
private fun ChapterBar(ui: PlayerUi, onOpen: () -> Unit) {
    val ch = ui.chapters.getOrNull(ui.index) ?: return
    Row(
        Modifier.fillMaxWidth().background(Panel).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(chapterLabel(ch), color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The printed book's page if the EPUB has it, else an approximate one ("≈"), and how far into the book.
            val page = ui.page?.let { p ->
                stringResource(if (p.real) R.string.page_real else R.string.page_approx, p.label, p.total, p.percent)
            }
            val status = when {
                ui.waiting -> stringResource(R.string.waiting_more)
                ui.live -> stringResource(R.string.live)
                else -> null
            }
            if (page != null || status != null) PageLine(page, status)
        }
        Icon(Icons.Filled.ArrowDropDown, stringResource(R.string.go_to_chapter), tint = Muted)
    }
}

/**
 * The page and the status ("Live", "Waiting for more narration…") on one line when both fit. When they don't,
 * the status goes on a line of its own: in a row, the status took its width first and the page was cut short.
 */
@Composable
private fun PageLine(page: String?, status: String?) {
    Layout(content = {
        if (page != null) Text(page, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (page != null && status != null) Text("  ·  ", color = Muted, fontSize = 12.sp, maxLines = 1)
        if (status != null) Text(status, color = Accent, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }) { measurables, constraints ->
        val parts = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        if (parts.size == 1) return@Layout layout(parts[0].width, parts[0].height) { parts[0].place(0, 0) }
        val (first, dot, second) = parts
        val row = first.width + dot.width + second.width
        if (row <= constraints.maxWidth) {
            layout(row, maxOf(first.height, second.height)) {
                first.place(0, 0)
                dot.place(first.width, 0)
                second.place(first.width + dot.width, 0)
            }
        } else {
            layout(maxOf(first.width, second.width), first.height + second.height) {
                first.place(0, 0)
                second.place(0, first.height)
            }
        }
    }
}

@Composable
private fun CueList(ui: PlayerUi, vm: AppViewModel, modifier: Modifier) {
    val list = rememberLazyListState()
    var lastTouch by remember { mutableLongStateOf(0L) }
    val touch = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) lastTouch = SystemClock.uptimeMillis()
                return Offset.Zero
            }

            // A fling goes on after the finger is lifted: the pause counts from when it stops.
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (lastTouch != 0L) lastTouch = SystemClock.uptimeMillis()
                return Velocity.Zero
            }
        }
    }
    val latest by rememberUpdatedState(ui)
    // Follows the sentence, but gives way for a moment when the user is reading elsewhere. One effect, and
    // nothing asked of the list before its first layout: scrolls that wait for that layout aren't cancelled
    // with their effect, so the late one (back to the top) would undo the one going to the sentence, and
    // with the book paused nothing would ever bring it back.
    LaunchedEffect(ui.activeCue, ui.index, ui.cues.isEmpty()) {
        val move = Format.cueScroll(ui.cues.size, ui.activeCue, SystemClock.uptimeMillis() - lastTouch, Format.FOLLOW_PAUSE_MS)
        if (move == Format.CueScroll.NONE) return@LaunchedEffect
        val height = snapshotFlow { list.layoutInfo.viewportSize.height }.first { it > 0 }
        if (move == Format.CueScroll.TOP) list.scrollToItem(0) else list.animateScrollToItem(ui.activeCue, -height / 3)
    }
    // Scrolled by hand: a few seconds without touching it and back to the sentence playing on its own, even
    // paused or in the middle of a long sentence. Each touch starts the wait again.
    LaunchedEffect(lastTouch) {
        if (lastTouch == 0L) return@LaunchedEffect
        delay(Format.followWait(SystemClock.uptimeMillis() - lastTouch))
        val st = latest
        if (st.cues.isEmpty() || st.activeCue !in st.cues.indices) return@LaunchedEffect
        val height = snapshotFlow { list.layoutInfo.viewportSize.height }.first { it > 0 }
        list.animateScrollToItem(st.activeCue, -height / 3)
    }
    if (ui.cues.isEmpty()) {
        Box(modifier.fillMaxWidth()) {
            if (ui.cuesLoaded) {
                Text(stringResource(R.string.no_text_yet), color = Muted, modifier = Modifier.align(Alignment.Center).padding(32.dp))
            }
        }
        return
    }
    LazyColumn(
        state = list,
        modifier = modifier.fillMaxWidth().nestedScroll(touch),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(ui.cues) { i, cue ->
            val active = i == ui.activeCue
            Text(
                cue.text,
                fontFamily = FontFamily.Serif,
                fontSize = 19.sp,
                lineHeight = 26.sp,
                color = if (active) Ink else Ink.copy(alpha = 0.42f),
                modifier = Modifier.fillMaxWidth()
                    .background(if (active) ActiveCue else Night, RoundedCornerShape(8.dp))
                    .clickable { vm.player.seekToCue(i) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun Controls(ui: PlayerUi, vm: AppViewModel) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = ui.durationMs.coerceAtLeast(1)
    val shownPos = if (dragging) dragValue.toLong() else ui.positionMs

    Column(Modifier.fillMaxWidth().background(Panel).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
        if (ui.playbackError) {
            Text(stringResource(R.string.playback_error), color = Danger, fontSize = 13.sp, modifier = Modifier.padding(4.dp))
        }
        Slider(
            value = shownPos.toFloat().coerceIn(0f, duration.toFloat()),
            valueRange = 0f..duration.toFloat(),
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                vm.player.seekTo(dragValue.toLong())
                dragging = false
            },
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Line),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Format.time(shownPos), color = Muted, fontSize = 12.sp)
            Box(Modifier.weight(1f))
            Text(Format.time(ui.durationMs), color = Muted, fontSize = 12.sp)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.player.goToChapter(ui.index - 1) }, enabled = ui.index > 0) {
                Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous_chapter), tint = if (ui.index > 0) Ink else Line)
            }
            IconButton(onClick = { vm.player.skip(-10_000) }) {
                Icon(Icons.Filled.Replay10, stringResource(R.string.back_10), tint = Ink)
            }
            IconButton(
                onClick = vm.player::playPause,
                modifier = Modifier.size(56.dp).background(Accent, CircleShape),
            ) {
                if (ui.buffering && !ui.playing) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = OnAccent, strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (ui.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (ui.playing) R.string.pause else R.string.play),
                        tint = OnAccent, modifier = Modifier.size(32.dp),
                    )
                }
            }
            IconButton(onClick = { vm.player.skip(10_000) }) {
                Icon(Icons.Filled.Forward10, stringResource(R.string.forward_10), tint = Ink)
            }
            val hasNext = ui.index < ui.chapters.size - 1
            IconButton(onClick = { vm.player.goToChapter(ui.index + 1) }, enabled = hasNext) {
                Icon(Icons.Filled.SkipNext, stringResource(R.string.next_chapter), tint = if (hasNext) Ink else Line)
            }
            SpeedPicker(ui.speed, vm.player::setSpeed)
        }
    }
}

/** The list is long: it takes half the screen at most and opens with the current speed in the middle. */
@Composable
private fun SpeedPicker(speed: Float, onPick: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(open) {
        val row = Format.SPEEDS.indexOf(speed)
        if (!open || row < 0) return@LaunchedEffect
        val viewport = snapshotFlow { scroll.viewportSize }.first { it > 0 }
        scroll.scrollTo(Format.centredScroll(row, Format.SPEEDS.size, scroll.maxValue + viewport, viewport))
    }
    Box {
        OutlinedButton(
            onClick = { open = true },
            border = BorderStroke(1.dp, Line),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        ) { Text(Format.speedLabel(speed), color = Ink, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false) }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp / 2),
            scrollState = scroll,
        ) {
            for (s in Format.SPEEDS) {
                DropdownMenuItem(
                    text = { Text(Format.speedLabel(s), color = if (s == speed) Accent else Ink) },
                    onClick = {
                        open = false
                        onPick(s)
                    },
                )
            }
        }
    }
}

@Composable
private fun ChapterDialog(ui: PlayerUi, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = ui.index.coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(stringResource(R.string.go_to_chapter), color = Ink) },
        text = {
            LazyColumn(state = list) {
                itemsIndexed(ui.chapters) { i, c ->
                    Text(
                        chapterLabel(c) + " · " + Format.time((c.duration * 1000).toLong()),
                        color = if (i == ui.index) Accent else Ink,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(i) }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PositionDialog(onGo: (Long) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val parsed = Format.parsePosition(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(stringResource(R.string.go_to_position), color = Ink) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.position_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = text.isNotBlank() && parsed == null,
                supportingText = {
                    if (text.isNotBlank() && parsed == null) Text(stringResource(R.string.bad_position))
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onGo) }, enabled = parsed != null) { Text(stringResource(R.string.go)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
