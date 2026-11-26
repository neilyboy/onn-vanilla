@file:OptIn(ExperimentalTvMaterial3Api::class)

package tv.vanilla.launcher

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

val EDIT_COLOR = Color(0xFFFFD54A)

fun themeShape(t: VanillaTheme): Shape =
    if (t.style == "neon") CutCornerShape(topEnd = 16.dp, bottomStart = 16.dp) else RoundedCornerShape(t.radius.dp)

/** Big clock and date on the left, weather and the week's forecast on the right. */
@Composable
fun Header(cfg: LauncherConfig, theme: VanillaTheme, weather: Weather?, greeting: String, extra: @Composable ColumnScope.() -> Unit = {}) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(1000L - System.currentTimeMillis() % 1000L) } }
    val timeFmt = remember(cfg.clock24h) { DateTimeFormatter.ofPattern(if (cfg.clock24h) "HH:mm" else "h:mm") }
    val dateFmt = remember { DateTimeFormatter.ofPattern("EEEE, MMMM d") }
    val clockFont = if (theme.monoClock) FontFamily.Monospace else FontFamily.Default
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(now.format(timeFmt), color = if (theme.style == "neon") theme.accent else theme.text, fontSize = 64.sp, fontWeight = FontWeight.Light, fontFamily = clockFont)
                if (!cfg.clock24h) Text(if (now.hour < 12) " AM" else " PM", color = theme.subText, fontSize = 18.sp, modifier = Modifier.padding(bottom = 12.dp))
            }
            Text(now.format(dateFmt) + if (greeting.isNotBlank()) "  ·  $greeting" else "", color = theme.subText, fontSize = 16.sp)
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            if (cfg.hasWeather && weather != null) {
                val unit = if (cfg.fahrenheit) "°" else "°C"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(weatherIcon(weather.codeNow, weather.isDay), null, tint = theme.text, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("${weather.tempNow}$unit", color = theme.text, fontSize = 38.sp, fontWeight = FontWeight.Light)
                }
                val label = listOfNotNull(weatherLabel(weather.codeNow).ifBlank { null }, cfg.locationName.ifBlank { null }).joinToString(" · ")
                Text(label, color = theme.subText, fontSize = 14.sp)
                if (cfg.showForecast) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    weather.days.take(7).forEach { d ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(d.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()).uppercase(), color = theme.subText, fontSize = 10.sp)
                            Icon(weatherIcon(d.code, true), null, tint = theme.text, modifier = Modifier.padding(vertical = 2.dp).size(14.dp))
                            Text("${d.high}°", color = theme.text, fontSize = 11.sp)
                        }
                    }
                }
            }
            extra()
        }
    }
}

/** One widget on the grid. OK zooms it full screen, holding OK opens its menu. */
@Composable
fun WidgetFrame(
    id: String, a: MainActivity, cfg: LauncherConfig, theme: VanillaTheme, wide: Boolean, modifier: Modifier,
    editing: Int = 0, onZoom: () -> Unit, onMenu: () -> Unit,
) {
    val shape = themeShape(theme)
    Surface(
        onClick = onZoom,
        onLongClick = onMenu,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = theme.panel,
            focusedContainerColor = theme.panel.copy(alpha = (theme.panel.alpha + 0.12f).coerceAtMost(1f)),
            contentColor = theme.text, focusedContentColor = theme.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (editing > 0) 1f else 1.025f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(if (editing > 0) 3.dp else 1.dp, if (editing > 0) EDIT_COLOR else theme.edge), shape = shape),
            focusedBorder = Border(BorderStroke(if (editing > 0) 3.dp else 2.dp, if (editing > 0) EDIT_COLOR else theme.accent), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = if (theme.glow.alpha > 0f && editing == 0) Glow(theme.glow, 16.dp) else Glow.None),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    widgetName(id).uppercase(), color = if (theme.style == "neon") theme.accent else theme.subText, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, fontFamily = if (theme.style == "neon") FontFamily.Monospace else FontFamily.Default,
                    modifier = Modifier.weight(1f), maxLines = 1,
                )
                if (editing > 0) Text(
                    if (editing == 2) "RESIZE" else "MOVE", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).background(EDIT_COLOR).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                CompositionLocalProvider(LocalPreview provides true, LocalFramed provides true) {
                    if (widgetEmpty(id, a)) Text("Nothing right now", color = theme.subText, fontSize = 13.sp)
                    else Widget(id, a, cfg, theme, wide)
                }
            }
        }
    }
}

