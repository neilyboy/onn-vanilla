package tv.vanilla.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppEntry(
    val id: String,
    val label: String,
    val packages: List<String>,
    val icon: String,
    val iconUrl: String,
)

data class LauncherConfig(
    val themeName: String,
    val accent: String,
    val wallpaperMode: String,
    val clock24h: Boolean,
    val wallpaperIntervalSec: Int,
    val idleTimeoutSec: Int,
    val scale: Float,
    val kenBurns: Boolean,
    val showWeather: Boolean,
    val showForecast: Boolean,
    val showSettings: Boolean,
    val greeting: String,
    val latitude: Double,
    val longitude: Double,
    val fahrenheit: Boolean,
    val locationName: String,
    val wallpaperManifestUrl: String,
    val apps: List<AppEntry>,
    val iconStyle: String = "original",
    val calendars: List<String> = emptyList(),
    val newsFeeds: List<String> = emptyList(),
    val alerts: Boolean = true,
    val layout: String = "minimal",
    val widgets: List<WidgetSpec> = emptyList(),
    val countdowns: List<String> = emptyList(),
    val haUrl: String = "",
    val haToken: String = "",
    val haEntities: List<String> = emptyList(),
    val photos: List<String> = emptyList(),
    val radioStations: List<String> = emptyList(),
) {
    fun widget(id: String) = if (layout == "minimal") null else widgets.firstOrNull { it.id == id }
    val hasWeather get() = showWeather && !latitude.isNaN() && !longitude.isNaN()

    companion object {
        fun parse(text: String, baseUrl: String?): LauncherConfig {
            val root = JSONObject(text)
            val theme = root.optJSONObject("theme") ?: JSONObject()
            val ui = root.optJSONObject("ui") ?: JSONObject()
            val weather = root.optJSONObject("weather") ?: JSONObject()
            val wallpapers = root.optJSONObject("wallpapers") ?: JSONObject()
            val apps = root.optJSONArray("apps") ?: JSONArray()
            return LauncherConfig(
                themeName = theme.optString("name", "midnight"),
                accent = theme.optString("accent", ""),
                wallpaperMode = theme.optString("wallpaper_mode", "gradient"),
                clock24h = theme.optBoolean("clock_24h", false),
                wallpaperIntervalSec = ui.optInt("wallpaper_interval_sec", 45).coerceAtLeast(10),
                idleTimeoutSec = ui.optInt("idle_timeout_sec", 300),
                iconStyle = ui.optString("icon_style", "original"),
                calendars = strings(root.optJSONObject("feeds")?.optJSONArray("calendars")),
                newsFeeds = strings(root.optJSONObject("feeds")?.optJSONArray("news")),
                alerts = root.optJSONObject("feeds")?.optBoolean("alerts", true) ?: true,
                layout = root.optJSONObject("widgets")?.optString("layout", "minimal") ?: "minimal",
                widgets = root.optJSONObject("widgets")?.optJSONArray("items")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { WidgetSpec(it.optString("id"), it.optString("pos", "center")) }
                } ?: emptyList(),
                countdowns = strings(root.optJSONObject("widgets")?.optJSONArray("countdowns")),
                haUrl = root.optJSONObject("ha")?.optString("url", "") ?: "",
                haToken = root.optJSONObject("ha")?.optString("token", "") ?: "",
                haEntities = strings(root.optJSONObject("ha")?.optJSONArray("entities")),
                photos = strings(root.optJSONObject("photos")?.optJSONArray("sources")),
                radioStations = strings(root.optJSONObject("radio")?.optJSONArray("stations")),
                scale = ui.optDouble("scale", 1.0).toFloat().coerceIn(0.6f, 1.6f),
                kenBurns = ui.optBoolean("ken_burns", true),
                showWeather = ui.optBoolean("show_weather", true),
                showForecast = ui.optBoolean("show_forecast", true),
                showSettings = ui.optBoolean("show_settings", true),
                greeting = ui.optString("greeting", ""),
                latitude = weather.optDouble("latitude", Double.NaN),
                longitude = weather.optDouble("longitude", Double.NaN),
                fahrenheit = weather.optString("unit", "fahrenheit") != "celsius",
                locationName = weather.optString("location_name", ""),
                wallpaperManifestUrl = resolve(baseUrl, wallpapers.optString("manifest_url", "")),
                apps = appsFromJson(apps).map { it.copy(iconUrl = resolve(baseUrl, it.iconUrl)) },
            )
        }

        private fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }.filter { it.isNotBlank() }

        fun resolve(base: String?, ref: String): String {
            if (ref.isBlank()) return ""
            if (base.isNullOrBlank()) return ref
            return runCatching { URL(URL(base), ref).toString() }.getOrDefault(ref)
        }
    }
}

class ConfigRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "launcher-config.json")

    var configUrl: String
        get() = prefs.getString("config_url", "") ?: ""
        set(value) = prefs.edit().putString("config_url", value.trim()).apply()

    private val dockFile = File(context.filesDir, "dock.json")

    /** Dock edited on the box (add/move/remove); overrides the config's app list when present. */
    var localDock: List<AppEntry>?
        get() = runCatching { appsFromJson(JSONArray(dockFile.readText())) }.getOrNull()
        set(value) {
            if (value == null) dockFile.delete() else dockFile.writeText(appsToJson(value).toString())
        }

    /** Applies a full config pushed by the setup app (`--es config_b64 <base64 json>`). */
    fun applyInline(text: String): LauncherConfig? = runCatching {
        val parsed = LauncherConfig.parse(text, configUrl.ifBlank { null })
        cacheFile.writeText(text)
        parsed
    }.getOrNull()

    fun loadCached(): LauncherConfig {
        val url = configUrl
        if (cacheFile.exists()) {
            runCatching { return LauncherConfig.parse(cacheFile.readText(), url) }
        }
        val text = context.assets.open("default-config.json").bufferedReader().use { it.readText() }
        return LauncherConfig.parse(text, null)
    }

    fun fetchRemote(): LauncherConfig? {
        val url = configUrl.ifBlank { return null }
        return runCatching {
            val text = httpGet(url)
            val parsed = LauncherConfig.parse(text, url)
            cacheFile.writeText(text)
            parsed
        }.getOrNull()
    }
}

fun appsToJson(apps: List<AppEntry>) = JSONArray().apply {
    apps.forEach { a ->
        put(JSONObject().apply {
            put("id", a.id); put("label", a.label); put("icon", a.icon); put("icon_url", a.iconUrl)
            put("package", a.packages.firstOrNull() ?: "")
            put("fallback_packages", JSONArray(a.packages.drop(1)))
        })
    }
}

fun appsFromJson(arr: JSONArray): List<AppEntry> = (0 until arr.length()).mapNotNull { i ->
    val a = arr.optJSONObject(i) ?: return@mapNotNull null
    val pkgs = buildList {
        a.optString("package").takeIf { it.isNotBlank() }?.let(::add)
        a.optJSONArray("fallback_packages")?.let { fp -> for (j in 0 until fp.length()) add(fp.optString(j)) }
    }.filter { it.isNotBlank() }
    AppEntry(a.optString("id", "app$i"), a.optString("label", ""), pkgs, a.optString("icon", ""), a.optString("icon_url", ""))
}

fun httpGet(url: String, timeoutMs: Int = 8000): String {
    var u = url
    repeat(5) {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "VanillaHome/1.0")
        try {
            val code = conn.responseCode
            // HttpURLConnection won't follow http -> https on its own, which most reverse proxies do.
            if (code in 300..399) { u = URL(URL(u), conn.getHeaderField("Location") ?: error("HTTP $code")).toString(); return@repeat }
            if (code !in 200..299) error("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
    error("Too many redirects")
}

/** Where [url] ends up after redirects, e.g. http:// share links behind an https proxy. */
fun finalUrl(url: String, timeoutMs: Int = 8000): String {
    var u = url
    repeat(5) {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = false
        try {
            if (conn.responseCode !in 300..399) return u
            u = URL(URL(u), conn.getHeaderField("Location") ?: return u).toString()
        } finally {
            conn.disconnect()
        }
    }
    return u
}

/** A home screen widget and where it sits: left, center or right. */
data class WidgetSpec(val id: String, val pos: String)
