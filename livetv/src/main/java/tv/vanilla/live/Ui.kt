@file:OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package tv.vanilla.live

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Tab(val label: String) { Home("Home"), Sports("Sports"), Guide("Guide"), Favorites("Favorites"), Movies("Movies"), Series("Series"), Search("Search"), Settings("Settings") }

private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private fun fmtTime(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFmt)
private fun fmtKickoff(ms: Long): String {
    val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val today = LocalDateTime.now().toLocalDate()
    return when (z.toLocalDate()) {
        today -> z.format(timeFmt)
        today.plusDays(1) -> "Tomorrow " + z.format(timeFmt)
        else -> z.format(DateTimeFormatter.ofPattern("EEE h:mm a"))
    }
}

private fun isFav(g: Game, favs: Set<String>) = g.home.abbr in favs || g.away.abbr in favs

/** Live games first (favorites first), then upcoming within a day, then recent finals. */
private fun sortGames(list: List<Game>, favs: Set<String>): List<Game> {
    fun rank(g: Game) = when (g.state) { "in" -> 0; "pre" -> 1; else -> 2 }
    val day = 86_400_000L
    val now = System.currentTimeMillis()
    return list
        .filter { it.state == "in" || (it.state == "pre" && it.start - now < day) || (it.state == "post" && now - it.start < day / 2) }
        .sortedWith(compareBy<Game>({ rank(it) }, { if (isFav(it, favs)) 0 else 1 }, { it.start }))
}

@Composable
fun LiveApp(a: MainActivity) {
    val c = a.config.value
    val look = a.look.value
    val accent = parseColor(look.accent.ifBlank { c?.accent ?: "" }, Palette.live)
    var tab by remember { mutableStateOf(Tab.Home) }
    LaunchedEffect(a.openSeries.value) { if (a.openSeries.value != null) tab = Tab.Series }
    LaunchedEffect(a.searchNow.intValue) { if (a.searchNow.intValue > 0) tab = Tab.Search }
    LaunchedEffect(a.openTab.value) { Tab.entries.firstOrNull { it.name.equals(a.openTab.value, true) }?.let { tab = it }; a.openTab.value = null }
    val tabReq = remember { Tab.entries.associateWith { FocusRequester() } }
    var topFocused by remember { mutableStateOf(false) }
    var lastBack by remember { mutableStateOf(0L) }
    BackHandler(enabled = a.status.value != "setup" && a.pinFor.value == null && !a.showPicker.value && !a.mvPicker.value) {
        if (topFocused) {
            val now = System.currentTimeMillis()
            if (now - lastBack < 2_500) a.finish()
            else { lastBack = now; android.widget.Toast.makeText(a, "Press Back again to exit", android.widget.Toast.LENGTH_SHORT).show() }
        } else if (a.tabBack?.invoke() != true) runCatching { tabReq.getValue(tab).requestFocus() }
    }
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density * look.scale * BASE_SCALE, d.fontScale * look.text)) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(lerp(Palette.bg, Color.White, 0.04f), Palette.bg, Palette.bg)))) {
        a.sourceForm.value?.let { SourceForm(a, it, accent) }
        if (a.status.value == "setup") { SetupScreen(a, accent); return@Box }
        a.pinFor.value?.let { p -> PinPad("Enter PIN for ${p.name}", p.pin.length, accent, a::unlock) { a.pinFor.value = null }; return@Box }
        if (a.showPicker.value) { ProfilePicker(a); return@Box }
        Column(Modifier.fillMaxSize()) {
            val tabs = Tab.entries.filter { c?.vodSources?.isNotEmpty() == true || (it != Tab.Movies && it != Tab.Series) }
            TopBar(tabs, tab, accent, a.profile.value, a.profileIndex(), tabReq, { topFocused = it }, { a.cycleProfile() }) { tab = it }
            val s = a.status.value
            if (s.isNotBlank()) Text(s, color = Palette.sub, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp))
            Crossfade(tab, label = "tab") { t ->
                when (t) {
                    Tab.Home -> HomeTab(a, c, accent)
                    Tab.Sports -> SportsTab(a, c, accent)
                    Tab.Guide -> when (look.layout) { "river" -> RiverTab(a, c, accent); "surf" -> SurfTab(a, c, accent); else -> GuideTab(a, c, accent) }
                    Tab.Favorites -> GuideTab(a, c, accent, favoritesOnly = true)
                    Tab.Movies -> c?.vodSources?.takeIf { it.isNotEmpty() }?.let { VodSources(a, it, false, accent) }
                    Tab.Series -> c?.vodSources?.takeIf { it.isNotEmpty() }?.let { VodSources(a, it, true, accent) }
                    Tab.Search -> SearchTab(a, accent)
                    Tab.Settings -> LookTab(a, accent)
                }
            }
        }
        if (a.mvPicker.value) MultiviewPicker(a, accent)
        a.kickoff.value?.let { (g, ch) -> KickoffCard(g, ch.name, accent, Modifier.align(Alignment.BottomEnd)) }
        if (a.typed.value.isNotEmpty()) {
            Text(
                a.typed.value, color = Palette.text, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 90.dp, end = 48.dp).clip(RoundedCornerShape(14.dp)).background(Palette.card).padding(horizontal = 24.dp, vertical = 10.dp),
            )
        }
    }
    }
}

@Composable
private fun TopBar(tabs: List<Tab>, tab: Tab, accent: Color, profile: Profile, profileIdx: Int, req: Map<Tab, FocusRequester>, onFocusIn: (Boolean) -> Unit, onProfile: () -> Unit, onTab: (Tab) -> Unit) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(15_000) } }
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(10.dp))
        Text("LIVE", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Spacer(Modifier.width(36.dp))
        Row(Modifier.onFocusChanged { onFocusIn(it.hasFocus) }.focusRestorer { req.getValue(tab) }.focusGroup()) {
        tabs.forEach { t ->
            val selected = t == tab
            Surface(
                onClick = { onTab(t) },
                modifier = Modifier.padding(end = 8.dp).focusRequester(req.getValue(t)).onFocusChanged { if (it.isFocused) onTab(t) },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = Palette.text,
                    contentColor = if (selected) Palette.text else Palette.sub,
                    focusedContentColor = Palette.bg,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t.label, fontSize = 17.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    Box(Modifier.padding(top = 3.dp).size(width = 18.dp, height = 2.dp).background(if (selected) accent else Color.Transparent))
                }
            }
        }
        }
        Spacer(Modifier.weight(1f))
        Surface(
            onClick = onProfile,
            modifier = Modifier.padding(end = 20.dp),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Palette.card, focusedContainerColor = Palette.text, contentColor = Palette.text, focusedContentColor = Palette.bg),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        ) {
            Row(Modifier.padding(start = 5.dp, end = 16.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(profile, profileIdx, 24.dp)
                Spacer(Modifier.width(9.dp))
                Text(profile.name, fontSize = 15.sp, maxLines = 1, softWrap = false)
            }
        }
        Text(now.format(timeFmt), color = Palette.sub, fontSize = 20.sp)
    }
}

