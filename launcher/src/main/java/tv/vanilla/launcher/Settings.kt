@file:OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tv.vanilla.launcher

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.*
import kotlinx.coroutines.delay

private val SECTIONS = linkedMapOf("look" to "Look", "layout" to "Layout", "widgets" to "Widgets", "ha" to "Home Assistant", "system" to "System")
private val PANEL = Color(0xF20B0D12)

/** Home settings on the TV. The menu is see-through on the right so theme changes show behind it. */
@Composable
fun HomeSettings(a: MainActivity, onEditLayout: () -> Unit, onAdvanced: () -> Unit, onClose: () -> Unit) {
    var section by remember { mutableStateOf("look") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val theme = a.theme
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(280.dp).fillMaxHeight().background(PANEL).padding(horizontal = 22.dp, vertical = 40.dp)) {
                Text("Settings", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, bottom = 24.dp))
                SECTIONS.entries.forEachIndexed { i, (id, label) ->
                    NavRow(label, section == id, theme, if (i == 0) Modifier.focusRequester(first) else Modifier) { section = id }
                }
            }
            LazyColumn(
                Modifier.width(720.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(Color(0xE60B0D12), Color(0xB30B0D12)))),
                contentPadding = PaddingValues(horizontal = 44.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (section) {
                    "look" -> lookSection(a, theme)
                    "layout" -> layoutSection(a, theme, onEditLayout)
                    "widgets" -> widgetsSection(a, theme)
                    "ha" -> haSection(a, theme)
                    "system" -> systemSection(a, theme, onAdvanced, onClose)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.heading(text: String, sub: String = "") = item {
    Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        Text(text, color = Color.White, fontSize = 22.sp)
        if (sub.isNotBlank()) Text(sub, color = Color(0x99FFFFFF), fontSize = 13.sp)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.lookSection(a: MainActivity, theme: VanillaTheme) {
    val look = a.look.value
    val current = look.theme ?: a.config.value?.themeName ?: "glass"
    heading("Theme", "Changes show behind this menu as you move.")
    item {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            themes.forEach { (name, t) -> ThemeCard(t, name == current) { a.setLook(look.copy(theme = name)) } }
        }
    }
    heading("Accent color")
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            ACCENTS.forEach { hex ->
                val c = if (hex.isBlank()) (themes[current] ?: theme).accent else Color(android.graphics.Color.parseColor(hex))
                Surface(
                    onClick = { a.setLook(look.copy(accent = hex)) },
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    colors = ClickableSurfaceDefaults.colors(containerColor = c, focusedContainerColor = c),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(BorderStroke(2.dp, if ((look.accent ?: "") == hex) Color.White else Color.Transparent), shape = CircleShape),
                        focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CircleShape),
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.2f),
                    modifier = Modifier.size(38.dp),
                ) { if (hex.isBlank()) Text("A", color = Color.Black, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center)) }
            }
        }
    }
    heading("App icons")
    item { SetRow("Icon size", ICON_SIZES[look.iconSize] ?: "Small", theme) { a.setLook(look.copy(iconSize = ICON_SIZES.keys.toList().let { it[(it.indexOf(look.iconSize) + 1) % it.size] })) } }
    item { SetRow("Icon style", ICON_STYLES[a.iconStyle] ?: "Original", theme) { a.cycleIconStyle() } }
}

private fun androidx.compose.foundation.lazy.LazyListScope.layoutSection(a: MainActivity, theme: VanillaTheme, onEditLayout: () -> Unit) {
    val look = a.look.value
    val grid = a.preset != "minimal"
    heading("Home layout")
    item { SetRow("Layout", if (grid) "Widgets" else "Minimal (clock and apps)", theme) { a.setLook(look.copy(preset = if (grid) "minimal" else "grid")) } }
    if (grid) item { SetRow("Edit layout", "Move and resize widgets", theme, onClick = onEditLayout) }
    heading("Smart layouts")
    item { SetRow("Game day mode", if (look.gameDay) "On: Home becomes a game hub while your team plays" else "Off", theme) { a.setLook(look.copy(gameDay = !look.gameDay)) } }
    item {
        SetRow("Layouts by time of day", if (look.timeOfDay) "On: editing now changes the ${PERIODS[a.period.value]} layout" else "Off", theme) {
            a.setLook(look.copy(timeOfDay = !look.timeOfDay))
        }
    }
    item { SetRow("Reset layout", "Go back to the setup app's layout", theme) { a.resetLayout() } }
}

