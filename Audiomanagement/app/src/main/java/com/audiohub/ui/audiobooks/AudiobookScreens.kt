package com.audiohub.ui.audiobooks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.audiohub.appContainer
import com.audiohub.data.audiobook.AudiobookRepository
import com.audiohub.data.audiobook.NaturalOrder
import com.audiohub.data.db.BookChapterEntity
import com.audiohub.data.db.BookEntity
import com.audiohub.playback.bookFileIdOf
import com.audiohub.system.AppPermissions
import com.audiohub.system.rememberPermission
import com.audiohub.ui.Routes
import com.audiohub.ui.components.Artwork
import com.audiohub.ui.components.EmptyState
import com.audiohub.ui.components.SectionLabel
import com.audiohub.ui.components.formatDuration
import com.audiohub.ui.components.plural
import com.audiohub.ui.library.DetailTopBar
import com.audiohub.ui.library.LocalTrackActions
import kotlinx.coroutines.launch

enum class BookSort(val label: String) { Recent("Recently played"), Title("Title"), Author("Author"), Added("Recently added") }

/** "9h 32m", "48 min". */
fun formatLength(ms: Long): String {
    val minutes = (ms.coerceAtLeast(0) + 30_000) / 60_000
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "$m min"
}

private fun BookEntity.statusLine(): String = when {
    finished -> "Finished"
    started -> "${(progress * 100).toInt()}% · ${formatLength(durationMs - listenedMs)} left"
    else -> listOf(plural(chapterCount, "chapter"), formatLength(durationMs)).joinToString(" · ")
}

/** Starts [bookId] where it was left (or at [fromChapter]); books never loop or shuffle. */
@Composable
private fun rememberBookPlayer(): (bookId: Long, fromChapter: Int?) -> Unit {
    val container = LocalContext.current.appContainer
    val scope = rememberCoroutineScope()
    return remember(container, scope) {
        { bookId, fromChapter ->
            scope.launch {
                val (items, resume) = container.audiobookRepository.playable(bookId, fromChapter) ?: return@launch
                container.playerConnection.playQueue(
                    items, resume.chapterIndex, shuffle = false, startPositionMs = resume.positionMs,
                    repeatMode = Player.REPEAT_MODE_OFF,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Library tab

@Composable
fun BooksScreen() {
    val permission = rememberPermission(AppPermissions.AUDIO)
    if (!permission.granted) {
        EmptyState(
            icon = Icons.Rounded.Lock,
            title = "Allow access to audio",
            message = "My Audio Hub needs permission to read the audiobooks on your phone.",
            action = { Button(onClick = permission.request) { Text("Allow") } },
        )
        return
    }
    val container = LocalContext.current.appContainer
    val repo = container.audiobookRepository
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val books by repo.books.collectAsStateWithLifecycle(initialValue = null)
    val player by container.playerConnection.state.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val folder = settings?.audiobookFolder

    fun rescan() {
        if (scanning) return
        scope.launch {
            scanning = true
            runCatching { repo.scan() }
            scanning = false
        }
    }
    // Rescan when the tab opens / the folder changes (MediaStore query: fast).
    LaunchedEffect(folder) { if (folder != null) rescan() }

    var sort by rememberSaveable { mutableStateOf(BookSort.Recent) }
    val actions = LocalTrackActions.current
    val play = rememberBookPlayer()
    BooksContent(
        books = books,
        folder = folder.orEmpty(),
        scanning = scanning,
        sort = sort,
        onSort = { sort = it },
        playingFileId = bookFileIdOf(player.mediaId),
        onRescan = ::rescan,
        onOpen = { actions.navigate(Routes.book(it.id)) },
        onResume = { play(it.id, null) },
    )
}

@Composable
fun BooksContent(
    books: List<BookEntity>?,
    folder: String,
    scanning: Boolean,
    sort: BookSort,
    onSort: (BookSort) -> Unit,
    onRescan: () -> Unit,
    onOpen: (BookEntity) -> Unit,
    onResume: (BookEntity) -> Unit,
    playingFileId: Long? = null,
) {
    val sorted = remember(books, sort) {
        when (sort) {
            BookSort.Recent -> books?.sortedWith(compareByDescending<BookEntity> { it.lastPlayedAt }.thenBy(NaturalOrder) { it.title })
            BookSort.Title -> books?.sortedWith(compareBy(NaturalOrder) { it.title })
            BookSort.Author -> books?.sortedWith(compareBy<BookEntity, String>(NaturalOrder) { it.author.ifBlank { "￿" } }.thenBy(NaturalOrder) { it.title })
            BookSort.Added -> books?.sortedByDescending { it.addedAt }
        }
    }
    val current = remember(books) { books?.filter { it.started }?.maxByOrNull { it.lastPlayedAt } }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Grid content is inset 12 dp; 8 dp more lines the title up with the 20 dp margins elsewhere.
                Column(Modifier.weight(1f).padding(start = 8.dp, top = 20.dp, bottom = 12.dp)) {
                    Text("Audiobooks", style = MaterialTheme.typography.headlineLarge)
                    books?.let {
                        Text(plural(it.size, "book"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                SortMenu(sort, onSort)
                IconButton(onClick = onRescan, enabled = !scanning) { Icon(Icons.Rounded.Refresh, contentDescription = "Rescan folder") }
            }
        }
        if (scanning) {
            item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) }
        }
        if (books != null && books.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    Icons.Rounded.Folder,
                    "No audiobooks yet",
                    "Put each book in its own folder inside\n/$folder\n(its files are the chapters), then tap refresh.",
                    modifier = Modifier.height(420.dp),
                )
            }
            return@LazyVerticalGrid
        }
        if (current != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ContinueCard(current, isPlaying = playingFileId != null && playingFileId == current.currentFileId, onOpen = { onOpen(current) }, onResume = { onResume(current) })
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "ALL BOOKS",
                    style = com.audiohub.ui.theme.SectionLabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 4.dp),
                )
            }
        }
        items(sorted.orEmpty(), key = { it.id }, contentType = { "book" }) { book ->
            BookTile(book, onClick = { onOpen(book) })
        }
    }
}