// ---------------------------------------------------------------- Home

@Composable
private fun HomeTab(a: MainActivity, c: LiveConfig?, accent: Color) {
    val favTeams = c?.favoriteTeams ?: emptySet()
    val profile = a.profile.value
    val look = a.look.value
    val games = remember(a.games.value, c) { sortGames(a.games.value, favTeams) }
    val chans = a.shownChannels.value
    val guide = a.epg.value
    val favs = a.favorites.value
    val sportsOn = profile.sports
    val liveGames = sportsOn && games.any { it.state == "in" }
    // Your teams' games (live, then coming up) get the big cards; with none, the top live game does.
    val featured = if (!sportsOn || look.hero == 0) emptyList() else
        games.filter { (it.state == "in" || it.state == "pre") && isFav(it, favTeams) }.take(8)
            .ifEmpty { listOfNotNull(games.firstOrNull { it.state == "in" }) }
    val hero = featured.firstOrNull()
    val live = if (sportsOn) games.filter { it.state == "in" && it !in featured } else emptyList()
    val upcoming = if (sportsOn) games.filter { it.state == "pre" && it !in featured } else emptyList()
    val sportsKeys = c?.sportsGroupKeywords ?: emptyList()
    val groups = remember(chans) { chans.groupBy { it.group }.filterKeys { !isAdultGroup(it) } }
    val sportsGroups = remember(groups, sportsKeys) { groups.keys.filter { g -> sportsKeys.any { g.contains(it, true) } } }
    // Sports leads only while games are on; otherwise the profile's picks, then everything else.
    val ordered = remember(groups, sportsGroups, profile, liveGames) {
        val preferred = profile.groups.filter { it in groups }
        val rest = groups.keys.filter { it !in preferred }
        val general = rest.filter { it !in sportsGroups }
        val sports = rest.filter { it in sportsGroups }
        preferred + if (liveGames) sports + general else general + sports
    }
    val moods by produceState(emptyList<Pair<String, List<Channel>>>(), chans, guide, profile) {
        value = withContext(Dispatchers.Default) {
            profile.keywords.map { it to onNowMatching(chans, guide, it) }.filter { it.second.isNotEmpty() }
        }
    }
    val favChans = remember(chans, favs) { chans.filter { it.name in favs } }
    val recentNames = a.recents.value
    val recentChans = remember(chans, recentNames) {
        val byName = chans.associateBy { it.name }
        recentNames.mapNotNull { byName[it] }.filter { !isAdultGroup(it.group) }
    }
    val first = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var homeTop by remember { mutableStateOf(0) }
    LaunchedEffect(homeTop) { if (homeTop > 0) listState.scrollToItem(0) }
    DisposableEffect(Unit) {
        val h: () -> Boolean = { homeTop++; false }
        a.tabBack = h
        onDispose { if (a.tabBack === h) a.tabBack = null }
    }
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(hero?.id, chans.isNotEmpty(), profile) {
        if (!touched && (hero != null || chans.isNotEmpty())) {
            listState.scrollToItem(0)
            delay(150); runCatching { first.requestFocus() }
        }
    }
    var firstTaken = false
    fun focusFor(): FocusRequester? = if (firstTaken) null else { firstTaken = true; first }

    LazyColumn(state = listState, modifier = Modifier.onPreviewKeyEvent { touched = true; false }, contentPadding = PaddingValues(top = 14.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (featured.size == 1) { val f = focusFor(); item("hero") { Hero(hero!!, favTeams, accent, look.hero == 1, Modifier.focusRequester(f!!)) { a.playGame(hero) } } }
        else if (featured.size > 1) {
            val f = focusFor()
            item("heroes") {
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(featured.size, key = { featured[it].league + featured[it].id }) { i ->
                        val g = featured[i]
                        Hero(g, favTeams, accent, true, if (i == 0) Modifier.focusRequester(f!!) else Modifier, width = 470.dp) { a.playGame(g) }
                    }
                }
            }
        }
        if (sportsOn && games.any { it.state == "in" }) item("live") { GameShelf("Live now", live, favTeams, accent, a, multiview = true) { a.playGame(it) } }
        if (a.resume.value.isNotEmpty()) item("resume") { ResumeShelf(a, a.resume.value, accent) }
        if (recentChans.isNotEmpty()) { val f = focusFor(); item("recent") { ChannelShelf(a, "Recently watched", recentChans, guide, accent, f) } }
        if (favChans.isNotEmpty()) { val f = focusFor(); item("favs") { ChannelShelf(a, "Favorite channels", favChans, guide, accent, f) } }
        moods.forEach { (k, list) -> val f = focusFor(); item("m-$k") { ChannelShelf(a, "$k on now", list, guide, accent, f) } }
        if (upcoming.isNotEmpty() && liveGames) item("up") { GameShelf("Coming up", upcoming, favTeams, accent, a) { a.playGame(it) } }
        ordered.forEach { g -> val f = focusFor(); item("g-$g") { ChannelShelf(a, g, groups.getValue(g), guide, accent, f) } }
        if (upcoming.isNotEmpty() && !liveGames) item("up") { GameShelf("Coming up", upcoming, favTeams, accent, a) { a.playGame(it) } }
    }
}

@Composable
private fun Shelf(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 48.dp, bottom = 12.dp))
        content()
    }
}

