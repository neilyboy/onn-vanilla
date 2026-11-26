package tv.vanilla.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A Home Assistant entity as shown on the home screen. */
data class HaEntity(val id: String, val name: String, val state: String, val unit: String, val deviceClass: String = "") {
    val domain get() = id.substringBefore('.')
    val toggleable get() = domain in setOf("light", "switch", "fan", "input_boolean", "lock", "cover", "script", "scene", "automation")
    val on get() = state in setOf("on", "open", "unlocked", "playing", "home")
}

private fun haCall(base: String, token: String, path: String, body: String? = null): String {
    val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
    c.connectTimeout = 8000; c.readTimeout = 10000
    c.setRequestProperty("Authorization", "Bearer $token")
    c.setRequestProperty("Content-Type", "application/json")
    if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
    return c.inputStream.use { it.readBytes().decodeToString() }
}

private val PICKABLE = setOf("light", "switch", "fan", "lock", "cover", "climate", "input_boolean", "camera", "scene", "script", "media_player", "sensor", "binary_sensor", "vacuum")
private val AUTO = setOf("light", "switch", "fan", "lock", "cover", "climate", "input_boolean", "camera")
private val NOISE = Regex("""^sensor\.(sun|backup)_|_led$|_cloud_connection$""")

/** Every Home Assistant entity worth showing on a TV, so new devices appear without touching the setup app. */
fun fetchHaAll(cfg: LauncherConfig): List<HaEntity>? {
    if (cfg.haUrl.isBlank() || cfg.haToken.isBlank()) return emptyList()
    val all = runCatching { JSONArray(haCall(cfg.haUrl, cfg.haToken, "/api/states")) }.getOrNull() ?: return null
    return (0 until all.length()).mapNotNull { all.optJSONObject(it) }.mapNotNull { o ->
        val id = o.optString("entity_id")
        if (id.substringBefore('.') !in PICKABLE || NOISE.containsMatchIn(id)) return@mapNotNull null
        val a = o.optJSONObject("attributes") ?: JSONObject()
        HaEntity(id, a.optString("friendly_name", id), o.optString("state"), a.optString("unit_of_measurement", ""), a.optString("device_class", ""))
    }
}

/** What Home shows: the setup app's picks, then every light/switch/lock/etc. (new ones included), minus what was hidden on the TV. */
fun haShown(cfg: LauncherConfig, all: List<HaEntity>, hidden: Set<String>, added: Set<String>): List<HaEntity> {
    val picked = cfg.haEntities.flatMap { it.split(',', ' ') }.map { it.trim() }.filter { it.isNotEmpty() }
    val byId = all.associateBy { it.id }
    val first = picked.mapNotNull { byId[it] }
    val rest = all.filter { it.id !in picked && (it.id in added || it.domain in AUTO) }.sortedBy { it.name.lowercase() }
    return (first + rest).filter { it.id !in hidden }
}

/** Toggles lights/switches, locks or unlocks locks, opens or closes covers, runs scripts and scenes. */
fun haToggle(cfg: LauncherConfig, e: HaEntity): Boolean = runCatching {
    val service = when (e.domain) {
        "lock" -> if (e.state == "locked") "lock/unlock" else "lock/lock"
        "cover" -> "cover/toggle"
        "script", "scene" -> "${e.domain}/turn_on"
        "automation" -> "automation/trigger"
        else -> "homeassistant/toggle"
    }
    haCall(cfg.haUrl, cfg.haToken, "/api/services/$service", JSONObject().put("entity_id", e.id).toString())
    true
}.getOrDefault(false)

fun haCameraUrl(cfg: LauncherConfig, id: String) = cfg.haUrl.trimEnd('/') + "/api/camera_proxy/$id"

private val GOOGLE_PHOTO = Regex("""https://lh3\.googleusercontent\.com/pw/[A-Za-z0-9_\-]+""")
private val IMAGE_EXT = Regex("""\.(jpe?g|png|webp|gif)(\?.*)?$""", RegexOption.IGNORE_CASE)

/** Photo frame sources: Immich share links, Google Photos shared albums, wallpaper manifests (.json) or image URLs. */
fun fetchPhotos(sources: List<String>): List<String> = sources.flatMap { src ->
    runCatching {
        when {
            "/share/" in src && !src.contains("google") -> {
                val url = finalUrl(src)
                val base = url.substringBefore("/share/"); val key = url.substringAfter("/share/").substringBefore('?').trimEnd('/')
                val assets = JSONObject(httpGet("$base/api/shared-links/me?key=$key", 15000)).optJSONArray("assets") ?: JSONArray()
                (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.filter { it.optString("type") == "IMAGE" }
                    .map { "$base/api/assets/${it.optString("id")}/thumbnail?size=preview&key=$key" }
            }
            "photos.app.goo.gl" in src || "photos.google.com" in src ->
                GOOGLE_PHOTO.findAll(httpGet(src, 20000)).map { it.value }.distinct().map { "$it=w1920-h1080" }.toList()
            src.substringBefore('?').endsWith(".json") -> fetchWallpaperList(src)
            IMAGE_EXT.containsMatchIn(src) -> listOf(src)
            else -> emptyList()
        }
    }.getOrDefault(emptyList())
}.shuffled()
