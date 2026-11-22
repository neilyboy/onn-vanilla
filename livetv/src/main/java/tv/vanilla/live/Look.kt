package tv.vanilla.live

import android.content.Context
import androidx.compose.ui.graphics.Color

/** On-TV appearance settings. Stored per box; the setup app can seed them via livetv.look. */
data class Look(
    val scale: Float = 1f,
    val text: Float = 1f,
    val rows: Int = 1,
    val guideHours: Int = 3,
    val accent: String = "",
    val background: String = "midnight",
    val guideLogos: Boolean = true,
    val hero: Int = 1,
    val adult: Boolean = true,
) {
    val rowHeightDp get() = listOf(50, 62, 80)[rows.coerceIn(0, 2)]

    fun save(context: Context) {
        context.getSharedPreferences("look", Context.MODE_PRIVATE).edit()
            .putFloat("scale", scale).putFloat("text", text).putInt("rows", rows).putInt("hours", guideHours)
            .putString("accent", accent).putString("background", background).putBoolean("logos", guideLogos)
            .putInt("hero", hero).putBoolean("adult", adult).apply()
    }

    companion object {
        val SCALES = listOf(0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.4f)
        val TEXTS = listOf(0.9f to "Small", 1f to "Normal", 1.15f to "Large", 1.3f to "Extra large")
        val ROWS = listOf("Compact", "Normal", "Large")
        val HOURS = listOf(2, 3, 4)
        val HEROES = listOf("Off", "Compact", "Large")
        val ACCENTS = listOf(
            "" to "From config", "#E5484D" to "Red", "#3E8BFF" to "Blue", "#30A46C" to "Green",
            "#F76B15" to "Orange", "#8E4EC6" to "Purple", "#12A594" to "Teal", "#FFC53D" to "Gold", "#EDEDED" to "White",
        )
        val BACKGROUNDS = linkedMapOf(
            "midnight" to Triple(Color(0xFF0B0D12), Color(0xFF171A22), Color(0xFF262B38)),
            "oled" to Triple(Color(0xFF000000), Color(0xFF111111), Color(0xFF222222)),
            "navy" to Triple(Color(0xFF0A1428), Color(0xFF14213D), Color(0xFF22345C)),
            "graphite" to Triple(Color(0xFF161616), Color(0xFF222222), Color(0xFF333333)),
        )

        fun load(context: Context): Look? {
            val p = context.getSharedPreferences("look", Context.MODE_PRIVATE)
            if (!p.contains("scale")) return null
            return Look(
                p.getFloat("scale", 1f), p.getFloat("text", 1f), p.getInt("rows", 1), p.getInt("hours", 3),
                p.getString("accent", "") ?: "", p.getString("background", "midnight") ?: "midnight", p.getBoolean("logos", true),
                p.getInt("hero", 1), p.getBoolean("adult", true),
            )
        }

        fun fromJson(o: org.json.JSONObject?): Look = if (o == null) Look() else Look(
            o.optDouble("scale", 1.0).toFloat(), o.optDouble("text", 1.0).toFloat(), o.optInt("rows", 1),
            o.optInt("guide_hours", 3), o.optString("accent", ""), o.optString("background", "midnight"),
            o.optBoolean("guide_logos", true), o.optInt("hero", 1), o.optBoolean("adult_channels", true),
        )
    }
}

fun <T> List<T>.next(cur: T): T = this[(indexOf(cur).coerceAtLeast(0) + 1) % size]