@Composable
private fun Hero(g: Game, favs: Set<String>, accent: Color, compact: Boolean, modifier: Modifier, width: Dp? = null, onClick: () -> Unit) {
    val awayC = parseColor(g.away.color, Palette.card)
    val homeC = parseColor(g.home.color, Palette.card)
    Surface(
        onClick = onClick,
        modifier = (if (width != null) modifier.width(width) else modifier.padding(horizontal = 48.dp).fillMaxWidth()).height(if (compact) 150.dp else 260.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Palette.card, focusedContainerColor = Palette.card),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Palette.text))),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(accent.copy(alpha = 0.35f), 24.dp)),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(lerp(awayC, Color.Black, 0.35f), lerp(Palette.bg, Color.Black, 0.2f), lerp(homeC, Color.Black, 0.35f)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))))
        Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = if (compact) 14.dp else 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(LEAGUE_LABELS[g.league] ?: g.league.uppercase(), color = Palette.text.copy(alpha = 0.8f), fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                if (isFav(g, favs)) { Spacer(Modifier.width(10.dp)); Pill("YOUR TEAM", Color(0x33FFFFFF)) }
                Spacer(Modifier.weight(1f))
                StatusPill(g)
            }
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HeroTeam(g.away, compact, Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(if (width != null) 140.dp else 260.dp)) {
                    if (g.state == "pre") {
                        Text(fmtKickoff(g.start), color = Palette.text, fontSize = if (compact) 26.sp else 34.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("${g.away.score}  –  ${g.home.score}", color = Palette.text, fontSize = if (compact) 38.sp else 56.sp, fontWeight = FontWeight.Bold)
                    }
                }
                HeroTeam(g.home, compact, Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.networks.joinToString("  ·  ").ifBlank { " " }, color = Palette.text.copy(alpha = 0.85f), fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(if (g.state == "post") "Final" else "Press OK to watch", color = Palette.text.copy(alpha = 0.7f), fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun HeroTeam(t: Team, compact: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AsyncImage(t.logo, t.name, Modifier.size(if (compact) 48.dp else 96.dp))
        Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
        Text(t.shortName.ifBlank { t.abbr }, color = Palette.text, fontSize = if (compact) 17.sp else 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color = Palette.text) {
    Text(
        text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun StatusPill(g: Game) = when (g.state) {
    "in" -> Row(verticalAlignment = Alignment.CenterVertically) {
        Pill("LIVE", Palette.live)
        Spacer(Modifier.width(8.dp))
        Text(g.detail, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
    "pre" -> Pill(fmtKickoff(g.start).uppercase(), Color(0x33FFFFFF))
    else -> Pill(g.detail.uppercase(), Color(0x22FFFFFF), Palette.sub)
}

@Composable
private fun GameShelf(title: String, list: List<Game>, favs: Set<String>, accent: Color, a: MainActivity? = null, multiview: Boolean = false, onPlay: (Game) -> Unit) {
    val rem = a?.reminders?.value ?: emptySet()
    Shelf(title) {
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            if (a != null && multiview) item("multiview") { MultiviewCard(MultiviewActivity.MAX, accent) { a.mvPicker.value = true } }
            items(list, key = { it.league + it.id }) { g -> GameCard(g, favs, accent, Modifier, gameKey(g) in rem, { a?.toggleReminder(g) }) { onPlay(g) } }
        }
    }
}

@Composable
private fun GameCard(g: Game, favs: Set<String>, accent: Color, modifier: Modifier, reminded: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val awayC = parseColor(g.away.color, Palette.card)
    val homeC = parseColor(g.home.color, Palette.card)
    val showScore = g.state != "pre"
    Surface(
        onClick = onClick,
        onLongClick = onLong,
        modifier = modifier.size(width = 330.dp, height = 176.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Palette.card, focusedContainerColor = Palette.card),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Palette.text))),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(accent.copy(alpha = 0.3f), 16.dp)),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(lerp(awayC, Color.Black, 0.45f), lerp(homeC, Color.Black, 0.45f)))))
        Box(Modifier.fillMaxSize().background(Color(0x40000000)))
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(LEAGUE_LABELS[g.league] ?: g.league.uppercase(), color = Palette.text.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                if (isFav(g, favs)) { Spacer(Modifier.width(6.dp)); Text("★", color = Color(0xFFFFD166), fontSize = 13.sp) }
                Spacer(Modifier.weight(1f))
                StatusPill(g)
            }
            Spacer(Modifier.weight(1f))
            CardTeam(g.away, showScore, g.state == "post" && (g.away.score.toIntOrNull() ?: 0) < (g.home.score.toIntOrNull() ?: 0))
            Spacer(Modifier.height(8.dp))
            CardTeam(g.home, showScore, g.state == "post" && (g.home.score.toIntOrNull() ?: 0) < (g.away.score.toIntOrNull() ?: 0))
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.networks.joinToString(" · ").ifBlank { " " }, color = Palette.text.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (reminded) Text("REMINDER ON", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                else if (g.state == "pre" && onLong != null) Text("hold OK to remind", color = Palette.text.copy(alpha = 0.4f), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun CardTeam(t: Team, showScore: Boolean, dim: Boolean) {
    val col = if (dim) Palette.text.copy(alpha = 0.55f) else Palette.text
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(t.logo, null, Modifier.size(34.dp))
        Spacer(Modifier.width(12.dp))
        Text(t.shortName.ifBlank { t.abbr }, color = col, fontSize = 19.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
        if (showScore) Text(t.score, color = col, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ChannelShelf(a: MainActivity, title: String, list: List<Channel>, guide: Map<String, List<Programme>>, accent: Color, focus: FocusRequester?) {
    Shelf("$title  ·  ${list.size}") {
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(list, key = { it.index }) { ch ->
                ChannelCard(a, ch, guide[ch.tvgId], accent, if (focus != null && ch === list.first()) Modifier.focusRequester(focus) else Modifier)
            }
        }
    }
}

@Composable
internal fun ChannelCard(a: MainActivity, ch: Channel, progs: List<Programme>?, accent: Color, modifier: Modifier) {
    val (now, next) = nowNext(progs)
    val fav = ch.name in a.favorites.value
    Surface(
        onClick = { a.play(ch) },
        onLongClick = { a.toggleFavorite(ch) },
        modifier = modifier.size(width = 250.dp, height = 150.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Palette.card, focusedContainerColor = Palette.cardFocused),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Palette.text))),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(78.dp).background(Color(0xFF1E2230)), contentAlignment = Alignment.Center) {
                var logoFailed by remember(ch.logo) { mutableStateOf(false) }
                if (ch.logo.isNotBlank() && !logoFailed) AsyncImage(ch.logo, ch.name, Modifier.fillMaxHeight(0.62f).fillMaxWidth(0.6f), contentScale = ContentScale.Fit, onError = { logoFailed = true })
                else Text(ch.name, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp))
                Row(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                    if (ch.number.isNotBlank()) Text(ch.number, color = Palette.sub, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                if (fav) Text("★", color = Color(0xFFFFD166), fontSize = 14.sp, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(now?.title ?: ch.name, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(next?.let { "Next: ${it.title}" } ?: if (now != null) ch.name else " ", color = Palette.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                if (now != null) ProgressBar(now, accent)
            }
        }
    }
}

@Composable
internal fun ProgressBar(p: Programme, accent: Color) {
    val frac = ((System.currentTimeMillis() - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(accent))
    }
}

// ---------------------------------------------------------------- Sports

@Composable
private fun SportsTab(a: MainActivity, c: LiveConfig?, accent: Color) {
    val favs = c?.favoriteTeams ?: emptySet()
    val byLeague = remember(a.games.value, c) {
        val order = c?.leagues ?: emptyList()
        a.games.value.groupBy { it.league }.toSortedMap(compareBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } })
            .mapValues { (_, v) -> v.sortedWith(compareBy<Game>({ when (it.state) { "in" -> 0; "pre" -> 1; else -> 2 } }, { if (isFav(it, favs)) 0 else 1 }, { it.start })) }
    }
    if (byLeague.isEmpty()) {
        Text("No games on the schedule right now.", color = Palette.sub, fontSize = 18.sp, modifier = Modifier.padding(48.dp))
        return
    }
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        byLeague.forEach { (league, list) ->
            item(league) {
                val live = list.count { it.state == "in" }
                GameShelf((LEAGUE_LABELS[league] ?: league.uppercase()) + if (live > 0) "  ·  $live live" else "", list, favs, accent) { a.playGame(it) }
            }
        }
    }
}

// ---------------------------------------------------------------- Guide

@Composable
private fun GuideTab(a: MainActivity, c: LiveConfig?, accent: Color, favoritesOnly: Boolean = false) {
    val chans = a.shownChannels.value
    val guide = a.epg.value
    val favs = a.favorites.value
    val sportsKeys = c?.sportsGroupKeywords ?: emptyList()
    val profile = a.profile.value
    val showAdult = a.look.value.adult && !profile.kids
    if (favoritesOnly && favs.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(48.dp)) {
            Text("No favorites yet", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("Hold OK on any channel in Home, Guide or Search to add it here.", color = Palette.sub, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
        }
        return
    }
    val groupNames = remember(chans, favs, profile, showAdult, favoritesOnly) {
        if (favoritesOnly) return@remember listOf("★ Favorites")
        // Every "Locals — <state>" group from the Lineup Manager plugin collapses into one Locals National chip.
        val all = chans.map { if (it.group.startsWith(LOCALS_PREFIX)) LOCALS_NATIONAL else it.group }.distinct().filter { showAdult || !isAdultGroup(it) }
        val preferred = profile.groups.filter { it in all }
        val sports = all.filter { g -> g !in preferred && sportsKeys.any { g.contains(it, true) } }
        val general = all - preferred.toSet() - sports.toSet()
        val list = buildList {
            if (favs.isNotEmpty()) add("★ Favorites")
            addAll(preferred)
            if (profile.sports) { addAll(sports); addAll(general) } else { addAll(general); addAll(sports) }
        }.toMutableList()
        if (LOCALS_NATIONAL in list && "Locals" in list) { list.remove(LOCALS_NATIONAL); list.add(list.indexOf("Locals") + 1, LOCALS_NATIONAL) }
        list
    }
    var group by remember { mutableStateOf<String?>(null) }
    val selected = group ?: groupNames.firstOrNull()
    var locNet by remember { mutableStateOf<String?>(null) }
    var locState by remember { mutableStateOf<String?>(null) }
    val nationals = remember(chans) { chans.filter { it.group.startsWith(LOCALS_PREFIX) } }
    val networks = remember(nationals) { nationals.mapNotNull { localNetwork(it.name) }.distinct().sortedBy { LOCAL_NETWORKS.indexOf(it) } }
    val states = remember(nationals) { nationals.map { it.group.removePrefix(LOCALS_PREFIX) }.distinct().sorted() }
    val rows = remember(chans, selected, favs, locNet, locState) {
        when (selected) {
            "★ Favorites" -> chans.filter { it.name in favs }
            LOCALS_NATIONAL -> nationals.filter { (locNet == null || localNetwork(it.name) == locNet) && (locState == null || it.group.removePrefix(LOCALS_PREFIX) == locState) }
            else -> chans.filter { it.group == selected }
        }
    }
    // Highlighting a group chip loads its channels after a short pause, without pressing OK.
    var hovered by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hovered) { val h = hovered ?: return@LaunchedEffect; delay(400); group = h }
    val nowFocus = remember { FocusRequester() }
    val netFocus = remember { FocusRequester() }
    // Down from the chips lands on what's on now in the first row, not wherever the cursor lines up.
    val downToNow = Modifier.onPreviewKeyEvent { ev ->
        if (ev.key == Key.DirectionDown && ev.type == KeyEventType.KeyDown && rows.isNotEmpty()) runCatching { nowFocus.requestFocus(); true }.getOrDefault(false) else false
    }
    var focusedProg by remember { mutableStateOf<Pair<Channel, Programme?>?>(null) }
    val gridState = rememberLazyListState()
    val chipFocus = remember { FocusRequester() }
    var gridFocused by remember { mutableStateOf(false) }
    var toTop by remember { mutableStateOf(0) }
    LaunchedEffect(toTop, selected) { gridState.scrollToItem(0) }
    DisposableEffect(Unit) {
        val h: () -> Boolean = { if (gridFocused) { toTop++; runCatching { chipFocus.requestFocus() }; true } else false }
        a.tabBack = h
        onDispose { if (a.tabBack === h) a.tabBack = null }
    }
    fun halfHour() = System.currentTimeMillis().let { it - it % (30 * 60_000L) }
    var windowStart by remember { mutableStateOf(halfHour()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); windowStart = halfHour() } }
    val look = a.look.value
    val windowMs = look.guideHours * 3_600_000L

    Column(Modifier.fillMaxSize()) {
        LazyRow(modifier = if (selected == LOCALS_NATIONAL) Modifier else downToNow, contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (chans.size >= 2) item("mv") {
                Surface(
                    onClick = { a.mvPicker.value = true },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Palette.card, focusedContainerColor = Palette.text, contentColor = Palette.text, focusedContentColor = Palette.bg),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                ) { Text("⊞  Multiview", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), maxLines = 1) }
            }
            items(groupNames, key = { it }) { g ->
                val sel = g == selected
                Surface(
                    onClick = { group = g },
                    modifier = (if (sel) Modifier.focusRequester(chipFocus) else Modifier).onFocusChanged { if (it.isFocused) hovered = g }
                        .focusProperties { if (selected == LOCALS_NATIONAL) down = netFocus else if (rows.isNotEmpty()) down = nowFocus },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (sel) accent.copy(alpha = 0.85f) else Palette.card,
                        focusedContainerColor = Palette.text,
                        contentColor = Palette.text, focusedContentColor = Palette.bg,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                ) { Text(g, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), maxLines = 1) }
            }
        }
        if (selected == LOCALS_NATIONAL) {
            SubChips(listOf<String?>(null) + networks, locNet, { it ?: "All networks" }, accent, Modifier, first = netFocus) { locNet = it }
            SubChips(listOf<String?>(null) + states, locState, { it ?: "All states" }, accent, downToNow, nowFocus.takeIf { rows.isNotEmpty() }) { locState = it }
        }
        // Detail strip for the focused programme
        Column(Modifier.fillMaxWidth().height(92.dp).padding(horizontal = 48.dp, vertical = 10.dp)) {
            val fp = focusedProg
            if (fp != null) {
                val p = fp.second
                Text(p?.title ?: fp.first.name, color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(fp.first.name, p?.let { "${fmtTime(it.start)} – ${fmtTime(it.stop)}" }, p?.desc?.takeIf { it.isNotBlank() }).joinToString("  ·  "),
                    color = Palette.sub, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 48.dp, end = 24.dp).onFocusChanged { gridFocused = it.hasFocus }) {
            val chanW = if (look.guideLogos) 210.dp else 160.dp
            val gridW = maxWidth - chanW - 8.dp
            val dpPerMs = gridW / windowMs.toFloat()
            Column {
                Row(Modifier.padding(start = chanW + 8.dp, bottom = 6.dp)) {
                    val slots = look.guideHours * 2
                    for (i in 0 until slots) Text(fmtTime(windowStart + i * 30 * 60_000L), color = Palette.sub, fontSize = 13.sp, modifier = Modifier.width(gridW / slots))
                }
                LazyColumn(state = gridState, contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(rows, key = { _, ch -> ch.index }) { i, ch ->
                        GuideRow(a, ch, guide[ch.tvgId], windowStart, windowMs, chanW, dpPerMs, accent, if (i == 0) nowFocus else null) { focusedProg = it }
                    }
                }
            }
            val nowX = chanW + 8.dp + dpPerMs * (System.currentTimeMillis() - windowStart).toFloat()
            Box(Modifier.offset(x = nowX).width(2.dp).fillMaxHeight().background(accent.copy(alpha = 0.8f)))
        }
    }
}

