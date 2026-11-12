package com.audiohub.ui.nowplaying

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiohub.appContainer
import com.audiohub.data.SearchText
import com.audiohub.playback.PlayerControls
import com.audiohub.playback.QueueItem
import com.audiohub.playback.QueueOrder
import com.audiohub.ui.components.Artwork
import com.audiohub.ui.components.EmptyState
import com.audiohub.ui.components.plural
import com.audiohub.ui.library.DetailTopBar
import com.audiohub.ui.library.LocalTrackActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Full-screen "Playing Queue": every item of the current play queue, searchable by title / artist.
 * Lives in the NavHost, so the mini player and tab bar stay visible and tabs can be switched freely
 * (the screen, its scroll position and search query are restored when coming back to the tab).
 *
 * Long-press selects songs; selected songs can be dragged as one block, played next or removed.
 * All of it works with shuffle on: then the shuffle order itself is rearranged.
 */
@Composable
fun QueueScreen() {
    val player = LocalContext.current.appContainer.playerConnection
    val state by player.state.collectAsStateWithLifecycle()
    val actions = LocalTrackActions.current
    // Only pass what the screen needs, so play/pause, buffering, sleep-timer ticks… don't touch the list.
    val isBook = com.audiohub.playback.isBookMediaId(state.mediaId)
    QueueContent(
        title = if (isBook) "Chapters" else "Playing Queue",
        noun = if (isBook) "chapter" else "song",
        queue = state.queue,
        currentIndex = state.queueIndex,
        shuffleEnabled = state.shuffleEnabled,
        player = player,
        onBack = actions::back,
    )
}

/** Normalized search keys, built once per queue (off the main thread) and reused for every keystroke. */
@Immutable
private class QueueSearchIndex(val items: List<QueueItem>, val keys: Array<String>) {
    fun filter(query: String): List<QueueItem> {
        val words = SearchText.normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return items
        val out = ArrayList<QueueItem>()
        for (i in keys.indices) {
            val key = keys[i]
            if (words.all { key.contains(it) }) out += items[i]
        }
        return out
    }

    companion object {
        fun build(items: List<QueueItem>) =
            QueueSearchIndex(items, Array(items.size) { SearchText.of(items[it].title, items[it].artist) })
    }
}

@Composable
fun QueueContent(
    queue: List<QueueItem>,
    currentIndex: Int,
    shuffleEnabled: Boolean,
    player: PlayerControls,
    onBack: () -> Unit,
    initialQuery: String = "",
    title: String = "Playing Queue",
    noun: String = "song",
    initialSelection: Set<Int> = emptySet(),
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    val searching = query.isNotBlank()
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    // Selected player indices. Indices shift when the list changes, so a new queue clears it.
    var selected by remember(queue) { mutableStateOf(initialSelection) }
    fun toggle(index: Int) {
        selected = if (index in selected) selected - index else selected + index
    }

    // Position (in play order) of the playing item; O(n) only when the queue or current item changes.
    val currentPos = remember(queue, currentIndex) { queue.indexOfFirst { it.index == currentIndex } }

    // The search index is only built once the user actually searches, then kept until the queue changes.
    val searchIndex by produceState<QueueSearchIndex?>(null, queue, searching) {
        if (searching && (value == null || value?.items !== queue)) {
            value = withContext(Dispatchers.Default) { QueueSearchIndex.build(queue) }
        }
    }
    // Debounced filtering on a background thread: typing stays smooth even with thousands of songs.
    val results by produceState<List<QueueItem>?>(null, query, searchIndex) {
        if (!searching) {
            value = null
            return@produceState
        }
        val index = searchIndex ?: return@produceState
        delay(120)
        value = withContext(Dispatchers.Default) { index.filter(query) }
    }

    val fullListState = rememberLazyListState()
    val searchListState = rememberLazyListState()

    // Open on the playing song (once; afterwards keep whatever the user scrolled to, also across tabs).
    var scrolledToCurrent by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(currentPos) {
        if (!scrolledToCurrent && currentPos >= 0) {
            fullListState.scrollToItem((currentPos - 2).coerceAtLeast(0))
            scrolledToCurrent = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (selected.isEmpty()) {
            DetailTopBar(title, onBack = onBack) {
                IconButton(onClick = player::toggleShuffle) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
                IconButton(
                    onClick = {
                        if (currentPos < 0) return@IconButton
                        query = ""
                        scope.launch { fullListState.scrollToItem((currentPos - 2).coerceAtLeast(0)) }
                    },
                    enabled = currentPos >= 0,
                ) { Icon(Icons.Rounded.MyLocation, contentDescription = "Show the playing song") }
            }
        } else {
            SelectionBar(
                count = selected.size,
                onClear = { selected = emptySet() },
                onPlayNext = {
                    // Selected songs, in their current play order, go right after the playing one.
                    val pinned = queue.map { it.index }.filter { it in selected && it != currentIndex }
                    player.setPlayOrder(QueueOrder.pinAfter(queue.map { it.index }, currentIndex, pinned))
                    selected = emptySet()
                },
                onRemove = {
                    player.removeAll(selected - currentIndex)
                    selected = emptySet()
                },
            )
        }
        Text(
            buildString {
                val shown = results
                if (searching && shown != null) append("${shown.size} of ")
                append(plural(queue.size, noun))
                if (shuffleEnabled) append(" · Shuffle")
                if (selected.isEmpty() && queue.size > 1) append(" · Hold to select")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(if (noun == "chapter") "Search chapters" else "Search song or artist in this queue") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, contentDescription = "Clear") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        val shown = results
        when {
            queue.isEmpty() -> EmptyState(
                Icons.AutoMirrored.Rounded.QueueMusic,
                "Nothing playing",
                "Play a playlist, album or song and its queue shows up here.",
            )
            !searching -> FullQueueList(queue, currentIndex, selected, ::toggle, player, fullListState)
            shown == null -> Box(Modifier.fillMaxSize()) // index/filter still running (a few ms)
            shown.isEmpty() -> EmptyState(Icons.Rounded.SearchOff, "No results", "Nothing in this queue matches \"$query\".")
            else -> SearchResultList(shown, currentIndex, selected, ::toggle, player, searchListState) { keyboard?.hide() }
        }
    }
}

@Composable
private fun SelectionBar(count: Int, onClear: () -> Unit, onPlayNext: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, contentDescription = "Clear selection") }
        Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onPlayNext) {
            Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Play next")
        }
        IconButton(onClick = onRemove) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove selected") }
    }
}

