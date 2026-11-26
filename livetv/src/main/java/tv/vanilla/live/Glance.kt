package tv.vanilla.live

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A small snapshot of what's on (favorites, your teams' games, continue watching) for the home screen widgets. */
object Glance {
    fun file(ctx: android.content.Context) = File(ctx.filesDir, "glance.json")

    private fun team(t: Team) = JSONObject().put("abbr", t.abbr).put("name", t.shortName.ifBlank { t.name }).put("logo", t.logo).put("color", t.color).put("score", t.score)

    fun write(
        ctx: android.content.Context, profile: String, channels: List<Channel>, epg: Map<String, List<Programme>>,
        favorites: Set<String>, recents: List<String>, games: List<Game>, favTeams: Set<String>, source: String = "",
    ) = runCatching {
        val now = System.currentTimeMillis()
        val byName = channels.associateBy { it.name }
        val airing = { c: Channel -> epg[c.tvgId]?.firstOrNull { now in it.start until it.stop }?.title?.let { !PLACEHOLDER.containsMatchIn(it) } == true }
        val picks = when {
            source == "fav" -> favorites.sorted().mapNotNull { byName[it] }
            source.startsWith("group:") -> channels.filter { it.group == source.removePrefix("group:") && airing(it) }
            else -> (recents + favorites.sorted()).distinct().mapNotNull { byName[it] }
        }.take(12)
        val onNow = JSONArray()
        picks.forEach { c ->
            val p = epg[c.tvgId]?.firstOrNull { now in it.start until it.stop }
            onNow.put(JSONObject().put("name", c.name).put("number", c.number).put("logo", c.logo).put("title", p?.title ?: "")
                .put("progress", p?.let { ((now - it.start).toDouble() / (it.stop - it.start)).coerceIn(0.0, 1.0) } ?: 0.0))
        }
        val mine = games.filter { g -> g.away.abbr in favTeams || g.home.abbr in favTeams }
        val shown = (mine.ifEmpty { games.filter { it.state == "in" } }).sortedBy { listOf("in", "pre", "post").indexOf(it.state) }.take(10)
        val ga = JSONArray()
        shown.forEach { g ->
            ga.put(JSONObject().put("id", g.id).put("league", g.league).put("state", g.state).put("detail", g.detail).put("start", g.start)
                .put("network", g.networks.firstOrNull() ?: "").put("fav", g.away.abbr in favTeams || g.home.abbr in favTeams).put("away", team(g.away)).put("home", team(g.home)))
        }
        val ra = JSONArray()
        ResumeStore.all(ctx, profile).take(10).forEach { r ->
            ra.put(JSONObject().put("key", r.key).put("title", r.title).put("subtitle", r.subtitle).put("poster", r.poster).put("progress", r.progress.toDouble()))
        }
        val cams = JSONArray()
        channels.filter { it.group.contains("camera", ignoreCase = true) }.take(12).forEach { cams.put(JSONObject().put("name", it.name).put("logo", it.logo)) }
        val old = runCatching { JSONObject(file(ctx).readText()) }.getOrNull()
        val root = JSONObject().put("profile", profile).put("at", now).put("onNow", onNow).put("games", ga).put("resume", ra).put("cameras", cams)
            .put("newVod", old?.optJSONArray("newVod") ?: JSONArray()).put("newVodAt3", old?.optLong("newVodAt3") ?: 0L)
            .put("teams", old?.optJSONArray("teams") ?: JSONArray()).put("teamsAt", old?.optLong("teamsAt") ?: 0L)
        val tmp = File(ctx.filesDir, "glance.json.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file(ctx))
    }

    private val PLACEHOLDER = Regex("""off ?air|no program|no info|no event|scoreboard final""", RegexOption.IGNORE_CASE)

    fun teamsAge(ctx: android.content.Context) =
        System.currentTimeMillis() - (runCatching { JSONObject(file(ctx).readText()).optLong("teamsAt") }.getOrNull() ?: 0L)

    /** Recent results and upcoming games for each favorite team, for the home screen Sports widget. */
    fun writeTeams(ctx: android.content.Context, teams: JSONArray) = runCatching {
        val root = runCatching { JSONObject(file(ctx).readText()) }.getOrDefault(JSONObject())
        root.put("teams", teams).put("teamsAt", System.currentTimeMillis())
        val tmp = File(ctx.filesDir, "glance.json.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file(ctx))
    }

    fun playingFile(ctx: android.content.Context) = File(ctx.filesDir, "playing.json")

    /** What this box is watching right now, for the Rooms widget on the home screen. */
    fun writePlaying(ctx: android.content.Context, c: Channel?) = runCatching {
        if (c == null) { playingFile(ctx).delete(); return@runCatching }
        playingFile(ctx).writeText(JSONObject().put("name", c.name).put("number", c.number).put("logo", c.logo).put("at", System.currentTimeMillis()).toString())
    }

    fun newVodAge(ctx: android.content.Context) =
        System.currentTimeMillis() - (runCatching { JSONObject(file(ctx).readText()).optLong("newVodAt3") }.getOrNull() ?: 0L)

    fun writeNewVod(ctx: android.content.Context, items: JSONArray) = runCatching {
        val root = runCatching { JSONObject(file(ctx).readText()) }.getOrDefault(JSONObject())
        root.put("newVod", items).put("newVodAt3", System.currentTimeMillis())
        val tmp = File(ctx.filesDir, "glance.json.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file(ctx))
    }
}

/** Read-only: `content://tv.vanilla.live.glance/snapshot` returns one row with a `json` column. */
class GlanceProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = context?.let { if (uri.lastPathSegment == "playing") Glance.playingFile(it) else Glance.file(it) }
        val text = f?.let { runCatching { it.readText() }.getOrNull() } ?: "{}"
        return MatrixCursor(arrayOf("json")).apply { addRow(arrayOf(text)) }
    }
    override fun getType(uri: Uri) = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
