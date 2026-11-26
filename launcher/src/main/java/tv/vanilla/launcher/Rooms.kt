package tv.vanilla.launcher

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.concurrent.thread

/** Another Vanilla box on the network and what it's watching ("" = nothing in Live TV). */
data class Peer(val name: String, val host: String, val port: Int, val playing: String = "", val logo: String = "")

private const val SERVICE = "_vanillahome._tcp."
private const val PORT = 8766

/** What Live TV on this box is playing right now, or null. */
fun readPlaying(ctx: Context): JSONObject? = runCatching {
    val text = ctx.contentResolver.query(Uri.parse("content://tv.vanilla.live.glance/playing"), null, null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: return null
    JSONObject(text).takeIf { it.optString("name").isNotBlank() && System.currentTimeMillis() - it.optLong("at") < 12 * 3600_000L }
}.getOrNull()

/**
 * Rooms: each box announces itself on the local network (mDNS) and answers two requests on port 8766:
 * `GET /now` (what it's watching) and `GET /tune?ch=<channel name>` (open that channel in Live TV).
 */
class Rooms(private val ctx: MainActivity) {
    val peers = mutableStateOf<List<Peer>>(emptyList())
    val myName: String = runCatching { android.provider.Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull()?.ifBlank { null } ?: Build.MODEL
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var server: ServerSocket? = null
    private var registered: String? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var started = false

    fun start() {
        if (started) return
        started = true
        thread(isDaemon = true, name = "rooms") { serve() }
        runCatching {
            nsd.registerService(NsdServiceInfo().apply { serviceName = myName; serviceType = SERVICE; port = PORT }, NsdManager.PROTOCOL_DNS_SD, regListener)
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discListener)
        }
    }

    private fun serve() = runCatching {
        val s = ServerSocket(PORT).also { server = it }
        while (true) {
            val sock = s.accept()
            thread(isDaemon = true) {
                runCatching {
                    sock.soTimeout = 4000
                    val line = sock.getInputStream().bufferedReader().readLine().orEmpty()
                    val path = line.split(' ').getOrNull(1).orEmpty()
                    val body = when {
                        path.startsWith("/now") -> {
                            val p = readPlaying(ctx)
                            JSONObject().put("name", myName).put("playing", p?.optString("name") ?: "").put("logo", p?.optString("logo") ?: "").toString()
                        }
                        path.startsWith("/tune?ch=") -> {
                            val ch = URLDecoder.decode(path.substringAfter("ch="), "UTF-8")
                            ctx.runOnUiThread { openLive(ctx, "tune", ch) }
                            """{"ok":true}"""
                        }
                        else -> """{"error":"not found"}"""
                    }
                    val bytes = body.toByteArray()
                    sock.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
                runCatching { sock.close() }
            }
        }
    }

    /** Refreshes what each other box is watching. Call off the main thread. */
    fun poll() {
        peers.value = peers.value.map { p ->
            runCatching { JSONObject(httpGet("http://${p.host}:${p.port}/now", 3000)) }.getOrNull()
                ?.let { p.copy(playing = it.optString("playing"), logo = it.optString("logo")) } ?: p
        }
    }

    fun send(p: Peer, channel: String) = thread(isDaemon = true) {
        runCatching { httpGet("http://${p.host}:${p.port}/tune?ch=" + URLEncoder.encode(channel, "UTF-8"), 4000) }
    }

    private val regListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) { registered = info.serviceName }
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
    }

    private val discListener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            if (info.serviceName == (registered ?: myName)) return
            synchronized(pending) { pending.addLast(info) }
            resolveNext()
        }
        override fun onServiceLost(info: NsdServiceInfo) { peers.value = peers.value.filter { it.name != info.serviceName } }
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onStartDiscoveryFailed(type: String, code: Int) = Unit
        override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
    }

    // NsdManager resolves one service at a time.
    private fun resolveNext() {
        val next = synchronized(pending) { if (resolving) null else pending.removeFirstOrNull()?.also { resolving = true } } ?: return
        @Suppress("DEPRECATION")
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                if (host != null && info.serviceName != registered) {
                    peers.value = peers.value.filter { it.name != info.serviceName } + Peer(info.serviceName, host, info.port)
                }
                synchronized(pending) { resolving = false }; resolveNext()
            }
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { synchronized(pending) { resolving = false }; resolveNext() }
        })
    }
}