const val LOCALS_PREFIX = "Locals — "
const val LOCALS_NATIONAL = "Locals National"
private val LOCAL_NETWORKS = listOf("ABC", "CBS", "NBC", "FOX", "PBS", "CW", "My", "IND")
private val LOCAL_NET_RE = Regex(""":\s*(ABC|CBS|NBC|FOX|PBS|CW|My|IND)\b""")
/** Network of a Locals National channel, from names like "Peoria: CBS 31 WMBD". */
fun localNetwork(name: String): String? = LOCAL_NET_RE.find(name)?.groupValues?.get(1)

@Composable
private fun <T> SubChips(values: List<T>, current: T, label: (T) -> String, accent: Color, modifier: Modifier, down: FocusRequester? = null, first: FocusRequester? = null, onPick: (T) -> Unit) {
    LazyRow(modifier.padding(top = 8.dp), contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(values) { i, v ->
            val sel = v == current
            Surface(
                onClick = { onPick(v) },
                modifier = (if (i == 0 && first != null) Modifier.focusRequester(first) else Modifier)
                    .onFocusChanged { if (it.isFocused) onPick(v) }.focusProperties { if (down != null) this.down = down },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (sel) accent.copy(alpha = 0.6f) else Palette.card, focusedContainerColor = Palette.text,
                    contentColor = Palette.text, focusedContentColor = Palette.bg,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            ) { Text(label(v), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), maxLines = 1) }
        }
    }
}

