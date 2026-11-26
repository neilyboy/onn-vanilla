package tv.vanilla.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** What Live TV shares with the home screen (see tv.vanilla.live.GlanceProvider). */
data class GChan(val name: String, val number: String, val logo: String, val title: String, val progress: Float)
data class GTeam(val abbr: String, val name: String, val logo: String, val color: String, val score: String)
data class GGame(val id: String, val league: String, val state: String, val detail: String, val start: Long, val network: String, val away: GTeam, val home: GTeam, val fav: Boolean = false)
data class GResume(val key: String, val title: String, val subtitle: String, val poster: String, val progress: Float)
data class GVod(val id: String, val title: String, val poster: String, val series: Boolean)
data class GCam(val name: String, val logo: String)
data class LiveGlance(val profile: String, val onNow: List<GChan>, val games: List<GGame>, val resume: List<GResume>, val newVod: List<GVod>, val cameras: List<GCam> = emptyList())

private const val LIVE_PKG = "tv.vanilla.live"

private fun JSONArray?.objs() = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun team(o: JSONObject?) = GTeam(o?.optString("abbr") ?: "", o?.optString("name") ?: "", o?.optString("logo") ?: "", o?.optString("color") ?: "", o?.optString("score") ?: "")

fun readGlance(ctx: Context): LiveGlance? = runCatching {
    val text = ctx.contentResolver.query(Uri.parse("content://$LIVE_PKG.glance/snapshot"), null, null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: return null
    val r = JSONObject(text)
    LiveGlance(
        r.optString("profile"),
        r.optJSONArray("onNow").objs().map { GChan(it.optString("name"), it.optString("number"), it.optString("logo"), it.optString("title"), it.optDouble("progress", 0.0).toFloat()) },
        r.optJSONArray("games").objs().map {
            GGame(it.optString("id"), it.optString("league"), it.optString("state"), it.optString("detail"), it.optLong("start"), it.optString("network"), team(it.optJSONObject("away")), team(it.optJSONObject("home")), it.optBoolean("fav"))
        },
        r.optJSONArray("resume").objs().map { GResume(it.optString("key"), it.optString("title"), it.optString("subtitle"), it.optString("poster"), it.optDouble("progress", 0.0).toFloat()) },
        r.optJSONArray("newVod").objs().map { GVod(it.optString("id"), it.optString("title"), it.optString("poster"), it.optBoolean("series")) },
        r.optJSONArray("cameras").objs().map { GCam(it.optString("name"), it.optString("logo")) },
    )
}.getOrNull()

/** Opens Live TV, optionally tuning a channel ("tune"), a game ("tune" = "game:<abbr>") or resuming a title ("vod_key"). */
fun openLive(ctx: Context, extra: String? = null, value: String? = null) {
    val i = ctx.packageManager.getLaunchIntentForPackage(LIVE_PKG)
        ?: ctx.packageManager.getLeanbackLaunchIntentForPackage(LIVE_PKG) ?: return
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // Bring Live TV's main screen forward (closing a player left open) so it receives the deep link.
    if (extra != null && value != null) i.putExtra(extra, value).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    runCatching { ctx.startActivity(i) }
}
