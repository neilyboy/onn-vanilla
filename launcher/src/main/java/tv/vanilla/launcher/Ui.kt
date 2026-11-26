@file:OptIn(ExperimentalTvMaterial3Api::class)

package tv.vanilla.launcher

import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

@Composable
fun LauncherScreen(
    activity: MainActivity,
    onLaunch: (AppEntry) -> Unit,
    onSettings: () -> Unit,
) {
    val cfg = activity.config.value ?: return
    val look = activity.look.value
    val theme = activity.theme
    val weather = activity.weather.value
    val walls = activity.wallpapers.value
    val homePresses = activity.homePresses.intValue

    val lastInput = activity.lastInput.longValue
    var idle by activity.idle
    var showSetup by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AppEntry?>(null) }
    var zoom by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val apps = activity.dock.value ?: cfg.apps
    val firstTile = remember { FocusRequester() }
    val cells = activity.cells
    val grid = activity.preset != "minimal" && cells.isNotEmpty()
    val liveGame = if (look.gameDay && activity.preset != "minimal") activity.glance.value?.games?.firstOrNull { it.fav && it.state == "in" } else null

    LaunchedEffect(cfg.idleTimeoutSec, lastInput) {
        idle = false
        if (cfg.idleTimeoutSec > 0) {
            delay(cfg.idleTimeoutSec * 1000L)
            idle = true
        }
    }
    LaunchedEffect(homePresses, apps.size, idle) {
        if (idle) return@LaunchedEffect
        delay(450)
        runCatching { firstTile.requestFocus() }
    }

    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * cfg.scale, d.fontScale)) {
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev ->
                val e = edit
                if (e != null) {
                    val step = when (ev.key) {
                        Key.DirectionLeft -> -1 to 0; Key.DirectionRight -> 1 to 0
                        Key.DirectionUp -> 0 to -1; Key.DirectionDown -> 0 to 1
                        else -> null
                    }
                    if (step != null) {
                        if (ev.type == KeyEventType.KeyDown) activity.setCells(nudge(activity.cells, e.first, step.first, step.second, e.second))
                        return@onPreviewKeyEvent true
                    }
                    if (ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter) {
                        if (ev.type == KeyEventType.KeyUp) edit = e.copy(second = !e.second)
                        return@onPreviewKeyEvent true
                    }
                    if (ev.key == Key.Back || ev.key == Key.Escape) {
                        if (ev.type == KeyEventType.KeyUp) edit = null
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (ev.key == Key.Menu) { showSettings = true; true } else false
            }
    ) {
        Background(cfg, theme, if (idle && activity.photos.value.isNotEmpty()) activity.photos.value else walls, idle && activity.photos.value.isNotEmpty())

        // Legibility scrims
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to theme.scrim.copy(alpha = theme.scrim.alpha * 0.5f),
                    0.3f to Color.Transparent,
                    0.7f to Color.Transparent,
                    1f to theme.scrim.copy(alpha = theme.scrim.alpha * 0.6f),
                )
            )
        )

        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
            if (idle) Row(Modifier.fillMaxWidth()) { Spacer(Modifier.weight(1f)); ClockWeather(cfg, theme, weather, compact = true) }
            else Header(cfg, theme, weather, cfg.greeting) { if (!activity.widgetOn("calendar")) Agenda(activity.agenda.value, theme) }
            activity.alerts.value.firstOrNull()?.let { AlertBanner(it, activity.alerts.value.size, theme) }
            when {
                idle -> Spacer(Modifier.weight(1f))
                liveGame != null -> GameDay(liveGame, activity, cfg, theme, null, Modifier.weight(1f).fillMaxWidth().padding(vertical = 18.dp), { zoom = it }, { menuFor = it })
                grid -> WidgetGrid(activity, cfg, theme, cells, edit, Modifier.weight(1f).fillMaxWidth().padding(vertical = 18.dp), { zoom = it }, { menuFor = it })
                else -> Spacer(Modifier.weight(1f))
            }
            if (!idle && !activity.widgetOn("news")) NewsStrip(activity.news.value, theme)
            AnimatedVisibility(visible = !idle && edit == null, enter = fadeIn(tween(400)), exit = fadeOut(tween(800))) {
                Dock(
                    apps, activity, theme, look.iconDp.dp, firstTile, cfg.showSettings,
                    onLaunch = onLaunch, onLongApp = { editing = it }, onAdd = { showPicker = true },
                    onGear = { showSettings = true }, onGearLong = onSettings,
                )
            }
        }
        edit?.let { EditBar(it.second, Modifier.align(Alignment.TopCenter).padding(top = 28.dp)) }

        if (showSettings) HomeSettings(
            activity,
            onEditLayout = { showSettings = false; activity.cells.firstOrNull()?.let { edit = it.id to false } },
            onAdvanced = { showSetup = true },
            onClose = { showSettings = false },
        )
        zoom?.let { ZoomView(it, activity, cfg, theme) { zoom = null } }
        menuFor?.let { id ->
            WidgetMenu(id, theme, onDismiss = { menuFor = null }) { action ->
                menuFor = null
                when (action) {
                    "zoom" -> zoom = id
                    "edit" -> edit = id to false
                    "remove" -> activity.setCells(activity.cells.filter { it.id != id })
                    "add" -> showAdd = true
                    "settings" -> showSettings = true
                }
            }
        }
        if (showAdd) AddWidgetDialog(activity.cells.map { it.id }, theme) { w ->
            showAdd = false
            if (w != null) {
                val spot = firstFree(activity.cells, w.id, w.w, w.h)
                if (spot == null) android.widget.Toast.makeText(activity, "No room left. Remove or shrink a widget first.", android.widget.Toast.LENGTH_SHORT).show()
                else { activity.setCells(activity.cells + spot); edit = spot.id to false }
            }
        }
        if (showSetup) SetupDialog(activity, theme, onSettings) { showSetup = false }
        if (showPicker) AppPickerDialog(activity, theme) { picked ->
            showPicker = false
            if (picked != null) activity.setDock(apps + picked)
        }
        editing?.let { app ->
            val idx = apps.indexOfFirst { it.id == app.id }
            TileMenuDialog(
                app, theme, ICON_STYLES[activity.iconStyle] ?: "Original",
                canLeft = idx > 0, canRight = idx in 0 until apps.size - 1,
                onDismiss = { editing = null },
                onAction = { action ->
                    editing = null
                    val list = apps.toMutableList()
                    when (action) {
                        "left" -> if (idx > 0) list.add(idx - 1, list.removeAt(idx))
                        "right" -> if (idx < list.size - 1) list.add(idx + 1, list.removeAt(idx))
                        "remove" -> list.removeAt(idx)
                        "reset" -> { activity.setDock(null); return@TileMenuDialog }
                        "icons" -> { activity.cycleIconStyle(); return@TileMenuDialog }
                        "settings" -> { showSettings = true; return@TileMenuDialog }
                    }
                    activity.setDock(list)
                },
            )
        }
    }
    }
}

