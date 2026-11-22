package tv.vanilla.live

import android.content.Context
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

data class Channel(
    val index: Int,
    val name: String,
    val number: String,
    val tvgId: String,
    val logo: String,
    val group: String,
    val url: String,
)

data class Programme(val start: Long, val stop: Long, val title: String, val desc: String)

data class Team(val abbr: String, val name: String, val shortName: String, val logo: String, val color: String, val score: String)

data class Game(
    val league: String,
    val id: String,
    val name: String,
    val state: String, // pre | in | post
    val detail: String,
    val start: Long,
    val away: Team,
    val home: Team,
    val networks: List<String>,
)

/** Who's watching: preferred groups come first, keywords become "on now" rows from the guide. */
data class Profile(
    val name: String,
    val sports: Boolean,
    val groups: List<String>,
    val keywords: List<String>,
    val color: String = "",
    /** Hides adult channels everywhere, including the guide and channel surfing. */
    val kids: Boolean = false,
    /** Digits required to switch to this profile; blank means no lock. */
    val pin: String = "",
)

val DEFAULT_PROFILES = listOf(
    Profile("Everyone", true, emptyList(), emptyList()),
    Profile("Sports", true, listOf("Sports", "NFL", "College Football", "NBA", "NHL", "MLB", "Soccer", "Fights & PPV"), emptyList()),
    Profile("Movies", false, listOf("Movies", "24/7 Channels"), listOf("Western", "Comedy", "Action", "Thriller")),
    Profile("TV & Game Shows", false, listOf("Entertainment", "Free TV"), listOf("Game Show", "Jeopardy", "Wheel of Fortune", "Family Feud", "Price Is Right")),
    Profile("News", false, listOf("News", "Locals"), emptyList()),
    Profile("Kids", false, listOf("Kids"), emptyList(), kids = true),
)

private val ADULT = Regex("""adult|xxx|18\+|porn""", RegexOption.IGNORE_CASE)
fun isAdultGroup(g: String) = ADULT.containsMatchIn(g)

/** Channels whose programme airing now mentions [keyword], one per guide id. */
fun onNowMatching(chans: List<Channel>, epg: Map<String, List<Programme>>, keyword: String, limit: Int = 40): List<Channel> {
    val now = System.currentTimeMillis()
    val seen = HashSet<String>()
    val out = ArrayList<Channel>()
    for (ch in chans) {
        if (ch.tvgId.isBlank() || isAdultGroup(ch.group) || ch.tvgId in seen) continue
        val p = epg[ch.tvgId]?.firstOrNull { it.start <= now && it.stop > now } ?: continue
        if (!containsToken(p.title + " " + p.desc, keyword)) continue
        seen += ch.tvgId
        out += ch
        if (out.size >= limit) break
    }
    return out
}

data class LiveConfig(
    val m3uUrl: String,
    val epgUrl: String,
    val leagues: List<String>,
    val favoriteTeams: Set<String>,
    val sportsGroupKeywords: List<String>,
    val networkAliases: Map<String, List<String>>,
    val accent: String,
    val profiles: List<Profile> = DEFAULT_PROFILES,
    val askProfile: Boolean = false,
    val xc: Xc? = null,
)

val LEAGUE_PATHS = mapOf(
    "nfl" to "football/nfl",
    "college-football" to "football/college-football",
    "nba" to "basketball/nba",
    "mens-college-basketball" to "basketball/mens-college-basketball",
    "wnba" to "basketball/wnba",
    "nhl" to "hockey/nhl",
    "mlb" to "baseball/mlb",
    "mls" to "soccer/usa.1",
    "epl" to "soccer/eng.1",
)

val LEAGUE_LABELS = mapOf(
    "nfl" to "NFL", "college-football" to "NCAAF", "nba" to "NBA", "mens-college-basketball" to "NCAAM",
    "wnba" to "WNBA", "nhl" to "NHL", "mlb" to "MLB", "mls" to "MLS", "epl" to "EPL",
)

