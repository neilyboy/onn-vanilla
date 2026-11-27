package tv.vanilla.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val HOME_PREFS = "home"
/** Per-TV state that isn't part of how Home looks. */
private val NOT_SHARED = setOf("home_released")

/** This TV's Home (look, layout, dock, Home Assistant picks) as JSON, to save in the setup app or copy to another TV. */
fun homeSnapshot(ctx: Context): JSONObject {
    val prefs = JSONObject()
    ctx.getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE).all.forEach { (k, v) ->
        if (k in NOT_SHARED || v == null) return@forEach
        val t = when (v) { is String -> "s"; is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is Set<*> -> "set"; else -> return@forEach }
        prefs.put(k, JSONObject().put("t", t).put("v", if (v is Set<*>) JSONArray(v.map { it.toString() }) else v))
    }
    val dock = runCatching { JSONArray(File(ctx.filesDir, "dock.json").readText()) }.getOrNull()
    val live = runCatching {
        ctx.contentResolver.query(android.net.Uri.parse("content://tv.vanilla.live.glance/prefs"), null, null, null, null)?.use { c -> if (c.moveToFirst()) JSONObject(c.getString(0)) else null }
    }.getOrNull()?.takeIf { it.length() > 0 }
    return JSONObject().put("vanillaHome", 1).put("name", Rooms.get(ctx).myName).put("prefs", prefs).apply {
        if (dock != null) put("dock", dock)
        if (live != null) put("livetv", live)
    }
}

/** Replaces this TV's Home with [j] from [homeSnapshot]. The caller reloads what's on screen. */
fun applyHomeSnapshot(ctx: Context, j: JSONObject, includeLive: Boolean = true): Boolean {
    if (j.optInt("vanillaHome") != 1) return false
    if (includeLive) j.optJSONObject("livetv")?.let { live ->
        runCatching { ctx.contentResolver.call(android.net.Uri.parse("content://tv.vanilla.live.glance"), "apply", live.toString(), null) }
    }
    val p = ctx.getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE)
    val e = p.edit()
    p.all.keys.filter { it !in NOT_SHARED }.forEach { e.remove(it) }
    val prefs = j.optJSONObject("prefs") ?: JSONObject()
    prefs.keys().forEach { k ->
        if (k in NOT_SHARED) return@forEach
        val o = prefs.optJSONObject(k) ?: return@forEach
        when (o.optString("t")) {
            "s" -> e.putString(k, o.optString("v"))
            "b" -> e.putBoolean(k, o.optBoolean("v"))
            "i" -> e.putInt(k, o.optInt("v"))
            "l" -> e.putLong(k, o.optLong("v"))
            "f" -> e.putFloat(k, o.optDouble("v").toFloat())
            "set" -> e.putStringSet(k, o.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet())
        }
    }
    e.apply()
    val dockFile = File(ctx.filesDir, "dock.json")
    j.optJSONArray("dock")?.let { dockFile.writeText(it.toString()) } ?: dockFile.delete()
    return true
}

/** App updates from the setup app (when it set this box up) or the project's GitHub releases. */
object Updates {
    private const val GITHUB = "https://github.com/neilyboy/onn-vanilla/releases/latest/download/"
    /** Live TV first: updating Vanilla Home restarts it. */
    val FILES = linkedMapOf("tv.vanilla.live" to "vanilla-livetv.apk", "tv.vanilla.launcher" to "vanilla-launcher.apk")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    fun setSource(ctx: Context, url: String) = prefs(ctx).edit().putString("update_url", url).apply()
    private fun source(ctx: Context) = prefs(ctx).getString("update_url", null)?.takeIf { it.isNotBlank() } ?: GITHUB

    fun installed(ctx: Context, pkg: String): Pair<Long, String>? = runCatching {
        val i = ctx.packageManager.getPackageInfo(pkg, 0)
        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(i) to (i.versionName ?: "")
    }.getOrNull()

    data class Found(val base: String, val newer: List<String>, val names: Map<String, String>)

    /** Which installed apps have a newer version available. Call off the main thread. */
    fun check(ctx: Context): Found? {
        val base = source(ctx)
        val j = runCatching { JSONObject(httpGet(base + "version.json")) }.getOrNull() ?: return null
        val names = mutableMapOf<String, String>()
        val newer = FILES.keys.filter { pkg ->
            val o = j.optJSONObject(pkg) ?: return@filter false
            names[pkg] = o.optString("versionName")
            val have = installed(ctx, pkg) ?: return@filter false
            o.optLong("versionCode") > have.first
        }
        return Found(base, newer, names)
    }

    /** Downloads and installs one app. Android may ask the viewer to confirm. Call off the main thread. */
    fun install(ctx: Context, base: String, pkg: String) {
        val file = File(ctx.cacheDir, FILES.getValue(pkg))
        download(base + FILES.getValue(pkg), file)
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(pkg)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("app", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val done = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_INSTALLED).putExtra("pkg", pkg), flags)
            s.commit(done.intentSender)
        }
    }

    private fun download(url: String, to: File) {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 30_000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "VanillaHome/1.0")
            try {
                val code = c.responseCode
                if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location")).toString(); return@repeat }
                if (code !in 200..299) error("HTTP $code")
                c.inputStream.use { inp -> to.outputStream().use { inp.copyTo(it) } }
                return
            } finally { c.disconnect() }
        }
        error("Too many redirects")
    }

    const val ACTION_INSTALLED = "tv.vanilla.launcher.INSTALLED"
}

/** Install results, and re-arming the Home button after Vanilla Home itself is updated. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pending = goAsync()
            HomeButtonService.heal(ctx)
            Rooms.get(ctx).start()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ pending.finish() }, 2000)
            return
        }
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(ctx, if (intent.getStringExtra("pkg") == "tv.vanilla.live") "Live TV updated" else "Vanilla Home updated", Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(ctx, "Update didn't install: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"), Toast.LENGTH_LONG).show()
        }
    }
}
