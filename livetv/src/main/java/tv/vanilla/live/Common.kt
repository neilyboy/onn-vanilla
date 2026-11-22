package tv.vanilla.live

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

object Palette {
    var bg by mutableStateOf(Color(0xFF0B0D12))
    var card by mutableStateOf(Color(0xFF171A22))
    var cardFocused by mutableStateOf(Color(0xFF262B38))
    val text = Color(0xFFF2F2F2)
    val sub = Color(0xFF9AA0AA)
    val live = Color(0xFFE5484D)

    fun apply(look: Look) {
        val (b, c, f) = Look.BACKGROUNDS[look.background] ?: Look.BACKGROUNDS.getValue("midnight")
        bg = b; card = c; cardFocused = f
    }
}

fun parseColor(hex: String, fallback: Color): Color = runCatching {
    if (hex.isBlank()) fallback else Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
}.getOrDefault(fallback)

/** Everything is laid out at this fraction of Compose's default size; Settings → Screen size scales from here. */
const val BASE_SCALE = 0.7f

/** Applies the saved screen and text size to screens outside the main UI (players). */
@androidx.compose.runtime.Composable
fun ScaledLook(ctx: android.content.Context, content: @androidx.compose.runtime.Composable () -> Unit) {
    val p = ctx.getSharedPreferences("look", android.content.Context.MODE_PRIVATE)
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * BASE_SCALE * p.getFloat("scale", 1f), d.fontScale * p.getFloat("text", 1f)),
        content = content,
    )
}
