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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    val theme = themeFor(cfg)
    val weather = activity.weather.value
    val walls = activity.wallpapers.value
    val homePresses = activity.homePresses.intValue

    val lastInput = activity.lastInput.longValue
    var idle by activity.idle
    var showSetup by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AppEntry?>(null) }
    val apps = activity.dock.value ?: cfg.apps
    val firstTile = remember { FocusRequester() }

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
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (ev.key == Key.Menu) { showSetup = true; true } else false
            }
    ) {
        Background(cfg, theme, walls)

        // Legibility scrims
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to theme.scrim.copy(alpha = theme.scrim.alpha * 0.55f),
                    0.35f to Color.Transparent,
                    0.6f to Color.Transparent,
                    1f to theme.scrim.copy(alpha = theme.scrim.alpha * 0.75f),
                )
            )
        )

        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                AnimatedVisibility(visible = cfg.showSettings && !idle, enter = fadeIn(), exit = fadeOut()) {
                    IconTile(Icons.Outlined.Settings, theme, size = 42.dp, onClick = onSettings, onLongClick = { showSetup = true })
                }
                Spacer(Modifier.weight(1f))
                ClockWeather(cfg, theme, weather, compact = idle)
            }
            Spacer(Modifier.weight(1f))
            if (cfg.greeting.isNotBlank() && !idle) {
                Text(cfg.greeting, color = theme.subText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 12.dp, start = 4.dp))
            }
            AnimatedVisibility(visible = !idle, enter = fadeIn(tween(400)), exit = fadeOut(tween(1200))) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    apps.forEachIndexed { i, app ->
                        key(app.id) {
                            AppTile(
                                app, theme, activity,
                                modifier = if (i == 0) Modifier.focusRequester(firstTile) else Modifier,
                                onClick = { onLaunch(app) },
                                onLongClick = { editing = app },
                            )
                        }
                    }
                    AddTile(theme, if (apps.isEmpty()) Modifier.focusRequester(firstTile) else Modifier) { showPicker = true }
                }
            }
        }

        if (showSetup) SetupDialog(activity, theme) { showSetup = false }
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
                    }
                    activity.setDock(list)
                },
            )
        }
    }
    }
}

@Composable
private fun Background(cfg: LauncherConfig, theme: VanillaTheme, walls: List<String>) {
    val base = Modifier.fillMaxSize().background(
        if (cfg.wallpaperMode == "solid") SolidColor(theme.background.first())
        else Brush.linearGradient(theme.background)
    )
    Box(base) {
        if (cfg.wallpaperMode != "photos" || walls.isEmpty()) return@Box
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
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(url).size(1920, 1080).crossfade(false).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().scale(zoom),
            )
        }
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

private fun weatherIcon(code: Int, day: Boolean): ImageVector = when (code) {
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
private fun tileColors(theme: VanillaTheme) = ClickableSurfaceDefaults.colors(
    containerColor = theme.tile,
    focusedContainerColor = theme.tileFocused,
    pressedContainerColor = theme.tileFocused,
    contentColor = theme.text,
    focusedContentColor = theme.text,
)

@Composable
private fun IconTile(icon: ImageVector, theme: VanillaTheme, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = tileColors(theme),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, theme.accent)),
        ),
        modifier = Modifier.size(size),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = "Settings", modifier = Modifier.size(size * 0.5f))
        }
    }
}

@Composable
private fun AppTile(app: AppEntry, theme: VanillaTheme, activity: MainActivity, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val installed = remember(app) { activity.isInstalled(app) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
            colors = tileColors(theme),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, theme.accent)),
            ),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(theme.accent.copy(alpha = 0.45f), 18.dp)),
            modifier = modifier.size(104.dp),
        ) {
            val drawable: Drawable? = remember(app) {
                if (app.iconUrl.isNotBlank()) null
                else app.packages.firstNotNullOfOrNull { runCatching { activity.packageManager.getApplicationIcon(it) }.getOrNull() }
            }
            if (drawable != null) {
                ThemedIcon(drawable, activity.iconStyle, theme.accent, app.label, Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(16.dp)))
            } else {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    AppIcon(app, activity)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            app.label + if (installed) "" else " (not installed)",
            color = if (installed) theme.text else theme.subText,
            fontSize = 14.sp,
        )
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
private fun SetupDialog(activity: MainActivity, theme: VanillaTheme, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(activity.currentConfigUrl()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(640.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xF0181A20)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Launcher config URL", color = Color.White, fontSize = 22.sp)
            BasicTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                cursorBrush = SolidColor(theme.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    .border(1.dp, theme.accent, RoundedCornerShape(10.dp)).padding(14.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { activity.setConfigUrl(url); onDismiss() }) { Text("Save & reload") }
                Button(onClick = { activity.refreshNow(); onDismiss() }) { Text("Reload") }
                Button(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun AddTile(theme: VanillaTheme, modifier: Modifier, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(28.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = theme.tile.copy(alpha = theme.tile.alpha * 0.4f),
                focusedContainerColor = theme.tileFocused,
                contentColor = theme.subText,
                focusedContentColor = theme.text,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, theme.accent)),
            ),
            modifier = modifier.size(104.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Add, "Add app", Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Add app", color = theme.subText, fontSize = 14.sp)
    }
}

@Composable
private fun DialogCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.width(560.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xF0181A20)).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun MenuRow(label: String, theme: VanillaTheme, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
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
                add("reset" to "Reset Home to default")
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
