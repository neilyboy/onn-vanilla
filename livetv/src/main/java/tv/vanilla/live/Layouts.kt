@file:OptIn(ExperimentalTvMaterial3Api::class)

package tv.vanilla.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

/** Channel groups in guide order: the profile's groups first, then sports or general first depending on the profile. */
private fun orderedGroups(a: MainActivity, c: LiveConfig?, chans: List<Channel>): List<String> {
    val profile = a.profile.value
    val showAdult = a.look.value.adult && !profile.kids
    val all = chans.map { it.group }.distinct().filter { showAdult || !isAdultGroup(it) }
    val keys = c?.sportsGroupKeywords ?: emptyList()
    val preferred = profile.groups.filter { it in all }
    val sports = all.filter { g -> g !in preferred && keys.any { g.contains(it, true) } }
    val general = all - preferred.toSet() - sports.toSet()
    return preferred + if (profile.sports) sports + general else general + sports
}

/** A minute tick so "now" rows and progress bars move along. */
@Composable
private fun minuteTick(): Int {
    var t by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); t++ } }
    return t
}

/** River: no grid. Rows of what's on right now: just started, starting soon, then one row per group. */
@Composable
fun RiverTab(a: MainActivity, c: LiveConfig?, accent: Color) {
    val chans = a.shownChannels.value
    val guide = a.epg.value
    val favs = a.favorites.value
    val tick = minuteTick()
    val rows = remember(chans, guide, favs, tick, a.profile.value) {
        val now = System.currentTimeMillis()
        val placeholder = Regex("""off ?air|no program|no info|no event|scoreboard final""", RegexOption.IGNORE_CASE)
        val airing = chans.mapNotNull { ch -> guide[ch.tvgId]?.let { nowNext(it) }?.first?.takeIf { !placeholder.containsMatchIn(it.title) }?.let { ch to it } }
        val started = airing.filter { now - it.second.start in 0..20 * 60_000L }.sortedByDescending { it.second.start }.map { it.first }.take(40)
        val soon = chans.mapNotNull { ch -> guide[ch.tvgId]?.let { nowNext(it) }?.second?.takeIf { it.start - now in 0..30 * 60_000L }?.let { ch } }.take(40)
        val byGroup = airing.groupBy({ it.first.group }, { it.first })
        buildList {
            chans.filter { it.name in favs }.takeIf { it.isNotEmpty() }?.let { add("★ Favorites" to it) }
            if (started.isNotEmpty()) add("Just started" to started)
            if (soon.isNotEmpty()) add("Starting in the next 30 minutes" to soon)
            orderedGroups(a, c, chans).forEach { g -> byGroup[g]?.take(60)?.let { add(g to it) } }
        }
    }
    if (rows.isEmpty()) {
        Text("Loading what's on…", color = Palette.sub, fontSize = 16.sp, modifier = Modifier.padding(48.dp))
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items(rows, key = { it.first }) { (title, list) ->
            Column {
                Text("$title  ·  ${list.size}", color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 48.dp, bottom = 10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(list, key = { it.index }) { ch -> ChannelCard(a, ch, guide[ch.tvgId], accent, Modifier) }
                }
            }
        }
    }
}

/** Surf: one channel at a time, full screen. Up/Down flips channels, Left/Right changes group, OK watches. */
@Composable
fun SurfTab(a: MainActivity, c: LiveConfig?, accent: Color) {
    val chans = a.shownChannels.value
    val guide = a.epg.value
    val favs = a.favorites.value
    val tick = minuteTick()
    val groups = remember(chans, favs, a.profile.value) { (if (favs.isNotEmpty()) listOf("★ Favorites") else emptyList()) + orderedGroups(a, c, chans) }
    var gi by remember { mutableIntStateOf(0) }
    var ci by remember { mutableIntStateOf(0) }
    val group = groups.getOrNull(gi.coerceIn(0, (groups.size - 1).coerceAtLeast(0)))
    val list = remember(chans, group, favs) { if (group == "★ Favorites") chans.filter { it.name in favs } else chans.filter { it.group == group } }
    if (list.isEmpty()) {
        Text("Loading channels…", color = Palette.sub, fontSize = 16.sp, modifier = Modifier.padding(48.dp))
        return
    }
    val ch = list[ci.mod(list.size)]
    val (now, next) = remember(ch, guide, tick) { nowNext(guide[ch.tvgId]) }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(300); runCatching { focus.requestFocus() } }
    val fmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    Box(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 16.dp)
            .focusRequester(focus).onFocusChanged { focused = it.isFocused }.focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionDown, Key.ChannelDown -> { ci = (ci + 1).mod(list.size); true }
                    Key.DirectionUp, Key.ChannelUp -> { if (ci == 0) false else { ci--; true } }
                    Key.DirectionRight -> { gi = (gi + 1).mod(groups.size); ci = 0; true }
                    Key.DirectionLeft -> { gi = (gi - 1).mod(groups.size); ci = 0; true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { a.play(ch); true }
                    else -> false
                }
            }
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = if (focused) 0.35f else 0.2f), Palette.card, Palette.bg))),
    ) {
        Row(Modifier.fillMaxSize().padding(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(320.dp, 220.dp).clip(RoundedCornerShape(24.dp)).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
                if (ch.logo.isNotBlank()) AsyncImage(ch.logo, ch.name, Modifier.fillMaxSize(0.7f), contentScale = ContentScale.Fit)
                else Text(ch.name, color = Palette.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(48.dp))
            Column(Modifier.weight(1f)) {
                Text("${group.orEmpty()}  ·  ${ci.mod(list.size) + 1} of ${list.size}", color = Palette.sub, fontSize = 15.sp)
                Text(listOf(ch.number, ch.name).filter { it.isNotBlank() }.joinToString("  "), color = Palette.text, fontSize = 22.sp, modifier = Modifier.padding(top = 6.dp))
                Text(now?.title ?: "No guide info", color = Palette.text, fontSize = 44.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 18.dp))
                if (now != null) {
                    Text("${fmt.format(now.start)} – ${fmt.format(now.stop)}", color = Palette.sub, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
                    Box(Modifier.padding(top = 12.dp).width(520.dp)) { ProgressBar(now, accent) }
                    if (now.desc.isNotBlank()) Text(now.desc, color = Palette.sub, fontSize = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp).widthIn(max = 900.dp))
                }
                if (next != null) Text("Next  ${fmt.format(next.start)}  ${next.title}", color = Palette.sub, fontSize = 17.sp, modifier = Modifier.padding(top = 20.dp))
                if (ch.name in favs) Text("★ Favorite", color = Color(0xFFFFD166), fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(horizontal = 40.dp, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            (1..4).map { list[(ci + it).mod(list.size)] }.distinct().filter { it != ch }.forEach { n ->
                val (np, _) = nowNext(guide[n.tvgId])
                Column(Modifier.width(220.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x22FFFFFF)).padding(10.dp)) {
                    Text(n.name, color = Palette.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(np?.title ?: "—", color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(if (focused) "▲▼ channels   ◀▶ groups   OK watch" else "Press ▼ to start surfing", color = Palette.sub, fontSize = 14.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 40.dp, vertical = 30.dp))
    }
}