fun httpOpen(url: String, timeoutMs: Int = 15000): Pair<HttpURLConnection, InputStream> {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = timeoutMs
    conn.readTimeout = timeoutMs * 4
    conn.setRequestProperty("User-Agent", "VanillaLiveTV/1.0")
    conn.setRequestProperty("Accept-Encoding", "gzip")
    if (conn.responseCode !in 200..299) {
        conn.disconnect()
        error("HTTP ${conn.responseCode} for $url")
    }
    val raw = conn.inputStream
    val stream = if (conn.contentEncoding == "gzip" || url.endsWith(".gz")) GZIPInputStream(raw) else raw
    return conn to stream.buffered()
}

fun httpGet(url: String, timeoutMs: Int = 10000): String {
    val (conn, s) = httpOpen(url, timeoutMs)
    try { return s.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
}

private val attrRe = Regex("""([\w-]+)="([^"]*)"""")

fun parseM3u(text: String): List<Channel> {
    val out = ArrayList<Channel>()
    var pending: String? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("#EXTINF") -> pending = line
            line.isEmpty() || line.startsWith("#") -> Unit
            pending != null -> {
                val info = pending
                val header = if (info.contains("\",")) info.substringBeforeLast("\",") + "\"" else info.substringBefore(",")
                val attrs = attrRe.findAll(header).associate { it.groupValues[1] to it.groupValues[2] }
                val title = info.substringAfterLast("\",", info.substringAfter(",")).trim()
                out += Channel(
                    index = out.size,
                    name = attrs["tvg-name"]?.takeIf { it.isNotBlank() && title.isBlank() } ?: title,
                    number = attrs["tvg-chno"] ?: attrs["channel-number"] ?: "",
                    tvgId = attrs["tvg-id"] ?: "",
                    logo = attrs["tvg-logo"] ?: "",
                    group = attrs["group-title"] ?: "Channels",
                    url = line,
                )
                pending = null
            }
        }
    }
    return out
}

private val xmltvFmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")

private fun parseXmltvTime(s: String): Long = runCatching {
    val t = s.trim()
    val norm = if (t.length > 14 && t[14] != ' ') t.substring(0, 14) + " " + t.substring(14) else if (t.length == 14) "$t +0000" else t
    ZonedDateTime.parse(norm, xmltvFmt).toInstant().toEpochMilli()
}.getOrDefault(0L)

/** Streams XMLTV, keeping only programmes overlapping [now - 1h, now + windowHours] for known channel ids. */
fun parseEpg(input: InputStream, wanted: Set<String>, windowHours: Int = 12): Map<String, List<Programme>> {
    val now = System.currentTimeMillis()
    val from = now - 3_600_000L
    val to = now + windowHours * 3_600_000L
    val result = HashMap<String, MutableList<Programme>>()
    val p = Xml.newPullParser()
    p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    p.setInput(input, null)
    var ev = p.eventType
    var chan: String? = null
    var start = 0L
    var stop = 0L
    var title = ""
    var desc = ""
    var keep = false
    while (ev != XmlPullParser.END_DOCUMENT) {
        if (ev == XmlPullParser.START_TAG) {
            when (p.name) {
                "programme" -> {
                    chan = p.getAttributeValue(null, "channel")
                    start = parseXmltvTime(p.getAttributeValue(null, "start") ?: "")
                    stop = parseXmltvTime(p.getAttributeValue(null, "stop") ?: "")
                    keep = chan != null && (wanted.isEmpty() || chan in wanted) && stop > from && start < to
                    title = ""; desc = ""
                }
                "title" -> if (keep && title.isEmpty()) title = p.nextText()
                "desc" -> if (keep && desc.isEmpty()) desc = p.nextText()
            }
        } else if (ev == XmlPullParser.END_TAG && p.name == "programme") {
            if (keep) result.getOrPut(chan!!) { mutableListOf() }.add(Programme(start, stop, title, desc))
            keep = false
        }
        ev = p.next()
    }
    result.values.forEach { it.sortBy { pr -> pr.start } }
    return result
}

