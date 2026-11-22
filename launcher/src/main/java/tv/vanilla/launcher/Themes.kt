package tv.vanilla.launcher

import androidx.compose.ui.graphics.Color

data class VanillaTheme(
    val background: List<Color>,
    val text: Color,
    val subText: Color,
    val tile: Color,
    val tileFocused: Color,
    val accent: Color,
    val scrim: Color,
)

private fun hex(s: String): Color? = runCatching {
    Color(android.graphics.Color.parseColor(if (s.startsWith("#")) s else "#$s"))
}.getOrNull()

val themes: Map<String, VanillaTheme> = mapOf(
    "midnight" to VanillaTheme(
        background = listOf(Color(0xFF0B0D12), Color(0xFF141826), Color(0xFF0B0D12)),
        text = Color(0xFFF2F2F2), subText = Color(0xB3E6E6E6),
        tile = Color(0x33FFFFFF), tileFocused = Color(0x55FFFFFF),
        accent = Color(0xFF8AB4F8), scrim = Color(0xCC000000),
    ),
    "graphite" to VanillaTheme(
        background = listOf(Color(0xFF111111), Color(0xFF1C1C1C), Color(0xFF111111)),
        text = Color(0xFFEDEDED), subText = Color(0xA6EDEDED),
        tile = Color(0x26FFFFFF), tileFocused = Color(0x4DFFFFFF),
        accent = Color(0xFFFFFFFF), scrim = Color(0xCC000000),
    ),
    "nord" to VanillaTheme(
        background = listOf(Color(0xFF2E3440), Color(0xFF3B4252), Color(0xFF2E3440)),
        text = Color(0xFFECEFF4), subText = Color(0xB3D8DEE9),
        tile = Color(0x334C566A), tileFocused = Color(0x6688C0D0),
        accent = Color(0xFF88C0D0), scrim = Color(0xAA2E3440),
    ),
    "forest" to VanillaTheme(
        background = listOf(Color(0xFF0E1A14), Color(0xFF16261D), Color(0xFF0E1A14)),
        text = Color(0xFFEFF5EF), subText = Color(0xB3D5E3D5),
        tile = Color(0x2EFFFFFF), tileFocused = Color(0x5591C49B),
        accent = Color(0xFF91C49B), scrim = Color(0xCC000000),
    ),
    "sand" to VanillaTheme(
        background = listOf(Color(0xFFEDE6DA), Color(0xFFF6F1E8), Color(0xFFE6DDCD)),
        text = Color(0xFF26231F), subText = Color(0xA626231F),
        tile = Color(0x22000000), tileFocused = Color(0x44000000),
        accent = Color(0xFF8C6A43), scrim = Color(0x66F6F1E8),
    ),
    "oled" to VanillaTheme(
        background = listOf(Color.Black, Color.Black, Color.Black),
        text = Color(0xFFE0E0E0), subText = Color(0x99E0E0E0),
        tile = Color(0x1AFFFFFF), tileFocused = Color(0x40FFFFFF),
        accent = Color(0xFFE0E0E0), scrim = Color(0xFF000000),
    ),
)

fun themeFor(cfg: LauncherConfig): VanillaTheme {
    val base = themes[cfg.themeName] ?: themes.getValue("midnight")
    val accent = hex(cfg.accent) ?: return base
    return base.copy(accent = accent)
}
