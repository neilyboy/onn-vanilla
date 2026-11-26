package tv.vanilla.launcher

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.runBlocking

/** Look and layout choices made on the TV. A null value means "use the setup app's setting". */
data class HomeLook(
    val theme: String? = null,
    val accent: String? = null,
    val preset: String? = null,
    val iconSize: String = "s",
    val gameDay: Boolean = true,
    val timeOfDay: Boolean = false,
) {
    fun save(p: SharedPreferences) = p.edit()
        .putString("theme", theme).putString("accent", accent).putString("preset", preset)
        .putString("icon_size", iconSize).putBoolean("game_day", gameDay).putBoolean("tod", timeOfDay).apply()

    val iconDp get() = when (iconSize) { "l" -> 96; "m" -> 72; else -> 56 }

    companion object {
        fun load(p: SharedPreferences) = HomeLook(
            p.getString("theme", null), p.getString("accent", null), p.getString("preset", null),
            p.getString("icon_size", "s") ?: "s", p.getBoolean("game_day", true), p.getBoolean("tod", false),
        )
    }
}

val ICON_SIZES = linkedMapOf("s" to "Small", "m" to "Medium", "l" to "Large")

/** Average colour of an image, from a tiny decode. */
fun averageColor(ctx: Context, url: String): Color? = runCatching {
    val req = ImageRequest.Builder(ctx).data(url).size(24, 24).allowHardware(false).build()
    val bmp = ((runBlocking { ctx.imageLoader.execute(req) } as? SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        ?: return null
    val b = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
    var r = 0L; var g = 0L; var bl = 0L; var n = 0
    for (y in 0 until b.height) for (x in 0 until b.width) {
        val c = b.getPixel(x, y); r += (c shr 16) and 255; g += (c shr 8) and 255; bl += c and 255; n++
    }
    if (n == 0) null else Color(r / n / 255f, g / n / 255f, bl / n / 255f)
}.getOrNull()