@Composable
private fun Background(cfg: LauncherConfig, theme: VanillaTheme, walls: List<String>, frame: Boolean = false) {
    val base = Modifier.fillMaxSize().background(
        if (cfg.wallpaperMode == "solid") SolidColor(theme.background.first())
        else Brush.linearGradient(theme.background)
    )
    Box(base) {
        if (cfg.wallpaperMode != "solid" && !frame) ThemeDecor(theme)
        if ((cfg.wallpaperMode != "photos" && !frame) || walls.isEmpty()) return@Box
        var index by remember(walls) { mutableIntStateOf(0) }
        LaunchedEffect(walls, cfg.wallpaperIntervalSec) {
            while (true) {
                delay(cfg.wallpaperIntervalSec * 1000L)
                index = (index + 1) % walls.size
            }
        }
        val ctx = LocalContext.current
        Crossfade(targetState = walls[index % walls.size], animationSpec = tween(1500), label = "wall") { url ->
            val zoom = if (cfg.kenBurns) {
                val t = rememberInfiniteTransition(label = "kb")
                t.animateFloat(
                    1f, 1.06f,
                    infiniteRepeatable(tween(cfg.wallpaperIntervalSec * 1000 + 1500, easing = LinearEasing), RepeatMode.Reverse),
                    label = "kbz",
                ).value
            } else 1f
            val model = ImageRequest.Builder(ctx).data(url).size(1920, 1080).crossfade(false).build()
            // Photo frame: portrait shots sit on a blurred copy of themselves instead of black bars.
            if (frame) AsyncImage(model, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(40.dp).alpha(0.55f))
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = if (frame) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize().scale(zoom),
            )
        }
        if (frame) Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, 0f), radius = 900f)))
    }
}

