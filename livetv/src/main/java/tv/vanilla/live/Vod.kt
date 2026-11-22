package tv.vanilla.live

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Dispatcharr's Xtream Codes API, used for providers' movies and series. */
data class Xc(val base: String, val user: String, val pass: String) {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun api(action: String, extra: String = "") = "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}&action=$action$extra"
    fun movieUrl(id: String, ext: String) = "$base/movie/$user/$pass/$id.${ext.ifBlank { "mp4" }}"
    fun episodeUrl(id: String, ext: String) = "$base/series/$user/$pass/$id.${ext.ifBlank { "mp4" }}"
}

data class VodCat(val id: String, val name: String)
data class VodItem(val id: String, val name: String, val poster: String, val ext: String, val rating: String, val year: String, val plot: String, val series: Boolean, val adult: Boolean)
data class Episode(val id: String, val season: Int, val num: Int, val title: String, val ext: String, val plot: String, val still: String, val duration: String)

private val YEAR = Regex("""(19|20)\d\d""")

private fun JSONObject.str(k: String) = optString(k).takeIf { it.isNotBlank() && it != "null" && it != "0" } ?: ""

/** Categories left out of "New on": other-language sections, sports replays and unsorted dumps. */
private val NOT_NEW = Regex("""^(?!US\b|UK\b|EN\b)[A-Z]{2} [|-]|(?i)bollywood|korean|anime sub|replays|unsorted|unlabeled|uncategori[sz]ed""")

private val NEW_CATS = Regex("""(?i)\b20\d\d\b|releases|most popular|new\b|recent|usa selection|netflix|disney|prime|apple tv|hulu|hbo|paramount|peacock""")

object Vod {
    private val cache = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(key: String, load: () -> T): T = synchronized(cache) { cache[key] as T? } ?: load().also { synchronized(cache) { cache[key] = it as Any } }

