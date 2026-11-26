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
import androidx.compose.material.icons.outlined.Radio
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

/** Every Vanilla box on the network and what it's watching. Zoomed: watch the same here, or send a channel there. */
@Composable
fun RoomsWidget(a: MainActivity, theme: VanillaTheme) {
    val peers = a.rooms.peers.value
    val zoom = LocalZoom.current
    var sendTo by remember { mutableStateOf<Peer?>(null) }
    if (peers.isEmpty()) {
        Text("No other Vanilla boxes found yet. Set up another TV and it shows up here.", color = theme.subText, fontSize = if (zoom) 16.sp else 12.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(if (zoom) 14.dp else 6.dp)) {
        peers.sortedBy { it.name }.forEach { p ->
            if (!zoom) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tv, null, tint = if (p.playing.isNotBlank()) theme.accent else theme.subText, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(p.name, color = theme.text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                    Text(p.playing.ifBlank { "Not watching" }, color = theme.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                }
                return@forEach
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.width(320.dp)) {
                    Text(p.name, color = theme.text, fontSize = 20.sp)
                    Text(p.playing.ifBlank { "Not watching Live TV" }, color = theme.subText, fontSize = 14.sp)
                }
                if (p.playing.isNotBlank()) Card(theme, 220.dp, 52.dp, onClick = { openLive(a, "tune", p.playing) }) {
                    Text("Watch the same here", color = theme.text, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
                }
                Card(theme, 220.dp, 52.dp, onClick = { sendTo = if (sendTo == p) null else p }) {
                    Text("Send a channel there", color = theme.text, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
                }
            }
            if (sendTo == p) {
                val chans = a.glance.value?.onNow.orEmpty()
                if (chans.isEmpty()) Text("Favorite or watch a few channels in Live TV first; they show up here.", color = theme.subText, fontSize = 14.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
                    items(chans, key = { it.name }) { c ->
                        Card(theme, 220.dp, 64.dp, onClick = {
                            a.rooms.send(p, c.name); sendTo = null
                            Toast.makeText(a, "Sent ${c.name} to ${p.name}", Toast.LENGTH_SHORT).show()
                        }) {
                            Column(Modifier.fillMaxSize().padding(10.dp)) {
                                Text(c.name, color = theme.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(c.title, color = theme.subText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
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
