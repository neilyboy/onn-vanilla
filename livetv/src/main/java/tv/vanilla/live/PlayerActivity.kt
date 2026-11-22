@file:OptIn(ExperimentalTvMaterial3Api::class)

package tv.vanilla.live

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import coil.compose.AsyncImage
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@AndroidXOptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private val index = mutableIntStateOf(0)
    private var previousIndex = -1
    private val overlayTick = mutableIntStateOf(0)
    private val showList = mutableStateOf(false)
    private val kids by lazy { intent.getBooleanExtra("kids", false) }
    private val profileName by lazy { intent.getStringExtra("profile") ?: "" }
    private val error = mutableStateOf<String?>(null)
    private val typed = mutableStateOf("")
    private val commitTyped = Runnable {
        val i = findByNumber(Repo.channels, typed.value)
        if (i >= 0) tune(i) else error.value = "No channel ${typed.value}"
        typed.value = ""
    }
    private val buffering = mutableStateOf(true)
    private val games = mutableStateOf(Repo.games)
    private val kickoff = mutableStateOf<Pair<Game, Int>?>(null)
    private val tickerMode by lazy { Settings(this).ticker }

    private fun checkKickoff() {
        if (kickoff.value != null) return
        val g = dueKickoff(games.value, Repo.favTeams, Settings(this).reminders) ?: return
        Repo.notified += gameKey(g)
        lifecycleScope.launch {
            val ch = withContext(Dispatchers.Default) { findChannelForGame(g, Repo.channels, Repo.epg, Repo.aliases) } ?: return@launch
            if (ch.index == index.intValue) return@launch
            kickoff.value = g to ch.index
            window.decorView.postDelayed({ if (kickoff.value?.first == g) kickoff.value = null }, 30_000)
        }
    }
    private var retries = 0
    private val playedOnce = mutableStateOf(false)
    // A live stream that sits buffering usually means the provider feed stalled; reconnecting lets Dispatcharr fail over.
    private val stallCheck = Runnable {
        if (!buffering.value) return@Runnable
        if (retries++ < 3) { error.value = "Reconnecting…"; tune(index.intValue, keepPrevious = true) }
        else error.value = "This channel isn't responding. Try another."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Repo.loadCachedPlaylist(this)
        if (Repo.channels.isEmpty()) { finish(); return }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(slowStartHttp()))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 30_000, 1_500, 3_000).build())
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering.value = state == Player.STATE_BUFFERING
                window.decorView.removeCallbacks(stallCheck)
                if (state == Player.STATE_BUFFERING) window.decorView.postDelayed(stallCheck, if (playedOnce.value) 15_000 else 30_000)
                if (state == Player.STATE_READY) { retries = 0; error.value = null; playedOnce.value = true }
            }
            override fun onPlayerError(e: PlaybackException) {
                if (retries++ < 3) {
                    window.decorView.postDelayed({ tune(index.intValue, keepPrevious = true) }, 2_000)
                    error.value = "Reconnecting…"
                } else error.value = "Stream unavailable (${e.errorCodeName})"
            }
        })
        tune(intent.getIntExtra("index", 0))

        setContent { ScaledLook(this) { PlayerScreen() } }
    }

    private fun tune(i: Int, keepPrevious: Boolean = false) {
        val list = Repo.channels
        fun wrap(x: Int) = ((x % list.size) + list.size) % list.size
        var target = wrap(i)
        if (kids) {
            val dir = if (i < index.intValue) -1 else 1
            var n = 0
            while (isAdultGroup(list[target].group) && n++ < list.size) target = wrap(target + dir)
        }
        if (!keepPrevious && target != index.intValue) previousIndex = index.intValue
        if (!keepPrevious) { retries = 0; playedOnce.value = false }
        index.intValue = target
        error.value = null
        Settings(this).apply {
            lastChannel = list[target].url
            if (profileName.isNotBlank()) pushRecent(profileName, list[target].name)
        }
        player.setMediaItem(MediaItem.fromUri(list[target].url))
        player.prepare()
        player.playWhenReady = true
        overlayTick.intValue++
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        kickoff.value?.let { (_, i) ->
            val k = event.keyCode
            val ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
            if (ok || k == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) { kickoff.value = null; if (ok) tune(i) }
                return true
            }
        }
        if (showList.value || event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            typed.value = (typed.value + (event.keyCode - KeyEvent.KEYCODE_0)).takeLast(5)
            window.decorView.removeCallbacks(commitTyped)
            window.decorView.postDelayed(commitTyped, 1_800)
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { tune(index.intValue - 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { tune(index.intValue + 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO -> { overlayTick.intValue++; return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MENU -> { showList.value = true; return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_LAST_CHANNEL -> {
                if (previousIndex >= 0) tune(previousIndex)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) player.pause()
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized && player.playbackState != Player.STATE_IDLE) {
            // Live streams: jump back to the live edge instead of resuming stale buffer.
            tune(index.intValue, keepPrevious = true)
        }
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(stallCheck)
        window.decorView.removeCallbacks(commitTyped)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    @Composable
    private fun PlayerScreen() {
        val ch = Repo.channels[index.intValue]
        var overlay by remember { mutableStateOf(true) }
        val tick = overlayTick.intValue
        LaunchedEffect(tick) { overlay = true; delay(5_000); overlay = false }
        BackHandler(enabled = showList.value) { showList.value = false }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setKeepContentOnPlayerReset(false)
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        this.player = this@PlayerActivity.player
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (buffering.value || error.value != null) {
                Text(
                    error.value ?: if (playedOnce.value) "Buffering…" else "Tuning…", color = Color.White, fontSize = 20.sp,
                    modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)).padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }

            if (typed.value.isNotEmpty()) {
                Text(
                    typed.value, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(40.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xAA000000)).padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }

            LaunchedEffect(Unit) {
                while (true) {
                    if (Repo.leagues.isNotEmpty()) {
                        val g = withContext(Dispatchers.IO) { runCatching { fetchGames(Repo.leagues) }.getOrDefault(emptyList()) }
                        if (g.isNotEmpty()) { games.value = g; Repo.games = g }
                    }
                    checkKickoff()
                    delay(60_000)
                }
            }
            if (tickerMode > 0 && !showList.value) ScoreBar(games.value, tickerMode, Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
            kickoff.value?.let { (g, i) -> KickoffCard(g, Repo.channels.getOrNull(i)?.name ?: "", Palette.live, Modifier.align(Alignment.BottomEnd)) }

            AnimatedVisibility(overlay && !showList.value, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                InfoBar(ch)
            }

            AnimatedVisibility(showList.value, enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut()) {
                ChannelList()
            }
        }
    }

    @Composable
    private fun ScoreBar(all: List<Game>, mode: Int, modifier: Modifier) {
        val favs = Repo.favTeams
        val live = all.filter { it.state == "in" && (mode == 2 || isFavGame(it, favs)) }.sortedByDescending { isFavGame(it, favs) }
        if (live.isEmpty()) return
        val perPage = 4
        val pages = (live.size + perPage - 1) / perPage
        var i by remember { mutableIntStateOf(0) }
        LaunchedEffect(pages) { while (true) { delay(10_000); i++ } }
        val page = i % pages
        Row(
            modifier.clip(RoundedCornerShape(50)).background(Color(0xB3000000)).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            live.drop(page * perPage).take(perPage).forEachIndexed { n, g ->
                if (n > 0) Box(Modifier.padding(horizontal = 14.dp).size(width = 1.dp, height = 18.dp).background(Color(0x40FFFFFF)))
                listOf(g.away, g.home).forEachIndexed { k, t ->
                    if (k == 1) Spacer(Modifier.width(8.dp))
                    AsyncImage(t.logo, null, Modifier.size(18.dp))
                    val fav = t.abbr.uppercase() in favs
                    Text(" ${t.abbr} ", color = if (fav) Color.White else Color(0xCCFFFFFF), fontSize = 13.sp, fontWeight = if (fav) FontWeight.Bold else FontWeight.Medium)
                    Text(t.score, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Text("  ${g.detail}", color = Palette.sub, fontSize = 12.sp, maxLines = 1)
            }
            if (pages > 1) Text("   ${page + 1}/$pages", color = Color(0x66FFFFFF), fontSize = 11.sp)
        }
    }

    @Composable
    private fun InfoBar(ch: Channel) {
        val (now, next) = nowNext(Repo.epg[ch.tvgId])
        val fmt = DateTimeFormatter.ofPattern("h:mm a")
        fun t(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(fmt)
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = 40.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ch.logo.isNotBlank()) {
                    AsyncImage(ch.logo, null, Modifier.size(width = 96.dp, height = 56.dp), contentScale = ContentScale.Fit)
                    Spacer(Modifier.width(18.dp))
                }
                if (ch.number.isNotBlank()) Text(ch.number, color = Palette.sub, fontSize = 28.sp, modifier = Modifier.padding(end = 14.dp))
                Text(ch.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            }
            if (now != null) {
                Spacer(Modifier.height(10.dp))
                Text("${t(now.start)} – ${t(now.stop)}   ${now.title}", color = Color.White, fontSize = 20.sp)
                val frac = ((System.currentTimeMillis() - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.padding(vertical = 8.dp).width(520.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Palette.live))
                }
                if (now.desc.isNotBlank()) Text(now.desc, color = Palette.sub, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 900.dp))
            }
            if (next != null) Text("Next  ${t(next.start)}   ${next.title}", color = Palette.sub, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
            Text("▲▼ channel   0–9 go to number   ◀ guide   ▶ last channel   OK info", color = Color(0x66FFFFFF), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }

    @Composable
    private fun ChannelList() {
        val list = Repo.channels
        val state = rememberLazyListState(initialFirstVisibleItemIndex = (index.intValue - 4).coerceAtLeast(0))
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(50); runCatching { focus.requestFocus() } }
        LazyColumn(
            state = state,
            modifier = Modifier.width(460.dp).fillMaxHeight().background(Color(0xEE0B0D12)),
            contentPadding = PaddingValues(vertical = 32.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val shown = list.withIndex().filter { !kids || !isAdultGroup(it.value.group) }
            items(shown, key = { it.value.index }) { (i, c) ->
                val (now, _) = nowNext(Repo.epg[c.tvgId])
                Surface(
                    onClick = { showList.value = false; tune(i) },
                    modifier = Modifier.fillMaxWidth().then(if (i == index.intValue) Modifier.focusRequester(focus) else Modifier),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Palette.cardFocused),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.number.ifBlank { "${i + 1}" }, color = Palette.sub, fontSize = 15.sp, modifier = Modifier.width(52.dp))
                        Column {
                            Text(c.name, color = if (i == index.intValue) Palette.live else Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (now != null) Text(now.title, color = Palette.sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
