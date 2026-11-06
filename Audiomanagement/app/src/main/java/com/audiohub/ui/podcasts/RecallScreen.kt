package com.audiohub.ui.podcasts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiohub.appContainer
import com.audiohub.data.db.EpisodeNoteEntity
import com.audiohub.data.podcast.NotesRepository
import com.audiohub.data.podcast.NotesRepository.EpisodeHeader
import com.audiohub.ui.Routes
import com.audiohub.ui.components.Artwork
import com.audiohub.ui.components.EmptyState
import com.audiohub.ui.components.Hairline
import com.audiohub.ui.library.DetailTopBar
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Everything the recall page needs about the episode, loaded once. */
data class RecallEpisode(val header: EpisodeHeader, val showNotesHtml: String, val note: EpisodeNoteEntity?)

/** Editable answers; survives recomposition, saved (debounced) as the user types. */
@Stable
class RecallForm(note: EpisodeNoteEntity?) {
    var mainIdeas by mutableStateOf(note?.mainIdeas.orEmpty())
    var highlight by mutableStateOf(note?.highlight.orEmpty())
    var takeaway by mutableStateOf(note?.takeaway.orEmpty())
    var notes by mutableStateOf(note?.notes.orEmpty())
    var score by mutableIntStateOf(note?.recallScore ?: 0)
    /** Show notes are hidden until the user has tried to recall (or already rated before). */
    var revealed by mutableStateOf((note?.recallScore ?: 0) > 0)

    fun snapshot() = listOf(mainIdeas, highlight, takeaway, notes, score.toString())
    val hasContent get() = snapshot().take(4).any { it.isNotBlank() } || score > 0
}

/** "Test your memory" for one finished episode: recall → compare with show notes → rate → notes. */
@OptIn(FlowPreview::class)
@Composable
fun RecallScreen(episodeId: Long) {
    val container = LocalContext.current.appContainer
    val repo = container.notesRepository
    val actions = LocalPodcastActions.current
    val data by produceState<RecallEpisode?>(null, episodeId) {
        val ep = container.podcastRepository.episode(episodeId)
        val note = repo.get(episodeId)
        value = when {
            ep != null -> RecallEpisode(
                EpisodeHeader(episodeId, ep.episode.title, ep.podcastTitle, ep.episode.artworkUrl ?: ep.podcastArtworkUrl),
                ep.episode.description,
                note?.takeIf { !it.skipped || it.hasContent },
            )
            note != null -> RecallEpisode(EpisodeHeader(episodeId, note.episodeTitle, note.showTitle, note.artworkUrl), "", note)
            else -> RecallEpisode(EpisodeHeader(episodeId, "", "", null), "", null)
        }
    }
    val loaded = data ?: return
    val form = remember(loaded) { RecallForm(loaded.note) }

    fun save() {
        val f = form
        container.appScope.launch {
            repo.save(loaded.header, f.mainIdeas, f.highlight, f.takeaway, f.notes, f.score)
        }
    }
    // Autosave shortly after typing stops, and once more when leaving the screen.
    LaunchedEffect(form) {
        snapshotFlow { form.snapshot() }.drop(1).debounce(600).collect { save() }
    }
    DisposableEffect(form) { onDispose { save() } }

    RecallContent(
        episode = loaded,
        form = form,
        onBack = actions::back,
        onDelete = {
            container.appScope.launch { repo.delete(episodeId) }
            form.mainIdeas = ""; form.highlight = ""; form.takeaway = ""; form.notes = ""; form.score = 0
            form.revealed = false
            actions.back()
        },
    )
}

