package tv.vanilla.live

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** 2–4 live channels at once. Arrows pick which tile has the sound, OK opens it full screen. */
@OptIn(UnstableApi::class)
class MultiviewActivity : ComponentActivity() {
    private val players = mutableStateListOf<ExoPlayer>()
    private val states = mutableStateListOf<String>()
    private val focus = mutableIntStateOf(0)
    private val hint = mutableIntStateOf(0)
    private var indices = intArrayOf()
    private var labels = arrayOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Repo.loadCachedPlaylist(this)
        indices = (intent.getIntArrayExtra("indices") ?: intArrayOf()).filter { it in Repo.channels.indices }.take(MAX).toIntArray()
        labels = intent.getStringArrayExtra("labels") ?: arrayOf()
        if (indices.size < 2) { finish(); return }
        setContent { ScaledLook(this) { Screen() } }
    }

    override fun onStart() {
        super.onStart()
        if (players.isNotEmpty() || indices.isEmpty()) return
        indices.forEachIndexed { i, ch ->
            states += "Loading…"
            val p = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
                .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(slowStartHttp()))
                .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(3_000, 15_000, 1_500, 3_000).build())
                .build()
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(s: Int) {
                    if (i < states.size) states[i] = if (s == Player.STATE_BUFFERING) "Buffering…" else ""
                }
                override fun onPlayerError(e: PlaybackException) {
                    if (i < states.size) states[i] = "Stream unavailable"
                    window.decorView.postDelayed({ if (i < players.size) { players[i].prepare(); players[i].play() } }, 4_000)
                }
            })
            p.setMediaItem(MediaItem.fromUri(Repo.channels[ch].url))
            p.volume = if (i == focus.intValue) 1f else 0f
            p.prepare(); p.play()
            players += p
        }
    }

    override fun onStop() {
        super.onStop()
        players.forEach { it.release() }
        players.clear(); states.clear()
    }

    private fun setFocus(i: Int) {
        focus.intValue = i.coerceIn(0, indices.size - 1)
        players.forEachIndexed { k, p -> p.volume = if (k == focus.intValue) 1f else 0f }
        hint.intValue++
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val n = indices.size
        val cols = 2
        val f = focus.intValue
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> setFocus(if (f % cols == 0) f else f - 1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> setFocus(if (f % cols == 1 || f + 1 >= n) f else f + 1)
            KeyEvent.KEYCODE_DPAD_UP -> setFocus(if (f >= cols) f - cols else f)
            KeyEvent.KEYCODE_DPAD_DOWN -> setFocus(if (f + cols < n) f + cols else f)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val ch = indices[f]
                players.forEach { it.release() }; players.clear()
                startActivity(Intent(this, PlayerActivity::class.java).putExtras(intent).putExtra("index", ch).putExtra("url", Repo.channels[ch].url))
                finish()
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    @androidx.compose.runtime.Composable
    private fun Screen() {
        var showHint by remember { mutableStateOf(true) }
        LaunchedEffect(hint.intValue) { showHint = true; delay(4_000); showHint = false }
        val n = indices.size
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val rows = if (n <= 2) listOf(0 until n) else listOf(0 until 2, 2 until n)
                rows.forEach { r ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        r.forEach { i -> Tile(i, Modifier.weight(1f, fill = n != 3 || i != 2).fillMaxHeight()) }
                        if (n == 3 && r.first == 2) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (showHint) Text(
                "◀ ▶ ▲ ▼ pick sound  ·  OK full screen  ·  Back exit",
                color = Color.White, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Color(0xCC000000)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun Tile(i: Int, modifier: Modifier) {
        val sel = focus.intValue == i
        Box(
            modifier.clip(RoundedCornerShape(10.dp))
                .border(if (sel) 3.dp else 1.dp, if (sel) Color.White else Color(0x33FFFFFF), RoundedCornerShape(10.dp))
                .background(Color(0xFF0B0C10))
        ) {
            players.getOrNull(i)?.let { p ->
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; player = p } },
                    update = { it.player = p },
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                )
            }
            val label = labels.getOrNull(i)?.ifBlank { null } ?: Repo.channels.getOrNull(indices[i])?.name.orEmpty()
            Row(
                Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xB3000000)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (sel) Text("🔊 ", fontSize = 12.sp)
                Text(label, color = Color.White, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            }
            states.getOrNull(i)?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color(0xCCFFFFFF), fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    companion object { const val MAX = 4 }
}
