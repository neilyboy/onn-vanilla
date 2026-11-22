@file:OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tv.vanilla.launcher

import androidx.compose.animation.Crossfade
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Widgets placed at [pos] (left, center or right), in the order the setup app listed them. */
@Composable
fun WidgetColumn(activity: MainActivity, cfg: LauncherConfig, theme: VanillaTheme, pos: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        cfg.widgets.filter { it.pos == pos }.forEach { w -> Widget(w.id, activity, cfg, theme, pos == "center") }
    }
}

@Composable
private fun Widget(id: String, activity: MainActivity, cfg: LauncherConfig, theme: VanillaTheme, wide: Boolean) {
    val g = activity.glance.value
    when (id) {
        "onnow" -> g?.onNow?.takeIf { it.isNotEmpty() }?.let { OnNow(it, theme) }
        "scores" -> g?.games?.takeIf { it.isNotEmpty() }?.let { Scores(it, theme) }
        "resume" -> g?.resume?.takeIf { it.isNotEmpty() }?.let { Posters("Continue watching", it.map { r -> Triple(r.title, r.poster, r.progress) }, theme) { i -> openLive(activity, "vod_key", it[i].key) } }
        "newvod" -> g?.newVod?.takeIf { it.isNotEmpty() }?.let { Posters("New on Movies & Series", it.map { v -> Triple(v.title, v.poster, -1f) }, theme) { i -> openLive(activity, "vod_id", (if (it[i].series) "s:" else "m:") + it[i].id) } }
        "calendar" -> Week(activity.agenda.value, cfg.countdowns, theme, wide)
        "news" -> NewsCards(activity.news.value, theme, wide)
        "ha" -> activity.ha.value.filter { it.domain != "camera" }.takeIf { it.isNotEmpty() }?.let { HomePanel(it, activity, theme) }
        "cameras" -> Cameras(activity.ha.value.filter { it.domain == "camera" }, g?.cameras.orEmpty(), activity, cfg, theme)
        "weather" -> activity.weather.value?.let { HourlyRadar(it, activity.radar.value, cfg, theme, wide) }
    }
}

@Composable
private fun Title(text: String, theme: VanillaTheme) =
    Text(text.uppercase(), color = theme.subText, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))

@Composable
private fun Panel(theme: VanillaTheme, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(theme.scrim.copy(alpha = 0.35f)).padding(14.dp), content = content)

@Composable
private fun Card(theme: VanillaTheme, width: Dp, height: Dp, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = tileColors(theme),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, theme.accent))),
        modifier = Modifier.size(width, height),
    ) { Box(Modifier.fillMaxSize(), content = content) }
}

@Composable
private fun Progress(p: Float, theme: VanillaTheme, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(theme.text.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().background(theme.accent))
    }
}