@Composable
private fun GuideRow(
    a: MainActivity, ch: Channel, progs: List<Programme>?, windowStart: Long, windowMs: Long,
    chanW: Dp, dpPerMs: Dp, accent: Color, nowFocus: FocusRequester? = null, onFocus: (Pair<Channel, Programme?>) -> Unit,
) {
    val nowMod = if (nowFocus != null) Modifier.focusRequester(nowFocus) else Modifier
    val end = windowStart + windowMs
    val visible = progs.orEmpty().filter { it.stop > windowStart && it.start < end }
    Row(Modifier.height(a.look.value.rowHeightDp.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.width(chanW).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(Palette.card).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(ch.number, color = Palette.sub, fontSize = 13.sp, modifier = Modifier.width(36.dp))
            if (a.look.value.guideLogos && ch.logo.isNotBlank()) { AsyncImage(ch.logo, null, Modifier.size(width = 48.dp, height = 32.dp), contentScale = ContentScale.Fit); Spacer(Modifier.width(8.dp)) }
            Text(ch.name, color = Palette.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (visible.isEmpty()) {
            GuideCell(ch.name, "", dpPerMs * windowMs.toFloat(), false, accent, { a.play(ch) }, { a.toggleFavorite(ch) }, nowMod) { onFocus(ch to null) }
        } else {
            var cursor = windowStart
            val nowMs = System.currentTimeMillis()
            val target = visible.firstOrNull { it.start <= nowMs && it.stop > nowMs } ?: visible.first()
            visible.forEach { p ->
                val s = maxOf(p.start, windowStart)
                if (s > cursor) Spacer(Modifier.width(dpPerMs * (s - cursor).toFloat()))
                val e = minOf(p.stop, end)
                val now = System.currentTimeMillis()
                GuideCell(
                    p.title, "${fmtTime(p.start)}", dpPerMs * (e - s).toFloat() - 4.dp, p.start <= now && p.stop > now, accent,
                    { a.play(ch) }, { a.toggleFavorite(ch) }, if (p === target) nowMod else Modifier,
                ) { onFocus(ch to p) }
                Spacer(Modifier.width(4.dp))
                cursor = e
            }
        }
    }
}

@Composable
private fun GuideCell(title: String, sub: String, width: Dp, airing: Boolean, accent: Color, onClick: () -> Unit, onLong: () -> Unit, modifier: Modifier = Modifier, onFocus: () -> Unit) {
    Surface(
        onClick = onClick,
        onLongClick = onLong,
        modifier = modifier.width(width.coerceAtLeast(8.dp)).fillMaxHeight().onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (airing) Color(0xFF232838) else Palette.card,
            focusedContainerColor = Palette.text,
            contentColor = Palette.text, focusedContentColor = Palette.bg,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize()) {
            if (airing) Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, maxLines = 1, color = LocalContentColor.current.copy(alpha = 0.65f))
            }
        }
    }
}

// ---------------------------------------------------------------- Setup

