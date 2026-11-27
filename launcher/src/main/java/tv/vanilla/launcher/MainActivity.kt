package tv.vanilla.launcher

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import android.view.KeyEvent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var configRepo: ConfigRepository
    private lateinit var weatherRepo: WeatherRepository

    val config = mutableStateOf<LauncherConfig?>(null)
    val weather = mutableStateOf<Weather?>(null)
    val wallpapers = mutableStateOf<List<String>>(emptyList())
    val homePresses = mutableIntStateOf(0)
    val dock = mutableStateOf<List<AppEntry>?>(null)
    val lastInput = mutableLongStateOf(System.currentTimeMillis())
    val idle = mutableStateOf(false)
    val agenda = mutableStateOf<List<CalEvent>>(emptyList())
    val news = mutableStateOf<List<Headline>>(emptyList())
    /** The story the Headlines widget shows now, so full screen opens on it. */
    val newsLead = mutableStateOf<Headline?>(null)
    val alerts = mutableStateOf<List<Alert>>(emptyList())
    val glance = mutableStateOf<LiveGlance?>(null)
    val radar = mutableStateOf<String?>(null)
    val ha = mutableStateOf<List<HaEntity>>(emptyList())
    val haAll = mutableStateOf<List<HaEntity>>(emptyList())
    val radarFrames = mutableStateOf<List<Pair<String, Long>>>(emptyList())
    val photos = mutableStateOf<List<String>>(emptyList())
    val wallColor = mutableStateOf<androidx.compose.ui.graphics.Color?>(null)
    val look = mutableStateOf(HomeLook())
    private val localCells = mutableStateOf<List<Cell>?>(null)
    private val slotCells = mutableStateOf<Map<String, List<Cell>>>(emptyMap())
    val period = mutableStateOf(periodNow())
    val rooms by lazy { Rooms.get(this) }
    val radioOn = mutableStateOf<Station?>(null)
    val radioPlaying = mutableStateOf(false)
    val radioList = mutableStateOf<List<Station>>(emptyList())
    val radioGenre = mutableStateOf("")
    private var radioPlayer: androidx.media3.exoplayer.ExoPlayer? = null

    /** Plays [s], or stops when it's already playing (or null). Pauses itself when another app starts playing. */
    fun playRadio(s: Station?) {
        if (s == null || (radioOn.value?.url == s.url && radioPlaying.value)) { radioPlayer?.stop(); radioOn.value = null; radioPlaying.value = false; return }
        val p = radioPlayer ?: androidx.media3.exoplayer.ExoPlayer.Builder(this)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .build().also { pl ->
                radioPlayer = pl
                pl.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { radioPlaying.value = isPlaying }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Toast.makeText(this@MainActivity, "${radioOn.value?.name ?: "Station"} isn't playing right now", Toast.LENGTH_SHORT).show()
                        radioOn.value = null; radioPlaying.value = false
                    }
                })
            }
        radioOn.value = s
        p.setMediaItem(androidx.media3.common.MediaItem.fromUri(s.url))
        p.prepare(); p.play()
    }

    fun loadRadio(tag: String) {
        radioGenre.value = tag
        val mine = stationsFromLines(config.value?.radioStations.orEmpty())
        lifecycleScope.launch { radioList.value = mine + withContext(Dispatchers.IO) { fetchStations(tag) }.filter { s -> mine.none { it.url == s.url } } }
    }

    private suspend fun roomsLoop() {
        while (true) {
            rooms.start()
            if (widgetOn("rooms")) withContext(Dispatchers.IO) { rooms.poll() }
            delay(if (idle.value) 60_000L else 15_000L)
        }
    }

    private val homePrefs get() = getSharedPreferences("home", MODE_PRIVATE)

    val theme get() = config.value.let { c -> themeFor(look.value.theme ?: c?.themeName ?: "glass", look.value.accent ?: c?.accent ?: "", wallColor.value) }
    val preset get() = look.value.preset ?: if (config.value?.layout.let { it == null || it == "minimal" }) "minimal" else "grid"

    /** The layout in use: this part of the day's (when time-of-day layouts are on), the one edited on the TV, or the setup app's. */
    val cells: List<Cell> get() {
        if (preset == "minimal") return emptyList()
        if (look.value.timeOfDay) slotCells.value[period.value]?.let { return it }
        return localCells.value ?: defaultCells(config.value?.widgets.orEmpty())
    }

    fun widgetOn(id: String) = cells.any { it.id == id }

    fun setCells(list: List<Cell>) {
        if (look.value.timeOfDay) {
            slotCells.value = slotCells.value + (period.value to list)
            homePrefs.edit().putString("slots", org.json.JSONObject(slotCells.value.mapValues { cellsToJson(it.value) }).toString()).apply()
        } else {
            localCells.value = list
            homePrefs.edit().putString("cells", cellsToJson(list).toString()).apply()
        }
        lifecycleScope.launch { config.value?.let { refreshFeeds(it) } }
    }

    fun resetLayout() {
        localCells.value = null; slotCells.value = emptyMap()
        homePrefs.edit().remove("cells").remove("slots").apply()
    }

    /** Takes on a Home from the setup app or another TV (see [homeSnapshot]). */
    fun applyHome(j: org.json.JSONObject, includeLive: Boolean = true): Boolean {
        if (!applyHomeSnapshot(this, j, includeLive)) return false
        loadHome()
        iconOverride.value = homePrefs.getString("icon_style", null)
        dock.value = configRepo.localDock
        config.value?.let { c -> ha.value = haShown(c, haAll.value, haSet("ha_hidden"), haSet("ha_added")); lifecycleScope.launch { refreshFeeds(c) } }
        if (look.value.theme == "wallpaper") lifecycleScope.launch { updateWallColor() }
        return true
    }

    fun copyHomeFrom(p: Peer) {
        lifecycleScope.launch {
            val j = withContext(Dispatchers.IO) { runCatching { org.json.JSONObject(httpGet("http://${p.host}:${p.port}/home", 5000)) }.getOrNull() }
            val ok = j != null && applyHome(j, copyLive.value)
            Toast.makeText(this@MainActivity, if (ok) "Copied Home from ${p.name}" else "Couldn't copy from ${p.name}. Update its apps first.", Toast.LENGTH_LONG).show()
        }
    }

    /** Whether Copy from another TV also takes its Live TV favorites and look. */
    val copyLive by lazy { mutableStateOf(getSharedPreferences("launcher", MODE_PRIVATE).getBoolean("copy_live", true)) }
    fun setCopyLive(on: Boolean) { copyLive.value = on; getSharedPreferences("launcher", MODE_PRIVATE).edit().putBoolean("copy_live", on).apply() }

    /** Status line for Settings → System → Updates. */
    val updateStatus = mutableStateOf("")
    private var updateFound: Updates.Found? = null

    /** Checks for app updates, or installs them when an update was already found. */
    fun updateNow() {
        val found = updateFound
        lifecycleScope.launch {
            if (found != null && found.newer.isNotEmpty()) {
                updateStatus.value = "Downloading…"
                val err = withContext(Dispatchers.IO) { runCatching { found.newer.forEach { Updates.install(this@MainActivity, found.base, it) } }.exceptionOrNull() }
                updateStatus.value = if (err == null) "Installing… press OK if the TV asks" else "Download failed: ${err.message}"
                updateFound = null
                return@launch
            }
            updateStatus.value = "Checking…"
            val f = withContext(Dispatchers.IO) { Updates.check(this@MainActivity) }
            updateFound = f
            updateStatus.value = when {
                f == null -> "Couldn't reach the update server"
                f.newer.isEmpty() -> "Up to date"
                else -> "Update available (${f.newer.joinToString { if (it == "tv.vanilla.live") "Live TV" else "Home" }}): press OK to install"
            }
        }
    }

    private suspend fun updateLoop() {
        delay(60_000L)
        while (true) {
            val f = withContext(Dispatchers.IO) { Updates.check(this@MainActivity) }
            if (f != null && f.newer.isNotEmpty() && updateFound == null) { updateFound = f; updateStatus.value = "Update available: press OK to install" }
            delay(12 * 60 * 60_000L)
        }
    }

    fun setLook(l: HomeLook) {
        look.value = l
        l.save(homePrefs)
        if (l.theme == "wallpaper") lifecycleScope.launch { updateWallColor() }
    }

    private suspend fun updateWallColor() {
        val url = (wallpapers.value + photos.value).firstOrNull() ?: return
        wallColor.value = withContext(Dispatchers.IO) { averageColor(this@MainActivity, url) }
    }

    private fun loadHome() {
        val p = homePrefs
        look.value = HomeLook.load(p)
        localCells.value = p.getString("cells", null)?.let { runCatching { cellsFromJson(org.json.JSONArray(it)) }.getOrNull() }
        slotCells.value = p.getString("slots", null)?.let { t ->
            runCatching { org.json.JSONObject(t).let { o -> o.keys().asSequence().associateWith { k -> cellsFromJson(o.optJSONArray(k)) } } }.getOrNull()
        } ?: emptyMap()
    }

    private suspend fun haLoop() {
        while (true) {
            config.value?.let { c -> if (widgetOn("ha") || widgetOn("cameras")) loadHa(c) }
            delay(if (idle.value) 120_000L else 20_000L)
        }
    }

    fun toggleHa(e: HaEntity) {
        val c = config.value ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { haToggle(c, e) }
            delay(700)
            loadHa(c)
        }
    }

    private fun haSet(key: String) = homePrefs.getStringSet(key, emptySet()).orEmpty()

    suspend fun loadHa(c: LauncherConfig) {
        withContext(Dispatchers.IO) { fetchHaAll(c) }?.let { haAll.value = it }
        ha.value = haShown(c, haAll.value, haSet("ha_hidden"), haSet("ha_added"))
    }

    /** Shows or hides one Home Assistant entity on Home (saved on this TV). */
    fun setHaShown(id: String, show: Boolean) {
        val hidden = haSet("ha_hidden").toMutableSet(); val added = haSet("ha_added").toMutableSet()
        if (show) { hidden -= id; added += id } else { hidden += id; added -= id }
        homePrefs.edit().putStringSet("ha_hidden", hidden).putStringSet("ha_added", added).apply()
        config.value?.let { c -> ha.value = haShown(c, haAll.value, hidden, added) }
    }

    private suspend fun glanceLoop() {
        while (true) {
            glance.value = withContext(Dispatchers.IO) { readGlance(this@MainActivity) }
            period.value = periodNow()
            delay(30_000L)
        }
    }

    private suspend fun refreshFeeds(c: LauncherConfig) = withContext(Dispatchers.IO) {
        agenda.value = if (c.calendars.isEmpty()) emptyList() else fetchCalendar(c.calendars)
        news.value = if (c.newsFeeds.isEmpty()) emptyList() else fetchNews(c.newsFeeds, if (widgetOn("news")) 15 else 8)
        alerts.value = if (c.alerts && c.hasWeather) fetchAlerts(c.latitude, c.longitude) else emptyList()
        if (widgetOn("radio") && radioList.value.isEmpty()) withContext(Dispatchers.Main) { loadRadio(radioGenre.value) }
        if (widgetOn("weather")) { radarFrames.value = fetchRadarFrames(); radar.value = radarFrames.value.lastOrNull()?.first }
    }

    private suspend fun feedLoop() {
        while (true) {
            config.value?.let { refreshFeeds(it) }
            delay(10 * 60_000L)
        }
    }
    private val iconOverride = mutableStateOf<String?>(null)

    /** Icon style picked on the box (tile menu) wins over the setup app's default. */
    val iconStyle get() = iconOverride.value ?: config.value?.iconStyle ?: "original"

    fun cycleIconStyle() {
        val next = ICON_STYLES.keys.toList().let { it[(it.indexOf(iconStyle) + 1) % it.size] }
        iconOverride.value = next
        getSharedPreferences("home", MODE_PRIVATE).edit().putString("icon_style", next).apply()
    }

    private fun storeIntent(): Intent? = launchIntentFor("com.android.vending")
        ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=")).takeIf { it.resolveActivity(packageManager) != null }

    val hasStore get() = storeIntent() != null

    fun openStore() {
        val i = storeIntent() ?: return
        runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // Handled here rather than in Compose: while idle no tile is focused, so Compose never sees the key.
    override fun onStart() {
        super.onStart()
        iconOverride.value = getSharedPreferences("home", MODE_PRIVATE).getString("icon_style", null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val wasIdle = idle.value
            lastInput.longValue = System.currentTimeMillis()
            if (wasIdle) { idle.value = false; return true }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        HomeButtonService.visitingStock = false
        idle.value = false
        lastInput.longValue = System.currentTimeMillis()
        lifecycleScope.launch { delay(3_000); HomeButtonService.heal(this@MainActivity) }
        lifecycleScope.launch { glance.value = withContext(Dispatchers.IO) { readGlance(this@MainActivity) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configRepo = ConfigRepository(this)
        weatherRepo = WeatherRepository(this)
        loadHome()
        applyIntent(intent)
        dock.value = configRepo.localDock
        if (config.value == null) config.value = configRepo.loadCached()
        weather.value = weatherRepo.cached()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        setContent {
            LauncherScreen(
                activity = this,
                onLaunch = ::launchApp,
                onSettings = ::openSettings,
            )
        }

        lifecycleScope.launch { configLoop() }
        lifecycleScope.launch { weatherLoop() }
        lifecycleScope.launch { delay(3_000); feedLoop() }
        lifecycleScope.launch { glanceLoop() }
        lifecycleScope.launch { haLoop() }
        lifecycleScope.launch { roomsLoop() }
        lifecycleScope.launch { updateLoop() }
    }

    override fun onDestroy() {
        radioPlayer?.release()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (applyIntent(intent)) refreshNow()
        idle.value = false
        lastInput.longValue = System.currentTimeMillis()
        homePresses.intValue++
    }

    /** `adb shell am start -n tv.vanilla.launcher/.MainActivity --es config_url http://host:9192/config/launcher-config.json` */
    private fun applyIntent(intent: Intent?): Boolean {
        intent ?: return false
        // keep_home: settings from the setup app leave this TV's own look, layout and dock alone.
        val keepHome = intent.getBooleanExtra("keep_home", false)
        intent.getStringExtra("update_url")?.let { Updates.setSource(this, it) }
        intent.getStringExtra("config_b64")?.let { b64 ->
            val text = runCatching { String(Base64.decode(b64, Base64.DEFAULT)) }.getOrNull()
            val before = config.value?.widgets?.map { it.id }?.toSet()
            text?.let { configRepo.applyInline(it) }?.let {
                if (!keepHome) {
                    iconOverride.value = null
                    homePrefs.edit().remove("icon_style").apply()
                    setLook(look.value.copy(theme = null, accent = null, preset = null))
                    if (before != null && before != it.widgets.map { w -> w.id }.toSet()) resetLayout()
                }
                config.value = it
                lifecycleScope.launch { reloadWallpapers(it); refreshWeather(it); refreshFeeds(it) }
            }
            if (!keepHome && intent.getBooleanExtra("reset_dock", true)) setDock(null)
        }
        intent.getStringExtra("home_b64")?.let { b64 ->
            runCatching { org.json.JSONObject(String(Base64.decode(b64, Base64.DEFAULT))) }.getOrNull()?.let { applyHome(it) }
        }
        val url = intent.getStringExtra("config_url") ?: return false
        configRepo.configUrl = url
        return true
    }

    private suspend fun refreshWeather(c: LauncherConfig) {
        if (!c.hasWeather) { weather.value = null; return }
        withContext(Dispatchers.IO) { weatherRepo.fetch(c.latitude, c.longitude, c.fahrenheit) }?.let { weather.value = it }
    }

    fun dockApps(): List<AppEntry> = dock.value ?: config.value?.apps.orEmpty()

    fun setDock(apps: List<AppEntry>?) {
        configRepo.localDock = apps
        dock.value = apps
    }

    /** Every launchable app on the box that isn't already on the dock. */
    fun availableApps(): List<AppEntry> {
        val onDock = dockApps().flatMap { it.packages }.toSet()
        val pm = packageManager
        val found = linkedMapOf<String, String>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0).forEach { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg != packageName && pkg !in onDock && pkg !in found) found[pkg] = ri.loadLabel(pm).toString()
            }
        }
        return found.entries.sortedBy { it.value.lowercase() }.map { (pkg, label) -> AppEntry(pkg, label, listOf(pkg), "", "") }
    }

    fun refreshNow() {
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) { configRepo.fetchRemote() } ?: return@launch
            config.value = fresh
            reloadWallpapers(fresh)
            refreshWeather(fresh)
        }
    }

    fun setConfigUrl(url: String) {
        configRepo.configUrl = url
        refreshNow()
    }

    fun currentConfigUrl() = configRepo.configUrl

    private suspend fun configLoop() {
        config.value?.let { reloadWallpapers(it) }
        while (true) {
            withContext(Dispatchers.IO) { configRepo.fetchRemote() }?.let {
                config.value = it
                reloadWallpapers(it)
            }
            delay(15 * 60_000L)
        }
    }

    private suspend fun weatherLoop() {
        while (true) {
            config.value?.let { refreshWeather(it) }
            delay(30 * 60_000L)
        }
    }

    private suspend fun reloadWallpapers(c: LauncherConfig) {
        photos.value = if (c.photos.isEmpty()) emptyList() else withContext(Dispatchers.IO) { fetchPhotos(c.photos) }
        if ((look.value.theme ?: c.themeName) == "wallpaper") updateWallColor()
        if (c.wallpaperMode != "photos" || c.wallpaperManifestUrl.isBlank()) {
            wallpapers.value = emptyList()
            return
        }
        val list = withContext(Dispatchers.IO) { fetchWallpaperList(c.wallpaperManifestUrl) }
        if (list.isNotEmpty()) wallpapers.value = list.shuffled()
        if ((look.value.theme ?: c.themeName) == "wallpaper") updateWallColor()
    }

    fun isInstalled(app: AppEntry): Boolean = app.packages.any { launchIntentFor(it) != null }

    private fun launchIntentFor(pkg: String): Intent? =
        packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)

    private fun launchApp(app: AppEntry) {
        val intent = app.packages.firstNotNullOfOrNull { launchIntentFor(it) }
        if (intent == null) {
            Toast.makeText(this, "${app.label} is not installed", Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(intent) }
    }

    fun openSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