@Composable
private fun OnNow(list: List<GChan>, theme: VanillaTheme) {
    val ctx = LocalContext.current
    Column {
        Title("On now", theme)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
            items(list, key = { it.name }) { c ->
                Card(theme, 230.dp, 74.dp, onClick = { openLive(ctx, "tune", c.name) }) {
                    Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                            if (c.logo.isNotBlank()) AsyncImage(c.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
                            else Text(c.name.take(2), color = theme.text, fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, color = theme.subText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.title.ifBlank { "Live" }, color = theme.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.progress > 0) Progress(c.progress, theme, Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun teamColor(hex: String, fallback: Color) = runCatching { Color(android.graphics.Color.parseColor("#" + hex.removePrefix("#"))) }.getOrDefault(fallback)

@Composable
private fun Scores(list: List<GGame>, theme: VanillaTheme) {
    val ctx = LocalContext.current
    val time = remember { DateTimeFormatter.ofPattern("EEE h:mm a") }
    Column {
        Title("Scores", theme)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
            items(list, key = { it.id }) { g ->
                Card(theme, 200.dp, 74.dp, onClick = { openLive(ctx, "tune", "game:" + g.home.abbr) }) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
                        listOf(g.away, g.home).forEach { t ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp, 14.dp).clip(RoundedCornerShape(2.dp)).background(teamColor(t.color, theme.accent)))
                                Spacer(Modifier.width(6.dp))
                                if (t.logo.isNotBlank()) AsyncImage(t.logo, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(t.abbr, color = theme.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (g.state != "pre") Text(t.score, color = theme.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        val detail = if (g.state == "pre" && g.start > 0) java.time.Instant.ofEpochMilli(g.start).atZone(ZoneId.systemDefault()).format(time) else g.detail
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (g.state == "in") Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFE53935)))
                            if (g.state == "in") Spacer(Modifier.width(4.dp))
                            Text(listOf(detail, g.network).filter { it.isNotBlank() }.joinToString(" · "), color = theme.subText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Posters(title: String, list: List<Triple<String, String, Float>>, theme: VanillaTheme, onClick: (Int) -> Unit) {
    Column {
        Title(title, theme)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
            items(list.size) { i ->
                val (name, poster, progress) = list[i]
                Column(Modifier.width(104.dp)) {
                    Card(theme, 104.dp, 152.dp, onClick = { onClick(i) }) {
                        if (poster.isNotBlank()) AsyncImage(poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Text(name, color = theme.text, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                        if (progress >= 0f) Progress(progress, theme, Modifier.align(Alignment.BottomCenter).padding(6.dp))
                    }
                    Text(name, color = theme.subText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
                }
            }
        }
    }
}

/** Days until each countdown: built-in US holidays plus "Name|MM-DD" or "Name|YYYY-MM-DD" lines from the setup app. */
fun countdowns(custom: List<String>, today: LocalDate = LocalDate.now()): List<Pair<String, Long>> {
    fun next(month: Int, day: Int): LocalDate = LocalDate.of(today.year, month, day).let { if (it.isBefore(today)) it.plusYears(1) else it }
    fun nthWeekday(year: Int, month: Int, dow: java.time.DayOfWeek, n: Int) =
        LocalDate.of(year, month, 1).with(java.time.temporal.TemporalAdjusters.dayOfWeekInMonth(n, dow))
    val thanks = nthWeekday(today.year, 11, java.time.DayOfWeek.THURSDAY, 4).let { if (it.isBefore(today)) nthWeekday(today.year + 1, 11, java.time.DayOfWeek.THURSDAY, 4) else it }
    val built = listOf("New Year's Day" to next(1, 1), "Valentine's Day" to next(2, 14), "Independence Day" to next(7, 4), "Halloween" to next(10, 31), "Thanksgiving" to thanks, "Christmas" to next(12, 25))
    val mine = custom.mapNotNull { line ->
        val name = line.substringBefore('|').trim(); val d = line.substringAfter('|', "").trim()
        runCatching {
            if (d.length == 5) name to next(d.take(2).toInt(), d.takeLast(2).toInt()) else name to LocalDate.parse(d)
        }.getOrNull()?.takeIf { name.isNotBlank() && !it.second.isBefore(today) }
    }
    return (mine + built).map { it.first to ChronoUnit.DAYS.between(today, it.second) }.sortedBy { it.second }
}

@Composable
private fun Week(events: List<CalEvent>, custom: List<String>, theme: VanillaTheme, wide: Boolean) {
    val today = LocalDate.now()
    val timeFmt = remember { DateTimeFormatter.ofPattern("h:mm a") }
    val dayFmt = remember { DateTimeFormatter.ofPattern("EEEE, MMM d") }
    val counts = remember(custom, today) { countdowns(custom, today).take(if (wide) 4 else 3) }
    Panel(theme) {
        Title("This week", theme)
        val byDay = events.groupBy { it.start.toLocalDate() }.toSortedMap().entries.take(5)
        if (byDay.isEmpty()) Text("Nothing on the calendar", color = theme.subText, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
        byDay.forEach { (d, list) ->
            val label = when (d) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> d.format(dayFmt) }
            Text(label, color = theme.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            list.take(4).forEach { e ->
                Row(Modifier.padding(start = 4.dp, top = 2.dp)) {
                    Text(if (e.allDay) "All day" else e.start.format(timeFmt), color = theme.subText, fontSize = 11.sp, modifier = Modifier.width(64.dp))
                    Text(e.title, color = theme.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (counts.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                counts.forEach { (name, days) ->
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(theme.text.copy(alpha = 0.07f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(if (days == 0L) "Today" else "$days ${if (days == 1L) "day" else "days"}", color = theme.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(name, color = theme.subText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsCards(items: List<Headline>, theme: VanillaTheme, wide: Boolean) {
    if (items.isEmpty()) return
    val withImg = items.filter { it.image.isNotBlank() }.ifEmpty { items }
    var i by remember(items) { mutableIntStateOf(0) }
    LaunchedEffect(items) { while (true) { delay(10_000); i++ } }
    val lead = withImg[i % withImg.size]
    Panel(theme) {
        Title("Headlines", theme)
        Crossfade(lead, label = "lead") { h ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (h.image.isNotBlank()) {
                    AsyncImage(h.image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(if (wide) 200.dp else 112.dp, if (wide) 112.dp else 63.dp).clip(RoundedCornerShape(10.dp)))
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    Text(h.source.uppercase(), color = theme.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(h.title, color = theme.text, fontSize = if (wide) 16.sp else 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val rest = (1..2).map { withImg[(i + it) % withImg.size] }.distinct().filter { it != lead }
        rest.forEach { h ->
            Row(Modifier.padding(top = 8.dp)) {
                Text(h.source.take(14).uppercase(), color = theme.subText, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(78.dp).padding(top = 2.dp))
                Text(h.title, color = theme.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HourlyRadar(w: Weather, radar: String?, cfg: LauncherConfig, theme: VanillaTheme, wide: Boolean) {
    val hourFmt = remember { DateTimeFormatter.ofPattern("ha") }
    Panel(theme) {
        Title("Next 12 hours", theme)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            w.hours.filterIndexed { i, _ -> i % 2 == 0 }.take(7).forEach { h ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(h.time.format(hourFmt).lowercase(), color = theme.subText, fontSize = 10.sp)
                    Icon(weatherIcon(h.code, h.time.hour in 7..18), null, tint = theme.text, modifier = Modifier.size(18.dp).padding(vertical = 2.dp))
                    Text("${h.temp}°", color = theme.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(if (h.rain >= 10) "${h.rain}%" else " ", color = Color(0xFF64B5F6), fontSize = 9.sp)
                }
            }
        }
        if (radar != null && cfg.hasWeather) {
            Spacer(Modifier.height(10.dp))
            Radar(cfg.latitude, cfg.longitude, radar, if (wide) 220.dp else 150.dp)
            Text("Radar: RainViewer · Map: Esri", color = theme.subText.copy(alpha = 0.6f), fontSize = 8.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private const val ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas"

/** A 3x3 map of zoom-7 tiles with the RainViewer radar on top, centered on the home location. */
@Composable
private fun Radar(lat: Double, lon: Double, radarBase: String, height: Dp) {
    val z = 7
    val n = 1 shl z
    val fx = (lon + 180) / 360 * n
    val fy = (1 - ln(tan(lat * PI / 180) + 1 / cos(lat * PI / 180)) / PI) / 2 * n
    val tx = floor(fx).toInt(); val ty = floor(fy).toInt()
    val tile = 128.dp
    BoxWithConstraints(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B1F27))) {
        val ox = maxWidth / 2 - tile * (1 + (fx - tx)).toFloat()
        val oy = maxHeight / 2 - tile * (1 + (fy - ty)).toFloat()
        Box(Modifier.offset(ox, oy).requiredSize(tile * 3)) {
            for (dy in -1..1) for (dx in -1..1) {
                val x = ((tx + dx) % n + n) % n; val y = ty + dy
                val m = Modifier.offset(tile * (dx + 1), tile * (dy + 1)).size(tile)
                AsyncImage("$ESRI/World_Dark_Gray_Base/MapServer/tile/$z/$y/$x", null, modifier = m)
                AsyncImage("$radarBase/256/$z/$x/$y/2/1_1.png", null, modifier = m.graphicsLayer { alpha = 0.85f })
                AsyncImage("$ESRI/World_Dark_Gray_Reference/MapServer/tile/$z/$y/$x", null, modifier = m)
            }
        }
        Box(Modifier.align(Alignment.Center).size(8.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun HomePanel(list: List<HaEntity>, activity: MainActivity, theme: VanillaTheme) {
    Column {
        Title("Home", theme)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { e ->
                val value = if (e.unit.isNotBlank()) "${e.state.toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) } ?: e.state}${e.unit}" else e.state.replace('_', ' ')
                val body: @Composable BoxScope.() -> Unit = {
                    Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (e.toggleable) Box(Modifier.size(8.dp).clip(CircleShape).background(if (e.on) theme.accent else theme.subText.copy(alpha = 0.4f)))
                        if (e.toggleable) Spacer(Modifier.width(8.dp))
                        Column {
                            Text(e.name, color = theme.subText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(value, color = theme.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
                if (e.toggleable) Card(theme, 168.dp, 48.dp, onClick = { activity.toggleHa(e) }, content = body)
                else Box(Modifier.size(168.dp, 48.dp).clip(RoundedCornerShape(12.dp)).background(theme.scrim.copy(alpha = 0.35f)), content = body)
            }
        }
    }
}

@Composable
private fun Cameras(haCams: List<HaEntity>, liveCams: List<GCam>, activity: MainActivity, cfg: LauncherConfig, theme: VanillaTheme) {
    if (haCams.isEmpty() && liveCams.isEmpty()) return
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); tick++ } }
    Column {
        Title("Cameras", theme)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
            items(haCams, key = { it.id }) { e ->
                val live = liveCams.firstOrNull { it.name.equals(e.name, true) }
                Card(theme, 192.dp, 108.dp, onClick = { live?.let { openLive(ctx, "tune", it.name) } }) {
                    AsyncImage(
                        coil.request.ImageRequest.Builder(ctx).data(haCameraUrl(cfg, e.id) + "?t=$tick").addHeader("Authorization", "Bearer ${cfg.haToken}")
                            .memoryCacheKey(e.id).placeholderMemoryCacheKey(e.id).build(),
                        null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                    Text(e.name, color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            items(liveCams.filter { c -> haCams.none { it.name.equals(c.name, true) } }, key = { "live:" + it.name }) { c ->
                Card(theme, 192.dp, 108.dp, onClick = { openLive(ctx, "tune", c.name) }) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Videocam, null, tint = theme.text, modifier = Modifier.size(28.dp))
                        Text(c.name, color = theme.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp))
                    }
                }
            }
        }
    }
}