private fun androidx.compose.foundation.lazy.LazyListScope.widgetsSection(a: MainActivity, theme: VanillaTheme) {
    heading("Widgets", "Turn widgets on or off. Hold OK on a widget on Home to move or resize it.")
    CATALOG.forEach { w ->
        item(w.id) {
            val on = a.widgetOn(w.id)
            SetRow(w.name, if (on) "On" else "Off", theme) {
                if (on) a.setCells(a.cells.filter { it.id != w.id })
                else {
                    if (a.preset == "minimal") a.setLook(a.look.value.copy(preset = "grid"))
                    val next = placeNew(a.cells, w)
                    if (next == null) Toast.makeText(a, "No room left. Remove a widget first.", Toast.LENGTH_SHORT).show()
                    else a.setCells(next)
                }
            }
        }
    }
}

private val HA_DOMAINS = linkedMapOf(
    "light" to "Lights", "switch" to "Switches and plugs", "fan" to "Fans", "lock" to "Locks", "cover" to "Doors and blinds", "climate" to "Thermostats",
    "camera" to "Cameras", "media_player" to "TVs and speakers", "scene" to "Scenes", "script" to "Scripts", "input_boolean" to "Helpers",
    "vacuum" to "Vacuums", "sensor" to "Sensors", "binary_sensor" to "Status sensors",
)

private fun androidx.compose.foundation.lazy.LazyListScope.haSection(a: MainActivity, theme: VanillaTheme) {
    heading("Home Assistant", "New lights, switches and locks show up on their own. OK shows or hides one on Home.")
    val all = a.haAll.value
    if (all.isEmpty()) { item { SetRow("Not connected", "Turn on the Home widget, or add Home Assistant in the setup app", theme) {} }; return }
    val shown = a.ha.value.map { it.id }.toSet()
    HA_DOMAINS.forEach { (domain, label) ->
        val list = all.filter { it.domain == domain }.sortedBy { it.name.lowercase() }
        if (list.isEmpty()) return@forEach
        heading(label)
        list.forEach { e -> item(e.id) { SetRow(e.name, if (e.id in shown) "Shown" else "Hidden", theme) { a.setHaShown(e.id, e.id !in shown) } } }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.systemSection(a: MainActivity, theme: VanillaTheme, onAdvanced: () -> Unit, onClose: () -> Unit) {
    val stock = HomeButtonService.stockHome(a)
    val stockName = stock?.let { runCatching { a.packageManager.getApplicationLabel(a.packageManager.getApplicationInfo(it.packageName, 0)).toString() }.getOrNull() } ?: "original home"
    heading("System")
    item { SetRow("Android settings", "Wi-Fi, display, remotes", theme) { onClose(); a.openSettings() } }
    if (stock != null) item { SetRow("Open $stockName", "The box's original home screen", theme) { onClose(); HomeButtonService.openStockHome(a) } }
    item {
        var released by remember { mutableStateOf(HomeButtonService.released(a)) }
        SetRow("Home button", if (released) "Opens $stockName" else "Opens Vanilla Home", theme) { released = !released; HomeButtonService.setReleased(a, released) }
    }
    item { SetRow("Config link", "Where this box gets its settings", theme) { onClose(); onAdvanced() } }
}

@Composable
private fun NavRow(label: String, selected: Boolean, theme: VanillaTheme, modifier: Modifier, onFocus: () -> Unit) {
    Surface(
        onClick = onFocus,
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0x26FFFFFF) else Color.Transparent, focusedContainerColor = Color.White,
            contentColor = Color(0xCCFFFFFF), focusedContentColor = Color(0xFF111111),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) { Text(label, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
}

@Composable
private fun SetRow(label: String, value: String, theme: VanillaTheme, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x14FFFFFF), focusedContainerColor = Color.White,
            contentColor = Color.White, focusedContentColor = Color(0xFF111111),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Text(value, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp).widthIn(max = 380.dp), maxLines = 1)
        }
    }
}

@Composable
private fun ThemeCard(t: VanillaTheme, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier.size(196.dp, 118.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color(0x33FFFFFF)), shape = shape),
            focusedBorder = Border(BorderStroke(3.dp, t.accent), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(t.background))) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { i -> Box(Modifier.size(if (i == 0) 64.dp else 40.dp, 40.dp).clip(themeShape(t.copy(radius = 8))).background(t.panel)) }
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(12.dp).size(14.dp).clip(CircleShape).background(t.accent))
            Text(t.label, color = t.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
    }
}
