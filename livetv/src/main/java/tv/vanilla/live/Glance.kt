package tv.vanilla.live

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.os.Bundle
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
        // Rooms mini guide: favorites, then each group's channels with something on now.
        val guide = JSONArray()
        fun chan(c: Channel): JSONObject {
            val p = epg[c.tvgId]?.firstOrNull { now in it.start until it.stop }
            return JSONObject().put("name", c.name).put("number", c.number).put("logo", c.logo).put("title", p?.title ?: "")
                .put("progress", p?.let { ((now - it.start).toDouble() / (it.stop - it.start)).coerceIn(0.0, 1.0) } ?: 0.0)
        }
        favorites.sorted().mapNotNull { byName[it] }.take(40).takeIf { it.isNotEmpty() }?.let { f ->
            guide.put(JSONObject().put("name", "Favorites").put("chans", JSONArray().apply { f.forEach { put(chan(it)) } }))
        }
        channels.filter { !it.group.contains("camera", true) }.groupBy { it.group }.entries.take(30).forEach { (g, cs) ->
            val on = cs.filter(airing).take(40)
            if (on.isNotEmpty()) guide.put(JSONObject().put("name", g).put("chans", JSONArray().apply { on.forEach { put(chan(it)) } }))
        }
        val old = runCatching { JSONObject(file(ctx).readText()) }.getOrNull()
        val root = JSONObject().put("profile", profile).put("at", now).put("onNow", onNow).put("games", ga).put("resume", ra).put("cameras", cams).put("guide", guide)
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

/** Live TV's look and favorites, for copying a Home between TVs (see the launcher's homeSnapshot). */
object SharedPrefs {
    @Volatile var changed = false
    private fun files(ctx: android.content.Context) = listOf("look" to { _: String -> true }, "live" to { k: String -> k.startsWith("fav:") })

    fun export(ctx: android.content.Context): JSONObject = JSONObject().apply {
        files(ctx).forEach { (name, keep) ->
            val o = JSONObject()
            ctx.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).all.forEach { (k, v) ->
                if (!keep(k) || v == null) return@forEach
                val t = when (v) { is String -> "s"; is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is Set<*> -> "set"; else -> return@forEach }
                o.put(k, JSONObject().put("t", t).put("v", if (v is Set<*>) JSONArray(v.map { it.toString() }) else v))
            }
            put(name, o)
        }
    }

    fun import(ctx: android.content.Context, j: JSONObject) {
        files(ctx).forEach { (name, keep) ->
            val src = j.optJSONObject(name) ?: return@forEach
            val p = ctx.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
            val e = p.edit()
            p.all.keys.filter(keep).forEach { e.remove(it) }
            src.keys().forEach { k ->
                val o = src.optJSONObject(k) ?: return@forEach
                if (!keep(k)) return@forEach
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
        }
        changed = true
    }
}

/**
 * `content://tv.vanilla.live.glance/snapshot` (and `/playing`, `/prefs`) return one row with a `json` column.
 * Vanilla Home can call `apply` with exported prefs to copy another TV's look and favorites.
 */
class GlanceProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        if (uri.lastPathSegment == "prefs") return MatrixCursor(arrayOf("json")).apply { addRow(arrayOf(context?.let { SharedPrefs.export(it).toString() } ?: "{}")) }
        val f = context?.let { if (uri.lastPathSegment == "playing") Glance.playingFile(it) else Glance.file(it) }
        val text = f?.let { runCatching { it.readText() }.getOrNull() } ?: "{}"
        return MatrixCursor(arrayOf("json")).apply { addRow(arrayOf(text)) }
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (method != "apply" || callingPackage != "tv.vanilla.launcher" || arg == null) return null
        runCatching { SharedPrefs.import(ctx, JSONObject(arg)) }
        return Bundle()
    }
    override fun getType(uri: Uri) = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