fun fetchGames(leagues: List<String>): List<Game> = leagues.flatMap { league ->
    val path = LEAGUE_PATHS[league] ?: return@flatMap emptyList()
    runCatching {
        val root = JSONObject(httpGet("https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard"))
        val events = root.optJSONArray("events") ?: return@runCatching emptyList()
        (0 until events.length()).mapNotNull { i ->
            val e = events.getJSONObject(i)
            val comp = e.getJSONArray("competitions").getJSONObject(0)
            val competitors = comp.getJSONArray("competitors")
            var home: Team? = null
            var away: Team? = null
            for (j in 0 until competitors.length()) {
                val c = competitors.getJSONObject(j)
                val t = c.getJSONObject("team")
                val team = Team(
                    abbr = t.optString("abbreviation"),
                    name = t.optString("displayName"),
                    shortName = t.optString("shortDisplayName", t.optString("name")),
                    logo = t.optString("logo"),
                    color = t.optString("color"),
                    score = c.optString("score"),
                )
                if (c.optString("homeAway") == "home") home = team else away = team
            }
            val nets = buildList {
                comp.optJSONArray("broadcasts")?.let { b ->
                    for (k in 0 until b.length()) b.getJSONObject(k).optJSONArray("names")?.let { n ->
                        for (m in 0 until n.length()) add(n.getString(m))
                    }
                }
            }.distinct()
            val status = e.getJSONObject("status").getJSONObject("type")
            Game(
                league = league,
                id = e.optString("id"),
                name = e.optString("shortName", e.optString("name")),
                state = status.optString("state"),
                detail = status.optString("shortDetail"),
                start = runCatching { Instant.parse(e.optString("date").let { if (it.length == 17) it.dropLast(1) + ":00Z" else it }).toEpochMilli() }.getOrDefault(0L),
                away = away ?: return@mapNotNull null,
                home = home ?: return@mapNotNull null,
                networks = nets,
            )
        }
    }.getOrDefault(emptyList())
}

private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

private fun containsToken(haystack: String, needle: String): Boolean {
    val h = " ${norm(haystack)} "
    val n = norm(needle)
    if (n.isEmpty()) return false
    return h.contains(" $n ")
}

/**
 * Best channel for a game. Network channels (ESPN, FOX…) come first because they are merged across providers
 * with backups; single-provider event channels whose guide names both teams are the fallback.
 */
@Volatile private var normNames: Pair<List<Channel>, Array<String>>? = null

private fun normalizedNames(channels: List<Channel>): Array<String> {
    normNames?.let { (list, names) -> if (list === channels) return names }
    return Array(channels.size) { norm(channels[it].name) }.also { normNames = channels to it }
}

/** Heavy on big lineups (tens of thousands of channels): call off the main thread. */
fun findChannelForGame(game: Game, channels: List<Channel>, epg: Map<String, List<Programme>>, aliases: Map<String, List<String>>): Channel? {
    val now = System.currentTimeMillis()
    val names = normalizedNames(channels)
    val teamsA = listOf(game.away.shortName, game.away.name).map(::norm).filter { it.isNotBlank() }
    val teamsH = listOf(game.home.shortName, game.home.name).map(::norm).filter { it.isNotBlank() }
    fun airs(ch: Channel) = epg[ch.tvgId]?.any { pr ->
        pr.start <= now + 30 * 60_000 && pr.stop > now && run {
            val h = " ${norm(pr.title + " " + pr.desc)} "
            teamsA.any { h.contains(" $it ") } && teamsH.any { h.contains(" $it ") }
        }
    } == true
    fun tok(i: Int, n: String) = n.isNotEmpty() && " ${names[i]} ".contains(" $n ")
    // Streaming-only networks ("ESPN+", "Peacock") would otherwise match the linear channel of a similar name.
    val linear = game.networks.filter { !it.trim().endsWith("+") }.flatMap { listOf(it) + (aliases[it] ?: emptyList()) }.map(::norm).toSet()
    channels.indices.firstOrNull { names[it] in linear && airs(channels[it]) }?.let { return channels[it] }
    for (n in linear) channels.indices.firstOrNull { names[it] == n }?.let { return channels[it] }
    channels.firstOrNull { airs(it) }?.let { return it }
    val junk = Regex("""\b(4k|uhd|backup)\b""", RegexOption.IGNORE_CASE)
    for (net in game.networks) {
        for (n in (listOf(net) + (aliases[net] ?: emptyList())).map(::norm)) {
            channels.indices.firstOrNull { names[it] == n }?.let { return channels[it] }
            channels.indices.firstOrNull { tok(it, n) && !junk.containsMatchIn(channels[it].name) }?.let { return channels[it] }
            channels.indices.firstOrNull { tok(it, n) }?.let { return channels[it] }
        }
    }
    return null
}

