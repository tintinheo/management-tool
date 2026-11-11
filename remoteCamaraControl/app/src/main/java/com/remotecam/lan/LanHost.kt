package com.remotecam.lan

import android.content.Context
import com.remotecam.camera.ControlRequest
import com.remotecam.remote.RemoteBus
import com.remotecam.remote.RemoteCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * Runs the browser control server on whatever local network the phone is on: home Wi-Fi, or the
 * phone's own hotspot when there is no router or internet. Hotspot traffic stays between the devices
 * and uses no mobile data.
 */
class LanHost(private val context: Context) {

    private var server: LanServer? = null
    private var watcher: Job? = null

    fun start(scope: CoroutineScope) {
        if (server != null) return
        val code = accessCode()
        val page = context.assets.open("remote.html").use { it.readBytes() }
        val library = AndroidMediaLibrary(context.contentResolver)
        val backend = object : LanBackend {
            override fun page() = page
            override fun status() = StatusJson.encode(RemoteBus.camera.value, LiveStream.hubs.totalViewers())
            override fun command(command: RemoteCommand) = RemoteBus.dispatchCommand(command, "Trình duyệt")
            override fun control(request: ControlRequest) = RemoteBus.dispatchControl(request)
            override val media: MediaLibrary = library
        }
        val lan = LanServer(
            token = code,
            streams = LiveStream.hubs,
            backend = backend,
            log = RemoteBus::log,
            onViewers = RemoteBus::setViewers,
        )
        val port = lan.start(PORT)
        server = lan
        // The hotspot can be switched on after the app starts, so keep re-reading the addresses.
        watcher = scope.launch(Dispatchers.IO) {
            var last: List<String>? = null
            while (isActive) {
                val urls = localAddresses().map { (ip, iface) -> "http://$ip:$port/?k=$code  ($iface)" }
                if (urls != last) {
                    RemoteBus.setServerUrls(urls)
                    if (urls.isEmpty()) {
                        RemoteBus.log("Chưa có mạng nội bộ: bật Wi-Fi hoặc Hotspot trên điện thoại")
                    } else {
                        urls.forEach { RemoteBus.log("Mở trên tablet/PC: $it") }
                    }
                    last = urls
                }
                delay(3_000)
            }
        }
    }

    fun stop() {
        watcher?.cancel()
        watcher = null
        server?.stop()
        server = null
        RemoteBus.setServerUrls(emptyList())
        RemoteBus.setViewers(0)
    }

    /** Six-digit code kept across launches, so bookmarks on the tablet and PC keep working. */
    private fun accessCode(): String {
        val prefs = context.getSharedPreferences("lan", Context.MODE_PRIVATE)
        prefs.getString(KEY_CODE, null)?.let { return it }
        val code = (SecureRandom().nextInt(900_000) + 100_000).toString()
        prefs.edit().putString(KEY_CODE, code).apply()
        return code
    }

    companion object {
        const val PORT = 8080
        private const val KEY_CODE = "access_code"

        /** Private IPv4 addresses of interfaces that are up (Wi-Fi client, hotspot, USB tethering). */
        fun localAddresses(): List<Pair<String, String>> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { iface ->
                    iface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .filter { it.isSiteLocalAddress }
                        .map { it.hostAddress.orEmpty() to iface.name }
                }
                .sortedBy { it.second }
        }.getOrDefault(emptyList())
    }
}
