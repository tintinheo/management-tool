package com.ytsync.ui

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ytsync.data.UploadItem
import com.ytsync.data.UploadSource
import com.ytsync.data.UploadState
import com.ytsync.media.CameraVideo
import com.ytsync.media.CameraVideos
import com.ytsync.youtube.VideoMetadata
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A camera video plus what the app knows about it (null row = never queued or renamed). */
data class GalleryEntry(
    val video: CameraVideo,
    val row: UploadItem?,
    /** Readable name of the app that saved a non-camera video ("Zalo", "Chrome"…). */
    val ownerLabel: String? = null,
) {
    val id get() = video.mediaId
    val state get() = row?.state ?: UploadState.IDLE
    val uploaded get() = state == UploadState.DONE && row?.videoId != null
}

data class GalleryState(
    val entries: List<GalleryEntry> = emptyList(),
    val loading: Boolean = false,
    val selected: Set<Long> = emptySet(),
    val autoTemplate: String = VideoMetadata.DEFAULT_TEMPLATE,
    val hasPermission: Boolean = true,
    /** Also list videos other apps saved into DCIM/Camera (downloads, edits). Off by default. */
    val showOthers: Boolean = false,
    val filter: GalleryFilter = GalleryFilter.ALL,
)

/** Video tab filter chips. */
enum class GalleryFilter(val label: String) {
    ALL("Tất cả"),
    NOT_SYNCED("Chưa đồng bộ"),
    PENDING("Đang chờ"),
    SYNCED("Đã lên YouTube");

    fun matches(e: GalleryEntry): Boolean = when (this) {
        ALL -> true
        NOT_SYNCED -> e.state in setOf(UploadState.IDLE, UploadState.SKIPPED, UploadState.FAILED)
        PENDING -> e.state in setOf(UploadState.QUEUED, UploadState.UPLOADING, UploadState.RECORDING)
        SYNCED -> e.uploaded
    }
}

/**
 * Shown by default: camera videos and anything the app is syncing / has synced (shared, picked,
 * recorded in-app — any folder, even if only on YouTube now). Hidden unless [showOthers]: videos other
 * apps saved into DCIM/Camera that were never picked.
 */
fun visibleEntries(entries: List<GalleryEntry>, showOthers: Boolean, filter: GalleryFilter): List<GalleryEntry> =
    entries.filter { (showOthers || it.video.fromCamera || it.row != null) && filter.matches(it) }

interface GalleryActions {
    fun refreshGallery()
    fun toggle(id: Long)
    fun setSelected(ids: Collection<Long>, selected: Boolean)
    fun clearSelection()
    fun renameGroup(ids: List<Long>, template: String)
    fun uploadGroup(ids: List<Long>)
    fun openStudio(videoId: String)
    fun requestVideoPermission()
    fun setShowOthers(show: Boolean)
    fun setFilter(filter: GalleryFilter)
    /** Delete phone copies of videos that are safely on YouTube (verifies first, then asks Android). */
    fun deleteFromPhone(ids: List<Long>)
    suspend fun thumbnail(video: CameraVideo): ImageBitmap?
}

/** Titles a group rename would produce: {n} numbers the group in recording order. */
fun groupTitles(entries: List<GalleryEntry>, template: String): List<Pair<GalleryEntry, String>> {
    val ordered = entries.sortedBy { it.video.recordedAt }
    return ordered.mapIndexed { i, e ->
        e to VideoMetadata.title(template, e.video.recordedAt, e.video.displayName, seq = i + 1, seqOf = ordered.size)
    }
}

/** What the video is (or will be) called on YouTube, and whether it's just the auto default. */
fun displayTitle(e: GalleryEntry, autoTemplate: String): Pair<String, Boolean> {
    e.row?.title?.takeIf { e.uploaded || e.state == UploadState.UPLOADING }?.let { return it to false }
    e.row?.titleOverride?.takeIf { it.isNotBlank() }?.let { return it to false }
    return VideoMetadata.title(autoTemplate, e.video.recordedAt, e.video.displayName) to true
}

private val dayFmt = SimpleDateFormat("EEEE, dd/MM/yyyy", Locale.forLanguageTag("vi"))
private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private val fullFmt = SimpleDateFormat("HH:mm:ss, dd/MM/yyyy", Locale.getDefault())

private fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** Where a non-auto video came from; null for plain camera videos. */
private fun sourceLabel(e: GalleryEntry): String? = when (e.row?.source) {
    UploadSource.SHARE -> "Gửi từ Thư viện"
    UploadSource.MANUAL -> if (e.video.fromCamera) null else "Tự chọn · ${e.ownerLabel ?: "app khác"}"
    UploadSource.LIVE -> "Quay & đồng bộ"
    else -> if (e.video.fromCamera) null else "Không phải từ camera · ${e.ownerLabel ?: "không rõ nguồn"}"
}

private fun statusLabel(state: UploadState): Pair<String, Color>? = when (state) {
    UploadState.IDLE -> null
    UploadState.QUEUED -> "Chờ tải" to Muted
    UploadState.UPLOADING -> "Đang tải" to Accent
    UploadState.RECORDING -> "Đang quay & tải lên" to Accent
    UploadState.DONE -> "Đã lên YouTube" to Ok
    UploadState.FAILED -> "Lỗi" to Accent
    UploadState.SKIPPED -> "Bỏ qua" to Muted
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(state: GalleryState, actions: GalleryActions, modifier: Modifier = Modifier) {
    var preview by remember { mutableStateOf<GalleryEntry?>(null) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteIds by remember { mutableStateOf<List<Long>?>(null) }
    val selecting = state.selected.isNotEmpty()
    val othersCount = remember(state.entries) { state.entries.count { !it.video.fromCamera && it.row == null } }
    val shown = remember(state.entries, state.showOthers) { visibleEntries(state.entries, state.showOthers, GalleryFilter.ALL) }
    val visible = remember(shown, state.filter) { shown.filter(state.filter::matches) }
    val cleanable = remember(shown) { shown.filter { it.uploaded && it.video.onDevice } }
    val byDay = remember(visible) {
        visible.groupBy { dayFmt.format(Date(it.video.recordedAt)) }.toList()
    }

    Column(modifier.fillMaxSize().background(Bg)) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Video", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (state.loading) "Đang đọc thư viện…"
                            else "${shown.size} video · camera, đã chọn, đã chia sẻ và đã đồng bộ · giữ để chọn nhiều",
                            color = Muted, fontSize = 13.sp,
                        )
                    }
                    TextButton(onClick = actions::refreshGallery) { Text("Làm mới", color = Accent) }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GalleryFilter.entries.forEach { f ->
                        val on = f == state.filter
                        val n = shown.count(f::matches)
                        Text(
                            "${f.label} $n", color = if (on) Color.White else Muted, fontSize = 12.sp, maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (on) Accent else Color.Transparent)
                                .border(1.dp, if (on) Accent else Line, RoundedCornerShape(50))
                                .clickable { actions.setFilter(f) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                if (cleanable.isNotEmpty() && !selecting) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(Card)
                            .border(1.dp, Line, RoundedCornerShape(12.dp)).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Dọn máy", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("${cleanable.size} video đã lên YouTube vẫn chiếm ${mb(cleanable.sumOf { it.video.sizeBytes })} trên máy", color = Muted, fontSize = 12.sp)
                        }
                        TextButton(onClick = { deleteIds = cleanable.map { it.id } }) { Text("Xoá khỏi máy…", color = Accent) }
                    }
                }
                if (othersCount > 0) {
                    Text(
                        if (state.showOthers) "Ẩn $othersCount video tải về / từ app khác"
                        else "Đã ẩn $othersCount video tải về / từ app khác · Hiện",
                        color = Muted, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { actions.setShowOthers(!state.showOthers) }
                            .padding(vertical = 6.dp),
                    )
                }
            }
            if (!state.hasPermission) {
                item {
                    Section {
                        Text("Cần quyền đọc video để hiển thị thư viện.", color = Ink, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
                        TextButton(onClick = actions::requestVideoPermission) { Text("Cho phép", color = Accent) }
                    }
                }
            }
            byDay.forEach { (day, entries) ->
                item(key = "day-$day") {
                    val allSel = entries.all { it.id in state.selected }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(day.replaceFirstChar { it.uppercase() }, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { actions.setSelected(entries.map { it.id }, !allSel) }) {
                            Text(if (allSel) "Bỏ chọn ngày" else "Chọn cả ngày", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
                items(entries, key = { it.id }) { e ->
                    VideoRow(
                        e, state.autoTemplate, selected = e.id in state.selected, selecting = selecting, actions = actions,
                        modifier = Modifier.combinedClickable(
                            onClick = { if (selecting) actions.toggle(e.id) else preview = e },
                            onLongClick = { actions.toggle(e.id) },
                        ),
                    )
                }
            }
        }

        if (selecting) {
            val sel = state.entries.filter { it.id in state.selected }
            val canUpload = sel.count { it.video.onDevice && it.state in setOf(UploadState.IDLE, UploadState.SKIPPED, UploadState.FAILED) }
            val canDelete = sel.count { it.uploaded && it.video.onDevice }
            Column(Modifier.fillMaxWidth().background(Card).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${sel.size} đã chọn", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    TextButton(onClick = { actions.setSelected(visible.map { it.id }, true) }) { Text("Chọn hết", color = Muted) }
                    TextButton(onClick = actions::clearSelection) { Text("Bỏ chọn", color = Muted) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { deleteIds = sel.map { it.id } }, enabled = canDelete > 0) {
                        Text(if (canDelete > 0) "Xoá khỏi máy ($canDelete)" else "Xoá khỏi máy", color = if (canDelete > 0) Ink else Muted)
                    }
                    TextButton(onClick = { actions.uploadGroup(sel.map { it.id }) }, enabled = canUpload > 0) {
                        Text(if (canUpload > 0) "Tải lên ($canUpload)" else "Tải lên", color = if (canUpload > 0) Ink else Muted)
                    }
                    TextButton(onClick = { renameOpen = true }) { Text("Đặt tên", color = Accent) }
                }
            }
        }
    }

    deleteIds?.let { ids ->
        val chosen = state.entries.filter { it.id in ids.toSet() }
        val (ok, skipped) = DeletePlan.candidates(chosen)
        AlertDialog(
            onDismissRequest = { deleteIds = null },
            title = { Text("Xoá ${ok.size} video khỏi điện thoại?") },
            text = {
                Column {
                    Text("Giải phóng ${mb(ok.sumOf { it.video.sizeBytes })}. Video vẫn còn trên YouTube (riêng tư).", color = Ink, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Trước khi xoá, app hỏi YouTube để chắc từng video còn trên kênh và đã xử lý xong; video nào chưa chắc sẽ được giữ lại. Sau đó Android sẽ hỏi bạn xác nhận lần cuối.", color = Muted, fontSize = 12.sp)
                    if (skipped.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Giữ lại: ${DeletePlan.skippedText(skipped)}.", color = Muted, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { deleteIds = null; actions.deleteFromPhone(ok.map { it.id }) }, enabled = ok.isNotEmpty()) {
                    Text("Xoá khỏi máy", color = Accent)
                }
            },
            dismissButton = { TextButton(onClick = { deleteIds = null }) { Text("Huỷ", color = Muted) } },
            containerColor = Card, titleContentColor = Ink,
        )
    }

    preview?.let { e ->
        PreviewDialog(e, state.autoTemplate, actions, onDismiss = { preview = null }, onRename = {
            preview = null
            actions.setSelected(listOf(e.id), true)
            renameOpen = true
        }, onDelete = { preview = null; deleteIds = listOf(e.id) })
    }
    if (renameOpen) {
        RenameDialog(
            entries = state.entries.filter { it.id in state.selected },
            onDismiss = { renameOpen = false },
            onApply = { ids, template -> renameOpen = false; actions.renameGroup(ids, template) },
        )
    }
}

@Composable
private fun Thumb(video: CameraVideo, actions: GalleryActions, modifier: Modifier = Modifier) {
    val durationMs = video.durationMs
    val bmp by produceState<ImageBitmap?>(null, video.mediaId, video.onDevice) { value = if (video.onDevice) actions.thumbnail(video) else null }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF262626))) {
        bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Icon(if (video.onDevice) Icons.Filled.PlayArrow else Icons.Filled.CloudDone, null, tint = Muted, modifier = Modifier.align(Alignment.Center))
        Text(
            duration(durationMs), color = Color.White, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                .background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun VideoRow(
    e: GalleryEntry, autoTemplate: String, selected: Boolean, selecting: Boolean,
    actions: GalleryActions, modifier: Modifier = Modifier,
) {
    val (title, isAuto) = displayTitle(e, autoTemplate)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFF2A1A1A) else Card)
            .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Thumb(e.video, actions, Modifier.width(120.dp).aspectRatio(16f / 9f))
            if (selecting) {
                Box(
                    Modifier.padding(4.dp).size(20.dp).clip(CircleShape)
                        .background(if (selected) Accent else Color(0x88000000))
                        .border(1.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (isAuto) Muted else Ink, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Quay ${timeFmt.format(Date(e.video.recordedAt))} · ${mb(e.video.sizeBytes)}", color = Muted, fontSize = 12.sp)
            sourceLabel(e)?.let { Text(it, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)) }
            statusLabel(e.state)?.let { (label, color) ->
                val where = when {
                    e.video.onDevice -> ""
                    e.uploaded -> " · đã xoá khỏi máy"
                    else -> " · không còn trên máy"
                }
                Text(label + where, color = color, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            } ?: run {
                if (!e.video.onDevice) Text("Không còn trên máy", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun PreviewDialog(e: GalleryEntry, autoTemplate: String, actions: GalleryActions, onDismiss: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Card)
                .verticalScroll(rememberScrollState()),
        ) {
            if (!e.video.onDevice) {
                Column(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.CloudDone, null, tint = Muted, modifier = Modifier.size(40.dp))
                    Text(if (e.uploaded) "Video chỉ còn trên YouTube" else "Video không còn trên máy", color = Muted, fontSize = 13.sp)
                }
            } else Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).aspectRatio(16f / 9f).background(Color.Black)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            val mc = MediaController(ctx)
                            mc.setAnchorView(this)
                            setMediaController(mc)
                            e.video.localPath?.let(::setVideoPath) ?: setVideoURI(CameraVideos.uriOf(e.id))
                            setOnPreparedListener { it.isLooping = false; start() }
                        }
                    },
                    onRelease = { it.stopPlayback() },
                )
            }
            Column(Modifier.padding(16.dp)) {
                val (title, isAuto) = displayTitle(e, autoTemplate)
                Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                if (isAuto) Text("Tên tự động theo mẫu (chưa đặt riêng)", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text("Quay lúc ${fullFmt.format(Date(e.video.recordedAt))}", color = Ink, fontSize = 13.sp)
                Text("${e.video.displayName} · ${duration(e.video.durationMs)} · ${mb(e.video.sizeBytes)}", color = Muted, fontSize = 12.sp)
                if (!e.video.fromCamera && e.row == null) {
                    Text("Video này do ${e.ownerLabel ?: "một app khác"} lưu, không phải quay bằng camera — không tự động tải lên. Bạn vẫn có thể tải tay.", color = Accent, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                statusLabel(e.state)?.let { (label, color) -> Text(label, color = color, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    if (e.uploaded && e.video.onDevice) TextButton(onClick = onDelete) { Text("Xoá khỏi máy", color = Ink) }
                    if (e.uploaded) TextButton(onClick = { actions.openStudio(e.row!!.videoId!!) }) { Text("Mở Studio", color = Ink) }
                    if (e.video.onDevice && e.state in setOf(UploadState.IDLE, UploadState.SKIPPED, UploadState.FAILED)) {
                        TextButton(onClick = { actions.uploadGroup(listOf(e.id)); onDismiss() }) { Text("Tải lên", color = Ink) }
                    }
                    if (e.state != UploadState.UPLOADING) TextButton(onClick = onRename) { Text("Đặt tên", color = Accent) }
                    TextButton(onClick = onDismiss) { Text("Đóng", color = Muted) }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(entries: List<GalleryEntry>, onDismiss: () -> Unit, onApply: (List<Long>, String) -> Unit) {
    var template by remember { mutableStateOf("{YYYYMMDD} ") }
    val usable = entries.filter { it.state != UploadState.UPLOADING }
    val titles = groupTitles(usable, template)
    val onYoutube = usable.count { it.uploaded }
    val busy = entries.size - usable.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Đặt tên ${usable.size} video") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TitleTemplateField(template, { template = it }, tokens = VideoMetadata.TOKENS + VideoMetadata.SEQ, showPreview = false)
                Text("{n} = số thứ tự 1, 2, 3… theo giờ quay.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                Text("Xem trước", color = Muted, fontSize = 11.sp)
                titles.take(5).forEach { (e, t) ->
                    Text("• $t", color = Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("   quay ${fullFmt.format(Date(e.video.recordedAt))}", color = Muted, fontSize = 11.sp)
                }
                if (titles.size > 5) Text("… và ${titles.size - 5} video nữa", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                if (onYoutube > 0) Text("$onYoutube video đã lên YouTube sẽ được đổi tên trực tiếp (lần đầu Google sẽ hỏi thêm quyền quản lý video).", color = Muted, fontSize = 12.sp)
                if (usable.size - onYoutube > 0) Text("${usable.size - onYoutube} video chưa tải: tên này sẽ được dùng khi tải lên.", color = Muted, fontSize = 12.sp)
                if (busy > 0) Text("$busy video đang tải lên — bỏ qua, đổi sau khi tải xong.", color = Accent, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(usable.map { it.id }, template) }, enabled = usable.isNotEmpty() && template.isNotBlank()) {
                Text("Áp dụng", color = Accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ", color = Muted) } },
        containerColor = Card,
        titleContentColor = Ink,
    )
}