fun nowNext(list: List<Programme>?): Pair<Programme?, Programme?> {
    if (list.isNullOrEmpty()) return null to null
    val now = System.currentTimeMillis()
    val i = list.indexOfFirst { it.start <= now && it.stop > now }
    if (i < 0) return null to list.firstOrNull { it.start > now }
    return list[i] to list.getOrNull(i + 1)
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("live", Context.MODE_PRIVATE)
    var configUrl: String
        get() = prefs.getString("config_url", "") ?: ""
        set(v) = prefs.edit().putString("config_url", v.trim()).apply()
    var m3uOverride: String
        get() = prefs.getString("m3u_url", "") ?: ""
        set(v) = prefs.edit().putString("m3u_url", v.trim()).apply()
    var epgOverride: String
        get() = prefs.getString("epg_url", "") ?: ""
        set(v) = prefs.edit().putString("epg_url", v.trim()).apply()
    var lastChannel: String
        get() = prefs.getString("last_channel", "") ?: ""
        set(v) = prefs.edit().putString("last_channel", v).apply()
    var profile: String
        get() = prefs.getString("profile", "") ?: ""
        set(v) = prefs.edit().putString("profile", v).apply()
    var favorites: Set<String>
        get() = prefs.getStringSet("fav_channels", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("fav_channels", v).apply()

    /** Per-profile favorites; profiles that never saved any start from the shared pre-profile list. */
    fun favoritesFor(p: String): Set<String> =
        if (prefs.contains("fav:$p")) prefs.getStringSet("fav:$p", emptySet()) ?: emptySet() else favorites
    fun setFavorites(p: String, v: Set<String>) = prefs.edit().putStringSet("fav:$p", v).apply()

    /** Score bar while watching: 0 off, 1 favorite teams, 2 all live games. */
    var ticker: Int
        get() = prefs.getInt("ticker", 1)
        set(v) = prefs.edit().putInt("ticker", v).apply()

    var reminders: Set<String>
        get() = prefs.getStringSet("reminders", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("reminders", v).apply()

    var resumeLast: Boolean
        get() = prefs.getBoolean("resume_last", false)
        set(v) = prefs.edit().putBoolean("resume_last", v).apply()

    fun recentsFor(p: String): List<String> = (prefs.getString("recent:$p", "") ?: "").split('\n').filter { it.isNotBlank() }
    fun pushRecent(p: String, name: String) {
        val l = (listOf(name) + recentsFor(p).filter { it != name }).take(15)
        prefs.edit().putString("recent:$p", l.joinToString("\n")).apply()
    }
}

fun parseLiveConfig(text: String?, settings: Settings): LiveConfig {
    val live = text?.let { runCatching { JSONObject(it).optJSONObject("livetv") }.getOrNull() } ?: JSONObject()
    fun list(key: String, def: List<String>) = live.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: def
    val aliases = live.optJSONObject("network_aliases")?.let { o ->
        o.keys().asSequence().associateWith { k -> o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList() }
    } ?: emptyMap()
    return LiveConfig(
        m3uUrl = settings.m3uOverride.ifBlank { live.optString("m3u_url", "") },
        epgUrl = settings.epgOverride.ifBlank { live.optString("epg_url", "") },
        leagues = list("leagues", listOf("nfl", "college-football", "nba", "nhl")),
        favoriteTeams = list("favorite_teams", emptyList()).map { it.uppercase() }.toSet(),
        sportsGroupKeywords = list("sports_group_keywords", listOf("sport", "espn", "nfl", "nba", "nhl", "mlb", "ncaa", "football", "fox sports", "dazn")),
        networkAliases = aliases,
        accent = live.optString("accent", "#E5484D"),
        askProfile = live.optBoolean("ask_profile", false),
        xc = live.optJSONObject("xc")?.let { o ->
            val base = o.optString("url").ifBlank { Regex("^https?://[^/]+").find(settings.m3uOverride.ifBlank { live.optString("m3u_url", "") })?.value ?: "" }
            val u = o.optString("username"); val p = o.optString("password")
            if (base.isBlank() || u.isBlank() || p.isBlank()) null else Xc(base.trimEnd('/'), u, p)
        },
        profiles = live.optJSONArray("profiles")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.map { o ->
                fun arr(k: String) = o.optJSONArray(k)?.let { x -> (0 until x.length()).map { x.getString(it) } } ?: emptyList()
                Profile(
                    o.optString("name", "Profile"), o.optBoolean("sports", false), arr("groups"), arr("keywords"),
                    o.optString("color", ""), o.optBoolean("kids", false), o.optString("pin", "").filter { it.isDigit() },
                )
            }.takeIf { it.isNotEmpty() }
        } ?: DEFAULT_PROFILES,
    )
}

/** Process-wide cache so the player can page channels without reloading. */
object Repo {
    @Volatile var channels: List<Channel> = emptyList()
    @Volatile var epg: Map<String, List<Programme>> = emptyMap()
    @Volatile var games: List<Game> = emptyList()
    @Volatile var leagues: List<String> = emptyList()
    @Volatile var favTeams: Set<String> = emptySet()
    @Volatile var aliases: Map<String, List<String>> = emptyMap()
    /** Games already announced this session, so Home and the player don't both prompt. */
    val notified: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    fun cacheDir(ctx: Context) = File(ctx.filesDir, "cache").apply { mkdirs() }

    fun loadPlaylist(ctx: Context, url: String): List<Channel> {
        val f = File(cacheDir(ctx), "playlist.m3u")
        val text = runCatching { httpGet(url, 20000).also { f.writeText(it) } }.getOrElse { if (f.exists()) f.readText() else throw it }
        return parseM3u(text).also { channels = it }
    }

    fun loadEpg(url: String, chans: List<Channel>) {
        val wanted = chans.map { it.tvgId }.filter { it.isNotBlank() }.toSet()
        runCatching {
            val (conn, s) = httpOpen(url, 20000)
            try { epg = parseEpg(s, wanted) } finally { conn.disconnect() }
        }
    }

    fun loadCachedPlaylist(ctx: Context) {
        if (channels.isNotEmpty()) return
        val f = File(cacheDir(ctx), "playlist.m3u")
        if (f.exists()) channels = parseM3u(f.readText())
    }
}

data class SearchResults(val channels: List<Channel> = emptyList(), val shows: List<Pair<Channel, Programme>> = emptyList())

/** Channel names, then guide titles airing in the next 12 hours. */
fun search(chans: List<Channel>, epg: Map<String, List<Programme>>, q: String, adult: Boolean): SearchResults {
    val ok = chans.filter { adult || !isAdultGroup(it.group) }
    val channels = ok.filter { it.name.contains(q, true) }.sortedBy { if (it.name.startsWith(q, true)) 0 else 1 }.take(40)
    val now = System.currentTimeMillis()
    val horizon = now + 12 * 3_600_000L
    val seen = HashSet<String>()
    val shows = ArrayList<Pair<Channel, Programme>>()
    for (ch in ok) {
        if (ch.tvgId.isBlank() || !seen.add(ch.tvgId)) continue
        epg[ch.tvgId]?.forEach { p -> if (p.stop > now && p.start < horizon && p.title.contains(q, true)) shows += ch to p }
    }
    return SearchResults(channels, shows.sortedBy { it.second.start }.take(80))
}

/** Index of the channel whose guide number matches what was typed on the remote ("7" matches "7" and "7.0"). */
fun findByNumber(list: List<Channel>, typed: String): Int {
    val n = typed.toDoubleOrNull() ?: return -1
    return list.indexOfFirst { it.number == typed || it.number.toDoubleOrNull() == n }
}

fun gameKey(g: Game) = "${g.league}:${g.id}"

fun isFavGame(g: Game, favs: Set<String>) = g.home.abbr.uppercase() in favs || g.away.abbr.uppercase() in favs

/** A favorite-team or reminded game starting now (2 minutes before to 20 minutes after kickoff) that hasn't been announced. */
fun dueKickoff(games: List<Game>, favs: Set<String>, reminders: Set<String>): Game? {
    val now = System.currentTimeMillis()
    return games.firstOrNull { g ->
        val k = gameKey(g)
        k !in Repo.notified && g.state != "post" && (isFavGame(g, favs) || k in reminders) &&
            g.start in (now - 20 * 60_000)..(now + 2 * 60_000)
    }
}