@Composable
private fun SetupScreen(a: MainActivity, accent: Color) {
    var server by remember { mutableStateOf("http://") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(80.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Connect to Dispatcharr", color = Palette.text, fontSize = 30.sp)
        Text("Enter your Dispatcharr address, e.g. http://192.168.1.10:9191", color = Palette.sub, fontSize = 18.sp)
        BasicTextField(
            value = server, onValueChange = { server = it }, singleLine = true,
            textStyle = TextStyle(color = Palette.text, fontSize = 20.sp), cursorBrush = SolidColor(accent),
            modifier = Modifier.width(640.dp).focusRequester(focus).border(1.dp, accent, RoundedCornerShape(10.dp)).padding(14.dp),
        )
        Button(onClick = {
            val base = server.trim().trimEnd('/')
            a.settings.m3uOverride = "$base/output/m3u"
            a.settings.epgOverride = "$base/output/epg"
            a.reload(true)
        }) { Text("Connect") }
        Spacer(Modifier.height(12.dp))
        Text("No Dispatcharr? Add your IPTV service directly:", color = Palette.sub, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Button(onClick = { a.sourceForm.value = "xtream" }) { Text("Xtream Codes login") }
            Button(onClick = { a.sourceForm.value = "m3u" }) { Text("M3U playlist") }
        }
    }
}

@Composable
fun KickoffCard(g: Game, channel: String, accent: Color, modifier: Modifier) {
    Column(
        modifier.padding(40.dp).width(440.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xF0101218))
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(18.dp)).padding(20.dp)
    ) {
        Text(if (g.state == "in") "GAME ON" else "KICKOFF", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(g.away.logo, null, Modifier.size(32.dp))
            Text("  ${g.away.shortName}  at  ${g.home.shortName}  ", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            AsyncImage(g.home.logo, null, Modifier.size(32.dp))
        }
        Text("on $channel", color = Palette.sub, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Text("OK to watch  ·  Back to dismiss", color = Color(0x99FFFFFF), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

// ---------------------------------------------------------------- Search

@Composable
private fun SearchTab(a: MainActivity, accent: Color) {
    var q by remember { mutableStateOf("") }
    val chans = a.shownChannels.value
    val guide = a.epg.value
    val showAdult = a.look.value.adult && !a.profile.value.kids
    DisposableEffect(Unit) { a.searching = true; onDispose { a.searching = false } }
    val fm = LocalFocusManager.current
    val kb = LocalSoftwareKeyboardController.current
    val field = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(a.searchNow.intValue) {
        if (a.searchNow.intValue > 0) { delay(350); runCatching { field.requestFocus() }; editing = true }
    }
    LaunchedEffect(editing) { if (editing) { delay(80); kb?.show() } }
    val results by produceState(SearchResults(), q, chans, guide, showAdult) {
        val t = q.trim()
        if (t.length < 2) { value = SearchResults(); return@produceState }
        delay(250)
        value = withContext(Dispatchers.Default) { search(chans, guide, t, showAdult) }
    }
    Column(Modifier.fillMaxSize()) {
        BasicTextField(
            value = q, onValueChange = { q = it }, singleLine = true, readOnly = !editing,
            textStyle = TextStyle(color = Palette.text, fontSize = 20.sp), cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { kb?.hide(); editing = false; fm.moveFocus(FocusDirection.Down) }),
            modifier = Modifier
                .onFocusChanged { if (!it.isFocused) editing = false }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else when (e.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (editing) false else { editing = true; true }
                        }
                        Key.DirectionDown -> { kb?.hide(); fm.moveFocus(FocusDirection.Down) }
                        Key.DirectionUp -> { kb?.hide(); fm.moveFocus(FocusDirection.Up) }
                        else -> false
                    }
                }
                .focusRequester(field)
                .padding(horizontal = 48.dp).width(640.dp).clip(RoundedCornerShape(12.dp)).background(Palette.card)
                .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
            decorationBox = { inner ->
                Box { if (q.isEmpty()) Text("Search channels and shows", color = Palette.sub, fontSize = 20.sp); inner() }
            },
        )
        Spacer(Modifier.height(18.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (results.channels.isNotEmpty()) item("ch") { ChannelShelf(a, "Channels", results.channels, guide, accent, null) }
            if (results.shows.isNotEmpty()) {
                item("sh") { Text("Shows", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 48.dp, top = 14.dp, bottom = 4.dp)) }
                items(results.shows, key = { "${it.first.index}-${it.second.start}" }) { (ch, p) -> ShowRow(ch, p, accent) { a.play(ch) } }
            }
            if (q.trim().length >= 2 && results.channels.isEmpty() && results.shows.isEmpty()) {
                item { Text("Nothing matches \"${q.trim()}\"", color = Palette.sub, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 48.dp)) }
            }
        }
    }
}

@Composable
private fun ShowRow(ch: Channel, p: Programme, accent: Color, onClick: () -> Unit) {
    val live = p.start <= System.currentTimeMillis()
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 48.dp).fillMaxWidth(0.75f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Palette.card, focusedContainerColor = Palette.cardFocused, contentColor = Palette.text, focusedContentColor = Palette.text),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Palette.text))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(96.dp)) {
                if (live) Pill("LIVE", accent) else Text(fmtTime(p.start), color = Palette.sub, fontSize = 15.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(p.title, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(ch.number, ch.name).filter { it.isNotBlank() }.joinToString("  ·  "), color = Palette.sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- Settings

@Composable
private fun LookTab(a: MainActivity, accent: Color) {
    val l = a.look.value
    val first = remember { FocusRequester() }
    val pct = { f: Float -> "${(f * 100).toInt()}%" }
    val armed = remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Appearance", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold); Text("Press OK to change. Saved on this TV.", color = Palette.sub, fontSize = 14.sp) }
        item { LookRow("Who's watching", a.profile.value.name, accent, Modifier.focusRequester(first)) { a.cycleProfile() } }
        item { LookRow("Screen size", pct(l.scale), accent) { a.setLook(l.copy(scale = Look.SCALES.next(l.scale))) } }
        item { LookRow("Featured game", Look.HEROES[l.hero.coerceIn(0, 2)], accent) { a.setLook(l.copy(hero = (l.hero + 1) % 3)) } }
        item { LookRow("Text size", Look.TEXTS.firstOrNull { it.first == l.text }?.second ?: pct(l.text), accent) {
            a.setLook(l.copy(text = Look.TEXTS.map { it.first }.next(l.text))) } }
        item { LookRow("Guide layout", Look.LAYOUTS[l.layout] ?: "Classic grid", accent) { a.setLook(l.copy(layout = Look.LAYOUTS.keys.toList().next(l.layout))) } }
        item {
            val groups = remember(a.shownChannels.value) { a.shownChannels.value.map { it.group }.distinct() }
            val opts = listOf("", "fav") + groups.map { "group:$it" }
            LookRow("Home screen On now", when { l.onNow == "fav" -> "Favorites"; l.onNow.startsWith("group:") -> l.onNow.removePrefix("group:"); else -> "Recent and favorites" }, accent) {
                a.setLook(l.copy(onNow = opts[(opts.indexOf(l.onNow) + 1) % opts.size])); a.writeGlance()
            }
        }
        item { LookRow("Guide rows", Look.ROWS[l.rows.coerceIn(0, 2)], accent) { a.setLook(l.copy(rows = (l.rows + 1) % 3)) } }
        item { LookRow("Guide shows", "${l.guideHours} hours", accent) { a.setLook(l.copy(guideHours = Look.HOURS.next(l.guideHours))) } }
        item { LookRow("Guide channel logos", if (l.guideLogos) "On" else "Off", accent) { a.setLook(l.copy(guideLogos = !l.guideLogos)) } }
        item { LookRow("Accent color", Look.ACCENTS.firstOrNull { it.first == l.accent }?.second ?: l.accent, accent, swatch = accent) {
            a.setLook(l.copy(accent = Look.ACCENTS.map { it.first }.next(l.accent))) } }
        item { LookRow("Background", l.background.replaceFirstChar { it.uppercase() }, accent, swatch = Palette.bg) {
            a.setLook(l.copy(background = Look.BACKGROUNDS.keys.toList().next(l.background))) } }
        item {
            val kids = a.profile.value.kids
            LookRow("Adult channels in guide", if (kids) "Hidden for ${a.profile.value.name}" else if (l.adult) "Shown" else "Hidden", accent) {
                if (!kids) a.setLook(l.copy(adult = !l.adult))
            }
        }
        item { LookRow("Empty event slots", if (l.emptySlots) "Shown" else "Hidden until a game is on", accent) { a.setLook(l.copy(emptySlots = !l.emptySlots)) } }
        item { LookRow("Score bar while watching", listOf("Off", "Favorite teams", "All live games")[a.ticker.intValue.coerceIn(0, 2)], accent) { a.cycleTicker() } }
        item { LookRow("Open on last channel", if (a.resumeLast.value) "On" else "Off", accent) { a.toggleResumeLast() } }
        item { LookRow("Reset appearance", "", accent) { a.setLook(Look()) } }
        item { LookRow("Reload playlist & guide", "", accent) { a.reload(true) } }
        item { Text("${a.channels.value.size} channels · ${a.config.value?.feeds()?.joinToString(", ") { it.name } ?: ""}", color = Palette.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        sourceRows(a, accent, armed) { label, value, onClick -> LookRow(label, value, accent, onClick = onClick) }
    }
}

@Composable
private fun LookRow(label: String, value: String, accent: Color, modifier: Modifier = Modifier, swatch: Color? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(0.6f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Palette.card, focusedContainerColor = Palette.cardFocused, contentColor = Palette.text, focusedContentColor = Palette.text),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, accent))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 17.sp, modifier = Modifier.weight(1f))
            if (swatch != null) { Box(Modifier.size(18.dp).clip(CircleShape).background(swatch).border(1.dp, Palette.sub, CircleShape)); Spacer(Modifier.width(10.dp)) }
            Text(value, fontSize = 17.sp, color = Palette.sub)
        }
    }
}

