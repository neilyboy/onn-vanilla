@file:OptIn(ExperimentalTvMaterial3Api::class)

package tv.vanilla.launcher

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage

@Composable
private fun StationLogo(s: Station?, theme: VanillaTheme, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(theme.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Radio, null, tint = theme.text, modifier = Modifier.size(size * 0.5f))
        if (s != null && s.logo.isNotBlank()) AsyncImage(s.logo, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** Grid tile: what's playing. Zoomed: genre chips and a station grid; OK plays, OK again stops. */
@Composable
fun RadioWidget(a: MainActivity, theme: VanillaTheme) {
    val on = a.radioOn.value
    val playing = a.radioPlaying.value
    val list = a.radioList.value
    if (!LocalZoom.current) Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StationLogo(on, theme, 40.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(on?.name ?: "Not playing", color = theme.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (on == null) "Press OK to pick a station" else if (playing) "Playing · OK for stations" else "Paused", color = theme.subText, fontSize = 12.sp, maxLines = 1)
            }
        }
        if (on == null) list.take(3).forEach { Text("· ${it.name}", color = theme.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
    }
    if (!LocalZoom.current) return
    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(4.dp)) {
            items(RADIO_GENRES.entries.toList(), key = { it.key }) { (tag, label) ->
                val sel = a.radioGenre.value == tag
                Surface(
                    onClick = { a.loadRadio(tag) },
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (sel) theme.accent.copy(alpha = 0.3f) else theme.tile, focusedContainerColor = theme.accent,
                        contentColor = theme.text, focusedContentColor = Color.Black,
                    ),
                ) { Text(label, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (list.isEmpty()) Text("Loading stations…", color = theme.subText, fontSize = 16.sp)
        LazyVerticalGrid(GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(4.dp)) {
            items(list, key = { it.url }) { s ->
                val cur = on?.url == s.url
                Card(theme, 360.dp, 76.dp, onClick = { a.playRadio(s) }) {
                    Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        StationLogo(s, theme, 52.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, color = theme.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (cur) (if (playing) "▶ Playing · OK to stop" else "Starting…") else s.tags, color = if (cur) theme.accent else theme.subText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Every Vanilla box on the network and what it's watching. Zoomed: a mini guide. Pick which TVs (this one, any others,
 * or all), then a channel; several TVs buffer it and start playing at the same moment.
 */
@Composable
fun RoomsWidget(a: MainActivity, theme: VanillaTheme) {
    val peers = a.rooms.peers.value.sortedBy { it.name }
    val zoom = LocalZoom.current
    if (!zoom) {
        if (peers.isEmpty()) {
            Text("No other Vanilla boxes found yet. Set up another TV and it shows up here.", color = theme.subText, fontSize = 12.sp)
            return
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            peers.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tv, null, tint = if (p.playing.isNotBlank()) theme.accent else theme.subText, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(p.name, color = theme.text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                    Text(p.playing.ifBlank { "Not watching" }, color = theme.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                }
            }
        }
        return
    }
    val here = "here"
    val picked = remember { mutableStateListOf(here) }
    val glance = a.glance.value
    val groups = buildList {
        val same = peers.filter { it.playing.isNotBlank() }.map { GChan(it.playing, "", it.logo, "On ${it.name}", 0f) }.distinctBy { it.name }
        if (same.isNotEmpty()) add(GGroup("On other TVs", same))
        if (!glance?.onNow.isNullOrEmpty()) add(GGroup("On now", glance!!.onNow))
        addAll(glance?.guide.orEmpty())
    }
    var group by remember { mutableStateOf(0) }
    fun send(ch: String) {
        val to = peers.filter { it.host in picked }
        val n = to.size + if (here in picked) 1 else 0
        if (n == 0) { Toast.makeText(a, "Pick at least one TV first", Toast.LENGTH_SHORT).show(); return }
        val startIn = if (n > 1) 4000L else 0L
        a.rooms.send(to, ch, startIn)
        if (here in picked) openLive(a, "tune", ch, startIn)
        Toast.makeText(a, if (n > 1) "Starting $ch on $n TVs together" else "Sent $ch to ${to.firstOrNull()?.name ?: "this TV"}", Toast.LENGTH_SHORT).show()
    }
    @Composable
    fun Target(id: String, name: String, sub: String) {
        val on = id in picked
        Card(theme, 230.dp, 62.dp, onClick = { if (on) picked.remove(id) else picked.add(id) }) {
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (on) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, tint = if (on) theme.accent else theme.subText, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(name, color = if (on) theme.accent else theme.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, color = theme.subText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("WATCH ON", color = theme.subText, fontSize = 12.sp, letterSpacing = 2.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
            item { Target(here, "This TV", a.rooms.myName) }
            items(peers, key = { it.host }) { p -> Target(p.host, p.name, p.playing.ifBlank { "Not watching" }) }
            if (peers.size > 1) item {
                val all = peers.all { it.host in picked } && here in picked
                Card(theme, 150.dp, 62.dp, onClick = { picked.clear(); if (!all) { picked.add(here); picked.addAll(peers.map { it.host }) } }) {
                    Text(if (all) "None" else "All TVs", color = theme.text, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
        if (peers.isEmpty()) Text("Other Vanilla boxes on your network show up here.", color = theme.subText, fontSize = 13.sp)
        if (groups.isEmpty()) {
            Text("Open Live TV once and its guide shows up here.", color = theme.subText, fontSize = 14.sp)
            return@Column
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(4.dp)) {
            items(groups.size) { i ->
                val on = i == group.coerceIn(groups.indices)
                Surface(
                    onClick = { group = i },
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    colors = ClickableSurfaceDefaults.colors(containerColor = if (on) theme.accent else theme.panel, contentColor = if (on) Color.Black else theme.text,
                        focusedContainerColor = theme.text, focusedContentColor = Color.Black),
                ) { Text(groups[i].name, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
            }
        }
        val chans = groups[group.coerceIn(groups.indices)].chans
        LazyVerticalGrid(GridCells.Adaptive(250.dp), Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
            items(chans, key = { it.name }) { c ->
                Card(theme, 250.dp, 72.dp, onClick = { send(c.name) }) {
                    Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (c.logo.isNotBlank()) AsyncImage(c.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, color = theme.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.title, color = theme.subText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.progress > 0f) Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(theme.subText.copy(alpha = 0.25f))) {
                                Box(Modifier.fillMaxWidth(c.progress).fillMaxHeight().background(theme.accent))
                            }
                        }
                    }
                }
            }
        }
        Text(if (picked.size > 1) "Picked TVs buffer the channel and start playing together." else "Pick more TVs above to start them together.", color = theme.subText, fontSize = 12.sp)
    }
}

/** Small "now playing" pill next to the dock while the radio is on. OK stops it. */
@Composable
fun RadioPill(a: MainActivity, theme: VanillaTheme) {
    val s = a.radioOn.value ?: return
    Surface(
        onClick = { a.playRadio(null) },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = theme.panel, focusedContainerColor = theme.accent, contentColor = theme.text, focusedContentColor = Color.Black),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, theme.edge), shape = CircleShape)),
    ) {
        Row(Modifier.padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            StationLogo(s, theme, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(s.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                Text(if (a.radioPlaying.value) "Playing · OK to stop" else "Paused · OK to stop", fontSize = 10.sp)
            }
        }
    }
}