@Composable
private fun SortMenu(sort: BookSort, onSort: (BookSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Sort: ${sort.label}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            BookSort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label, color = if (s == sort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                    onClick = { open = false; onSort(s) },
                )
            }
        }
    }
}

@Composable
private fun ContinueCard(book: BookEntity, isPlaying: Boolean, onOpen: () -> Unit, onResume: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(book.artworkUri?.let(android.net.Uri::parse), Modifier.size(88.dp), cornerRadius = 6.dp, placeholder = Icons.AutoMirrored.Rounded.MenuBook)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("CONTINUE LISTENING", style = com.audiohub.ui.theme.SectionLabelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            if (book.author.isNotBlank()) {
                Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ProgressLine(book.progress, Modifier.padding(top = 8.dp))
            Text(book.statusLine(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = onResume,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Icon(if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow, contentDescription = "Resume")
        }
    }
}

@Composable
private fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress },
        modifier = modifier.fillMaxWidth().height(2.dp),
        trackColor = MaterialTheme.colorScheme.outlineVariant,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
private fun BookTile(book: BookEntity, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(8.dp)) {
        Artwork(
            book.artworkUri?.let(android.net.Uri::parse),
            Modifier.fillMaxWidth().aspectRatio(1f),
            cornerRadius = 6.dp,
            placeholder = Icons.AutoMirrored.Rounded.MenuBook,
        )
        if (book.started) ProgressLine(book.progress, Modifier.padding(top = 6.dp))
        Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(
            book.author.ifBlank { book.statusLine() },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (book.author.isNotBlank()) {
            Text(
                book.statusLine(),
                style = MaterialTheme.typography.labelSmall,
                color = if (book.finished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

// ------------------------------------------------------------------ Book detail

@Composable
fun BookDetailScreen(bookId: Long) {
    val container = LocalContext.current.appContainer
    val repo = container.audiobookRepository
    val book by remember(bookId) { repo.book(bookId) }.collectAsStateWithLifecycle(initialValue = null)
    val chapters by remember(bookId) { repo.chapters(bookId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val player by container.playerConnection.state.collectAsStateWithLifecycle()
    val actions = LocalTrackActions.current
    val play = rememberBookPlayer()
    val scope = rememberCoroutineScope()
    val b = book ?: return
    BookDetailContent(
        book = b,
        chapters = chapters,
        playingFileId = bookFileIdOf(player.mediaId)?.takeIf { player.isPlaying },
        onBack = actions::back,
        onPlay = { play(b.id, null) },
        onPlayChapter = { index -> play(b.id, index) },
        onSetFinished = { finished -> scope.launch { repo.setFinished(b.id, finished) } },
    )
}

@Composable
fun BookDetailContent(
    book: BookEntity,
    chapters: List<BookChapterEntity>,
    playingFileId: Long?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPlayChapter: (Int) -> Unit,
    onSetFinished: (Boolean) -> Unit,
) {
    val resume = remember(book, chapters) { AudiobookRepository.resumeOf(book, chapters) }
    val currentIndex = if (book.started) resume.chapterIndex else -1
    Column(Modifier.fillMaxSize()) {
        DetailTopBar("", onBack = onBack) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!book.finished) {
                        DropdownMenuItem(text = { Text("Mark as finished") }, onClick = { menu = false; onSetFinished(true) })
                    }
                    if (book.finished || book.started) {
                        DropdownMenuItem(text = { Text("Start over") }, onClick = { menu = false; onSetFinished(false) })
                    }
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Artwork(
                        book.artworkUri?.let(android.net.Uri::parse),
                        Modifier.widthIn(max = 220.dp).fillMaxWidth(0.6f).aspectRatio(1f),
                        cornerRadius = 8.dp,
                        placeholder = Icons.AutoMirrored.Rounded.MenuBook,
                    )
                    Text(book.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
                    if (book.author.isNotBlank()) {
                        Text(book.author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    Text(
                        listOf(plural(book.chapterCount, "chapter"), formatLength(book.durationMs)).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (book.started || book.finished) {
                        ProgressLine(book.progress, Modifier.padding(top = 16.dp))
                        Text(book.statusLine(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    Button(
                        onClick = onPlay,
                        modifier = Modifier.padding(top = 20.dp).fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                book.finished -> "Listen again"
                                book.started -> "Resume · chapter ${resume.chapterIndex + 1}, ${formatDuration(resume.positionMs)}"
                                else -> "Start listening"
                            },
                        )
                    }
                }
            }
            item(key = "label") { SectionLabel("Chapters") }
            itemsIndexed(chapters, key = { _, c -> c.fileId }, contentType = { _, _ -> "chapter" }) { i, c ->
                ChapterRow(
                    index = i,
                    chapter = c,
                    isCurrent = i == currentIndex,
                    isDone = book.finished || (currentIndex >= 0 && i < currentIndex),
                    isPlaying = c.fileId == playingFileId,
                    resumeAtMs = if (i == currentIndex) resume.positionMs else 0,
                    onClick = { if (i == currentIndex) onPlay() else onPlayChapter(i) },
                )
            }
        }
    }
}

@Composable
private fun ChapterRow(
    index: Int,
    chapter: BookChapterEntity,
    isCurrent: Boolean,
    isDone: Boolean,
    isPlaying: Boolean,
    resumeAtMs: Long,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth()
            .then(if (isCurrent) Modifier.background(accent.copy(alpha = 0.08f)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
            when {
                isPlaying -> Icon(Icons.Rounded.GraphicEq, contentDescription = "Playing", tint = accent, modifier = Modifier.size(18.dp))
                isDone -> Icon(Icons.Rounded.Check, contentDescription = "Listened", tint = muted, modifier = Modifier.size(16.dp))
                else -> Text("%02d".format(index + 1), style = MaterialTheme.typography.labelMedium, color = if (isCurrent) accent else muted)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                chapter.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                color = when {
                    isCurrent -> accent
                    isDone -> muted
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (isCurrent && resumeAtMs > 0) {
                Text("Resume at ${formatDuration(resumeAtMs)}", style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
        Text(formatDuration(chapter.durationMs), style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.padding(start = 12.dp))
    }
}
