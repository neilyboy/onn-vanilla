package tv.vanilla.live

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** Movies and episodes: seekable playback that remembers where each profile stopped. */
@OptIn(UnstableApi::class)
class VodPlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private val overlayTick = mutableIntStateOf(0)
    private val paused = mutableStateOf(false)
    private val buffering = mutableStateOf(true)
    private val error = mutableStateOf<String?>(null)
    private val pos = mutableLongStateOf(0)
    private val dur = mutableLongStateOf(0)
    private val title by lazy { intent.getStringExtra("title") ?: "" }
    private val subtitle by lazy { intent.getStringExtra("subtitle") ?: "" }
    private val profile by lazy { intent.getStringExtra("profile") ?: "" }
    private val key by lazy { intent.getStringExtra("key") ?: intent.getStringExtra("url") ?: "" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        player = ExoPlayer.Builder(this).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering.value = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) { save(); finish() }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { paused.value = !isPlaying && player.playbackState == Player.STATE_READY }
            override fun onPlayerError(e: PlaybackException) { error.value = "Can't play this title (${e.errorCodeName})" }
        })
        player.setMediaItem(MediaItem.fromUri(url))
        val start = ResumeStore.get(this, profile, key)?.pos ?: 0L
        player.prepare()
        if (start > 0) player.seekTo(start)
        player.playWhenReady = true
        setContent { ScaledLook(this) { Screen() } }
    }

    private fun save() {
        if (!::player.isInitialized || player.duration <= 0) return
        ResumeStore.save(
            this, profile,
            Resume(key, title, subtitle, intent.getStringExtra("poster") ?: "", intent.getStringExtra("url") ?: "", player.currentPosition, player.duration, System.currentTimeMillis()),
        )
    }

    override fun onPause() { save(); super.onPause() }
    override fun onStop() { super.onStop(); if (::player.isInitialized) player.pause() }
    override fun onDestroy() { if (::player.isInitialized) player.release(); super.onDestroy() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val step = if (event.repeatCount > 3) 60_000L else if (event.repeatCount > 0) 30_000L else 10_000L
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> player.seekTo((player.currentPosition - step).coerceAtLeast(0))
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> player.seekTo(player.currentPosition + step)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE ->
                if (player.isPlaying) player.pause() else player.play()
            KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> {}
            else -> return super.dispatchKeyEvent(event)
        }
        overlayTick.intValue++
        return true
    }

    @Composable
    private fun Screen() {
        var overlay by remember { mutableStateOf(true) }
        val tick = overlayTick.intValue
        LaunchedEffect(tick) { overlay = true; delay(4_000); overlay = false }
        LaunchedEffect(Unit) { while (true) { pos.longValue = player.currentPosition; dur.longValue = player.duration.coerceAtLeast(0); delay(500) } }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView({ PlayerView(it).apply { useController = false; player = this@VodPlayerActivity.player } }, Modifier.fillMaxSize())
            if (buffering.value && error.value == null) Text("Loading…", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
            error.value?.let { Text(it, color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center)) }
            if (overlay || paused.value) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                        .padding(horizontal = 48.dp, vertical = 32.dp)
                ) {
                    if (subtitle.isNotBlank()) Text(subtitle, color = Color(0xB3FFFFFF), fontSize = 14.sp)
                    Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.height(14.dp))
                    val d = dur.longValue
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(if (d > 0) (pos.longValue.toFloat() / d).coerceIn(0f, 1f) else 0f).background(Palette.live))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text(fmt(pos.longValue), color = Color.White, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text(if (paused.value) "PAUSED   ·   " else "", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("◀ ▶ skip (hold to go faster)   OK pause   Back exit", color = Color(0x80FFFFFF), fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text(if (d > 0) "-" + fmt(d - pos.longValue) else "", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
