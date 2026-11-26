package tv.vanilla.launcher

import org.json.JSONArray
import java.net.URLEncoder

data class Station(val name: String, val url: String, val logo: String = "", val tags: String = "")

/** Genre chips in the Radio widget; the value is a Radio Browser tag ("" = most popular). */
val RADIO_GENRES = linkedMapOf(
    "" to "Popular", "news" to "News", "pop" to "Hits", "rock" to "Rock", "country" to "Country", "jazz" to "Jazz",
    "classical" to "Classical", "hip-hop" to "Hip-hop", "talk" to "Talk", "christmas" to "Christmas",
)

/** Free stations from the Radio Browser directory (radio-browser.info), most listened first. */
fun fetchStations(tag: String): List<Station> = runCatching {
    val q = "https://de1.api.radio-browser.info/json/stations/search?limit=48&order=clickcount&reverse=true&hidebroken=true&countrycode=US" +
        if (tag.isNotBlank()) "&tag=" + URLEncoder.encode(tag, "UTF-8") else ""
    val a = JSONArray(httpGet(q, 12000))
    (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        .map { Station(it.optString("name").trim(), it.optString("url_resolved").ifBlank { it.optString("url") }, it.optString("favicon"), it.optString("tags").split(',').take(3).joinToString(" · ")) }
        .filter { it.name.isNotBlank() && it.url.startsWith("http") }
        .distinctBy { it.name.lowercase() }
}.getOrDefault(emptyList())

/** "Name|https://stream" lines from the setup app. */
fun stationsFromLines(lines: List<String>) = lines.mapNotNull { l ->
    val name = l.substringBefore('|').trim(); val url = l.substringAfter('|', "").trim()
    if (url.startsWith("http")) Station(name.ifBlank { url }, url, tags = "Your station") else null
}