@Composable
fun RecallContent(episode: RecallEpisode, form: RecallForm, onBack: () -> Unit, onDelete: () -> Unit) {
    val header = episode.header
    Column(Modifier.fillMaxSize().imePadding()) {
        DetailTopBar("Test your memory", onBack = onBack) {
            if (episode.note?.hasContent == true) TextButton(onClick = onDelete) { Text("Delete") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                Artwork(artworkUri(header.artworkUrl), Modifier.size(56.dp), placeholder = Icons.Rounded.Podcasts)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(header.showTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(header.title.ifBlank { "Episode" }, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }

            StepLabel("1 · Recall")
            if (!form.revealed) {
                Text(
                    "Before peeking at the show notes, write down what you remember. Recalling is what makes it stick.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Prompt("What was this episode about?", "The main ideas, in your own words", form.mainIdeas) { form.mainIdeas = it }
            Prompt("What stuck with you most?", "A fact, story, quote or number", form.highlight) { form.highlight = it }
            Prompt("What will you do with it?", "One thing to try, remember or look up", form.takeaway) { form.takeaway = it }

            StepLabel("2 · Compare")
            if (!form.revealed) {
                OutlinedButton(onClick = { form.revealed = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reveal show notes")
                }
            } else {
                val notesText = remember(episode.showNotesHtml) {
                    episode.showNotesHtml.takeIf { it.isNotBlank() }?.let { AnnotatedString.fromHtml(it) }
                }
                Box(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                        .padding(16.dp),
                ) {
                    Text(
                        notesText ?: AnnotatedString("This episode has no show notes — rate yourself from memory."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (notesText == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    "How much did you remember?",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NotesRepository.SCORE_LABELS.forEachIndexed { i, label ->
                        val value = i + 1
                        FilterChip(
                            selected = form.score == value,
                            onClick = { form.score = if (form.score == value) 0 else value },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
            }

            StepLabel("3 · Notes")
            OutlinedTextField(
                value = form.notes,
                onValueChange = { form.notes = it },
                placeholder = { Text("Anything else worth keeping") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Saved automatically",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
            )
        }
    }
}

@Composable
private fun StepLabel(text: String) {
    Text(
        text.uppercase(),
        style = com.audiohub.ui.theme.SectionLabelStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun Prompt(question: String, hint: String, value: String, onChange: (String) -> Unit) {
    Text(question, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ------------------------------------------------------------------ All notes

@Composable
fun NotesScreen() {
    val container = LocalContext.current.appContainer
    val notes by container.notesRepository.all.collectAsStateWithLifecycle(initialValue = null)
    NotesContent(notes, LocalPodcastActions.current)
}

@Composable
fun NotesContent(notes: List<EpisodeNoteEntity>?, actions: PodcastActions) {
    Column(Modifier.fillMaxSize()) {
        DetailTopBar("Notes & recall", onBack = actions::back)
        when {
            notes == null -> Unit
            notes.isEmpty() -> EmptyState(
                Icons.AutoMirrored.Rounded.Notes,
                "No notes yet",
                "Finish an episode, then test your memory from the Podcasts tab — or pick \"Recall & notes\" in an episode's menu.",
            )
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(notes, key = { it.episodeId }) { n ->
                    NoteRow(n, onClick = { actions.navigate(Routes.recall(n.episodeId)) })
                    Hairline(start = 88.dp)
                }
            }
        }
    }
}

@Composable
private fun NoteRow(note: EpisodeNoteEntity, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Artwork(artworkUri(note.artworkUrl), Modifier.size(56.dp), placeholder = Icons.Rounded.Podcasts)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(note.showTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note.episodeTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val preview = listOf(note.mainIdeas, note.highlight, note.takeaway, note.notes).firstOrNull { it.isNotBlank() }
            if (preview != null) {
                Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            if (note.recallScore > 0) RecallDots(note.recallScore, Modifier.padding(top = 6.dp))
        }
    }
}

/** ●●●○ — self-rated recall (1…4). */
@Composable
fun RecallDots(score: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(4) { i ->
            Box(
                Modifier.size(6.dp).background(
                    if (i < score) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape,
                ),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            NotesRepository.SCORE_LABELS[(score - 1).coerceIn(0, 3)],
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One "just finished" episode with Recall / Not now. */
@Composable
fun PendingRecallRow(episode: EpisodeUi, onRecall: () -> Unit, onSkip: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onRecall).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(artworkUri(episode.artworkUrl), Modifier.size(44.dp), placeholder = Icons.Rounded.Podcasts)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(episode.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "Finished · ${episode.showTitle}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(onClick = onRecall, contentPadding = PaddingValues(horizontal = 12.dp)) {
            androidx.compose.material3.Icon(Icons.Rounded.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Recall")
        }
        androidx.compose.material3.IconButton(onClick = onSkip) {
            androidx.compose.material3.Icon(Icons.Rounded.Close, contentDescription = "Not now", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