@Composable
fun WidgetGrid(
    a: MainActivity, cfg: LauncherConfig, theme: VanillaTheme, cells: List<Cell>, edit: Pair<String, Boolean>?,
    modifier: Modifier, onZoom: (String) -> Unit, onMenu: (String) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val gap = 14.dp
        val cw = (maxWidth - gap * (COLS - 1)) / COLS
        val ch = (maxHeight - gap * (ROWS - 1)) / ROWS
        if (edit != null) for (x in 0 until COLS) for (y in 0 until ROWS) {
            Box(Modifier.offset((cw + gap) * x, (ch + gap) * y).size(cw, ch).border(1.dp, theme.text.copy(alpha = 0.1f), RoundedCornerShape(8.dp)))
        }
        cells.sortedWith(compareBy({ it.y }, { it.x })).forEach { c ->
            key(c.id) {
                val x by animateDpAsState((cw + gap) * c.x, label = "x")
                val y by animateDpAsState((ch + gap) * c.y, label = "y")
                val w by animateDpAsState(cw * c.w + gap * (c.w - 1), label = "w")
                val h by animateDpAsState(ch * c.h + gap * (c.h - 1), label = "h")
                WidgetFrame(
                    c.id, a, cfg, theme, c.w >= 6, Modifier.offset(x, y).size(w, h),
                    editing = if (edit?.first == c.id) (if (edit.second) 2 else 1) else 0,
                    onZoom = { onZoom(c.id) }, onMenu = { onMenu(c.id) },
                )
            }
        }
    }
}

/** A widget full screen, with its cards, toggles and posters selectable. */
@Composable
fun ZoomView(id: String, a: MainActivity, cfg: LauncherConfig, theme: VanillaTheme, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val req = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(200); runCatching { req.requestFocus() } }
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(theme.background)).background(Color.Black.copy(alpha = 0.25f)).padding(horizontal = 64.dp, vertical = 48.dp)) {
            Column {
                Text(widgetName(id), color = theme.text, fontSize = 32.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.height(20.dp))
                Box(Modifier.weight(1f).fillMaxWidth().focusRequester(req).focusGroup()) {
                    CompositionLocalProvider(LocalFramed provides true, LocalZoom provides true) {
                        if (widgetEmpty(id, a)) Text("Nothing right now", color = theme.subText, fontSize = 18.sp)
                        else Widget(id, a, cfg, theme, true)
                    }
                }
                Text("Back to close", color = theme.subText, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun WidgetMenu(id: String, theme: VanillaTheme, onDismiss: () -> Unit, onAction: (String) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        DialogCard {
            Text(widgetName(id), color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(bottom = 6.dp))
            listOf("zoom" to "Open full screen", "edit" to "Move or resize", "remove" to "Remove from Home", "add" to "Add a widget", "settings" to "Home settings", "cancel" to "Cancel")
                .forEachIndexed { i, (k, label) -> MenuRow(label, theme, if (i == 0) Modifier.focusRequester(first) else Modifier) { if (k == "cancel") onDismiss() else onAction(k) } }
        }
    }
}

@Composable
fun AddWidgetDialog(on: List<String>, theme: VanillaTheme, onDone: (WidgetInfo?) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
    val left = CATALOG.filter { it.id !in on }
    Dialog(onDismissRequest = { onDone(null) }) {
        DialogCard {
            Text("Add a widget", color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(bottom = 6.dp))
            if (left.isEmpty()) MenuRow("Every widget is already on Home", theme, Modifier.focusRequester(first)) { onDone(null) }
            left.forEachIndexed { i, w -> MenuRow("${w.name}   ·   ${w.w}×${w.h}", theme, if (i == 0) Modifier.focusRequester(first) else Modifier) { onDone(w) } }
        }
    }
}

@Composable
fun EditBar(resize: Boolean, modifier: Modifier) {
    Row(modifier.clip(CircleShape).background(EDIT_COLOR).padding(horizontal = 26.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(if (resize) "Resizing" else "Moving", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(if (resize) "◀ ▶ ▲ ▼ size  ·  OK move  ·  Back done" else "◀ ▶ ▲ ▼ move  ·  OK resize  ·  Back done", color = Color.Black.copy(alpha = 0.75f), fontSize = 16.sp)
    }
}

/** While one of your teams is playing, Home turns into a game hub. */
@Composable
fun GameDay(g: GGame, a: MainActivity, cfg: LauncherConfig, theme: VanillaTheme, focus: FocusRequester?, modifier: Modifier, onZoom: (String) -> Unit, onMenu: (String) -> Unit) {
    val ctx = LocalContext.current
    Column(modifier) {
        Text("GAME DAY  ·  ${g.away.name.uppercase()} at ${g.home.name.uppercase()}", color = theme.subText, fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 10.dp))
        Surface(
            onClick = { openLive(ctx, "tune", "game:" + g.home.abbr) },
            modifier = Modifier.fillMaxWidth().weight(1.25f).then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(26.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF111111), focusedContainerColor = Color(0xFF111111)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(26.dp))),
        ) {
            val ca = teamColor(g.away.color, Color(0xFF333333)); val chome = teamColor(g.home.color, Color(0xFF333333))
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(ca, Color(0xFF101010), Color(0xFF101010), chome)))) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TeamBlock(g.away)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("LIVE", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(CircleShape).background(Color(0xFFE5484D)).padding(horizontal = 14.dp, vertical = 4.dp))
                        Text(g.detail, color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(top = 14.dp))
                        if (g.network.isNotBlank()) Text(g.network, color = Color(0xB3FFFFFF), fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
                        Text("Press OK to watch", color = Color(0x99FFFFFF), fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp))
                    }
                    TeamBlock(g.home)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        val rest = listOf("scores", "onnow") + a.cells.map { it.id }.filter { it != "scores" && it != "onnow" }.take(1)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            rest.forEach { id -> WidgetFrame(id, a, cfg, theme, false, Modifier.weight(1f).fillMaxHeight(), onZoom = { onZoom(id) }, onMenu = { onMenu(id) }) }
        }
    }
}