// ---------------------------------------------------------------- Movies & Series

/** Movies/Series for one provider at a time, with a chip row to switch when there are several. */
@Composable
private fun VodSources(a: MainActivity, list: List<Pair<String, Xc>>, series: Boolean, accent: Color) {
    var pick by remember { mutableIntStateOf(0) }
    val i = pick.coerceIn(0, list.size - 1)
    Column(Modifier.fillMaxSize()) {
        if (list.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(list) { n, (name, _) ->
                Surface(
                    onClick = { pick = n },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = if (n == i) accent.copy(alpha = 0.35f) else Palette.card, focusedContainerColor = Palette.text, contentColor = Palette.text, focusedContentColor = Palette.bg),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                ) { Text(name, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
            }
        }
        key(list[i].second) { VodTab(a, list[i].second, series, accent) }
    }
}

@Composable
private fun VodTab(a: MainActivity, xc: Xc, series: Boolean, accent: Color) {
    val adult = a.look.value.adult && !a.profile.value.kids
    val cats by produceState<List<VodCat>?>(null, adult, series) {
        value = withContext(Dispatchers.IO) { runCatching { Vod.cats(xc, series, adult) }.getOrDefault(emptyList()) }
    }
    var cat by remember(series) { mutableStateOf<VodCat?>(null) }
    var open by remember(series) { mutableStateOf<VodItem?>(null) }
    LaunchedEffect(series, a.openSeries.value) { if (series) a.openSeries.value?.let { open = it; a.openSeries.value = null } }
    open?.let { s -> SeriesDetail(a, xc, s, accent) { open = null }; return }
    val list = cats
    if (list == null) { Text("Loading…", color = Palette.sub, modifier = Modifier.padding(horizontal = 48.dp)); return }
    if (list.isEmpty()) {
        Text("Nothing here yet. Turn on VOD for your providers in Dispatcharr (M3U accounts → Edit → VOD).", color = Palette.sub, modifier = Modifier.padding(horizontal = 48.dp))
        return
    }
    LaunchedEffect(list) { if (cat == null || cat !in list) cat = list.first() }
    val items by produceState<List<VodItem>?>(null, cat, adult) {
        value = null
        val c = cat ?: return@produceState
        delay(300)
        value = withContext(Dispatchers.IO) { runCatching { Vod.items(xc, series, c.id, adult) }.getOrDefault(emptyList()) }
    }
    Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 24.dp, top = 4.dp)) {
        LazyColumn(Modifier.width(230.dp), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(list, key = { it.id }) { c ->
                val sel = c == cat
                Surface(
                    onClick = { cat = c },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) cat = c },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = if (sel) Palette.card else Color.Transparent, focusedContainerColor = Palette.text, contentColor = if (sel) Palette.text else Palette.sub, focusedContentColor = Palette.bg),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                ) { Text(c.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) }
            }
        }
        Spacer(Modifier.width(20.dp))
        val shown = items
        when {
            shown == null -> Text("Loading…", color = Palette.sub)
            shown.isEmpty() -> Text("Nothing in this category.", color = Palette.sub)
            else -> LazyVerticalGrid(
                GridCells.Adaptive(112.dp), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 6.dp, bottom = 40.dp, end = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                gridItems(shown, key = { it.id }) { v ->
                    PosterCard(v.name, v.poster, listOf(v.year, v.rating.let { if (it.isBlank()) "" else "★ $it" }).filter { it.isNotBlank() }.joinToString("  "), accent) {
                        if (series) open = v
                        else a.playVod(xc.movieUrl(v.id, v.ext), v.name, v.year, v.poster, "movie:${v.id}")
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterCard(title: String, poster: String, meta: String, accent: Color, progress: Float = 0f, onClick: () -> Unit) {
    Column {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(width = 112.dp, height = 166.dp),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Palette.card, focusedContainerColor = Palette.card),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Palette.text))),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(accent.copy(alpha = 0.3f), 12.dp)),
        ) {
            Text(title, color = Palette.sub, fontSize = 12.sp, maxLines = 4, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            if (poster.isNotBlank()) AsyncImage(poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x80000000))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(accent))
            }
        }
        Text(title, color = Palette.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(112.dp).padding(top = 6.dp))
        if (meta.isNotBlank()) Text(meta, color = Palette.sub, fontSize = 11.sp, maxLines = 1, modifier = Modifier.width(112.dp))
    }
}

@Composable
private fun ResumeShelf(a: MainActivity, list: List<Resume>, accent: Color) {
    Shelf("Continue watching") {
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(list, key = { it.key }) { r ->
                PosterCard(r.title, r.poster, r.subtitle, accent, r.progress) { a.playVod(r.url, r.title, r.subtitle, r.poster, r.key) }
            }
        }
    }
}

