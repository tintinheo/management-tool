package com.ytsync.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ytsync.data.UploadState
import com.ytsync.media.CameraVideo
import com.ytsync.media.CameraVideos
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ytsync.auth.YouTubeAuth
import com.ytsync.data.SettingsStore
import com.ytsync.data.UploadDb
import com.ytsync.data.UploadItem
import com.ytsync.sync.SyncScheduler
import com.ytsync.youtube.YouTubeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity(), HomeActions, GalleryActions {
    companion object {
        const val EXTRA_RECONNECT = "reconnect"
    }

    private val store by lazy { SettingsStore(this) }
    private val dao by lazy { UploadDb.get(this).dao() }

    private var message by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    /** Bumped on resume so permission/battery rows refresh after returning from system settings. */
    private var resumeTick by mutableStateOf(0)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        resumeTick++
        if (!hasVideo() && it.containsKey(Manifest.permission.READ_MEDIA_VIDEO)) {
            message = "Cần quyền \"Cho phép tất cả\" video. Nếu chỉ chọn một số video, app sẽ không thấy video mới."
        }
        lifecycleScope.launch { SyncScheduler.scanAndUpload(this@MainActivity) }
    }

    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        handleAuth(YouTubeAuth.fromIntent(this, res.data), allowPrompt = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent?.getBooleanExtra(EXTRA_RECONNECT, false) == true) connect()
        setContent {
            val settings by store.flow.collectAsState(initial = null)
            val items by dao.observeRecent().collectAsState(initial = emptyList())
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { resumeTick++ }
            }
            val tick = resumeTick
            val state = remember(settings, items, tick, busy, message) {
                HomeState(
                    settings = settings ?: com.ytsync.data.Settings(),
                    items = items,
                    hasVideoPermission = hasVideo(),
                    hasNotificationPermission = granted(Manifest.permission.POST_NOTIFICATIONS),
                    batteryUnrestricted = PhoneSetup.ignoresBatteryOptimizations(this),
                    isXiaomi = PhoneSetup.isXiaomi,
                    busy = busy,
                    message = message,
                )
            }
            val rows by dao.observeAll().collectAsState(initial = emptyList())
            val gallery = remember(galleryVideos, rows, selected, galleryLoading, settings, tick, othersVisible) {
                val byId = rows.associateBy { it.mediaId }
                GalleryState(
                    entries = galleryVideos.map { v ->
                        GalleryEntry(v, byId[v.mediaId], if (v.fromCamera) null else v.ownerPackage?.let(::appLabel))
                    },
                    loading = galleryLoading,
                    selected = selected,
                    autoTemplate = settings?.titleTemplate ?: com.ytsync.youtube.VideoMetadata.DEFAULT_TEMPLATE,
                    hasPermission = hasVideo(),
                    showOthers = othersVisible,
                )
            }
            LaunchedEffect(tab, tick) { if (tab == 1) refreshGallery() }
            BackHandler(enabled = tab == 1 && selected.isNotEmpty()) { selected = emptySet() }

            YtTheme {
                Column(Modifier.fillMaxSize().background(Bg)) {
                    Box(Modifier.weight(1f)) {
                        if (tab == 0) HomeScreen(state, this@MainActivity)
                        else GalleryScreen(gallery, this@MainActivity)
                    }
                    NavigationBar(containerColor = Card) {
                        val colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Accent, selectedTextColor = Ink, indicatorColor = Line,
                            unselectedIconColor = Muted, unselectedTextColor = Muted,
                        )
                        NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.CloudUpload, null) }, label = { Text("Đồng bộ") }, colors = colors)
                        NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.VideoLibrary, null) }, label = { Text("Video") }, colors = colors)
                    }
                }
                if (tab == 1) message?.let { msg ->
                    AlertDialog(
                        onDismissRequest = { message = null },
                        text = { Text(msg) },
                        confirmButton = { TextButton(onClick = { message = null }) { Text("OK", color = Accent) } },
                        containerColor = Card, textContentColor = Ink,
                    )
                }
            }
        }
    }

    // ---- Video tab ----

    private var tab by mutableStateOf(0)
    private var galleryVideos by mutableStateOf<List<CameraVideo>>(emptyList())
    private var galleryLoading by mutableStateOf(false)
    private var selected by mutableStateOf<Set<Long>>(emptySet())
    private val thumbs = LruCache<Long, ImageBitmap>(300)
    /** Renames waiting for the extra "manage videos" consent: (mediaId, videoId, title). */
    private var pendingRemote: List<Triple<Long, String, String>> = emptyList()

    private val manageAuthLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        when (val r = YouTubeAuth.fromIntent(this, res.data)) {
            is YouTubeAuth.Result.Token -> runRemoteRenames(r.accessToken)
            else -> {
                message = "Chưa cấp quyền quản lý video — tên mới đã lưu trong app nhưng chưa đổi trên YouTube."
                pendingRemote = emptyList()
            }
        }
    }

    override fun refreshGallery() {
        if (!hasVideo() || galleryLoading) return
        galleryLoading = true
        lifecycleScope.launch {
            galleryVideos = withContext(Dispatchers.IO) { runCatching { CameraVideos.listAll(this@MainActivity) }.getOrDefault(emptyList()) }
            val ids = galleryVideos.mapTo(HashSet()) { it.mediaId }
            selected = selected.filterTo(HashSet()) { it in ids }
            galleryLoading = false
        }
    }

    override fun toggle(id: Long) { selected = if (id in selected) selected - id else selected + id }
    override fun setSelected(ids: Collection<Long>, selected: Boolean) {
        this.selected = if (selected) this.selected + ids else this.selected - ids.toSet()
    }
    override fun clearSelection() { selected = emptySet() }

    private var othersVisible by mutableStateOf(false)
    private val labels = HashMap<String, String>()

    override fun setShowOthers(show: Boolean) {
        othersVisible = show
        if (!show) { // don't keep hidden videos selected
            val hidden = galleryVideos.filter { !it.fromCamera }.mapTo(HashSet()) { it.mediaId }
            selected = selected - hidden
        }
    }

    /** "com.zing.zalo" → "Zalo"; falls back to the package name for uninstalled apps. */
    private fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
        }.getOrDefault(pkg)
    }

    override suspend fun thumbnail(id: Long): ImageBitmap? = thumbs.get(id) ?: withContext(Dispatchers.IO) {
        CameraVideos.thumbnail(this@MainActivity, id)?.asImageBitmap()?.also { thumbs.put(id, it) }
    }

    override fun openStudio(videoId: String) = PhoneSetup.openUrl(this, "https://studio.youtube.com/video/$videoId/edit")

    private fun entriesFor(ids: Collection<Long>): List<GalleryEntry> {
        val set = ids.toSet()
        return galleryVideos.filter { it.mediaId in set }.map { GalleryEntry(it, null) }
    }

    override fun uploadGroup(ids: List<Long>) {
        lifecycleScope.launch {
            val s = store.current()
            if (!s.connected) { message = "Hãy kết nối YouTube ở tab Đồng bộ trước."; return@launch }
            val known = dao.knownIds().toHashSet()
            val videos = entriesFor(ids).map { it.video }
            dao.insertNew(videos.filter { it.mediaId !in known }.map { it.toItem(UploadState.QUEUED) })
            dao.queue(ids)
            SyncScheduler.enqueueUpload(this@MainActivity, s)
            selected = emptySet()
            message = "Đã thêm ${videos.size} video vào hàng đợi." + if (s.wifiOnly) " Sẽ tải khi có Wi‑Fi." else ""
        }
    }

    override fun renameGroup(ids: List<Long>, template: String) {
        lifecycleScope.launch {
            val rowsById = ids.mapNotNull { dao.get(it) }.associateBy { it.mediaId }
            val entries = entriesFor(ids).map { it.copy(row = rowsById[it.id]) }
                .filter { it.state != UploadState.UPLOADING }
            val titles = groupTitles(entries, template)
            var local = 0
            val remote = ArrayList<Triple<Long, String, String>>()
            for ((e, title) in titles) {
                when {
                    e.row == null -> { dao.insertNew(listOf(e.video.toItem(UploadState.IDLE).copy(titleOverride = title))); local++ }
                    e.uploaded -> { dao.setTitleOverride(e.id, title); remote += Triple(e.id, e.row!!.videoId!!, title) }
                    else -> { dao.setTitleOverride(e.id, title); local++ }
                }
            }
            selected = emptySet()
            // A video recorded inside the auto-sync window but not scanned yet must still auto-upload.
            SyncScheduler.scanAndUpload(this@MainActivity)
            if (remote.isEmpty()) {
                message = "Đã đặt tên $local video. Tên sẽ được dùng khi tải lên."
                return@launch
            }
            pendingRemote = remote
            when (val r = YouTubeAuth.token(this@MainActivity, YouTubeAuth.manageScopes)) {
                is YouTubeAuth.Result.Token -> runRemoteRenames(r.accessToken)
                is YouTubeAuth.Result.NeedsUser -> manageAuthLauncher.launch(IntentSenderRequest.Builder(r.intent.intentSender).build())
                is YouTubeAuth.Result.Error -> { message = "Không lấy được quyền: ${r.message}"; pendingRemote = emptyList() }
            }
        }
    }

    private fun runRemoteRenames(token: String) {
        val jobs = pendingRemote
        pendingRemote = emptyList()
        if (jobs.isEmpty()) return
        busy = true
        lifecycleScope.launch {
            val api = YouTubeApi()
            var ok = 0
            val errors = ArrayList<String>()
            for ((mediaId, videoId, title) in jobs) {
                val result = withContext(Dispatchers.IO) { runCatching { api.updateTitle(token, videoId, title) } }
                result.onSuccess { dao.setRemoteTitle(mediaId, title); ok++ }
                    .onFailure { errors += (it.message ?: it.javaClass.simpleName) }
                if (errors.firstOrNull()?.contains("quotaExceeded") == true) break
            }
            busy = false
            message = buildString {
                append("Đã đổi tên $ok/${jobs.size} video trên YouTube.")
                if (errors.isNotEmpty()) append("\nLỗi: ${errors.first()}")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RECONNECT, false)) connect()
    }

    /** SHA-1 of the certificate this installed APK is signed with — what Google matches against. */
    private fun signingSha1(): String? = runCatching {
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo!!.apkContentsSigners.first().toByteArray()
        java.security.MessageDigest.getInstance("SHA-1").digest(cert).joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasVideo() = granted(Manifest.permission.READ_MEDIA_VIDEO)

    // ---- auth ----

    override fun connect() {
        busy = true
        lifecycleScope.launch { handleAuth(YouTubeAuth.token(this@MainActivity), allowPrompt = true) }
    }

    private fun handleAuth(result: YouTubeAuth.Result, allowPrompt: Boolean) {
        when (result) {
            is YouTubeAuth.Result.NeedsUser -> {
                if (allowPrompt) authLauncher.launch(IntentSenderRequest.Builder(result.intent.intentSender).build())
                else { busy = false; message = "Chưa cấp quyền YouTube." }
            }
            is YouTubeAuth.Result.Error -> {
                busy = false
                val m = result.message
                message = if (m.contains("UNREGISTERED_ON_API_CONSOLE") || m.contains("10:") || m.contains("DEVELOPER_ERROR")) {
                    "Google không tìm thấy Android OAuth client khớp với app này ($m).\n\n" +
                        "Trong Google Cloud → Clients, client loại Android phải có ĐÚNG:\n" +
                        "Package: $packageName\nSHA-1: ${signingSha1() ?: "?"}\n\n" +
                        "Client phải nằm cùng project đã bật YouTube Data API v3. Sau khi lưu, đợi 5–30 phút rồi thử lại."
                } else "Không kết nối được: $m"
            }
            is YouTubeAuth.Result.Token -> lifecycleScope.launch {
                val channel = withContext(Dispatchers.IO) {
                    runCatching { YouTubeApi().myChannelTitle(result.accessToken) }.getOrNull()
                }
                store.setConnected(true, channel ?: "")
                busy = false
                message = if (channel == null) "Đã cấp quyền, nhưng tài khoản này có thể chưa có kênh YouTube." else null
                SyncScheduler.scanAndUpload(this@MainActivity)
            }
        }
    }

    // ---- setup ----

    override fun requestVideoPermission() {
        permissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    }

    override fun requestNotificationPermission() {
        permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    override fun fixBattery() {
        if (!PhoneSetup.ignoresBatteryOptimizations(this)) PhoneSetup.requestIgnoreBatteryOptimizations(this)
        else if (PhoneSetup.isXiaomi) PhoneSetup.openBattery(this)
    }

    override fun openAutostart() = PhoneSetup.openAutostart(this)

    // ---- settings ----

    override fun setEnabled(on: Boolean) {
        lifecycleScope.launch {
            store.setEnabled(on)
            SyncScheduler.ensure(this@MainActivity)
            if (on) SyncScheduler.scanAndUpload(this@MainActivity)
        }
    }

    private fun afterConstraintChange() = lifecycleScope.launch {
        val s = store.current()
        if (s.enabled && dao.pendingCount() > 0) SyncScheduler.enqueueUpload(this@MainActivity, s, replace = true)
    }

    override fun setWifiOnly(on: Boolean) { lifecycleScope.launch { store.setWifiOnly(on); afterConstraintChange() } }
    override fun setChargingOnly(on: Boolean) { lifecycleScope.launch { store.setChargingOnly(on); afterConstraintChange() } }
    override fun setAgeRestrict(on: Boolean) { lifecycleScope.launch { store.setAgeRestrict(on) } }
    override fun setTitleTemplate(text: String) { lifecycleScope.launch { store.setTitleTemplate(text) } }

    override fun scanNow() {
        busy = true
        lifecycleScope.launch {
            val n = SyncScheduler.scan(this@MainActivity)
            val s = store.current()
            if (dao.pendingCount() > 0) SyncScheduler.enqueueUpload(this@MainActivity, s)
            busy = false
            message = when {
                n > 0 -> "Đã thêm $n video vào hàng đợi."
                s.wifiOnly && dao.pendingCount() > 0 -> "Không có video mới. Video đang chờ sẽ tải khi có Wi‑Fi."
                else -> "Không có video mới."
            }
        }
    }

    override fun backfill(days: Int?) {
        lifecycleScope.launch {
            val now = System.currentTimeMillis() / 1000
            val since = if (days == null) 1L else now - days * 86_400L
            if (since < store.current().sinceEpochSec) store.setSince(since)
            scanNow()
        }
    }

    // ---- queue ----

    override fun retry(item: UploadItem) {
        lifecycleScope.launch { dao.retry(item.mediaId); SyncScheduler.enqueueUpload(this@MainActivity, store.current()) }
    }

    override fun retryAllFailed() {
        lifecycleScope.launch { dao.retryAllFailed(); SyncScheduler.enqueueUpload(this@MainActivity, store.current()) }
    }

    override fun skip(item: UploadItem) { lifecycleScope.launch { dao.skip(item.mediaId) } }

    override fun openStudio(item: UploadItem) {
        item.videoId?.let { PhoneSetup.openUrl(this, "https://studio.youtube.com/video/$it/edit") }
    }

    override fun dismissMessage() { message = null }
}