@Composable
private fun ClockWeather(cfg: LauncherConfig, theme: VanillaTheme, weather: Weather?, compact: Boolean) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000L - (System.currentTimeMillis() % 1000L))
        }
    }
    val timeFmt = remember(cfg.clock24h) { DateTimeFormatter.ofPattern(if (cfg.clock24h) "HH:mm" else "h:mm") }
    val dateFmt = remember { DateTimeFormatter.ofPattern("EEEE, MMMM d") }
    val unit = if (cfg.fahrenheit) "°" else "°C"

    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(now.format(timeFmt), color = theme.text, fontSize = 46.sp, fontWeight = FontWeight.Light)
            if (!cfg.clock24h) {
                Text(
                    if (now.hour < 12) " AM" else " PM", color = theme.subText, fontSize = 15.sp,
                    modifier = Modifier.padding(bottom = 9.dp),
                )
            }
        }
        Text(now.format(dateFmt), color = theme.subText, fontSize = 15.sp)
        if (cfg.hasWeather && weather != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(weatherIcon(weather.codeNow, weather.isDay), null, tint = theme.text, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("${weather.tempNow}$unit", color = theme.text, fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                val label = listOfNotNull(weatherLabel(weather.codeNow).ifBlank { null }, cfg.locationName.ifBlank { null }).joinToString(" · ")
                Text(label, color = theme.subText, fontSize = 14.sp)
            }
            AnimatedVisibility(visible = cfg.showForecast && !compact, enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    weather.days.take(7).forEach { d ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                d.date.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.getDefault()).uppercase(),
                                color = theme.subText, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            )
                            Icon(weatherIcon(d.code, true), null, tint = theme.text, modifier = Modifier.padding(vertical = 3.dp).size(16.dp))
                            Text("${d.high}°", color = theme.text, fontSize = 12.sp)
                            Text("${d.low}°", color = theme.subText, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

internal fun weatherIcon(code: Int, day: Boolean): ImageVector = when (code) {
    0, 1 -> if (day) Icons.Outlined.WbSunny else Icons.Outlined.NightsStay
    2 -> Icons.Outlined.WbCloudy
    3 -> Icons.Outlined.Cloud
    45, 48 -> Icons.Outlined.Dehaze
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Icons.Outlined.WaterDrop
    71, 73, 75, 77, 85, 86 -> Icons.Outlined.AcUnit
    95, 96, 99 -> Icons.Outlined.Thunderstorm
    else -> Icons.Outlined.Cloud
}

private fun builtinIcon(name: String): ImageVector? = when (name) {
    "livetv", "tv" -> Icons.Outlined.LiveTv
    "silo", "movies" -> Icons.Outlined.VideoLibrary
    "music" -> Icons.Outlined.LibraryMusic
    "sports" -> Icons.Outlined.SportsFootball
    "browser" -> Icons.Outlined.Language
    "apps" -> Icons.Outlined.Apps
    "settings" -> Icons.Outlined.Settings
    else -> null
}

@Composable
internal fun tileColors(theme: VanillaTheme) = ClickableSurfaceDefaults.colors(
    containerColor = theme.tile,
    focusedContainerColor = theme.tileFocused,
    pressedContainerColor = theme.tileFocused,
    contentColor = theme.text,
    focusedContentColor = theme.text,
)

@Composable
private fun DockItem(theme: VanillaTheme, size: androidx.compose.ui.unit.Dp, label: String, dim: Boolean, modifier: Modifier, onClick: () -> Unit, onLongClick: (() -> Unit)?, content: @Composable BoxScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = if (theme.style == "neon") RoundedCornerShape(8.dp) else RoundedCornerShape(size * 0.26f)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(size + 24.dp)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = tileColors(theme),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, theme.accent), shape = shape)),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = if (theme.glow.alpha > 0f) Glow(theme.glow, 14.dp) else Glow.None),
            modifier = modifier.size(size).onFocusChanged { focused = it.isFocused },
        ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = content) }
        Text(
            label, color = if (dim) theme.subText else theme.text, fontSize = 12.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp).alpha(if (focused) 1f else 0f),
        )
    }
}

@Composable
internal fun GearTile(theme: VanillaTheme, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, onLongClick: () -> Unit) =
    DockItem(theme, size, "Settings", false, Modifier, onClick, onLongClick) { Icon(Icons.Outlined.Settings, "Settings", Modifier.size(size * 0.45f)) }

@Composable
internal fun AppTile(app: AppEntry, theme: VanillaTheme, activity: MainActivity, size: androidx.compose.ui.unit.Dp, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val installed = remember(app) { activity.isInstalled(app) }
    DockItem(theme, size, app.label + if (installed) "" else " (not installed)", !installed, modifier, onClick, onLongClick) {
        val drawable: Drawable? = remember(app) {
            if (app.iconUrl.isNotBlank()) null
            else app.packages.firstNotNullOfOrNull { runCatching { activity.packageManager.getApplicationIcon(it) }.getOrNull() }
        }
        if (drawable != null) ThemedIcon(drawable, activity.iconStyle, theme.accent, app.label, Modifier.fillMaxSize().padding(size * 0.06f).clip(RoundedCornerShape(size * 0.2f)))
        else Box(Modifier.fillMaxSize().padding(size * 0.22f), contentAlignment = Alignment.Center) { AppIcon(app, activity) }
    }
}