@Composable
private fun TeamBlock(t: GTeam) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (t.logo.isNotBlank()) AsyncImage(t.logo, null, modifier = Modifier.size(84.dp))
        Text(t.score.ifBlank { "0" }, color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold)
        Text(t.name, color = Color.White, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The app row: small icons, the name shows on the one you're on. */
@Composable
fun Dock(
    apps: List<AppEntry>, a: MainActivity, theme: VanillaTheme, size: Dp, firstTile: FocusRequester, showGear: Boolean,
    onLaunch: (AppEntry) -> Unit, onLongApp: (AppEntry) -> Unit, onAdd: () -> Unit, onGear: () -> Unit, onGearLong: () -> Unit,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val shape = if (theme.style == "neon") RoundedCornerShape(8.dp) else RoundedCornerShape(size * 0.45f)
        Row(
            Modifier.clip(shape).background(theme.panel).border(1.dp, theme.edge, shape).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top,
        ) {
            apps.forEachIndexed { i, app ->
                key(app.id) {
                    AppTile(app, theme, a, size, if (i == 0) Modifier.focusRequester(firstTile) else Modifier, onClick = { onLaunch(app) }, onLongClick = { onLongApp(app) })
                }
            }
            AddTile(theme, size, if (apps.isEmpty()) Modifier.focusRequester(firstTile) else Modifier, onAdd)
            if (showGear) GearTile(theme, size, onGear, onGearLong)
        }
    }
}

/** Background touches per theme: soft colour blobs (glass), drifting light (aurora), a HUD grid (neon). */
@Composable
fun ThemeDecor(theme: VanillaTheme) {
    val drift = if (theme.animated) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "aurora")
        t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(24_000, easing = androidx.compose.animation.core.LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "d").value
    } else 0f
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        fun blob(c: Color, x: Float, y: Float, r: Float) =
            drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), center = androidx.compose.ui.geometry.Offset(x, y), radius = r), r, androidx.compose.ui.geometry.Offset(x, y))
        when {
            theme.style == "neon" -> {
                val step = 60.dp.toPx(); val line = theme.accent.copy(alpha = 0.05f)
                var x = 0f; while (x < w) { drawLine(line, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, h)); x += step }
                var y = 0f; while (y < h) { drawLine(line, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(w, y)); y += step }
                blob(Color(0x553A0A5A), w * 0.5f, h * 1.1f, w * 0.55f)
            }
            theme.animated -> {
                blob(theme.accent.copy(alpha = 0.28f), w * (0.15f + 0.5f * drift), h * 0.25f, w * 0.45f)
                blob(Color(0x447B5CFF), w * (0.85f - 0.4f * drift), h * (0.75f - 0.2f * drift), w * 0.4f)
                blob(Color(0x3329B6F6), w * 0.5f, h * (0.1f + 0.3f * drift), w * 0.35f)
            }
            theme.style == "glass" -> {
                blob(theme.background[0].let { Color((it.red * 1.6f).coerceAtMost(1f), (it.green * 1.4f).coerceAtMost(1f), (it.blue * 1.3f).coerceAtMost(1f), 0.8f) }, w * 0.15f, h * 0.2f, w * 0.45f)
                blob(theme.background[2].copy(alpha = 0.8f), w * 0.85f, h * 0.8f, w * 0.45f)
                blob(theme.accent.copy(alpha = 0.12f), w * 0.6f, h * 0.3f, w * 0.35f)
            }
        }
    }
}