@Composable
private fun SeriesDetail(a: MainActivity, xc: Xc, s: VodItem, accent: Color, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val eps by produceState<List<Episode>?>(null, s.id) {
        value = withContext(Dispatchers.IO) { runCatching { Vod.episodes(xc, s.id) }.getOrDefault(emptyList()) }
    }
    val resume = a.resume.value.associateBy { it.key }
    val first = remember { FocusRequester() }
    Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 8.dp)) {
        Column(Modifier.width(220.dp)) {
            Box(Modifier.size(width = 160.dp, height = 236.dp).clip(RoundedCornerShape(12.dp)).background(Palette.card)) {
                if (s.poster.isNotBlank()) AsyncImage(s.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.height(12.dp))
            Text(s.name, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 3)
            Text(listOf(s.year, s.rating.let { if (it.isBlank()) "" else "★ $it" }).filter { it.isNotBlank() }.joinToString("  "), color = Palette.sub, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text(s.plot, color = Palette.sub, fontSize = 12.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(24.dp))
        val list = eps
        when {
            list == null -> Text("Loading episodes…", color = Palette.sub)
            list.isEmpty() -> Text("No episodes found.", color = Palette.sub)
            else -> {
                LaunchedEffect(list) { delay(100); runCatching { first.requestFocus() } }
                LazyColumn(contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(list, key = { _, e -> e.id }) { i, e ->
                        val showSeason = i == 0 || list[i - 1].season != e.season
                        if (showSeason) Text("Season ${e.season}", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = if (i == 0) 0.dp else 12.dp, bottom = 4.dp))
                        val key = "ep:${e.id}"
                        val r = resume[key]
                        Surface(
                            onClick = { a.playVod(xc.episodeUrl(e.id, e.ext), e.title.ifBlank { "Episode ${e.num}" }, "${s.name} · S${e.season} E${e.num}", s.poster, key) },
                            modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Palette.card, focusedContainerColor = Palette.cardFocused),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
                            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Palette.text))),
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("E${e.num}", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
                                    Text(e.title.ifBlank { "Episode ${e.num}" }, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (e.duration.isNotBlank()) Text(e.duration, color = Palette.sub, fontSize = 12.sp)
                                }
                                if (r != null) Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).background(Color(0x40FFFFFF))) {
                                    Box(Modifier.fillMaxHeight().fillMaxWidth(r.progress).background(accent))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiviewCard(n: Int, accent: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 176.dp, height = 176.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Palette.card, focusedContainerColor = Palette.card),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Palette.text))),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(accent.copy(alpha = 0.3f), 16.dp)),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(2) { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(2) { c ->
                            val on = r * 2 + c < n
                            Box(Modifier.size(width = 38.dp, height = 24.dp).clip(RoundedCornerShape(4.dp)).background(if (on) accent.copy(alpha = if (r + c == 0) 1f else 0.55f) else Palette.sub.copy(alpha = 0.2f)))
                        }
                    }
                }
            }
            Text("Multiview", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
            Text("Pick up to $n channels", color = Palette.sub, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MultiviewPicker(a: MainActivity, accent: Color) {
    val chans = a.shownChannels.value
    val guide = a.epg.value
    val showAdult = a.look.value.adult && !a.profile.value.kids
    val gameOpts by produceState<List<Pair<Channel, String>>?>(null, a.games.value, chans) { value = a.liveGameChannels() }
    val sections = remember(chans, gameOpts, a.favorites.value, a.recents.value, showAdult) {
        val byName = chans.associateBy { it.name }
        val groups = chans.groupBy { it.group }.filterKeys { showAdult || !isAdultGroup(it) }
        buildList<Pair<String, List<Pair<Channel, String>>>> {
            add("Live games" to gameOpts.orEmpty())
            add("Favorites" to chans.filter { it.name in a.favorites.value }.map { it to it.name })
            add("Recently watched" to a.recents.value.mapNotNull { byName[it] }.map { it to it.name })
            groups.forEach { (g, list) -> add(g to list.map { it to it.name }) }
        }.filter { it.second.isNotEmpty() || it.first == "Live games" }
    }
    var section by remember { mutableStateOf(0) }
    val opts = sections.getOrNull(section)?.second.orEmpty()
    val picked = remember { mutableStateListOf<Pair<Channel, String>>() }
    val first = remember { FocusRequester() }
    val startReq = remember { FocusRequester() }
    val now = System.currentTimeMillis()
    BackHandler { a.mvPicker.value = false }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Palette.bg.copy(alpha = 0.97f))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
            Text("Multiview", color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Pick 2 to 4 channels. Each one uses one of your provider streams.", color = Palette.sub, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    items(sections.size, key = { sections[it].first }) { i ->
                        val (name, list) = sections[i]
                        Surface(
                            onClick = { section = i },
                            modifier = Modifier.fillMaxWidth().height(38.dp).onFocusChanged { if (it.isFocused) section = i }.then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = ClickableSurfaceDefaults.colors(containerColor = if (i == section) Palette.card else Color.Transparent, focusedContainerColor = Palette.text, contentColor = if (i == section) Palette.text else Palette.sub, focusedContentColor = Palette.bg),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(if (name == "Live games" && gameOpts == null) "…" else "${list.size}", fontSize = 12.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(20.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    if (opts.isEmpty()) item("empty") {
                        Text(if (gameOpts == null) "Finding live games…" else "No live games with a channel right now.", color = Palette.sub, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(opts.size, key = { "$section-${opts[it].first.index}" }) { i ->
                        val o = opts[i]
                        val n = picked.indexOfFirst { it.first.index == o.first.index }
                        val onNow = guide[o.first.tvgId]?.firstOrNull { now in it.start until it.stop }?.title
                        PickRow(o.second, onNow, n + 1, accent) {
                            if (n >= 0) picked.removeAt(n) else if (picked.size < MultiviewActivity.MAX) picked += o
                        }
                    }
                }
                Spacer(Modifier.width(28.dp))
                Column(Modifier.width(280.dp).focusRestorer { startReq }.focusGroup()) {
                    PickButton(if (picked.size >= 2) "Start multiview" else "Pick at least 2", picked.size >= 2, accent, Modifier.focusRequester(startReq)) { a.startMultiview(picked.toList()) }
                    Spacer(Modifier.height(10.dp))
                    PickButton("Cancel", false, accent) { a.mvPicker.value = false }
                    Spacer(Modifier.height(20.dp))
                    Text("Selected ${picked.size} of ${MultiviewActivity.MAX}", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    picked.forEachIndexed { i, p -> Text("${i + 1}.  ${p.second}", color = Palette.sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
        }
    }
}

@Composable
private fun PickButton(label: String, primary: Boolean, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(44.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(containerColor = if (primary) accent else Palette.card, focusedContainerColor = Palette.text, contentColor = Palette.text, focusedContentColor = Palette.bg),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) } }
}

@Composable
private fun PickRow(label: String, sub: String?, n: Int, accent: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Palette.card, focusedContainerColor = Palette.text, contentColor = Palette.text, focusedContentColor = Palette.bg),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(if (n > 0) accent else Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
                if (n > 0) Text("$n", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(0.7f))
            }
        }
    }
}
