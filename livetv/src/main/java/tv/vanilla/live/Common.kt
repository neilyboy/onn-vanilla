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
