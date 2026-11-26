@file:OptIn(ExperimentalTvMaterial3Api::class)

package tv.vanilla.live

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    lateinit var settings: Settings
    val config = mutableStateOf<LiveConfig?>(null)
    val channels = mutableStateOf<List<Channel>>(emptyList())
    /** [channels] minus event slots with nothing on, unless Settings shows them. */
    val shownChannels = derivedStateOf { channels.value.let { l -> if (look.value.emptySlots) l else l.filterNot { isEmptySlot(it.name) } } }
    val epg = mutableStateOf<Map<String, List<Programme>>>(emptyMap())
    val games = mutableStateOf<List<Game>>(emptyList())
    val favorites = mutableStateOf<Set<String>>(emptySet())
    val status = mutableStateOf("Loading…")
    val look = mutableStateOf(Look())
    val profile = mutableStateOf(DEFAULT_PROFILES[0])

    val recents = mutableStateOf<List<String>>(emptyList())
    val showPicker = mutableStateOf(false)
    val pinFor = mutableStateOf<Profile?>(null)
    private var askedProfile = false
    private var resumed = false
    val typed = mutableStateOf("")
    val resumeLast = mutableStateOf(false)
    var searching = false
    val searchNow = mutableIntStateOf(0)
    /** A tab to open from the home screen, e.g. "sports". */
    val openTab = mutableStateOf<String?>(null)
    val reminders = mutableStateOf<Set<String>>(emptySet())
    val ticker = mutableIntStateOf(1)
    val kickoff = mutableStateOf<Pair<Game, Channel>?>(null)

    fun toggleReminder(g: Game) {
        if (g.state != "pre") { playGame(g); return }
        val k = gameKey(g)
        val s = reminders.value.toMutableSet()
        val on = s.add(k)
        if (!on) s.remove(k)
        reminders.value = s
        settings.reminders = s
        Toast.makeText(this, if (on) "We'll pop up when ${g.away.shortName} at ${g.home.shortName} starts" else "Reminder off", Toast.LENGTH_SHORT).show()
    }

    fun cycleTicker() { ticker.intValue = (ticker.intValue + 1) % 3; settings.ticker = ticker.intValue }

    private fun checkKickoff() {
        if (kickoff.value != null || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val c = config.value ?: return
        val g = dueKickoff(games.value, c.favoriteTeams, reminders.value) ?: return
        Repo.notified += gameKey(g)
        lifecycleScope.launch {
            val ch = withContext(Dispatchers.Default) { findChannelForGame(g, channels.value, epg.value, c.networkAliases) } ?: return@launch
            kickoff.value = g to ch
            window.decorView.postDelayed({ if (kickoff.value?.first == g) kickoff.value = null }, 30_000)
        }
    }
    private val commitTyped = Runnable {
        val i = findByNumber(channels.value, typed.value)
        if (i >= 0) play(channels.value[i]) else Toast.makeText(this, "No channel ${typed.value}", Toast.LENGTH_SHORT).show()
        typed.value = ""
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val k = event.keyCode
        kickoff.value?.let { (_, ch) ->
            val ok = k == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k == android.view.KeyEvent.KEYCODE_ENTER
            if (ok || k == android.view.KeyEvent.KEYCODE_BACK) {
                if (event.action == android.view.KeyEvent.ACTION_UP) { kickoff.value = null; if (ok) play(ch) }
                return true
            }
        }
        if (k == android.view.KeyEvent.KEYCODE_SEARCH && pinFor.value == null && !showPicker.value) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) searchNow.intValue++
            return true
        }
        if (!searching && pinFor.value == null && k in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                typed.value = (typed.value + (k - android.view.KeyEvent.KEYCODE_0)).takeLast(5)
                window.decorView.removeCallbacks(commitTyped)
                window.decorView.postDelayed(commitTyped, 1_800)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun toggleResumeLast() { resumeLast.value = !resumeLast.value; settings.resumeLast = resumeLast.value }

    fun cycleProfile() { showPicker.value = true }

    fun profileIndex() = (config.value?.profiles ?: DEFAULT_PROFILES).indexOfFirst { it.name == profile.value.name }

    fun requestProfile(p: Profile) {
        if (p.pin.isNotBlank() && p.name != profile.value.name) { pinFor.value = p; return }
        setProfile(p)
        showPicker.value = false
    }

    fun unlock(pin: String): Boolean {
        val p = pinFor.value ?: return false
        if (pin != p.pin) return false
        pinFor.value = null
        showPicker.value = false
        setProfile(p)
        return true
    }

    private fun setProfile(p: Profile) {
        profile.value = p
        settings.profile = p.name
        favorites.value = settings.favoritesFor(p.name)
        recents.value = settings.recentsFor(p.name)
        resume.value = ResumeStore.all(this, p.name)
    }

    val resume = mutableStateOf<List<Resume>>(emptyList())

    fun playVod(url: String, title: String, subtitle: String, poster: String, key: String, who: String = profile.value.name) {
        startActivity(
            Intent(this, VodPlayerActivity::class.java)
                .putExtra("url", url).putExtra("title", title).putExtra("subtitle", subtitle)
                .putExtra("poster", poster).putExtra("key", key).putExtra("profile", who)
        )
    }

    /** Refreshes the snapshot the home screen widgets read. */
    fun writeGlance() {
        val p = profile.value.name
        val ch = shownChannels.value; val g = epg.value; val f = favorites.value; val r = recents.value; val gm = games.value
        if (ch.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) { Glance.write(this@MainActivity, p, ch, g, f, r, gm, Repo.favTeams, look.value.onNow) }
        if (Glance.teamsAge(this) > 30 * 60_000L && Repo.favTeams.isNotEmpty()) lifecycleScope.launch(Dispatchers.IO) {
            Glance.writeTeams(this@MainActivity, fetchTeamSchedules(Repo.leagues, Repo.favTeams))
        }
    }

    override fun onStop() {
        super.onStop()
        writeGlance()
    }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized) recents.value = settings.recentsFor(profile.value.name)
        resume.value = ResumeStore.all(this, profile.value.name)
    }

    fun setLook(l: Look) {
        look.value = l
        Palette.apply(l)
        l.save(this)
    }
    /** "xtream" or "m3u" while the add-source form is open. */
    val sourceForm = mutableStateOf<String?>(null)

    fun addSource(src: Source) {
        settings.sources = settings.sources.filter { it.m3u() != src.m3u() } + src
        reload(true)
    }

    fun removeSource(src: Source) {
        settings.sources = settings.sources.filter { it.m3u() != src.m3u() }
        reload(true)
    }
    private var pendingTune: String? = null
    /** Wall-clock time a Rooms "start together" tune should begin playing (0 = right away). */
    private var pendingStartAt = 0L
    private var pendingVod: String? = null
    val openSeries = mutableStateOf<VodItem?>(null)

    /** "m:<id>" plays a movie, "s:<id>" opens a series; both come from the home screen's "New on" widget. */
    private fun openPendingVod() {
        val v = pendingVod ?: return
        val xc = config.value?.let { it.xc ?: it.vodSources.firstOrNull()?.second } ?: return
        pendingVod = null
        val item = runCatching { org.json.JSONObject(Glance.file(this).readText()).optJSONArray("newVod") }.getOrNull()?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { (if (it.optBoolean("series")) "s:" else "m:") + it.optString("id") == v }
        }
        val id = v.drop(2)
        val title = item?.optString("title") ?: ""
        val poster = item?.optString("poster") ?: ""
        if (v.startsWith("m:")) playVod(xc.movieUrl(id, item?.optString("ext") ?: ""), title, item?.optString("year") ?: "", poster, "movie:$id")
        else openSeries.value = VodItem(id, title, poster, "", "", item?.optString("year") ?: "", "", true, false)
    }

    private fun refreshNewVod(xc: Xc) {
        if (Glance.newVodAge(this) < 6 * 3600_000L) return
        lifecycleScope.launch(Dispatchers.IO) {
            val items = org.json.JSONArray()
            val movies = runCatching { Vod.newest(xc, false, 8) }.getOrNull()
            val shows = runCatching { Vod.newest(xc, true, 8) }.getOrNull()
            if (movies == null && shows == null) return@launch
            val m = movies ?: org.json.JSONArray(); val sh = shows ?: org.json.JSONArray()
            for (i in 0 until maxOf(m.length(), sh.length())) {
                m.optJSONObject(i)?.let { items.put(it) }; sh.optJSONObject(i)?.let { items.put(it) }
            }
            Glance.writeNewVod(this@MainActivity, items)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        favorites.value = settings.favoritesFor(settings.profile)
        resumeLast.value = settings.resumeLast
        reminders.value = settings.reminders
        ticker.intValue = settings.ticker
        val fresh = applyIntent(intent)
        setContent { LiveApp(this) }
        reload(fresh)
        lifecycleScope.launch {
            while (true) {
                config.value?.let { c -> games.value = withContext(Dispatchers.IO) { fetchGames(c.leagues) }; Repo.games = games.value }
                writeGlance()
                checkKickoff()
                delay(60_000)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (applyIntent(intent)) reload(true) else { pendingTune?.let { tryTune() }; openPendingVod() }
    }

    /**
     * Extras: config_url (shared launcher-config.json), m3u_url, epg_url, tune (channel name or "game:<team abbr>").
     * Returns true when the source settings changed.
     */
    private fun applyIntent(i: Intent?): Boolean {
        if (i == null) return false
        var changed = false
        i.getStringExtra("config_url")?.let { settings.configUrl = it; changed = true }
        i.getStringExtra("config_b64")?.let { b64 ->
            runCatching { File(filesDir, "config.json").writeText(String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))) }
            changed = true
        }
        i.getStringExtra("m3u_url")?.let { settings.m3uOverride = it; changed = true }
        i.getStringExtra("epg_url")?.let { settings.epgOverride = it; changed = true }
        pendingTune = i.getStringExtra("tune")
        pendingStartAt = i.getStringExtra("start_in")?.toLongOrNull()?.let { System.currentTimeMillis() + it } ?: 0L
        i.getStringExtra("tab")?.let { openTab.value = it }
        i.getStringExtra("vod_id")?.let { pendingVod = it }
        i.getStringExtra("vod_key")?.let { key ->
            ResumeStore.get(this, settings.profile, key)?.let { r -> playVod(r.url, r.title, r.subtitle, r.poster, r.key, settings.profile) }
        }
        return changed
    }

    /** [force] skips the cached lineup and guide, e.g. after new settings or a manual reload. */
    fun reload(force: Boolean = false) {
        lifecycleScope.launch {
            status.value = "Loading…"
            val cfgFile = File(filesDir, "config.json")
            val cfgText = withContext(Dispatchers.IO) {
                settings.configUrl.takeIf { it.isNotBlank() }?.let { url ->
                    runCatching { httpGet(url).also { cfgFile.writeText(it) } }.getOrNull()
                } ?: cfgFile.takeIf { it.exists() }?.readText()
            }
            val c = parseLiveConfig(cfgText, settings)
            config.value = c
            openPendingVod()
            (c.xc ?: c.vodSources.firstOrNull()?.second)?.let { refreshNewVod(it) }
            Repo.leagues = c.leagues; Repo.favTeams = c.favoriteTeams; Repo.aliases = c.networkAliases
            setProfile(c.profiles.firstOrNull { it.name == settings.profile } ?: c.profiles.first())
            if (!askedProfile && c.askProfile && c.profiles.size > 1) { askedProfile = true; showPicker.value = true }
            val l = Look.load(this@MainActivity) ?: Look.fromJson(runCatching { org.json.JSONObject(cfgText ?: "{}").optJSONObject("livetv")?.optJSONObject("look") }.getOrNull())
            look.value = l; Palette.apply(l)
            val feeds = c.feeds()
            val feedsKey = Repo.feedsKey(feeds)
            if (feeds.isEmpty()) {
                status.value = "setup"
                return@launch
            }
            // Show the last lineup and guide right away, then refresh from the server.
            val t0 = System.currentTimeMillis()
            fun mark(what: String) = android.util.Log.i("VanillaLive", "$what at ${System.currentTimeMillis() - t0} ms")
            val cached = withContext(Dispatchers.IO) { Repo.loadCachedPlaylist(this@MainActivity, feedsKey); Repo.channels }
            if (cached.isNotEmpty()) { channels.value = cached; status.value = ""; mark("cached playlist ${cached.size}") }
            if (cached.isNotEmpty() && Repo.epg.isEmpty()) {
                withContext(Dispatchers.IO) { Repo.loadCachedEpg(this@MainActivity) }
                epg.value = Repo.epg
                mark("cached guide ${Repo.epg.size}")
            }
            launch { games.value = withContext(Dispatchers.IO) { fetchGames(c.leagues) } }
            val stale = force || cached.isEmpty() || Repo.playlistAgeMs(this@MainActivity) > 3_600_000L
            val list = if (!stale) null else withContext(Dispatchers.IO) { runCatching { Repo.loadPlaylist(this@MainActivity, feeds) }.getOrNull() }
            if (list == null && cached.isEmpty()) {
                status.value = "Can't reach ${feeds.joinToString(" or ") { it.name }}"
                return@launch
            }
            mark("playlist ${list?.size}")
            if (list != null) {
                channels.value = list
                status.value = if (list.isEmpty()) "Playlist is empty — add channels in Dispatcharr" else ""
            }
            val chans = channels.value
            val epgUrls = feeds.map { it.epg }
            if (force || Repo.epg.isEmpty() || Repo.epgAgeMs(this@MainActivity) > 2 * 3_600_000L || (list != null && list.size != cached.size)) {
                withContext(Dispatchers.IO) { Repo.loadEpg(this@MainActivity, epgUrls, chans) }
                epg.value = Repo.epg
                writeGlance()
                mark("guide ${Repo.epg.size}")
            }
            if (!resumed && pendingTune == null && settings.resumeLast) {
                chans.firstOrNull { it.url == settings.lastChannel }?.let { play(it) }
            }
            resumed = true
            tryTune()
        }
    }

    private fun tryTune() {
        val q = pendingTune ?: return
        val list = channels.value
        if (list.isEmpty()) return
        pendingTune = null
        val startAt = pendingStartAt.also { pendingStartAt = 0L }
        lifecycleScope.launch {
            val ch = withContext(Dispatchers.Default) {
                if (q.startsWith("game:")) {
                    val abbr = q.removePrefix("game:").uppercase()
                    games.value.firstOrNull { it.home.abbr == abbr || it.away.abbr == abbr }
                        ?.let { findChannelForGame(it, list, epg.value, config.value?.networkAliases ?: emptyMap()) }
                } else {
                    list.firstOrNull { it.name.equals(q, true) } ?: list.firstOrNull { it.name.contains(q, true) }
                }
            }
            if (ch != null) play(ch, startAt) else Toast.makeText(this@MainActivity, "No channel for \"$q\"", Toast.LENGTH_SHORT).show()
        }
    }

    fun play(ch: Channel, startAt: Long = 0L) {
        settings.lastChannel = ch.url
        settings.pushRecent(profile.value.name, ch.name)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("index", ch.index)
                .putExtra("url", ch.url)
                .putExtra("profile", profile.value.name)
                .putExtra("kids", profile.value.kids)
                .putExtra("start_at", startAt)
        )
    }

    val mvPicker = mutableStateOf(false)
    /** Set by the visible tab: handles Back first (e.g. guide grid → group chips); false lets Back go to the tab bar. */
    var tabBack: (() -> Boolean)? = null

    /** Up to 4 channels side by side; each uses one provider stream. */
    fun startMultiview(picks: List<Pair<Channel, String>>) {
        if (picks.size < 2) { Toast.makeText(this, "Pick at least 2 channels", Toast.LENGTH_SHORT).show(); return }
        mvPicker.value = false
        startActivity(
            Intent(this, MultiviewActivity::class.java)
                .putExtra("indices", picks.map { it.first.index }.toIntArray())
                .putExtra("labels", picks.map { it.second }.toTypedArray())
                .putExtra("profile", profile.value.name)
                .putExtra("kids", profile.value.kids)
        )
    }

    suspend fun liveGameChannels(): List<Pair<Channel, String>> {
        val c = config.value ?: return emptyList()
        val live = games.value.filter { it.state == "in" }.sortedBy { if (isFavGame(it, c.favoriteTeams)) 0 else 1 }
        return withContext(Dispatchers.Default) {
            live.mapNotNull { g ->
                findChannelForGame(g, channels.value, epg.value, c.networkAliases)
                    ?.let { it to listOfNotNull("${g.away.abbr} @ ${g.home.abbr}", g.networks.firstOrNull()).joinToString(" · ") }
            }.distinctBy { it.first.index }
        }
    }

    fun playGame(g: Game) {
        val c = config.value ?: return
        lifecycleScope.launch {
            val ch = withContext(Dispatchers.Default) { findChannelForGame(g, channels.value, epg.value, c.networkAliases) }
            if (ch != null) play(ch)
            else Toast.makeText(this@MainActivity, "No channel found for ${g.networks.joinToString().ifBlank { g.name }}", Toast.LENGTH_LONG).show()
        }
    }

    fun toggleFavorite(ch: Channel) {
        val set = favorites.value.toMutableSet()
        if (!set.add(ch.name)) set.remove(ch.name)
        favorites.value = set
        settings.setFavorites(profile.value.name, set)
        Toast.makeText(this, if (ch.name in set) "Added ${ch.name} to favorites" else "Removed ${ch.name}", Toast.LENGTH_SHORT).show()
    }
}
