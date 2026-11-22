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
) {
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
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = timeoutMs
    conn.readTimeout = timeoutMs
    conn.setRequestProperty("User-Agent", "VanillaHome/1.0")
    try {
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}