    private fun objs(a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    fun cats(xc: Xc, series: Boolean, adult: Boolean): List<VodCat> = cached("c$series") {
        objs(JSONArray(httpGet(xc.api(if (series) "get_series_categories" else "get_vod_categories"), 30000)))
            .map { VodCat(it.optString("category_id"), it.optString("category_name")) }
    }.filter { adult || !isAdultGroup(it.name) }.sortedBy { isAdultGroup(it.name) }

    fun items(xc: Xc, series: Boolean, catId: String, adult: Boolean): List<VodItem> = cached("i$series:$catId") {
        objs(JSONArray(httpGet(xc.api(if (series) "get_series" else "get_vod_streams", "&category_id=$catId"), 60000))).map { o ->
            VodItem(
                id = o.optString(if (series) "series_id" else "stream_id"),
                name = o.optString("name"),
                poster = o.str(if (series) "cover" else "stream_icon"),
                ext = o.str("container_extension"),
                rating = o.str("rating").toDoubleOrNull()?.takeIf { it > 0 }?.let { "%.1f".format(it) } ?: "",
                year = (o.str("year").takeIf { YEAR.matches(it) } ?: o.str("releaseDate").take(4)).takeIf { YEAR.matches(it) } ?: "",
                plot = o.str("plot"),
                series = series,
                adult = o.optInt("is_adult") == 1,
            )
        }
    }.filter { adult || !it.adult }

    /** The [n] most recently added movies or series (non-adult), streamed so the full catalog never sits in memory. */
    fun newest(xc: Xc, series: Boolean, n: Int = 12): JSONArray {
        val all = runCatching { cats(xc, series, true) }.getOrDefault(emptyList()).filter { !isAdultGroup(it.name) && !NOT_NEW.containsMatchIn(it.name) }
        // Providers' "2026 Releases" / "Most Popular" / streaming-service sections beat raw upload dates, which are mostly foreign back catalog.
        val fresh = all.filter { NEW_CATS.containsMatchIn(it.name) }.map { it.id }.toSet()
        val allowed = fresh.ifEmpty { all.map { it.id }.toSet() }
        val top = java.util.PriorityQueue<Pair<Long, JSONObject>>(compareBy { it.first })
        val conn = java.net.URL(xc.api(if (series) "get_series" else "get_vod_streams")).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 120000
        conn.inputStream.use { ins ->
            val r = android.util.JsonReader(ins.reader())
            r.beginArray()
            while (r.hasNext()) {
                var id = ""; var name = ""; var poster = ""; var ext = ""; var year = ""; var added = 0L; var cat = ""; var adult = false
                r.beginObject()
                while (r.hasNext()) {
                    val k = r.nextName()
                    if (r.peek() == android.util.JsonToken.NULL) { r.skipValue(); continue }
                    if (r.peek() == android.util.JsonToken.BEGIN_ARRAY || r.peek() == android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                    val v = r.nextString()
                    when (k) {
                        "stream_id", "series_id" -> id = v
                        "name" -> name = v
                        "stream_icon", "cover" -> poster = v
                        "container_extension" -> ext = v
                        "year" -> year = v
                        "added", "last_modified" -> added = maxOf(added, v.toLongOrNull() ?: 0L)
                        "category_id" -> cat = v
                        "is_adult" -> adult = v == "1"
                    }
                }
                r.endObject()
                if (adult || cat !in allowed || isAdultGroup(name) || poster.isBlank() || id.isBlank() || name.any { it.code > 0xFF }) continue
                top.add(added to JSONObject().put("id", id).put("title", name).put("poster", poster).put("ext", ext).put("year", year).put("series", series))
                if (top.size > n) top.poll()
            }
        }
        val out = JSONArray()
        top.sortedByDescending { it.first }.forEach { out.put(it.second) }
        return out
    }

    fun episodes(xc: Xc, seriesId: String): List<Episode> = cached("e$seriesId") {
        val root = JSONObject(httpGet(xc.api("get_series_info", "&series_id=$seriesId"), 30000))
        val eps = root.opt("episodes")
        val all = when (eps) {
            is JSONObject -> eps.keys().asSequence().flatMap { k -> objs(eps.optJSONArray(k) ?: JSONArray()).asSequence() }.toList()
            is JSONArray -> objs(eps)
            else -> emptyList()
        }
        all.map { o ->
            val info = o.optJSONObject("info") ?: JSONObject()
            Episode(
                id = o.optString("id"), season = o.optInt("season", 1), num = o.optInt("episode_num"),
                title = o.str("title"), ext = o.str("container_extension"), plot = info.str("plot"),
                still = info.str("movie_image"), duration = info.str("duration").removePrefix("00:"),
            )
        }.sortedWith(compareBy({ it.season }, { it.num }))
    }
}

/** Where each profile stopped in a movie or episode. */
data class Resume(val key: String, val title: String, val subtitle: String, val poster: String, val url: String, val pos: Long, val dur: Long, val at: Long) {
    val progress get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
}

object ResumeStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vod", Context.MODE_PRIVATE)

    fun all(ctx: Context, profile: String): List<Resume> {
        val a = runCatching { JSONArray(prefs(ctx).getString("resume:$profile", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Resume(it.optString("key"), it.optString("title"), it.optString("subtitle"), it.optString("poster"), it.optString("url"), it.optLong("pos"), it.optLong("dur"), it.optLong("at"))
        }.sortedByDescending { it.at }
    }

    fun get(ctx: Context, profile: String, key: String) = all(ctx, profile).firstOrNull { it.key == key }

    /** Saves progress; a title watched past 95% (or under a minute in) is dropped from Continue watching. */
    fun save(ctx: Context, profile: String, r: Resume) {
        val keep = r.pos > 60_000 && (r.dur <= 0 || r.pos < r.dur * 0.95)
        val list = (if (keep) listOf(r) else emptyList()) + all(ctx, profile).filter { it.key != r.key }
        val a = JSONArray()
        list.take(30).forEach {
            a.put(JSONObject().put("key", it.key).put("title", it.title).put("subtitle", it.subtitle).put("poster", it.poster)
                .put("url", it.url).put("pos", it.pos).put("dur", it.dur).put("at", it.at))
        }
        prefs(ctx).edit().putString("resume:$profile", a.toString()).apply()
    }
}
