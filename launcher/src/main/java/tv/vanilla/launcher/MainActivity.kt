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

    // Handled here rather than in Compose: while idle no tile is focused, so Compose never sees the key.
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
        idle.value = false
        lastInput.longValue = System.currentTimeMillis()
        lifecycleScope.launch { delay(3_000); HomeButtonService.heal(this@MainActivity) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configRepo = ConfigRepository(this)
        weatherRepo = WeatherRepository(this)
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
        intent.getStringExtra("config_b64")?.let { b64 ->
            val text = runCatching { String(Base64.decode(b64, Base64.DEFAULT)) }.getOrNull()
            text?.let { configRepo.applyInline(it) }?.let { config.value = it; lifecycleScope.launch { reloadWallpapers(it); refreshWeather(it) } }
            if (intent.getBooleanExtra("reset_dock", true)) setDock(null)
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
        if (c.wallpaperMode != "photos" || c.wallpaperManifestUrl.isBlank()) {
            wallpapers.value = emptyList()
            return
        }
        val list = withContext(Dispatchers.IO) { fetchWallpaperList(c.wallpaperManifestUrl) }
        if (list.isNotEmpty()) wallpapers.value = list.shuffled()
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

    private fun openSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