/**
 * The whole queue in play order; drag the handle to reorder — with shuffle on as well. Dragging a
 * selected song carries every selected song with it: the others fold into the dragged row (badge
 * "+N") and are dropped together, in their current order, where it lands.
 */
@Composable
private fun FullQueueList(
    queue: List<QueueItem>,
    currentIndex: Int,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    player: PlayerControls,
    listState: LazyListState,
) {
    // Local copy so a drag moves rows instantly; replaced by the player's queue when it updates.
    var items by remember(queue) { mutableStateOf(queue) }
    // Player indices folded into the dragged row during a multi-select drag (in play order).
    var block by remember(queue) { mutableStateOf<List<Int>>(emptyList()) }
    val haptics = LocalHapticFeedback.current
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val a = items.indexOfFirst { it.index == from.key }
        val b = items.indexOfFirst { it.index == to.key }
        if (a >= 0 && b >= 0) items = items.toMutableList().apply { add(b, removeAt(a)) }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    fun dragStarted(q: QueueItem) {
        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        if (q.index in selected && selected.size > 1) {
            block = items.map { it.index }.filter { it in selected }
            items = items.filter { it.index !in selected || it.index == q.index }
        }
    }

    fun dragStopped(q: QueueItem) {
        val order = items.map { it.index }
        val desired = if (block.isNotEmpty()) {
            QueueOrder.insertBlock(order.filter { it != q.index }, block, order.indexOf(q.index))
        } else {
            order
        }
        block = emptyList()
        if (desired != queue.map { it.index }) player.setPlayOrder(desired) else items = queue
    }

    LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.index }, contentType = { "queue" }) { q ->
            ReorderableItem(reorderState, key = q.index) { isDragging ->
                Surface(
                    color = if (isDragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background,
                    shadowElevation = if (isDragging) 6.dp else 0.dp,
                ) {
                    QueueRow(
                        q,
                        isCurrent = q.index == currentIndex,
                        isSelected = q.index in selected,
                        selecting = selected.isNotEmpty(),
                        carried = if (isDragging && block.size > 1) block.size - 1 else 0,
                        onClick = { if (selected.isNotEmpty()) onToggle(q.index) else player.jumpTo(q.index) },
                        onLongClick = { onToggle(q.index) },
                        onRemove = { player.removeAt(q.index) },
                    ) {
                        Icon(
                            Icons.Rounded.DragHandle,
                            contentDescription = "Reorder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.draggableHandle(
                                onDragStarted = { dragStarted(q) },
                                onDragStopped = { dragStopped(q) },
                            ).padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultList(
    results: List<QueueItem>,
    currentIndex: Int,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    player: PlayerControls,
    listState: LazyListState,
    onPicked: () -> Unit,
) {
    // New query → start at the top of the results.
    LaunchedEffect(results) { listState.scrollToItem(0) }
    LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        items(results, key = { it.index }, contentType = { "queue" }) { q ->
            QueueRow(
                q,
                isCurrent = q.index == currentIndex,
                isSelected = q.index in selected,
                selecting = selected.isNotEmpty(),
                onClick = {
                    if (selected.isNotEmpty()) onToggle(q.index) else { player.jumpTo(q.index); onPicked() }
                },
                onLongClick = { onToggle(q.index) },
                onRemove = { player.removeAt(q.index) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(
    q: QueueItem,
    isCurrent: Boolean,
    isSelected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: () -> Unit,
    carried: Int = 0,
    handle: @Composable () -> Unit = {},
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth()
            .then(
                when {
                    isSelected -> Modifier.background(accent.copy(alpha = 0.14f))
                    isCurrent -> Modifier.background(accent.copy(alpha = 0.08f))
                    else -> Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select")
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Artwork(q.artworkUri, Modifier.size(44.dp))
            when {
                isSelected -> {
                    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f), RoundedCornerShape(4.dp)))
                    Icon(Icons.Rounded.CheckCircle, contentDescription = "Selected", tint = accent)
                }
                isCurrent -> {
                    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f), RoundedCornerShape(4.dp)))
                    Icon(Icons.Rounded.GraphicEq, contentDescription = "Now playing", tint = accent)
                }
            }
            if (carried > 0) {
                Text(
                    "+$carried",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.TopEnd).background(accent, CircleShape).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (isCurrent) {
                // Only the playing row scrolls; the rest stay static so long lists cost no frames.
                MarqueeText(q.title, style = MaterialTheme.typography.bodyMedium, color = accent, fontWeight = FontWeight.SemiBold)
            } else {
                Text(q.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                if (carried > 0) "Moving ${carried + 1} songs" else q.artist,
                style = MaterialTheme.typography.bodySmall,
                color = if (carried > 0) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Removing the playing item would silently skip it; use Next for that instead.
        if (!isCurrent && !selecting) {
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = "Remove from queue") }
        }
        handle()
    }
}