@Composable
private fun AppIcon(app: AppEntry, activity: MainActivity) {
    val builtin = builtinIcon(app.icon)
    when {
        app.iconUrl.isNotBlank() -> AsyncImage(app.iconUrl, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        builtin != null -> Icon(builtin, app.label, Modifier.fillMaxSize())
        else -> {
            val drawable: Drawable? = remember(app) {
                app.packages.firstNotNullOfOrNull { runCatching { activity.packageManager.getApplicationIcon(it) }.getOrNull() }
            }
            if (drawable != null) {
                Image(drawable.toBitmap(192, 192).asImageBitmap(), app.label, Modifier.fillMaxSize())
            } else {
                Icon(Icons.Outlined.Apps, app.label, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun SetupDialog(activity: MainActivity, theme: VanillaTheme, onAndroidSettings: () -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(activity.currentConfigUrl()) }
    var released by remember { mutableStateOf(HomeButtonService.released(activity)) }
    val stock = remember { HomeButtonService.stockHome(activity) }
    val stockName = remember(stock) {
        stock?.let { runCatching { activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(it.packageName, 0)).toString() }.getOrNull() } ?: "original home"
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(640.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xF0181A20)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Vanilla Home", color = Color.White, fontSize = 22.sp)
            MenuRow("Android settings", theme, Modifier.focusRequester(focus)) { onDismiss(); onAndroidSettings() }
            if (stock != null) MenuRow("Open $stockName", theme) { onDismiss(); HomeButtonService.openStockHome(activity) }
            MenuRow(if (released) "Home button: $stockName  (switch to Vanilla Home)" else "Home button: Vanilla Home  (give back to $stockName)", theme) {
                released = !released
                HomeButtonService.setReleased(activity, released)
            }
            if (released) Text("The Home button now opens $stockName. Open Vanilla Home from its app tile, or switch back here.", color = Color(0xB3FFFFFF), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text("Launcher config URL", color = Color(0xB3FFFFFF), fontSize = 14.sp)
            BasicTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(theme.accent),
                modifier = Modifier.fillMaxWidth().border(1.dp, theme.accent, RoundedCornerShape(10.dp)).padding(12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { activity.setConfigUrl(url); onDismiss() }) { Text("Save & reload") }
                Button(onClick = { activity.refreshNow(); onDismiss() }) { Text("Reload") }
                Button(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

@Composable
internal fun AddTile(theme: VanillaTheme, size: androidx.compose.ui.unit.Dp, modifier: Modifier, onClick: () -> Unit) =
    DockItem(theme, size, "Add app", false, modifier, onClick, null) { Icon(Icons.Outlined.Add, "Add app", Modifier.size(size * 0.4f)) }

@Composable
internal fun DialogCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.width(560.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xF0181A20)).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
internal fun MenuRow(label: String, theme: VanillaTheme, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = theme.accent.copy(alpha = 0.25f),
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) { leading(); Spacer(Modifier.width(16.dp)) }
            Text(label, fontSize = 18.sp)
        }
    }
}

@Composable
private fun AppPickerDialog(activity: MainActivity, theme: VanillaTheme, onDone: (AppEntry?) -> Unit) {
    val apps = remember { activity.availableApps() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = { onDone(null) }) {
        DialogCard {
            Text("Add to Home", color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(bottom = 6.dp))
            val store = activity.hasStore
            if (store) MenuRow(
                "Get more apps", theme, Modifier.focusRequester(first),
                leading = { Icon(Icons.Outlined.Shop, null, Modifier.size(36.dp)) },
            ) { onDone(null); activity.openStore() }
            if (apps.isEmpty()) {
                Text("Every installed app is already on Home.", color = Color(0xFFB0B4BC), fontSize = 16.sp)
                MenuRow("Close", theme, if (store) Modifier else Modifier.focusRequester(first)) { onDone(null) }
            }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(apps.size) { i ->
                    val app = apps[i]
                    MenuRow(
                        app.label, theme,
                        modifier = if (i == 0 && !store) Modifier.focusRequester(first) else Modifier,
                        leading = { Box(Modifier.size(36.dp)) { AppIcon(app, activity) } },
                    ) { onDone(app) }
                }
            }
        }
    }
}

@Composable
private fun TileMenuDialog(app: AppEntry, theme: VanillaTheme, iconStyle: String, canLeft: Boolean, canRight: Boolean, onDismiss: () -> Unit, onAction: (String) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        DialogCard {
            Text(app.label, color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(bottom = 6.dp))
            val rows = buildList {
                if (canLeft) add("left" to "Move left")
                if (canRight) add("right" to "Move right")
                add("remove" to "Remove from Home")
                add("icons" to "Icon style: $iconStyle")
                add("reset" to "Reset apps to default")
                add("settings" to "Home settings")
                add("cancel" to "Cancel")
            }
            rows.forEachIndexed { i, (id, label) ->
                MenuRow(label, theme, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                    if (id == "cancel") onDismiss() else onAction(id)
                }
            }
        }
    }
}

val ICON_STYLES = linkedMapOf("original" to "Original", "mono" to "Monochrome", "accent" to "Accent tint")

/** App icon in the chosen style. Uses the app's own monochrome (themed) icon when it ships one, otherwise recolors the full icon. */
@Composable
private fun ThemedIcon(d: Drawable, style: String, accent: Color, label: String, modifier: Modifier) {
    val mono = remember(d, style) {
        if (style != "original" && Build.VERSION.SDK_INT >= 33) (d as? AdaptiveIconDrawable)?.monochrome else null
    }
    if (mono != null) {
        Image(mono.toBitmap(192, 192).asImageBitmap(), label, modifier, colorFilter = ColorFilter.tint(if (style == "accent") accent else Color.White))
        return
    }
    val filter = when (style) {
        "mono" -> ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        "accent" -> ColorFilter.colorMatrix(accentMatrix(accent))
        else -> null
    }
    Image(d.toBitmap(256, 256).asImageBitmap(), label, modifier, colorFilter = filter)
}

/** Grayscale, then mapped from a dark shade of [c] (black) to a light tint of it (white). */
private fun accentMatrix(c: Color): ColorMatrix {
    fun row(ch: Float): FloatArray {
        val lo = ch * 0.18f
        val hi = ch + (1 - ch) * 0.55f
        val k = hi - lo
        return floatArrayOf(0.299f * k, 0.587f * k, 0.114f * k, 0f, lo * 255f)
    }
    return ColorMatrix(row(c.red) + row(c.green) + row(c.blue) + floatArrayOf(0f, 0f, 0f, 1f, 0f))
}

@Composable
private fun Agenda(events: List<CalEvent>, theme: VanillaTheme) {
    if (events.isEmpty()) return
    val today = java.time.LocalDate.now()
    val timeFmt = remember { DateTimeFormatter.ofPattern("h:mm a") }
    val dayFmt = remember { DateTimeFormatter.ofPattern("EEE") }
    Column(Modifier.padding(top = 14.dp).widthIn(max = 300.dp), horizontalAlignment = Alignment.End) {
        events.take(4).forEach { e ->
            val d = e.start.toLocalDate()
            val day = when (d) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> d.format(dayFmt) }
            val time = if (e.allDay) day else "$day ${e.start.format(timeFmt)}"
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(e.title, color = theme.text, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("  $time", color = theme.subText, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun AlertBanner(a: Alert, count: Int, theme: VanillaTheme) {
    val color = when (a.severity) { "Extreme", "Severe" -> Color(0xFFD93A3A); "Moderate" -> Color(0xFFE08A1E); else -> Color(0xFF6B7280) }
    Row(
        Modifier.padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.92f)).padding(horizontal = 14.dp, vertical = 8.dp).widthIn(max = 760.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Warning, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(a.event.uppercase(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(a.headline, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (count > 1) Text("  +${count - 1} more", color = Color(0xCCFFFFFF), fontSize = 12.sp)
    }
}

@Composable
private fun NewsStrip(items: List<Headline>, theme: VanillaTheme) {
    if (items.isEmpty()) return
    var i by remember { mutableStateOf(0) }
    LaunchedEffect(items) { while (true) { delay(9_000); i++ } }
    val h = items[i % items.size]
    androidx.compose.animation.Crossfade(h, label = "news") { cur ->
        Row(Modifier.padding(bottom = 14.dp, start = 4.dp).widthIn(max = 900.dp), verticalAlignment = Alignment.CenterVertically) {
            if (cur.source.isNotBlank()) Text(cur.source.uppercase(), color = theme.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            Text(cur.title, color = theme.text, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}
