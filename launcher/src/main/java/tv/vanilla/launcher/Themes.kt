package tv.vanilla.launcher

import androidx.compose.ui.graphics.Color

/** [style]: "flat" (plain panels), "glass" (frosted, bright edge), "neon" (dark HUD with glow) or "paper" (light). */
data class VanillaTheme(
    val background: List<Color>,
    val text: Color,
    val subText: Color,
    val tile: Color,
    val tileFocused: Color,
    val accent: Color,
    val scrim: Color,
    val style: String = "flat",
    val radius: Int = 18,
    val panel: Color = Color(0x59000000),
    val edge: Color = Color.Transparent,
    val glow: Color = Color.Transparent,
    val monoClock: Boolean = false,
    val animated: Boolean = false,
    val label: String = "",
)

private fun hex(s: String): Color? = runCatching {
    Color(android.graphics.Color.parseColor(if (s.startsWith("#")) s else "#$s"))
}.getOrNull()

val themes: Map<String, VanillaTheme> = linkedMapOf(
    "glass" to VanillaTheme(
        background = listOf(Color(0xFF16305A), Color(0xFF0D1220), Color(0xFF5A1F44)),
        text = Color(0xFFF5F7FA), subText = Color(0xB3E8ECF2),
        tile = Color(0x26FFFFFF), tileFocused = Color(0x4DFFFFFF),
        accent = Color(0xFFFFFFFF), scrim = Color(0x99000000),
        style = "glass", radius = 22, panel = Color(0x1FFFFFFF), edge = Color(0x33FFFFFF), glow = Color(0x66FFFFFF), label = "Glass",
    ),
    "neon" to VanillaTheme(
        background = listOf(Color(0xFF05060B), Color(0xFF070912), Color(0xFF1A0630)),
        text = Color(0xFFD8F6FF), subText = Color(0x99BFEFFF),
        tile = Color(0x2600F0FF), tileFocused = Color(0x4D00F0FF),
        accent = Color(0xFF00F0FF), scrim = Color(0xCC000000),
        style = "neon", radius = 6, panel = Color(0xD9080E1A), edge = Color(0x4000F0FF), glow = Color(0xAA00F0FF), monoClock = true, label = "Neon",
    ),
    "oled" to VanillaTheme(
        background = listOf(Color.Black, Color.Black, Color.Black),
        text = Color(0xFFE0E0E0), subText = Color(0x99E0E0E0),
        tile = Color(0x1AFFFFFF), tileFocused = Color(0x40FFFFFF),
        accent = Color(0xFFE0E0E0), scrim = Color(0xFF000000),
        panel = Color(0xFF0B0B0B), edge = Color(0x14FFFFFF), label = "OLED",
    ),
    "paper" to VanillaTheme(
        background = listOf(Color(0xFFF4F1EA), Color(0xFFF8F6F1), Color(0xFFECE7DC)),
        text = Color(0xFF1F1D1A), subText = Color(0x991F1D1A),
        tile = Color(0x14000000), tileFocused = Color(0x2E000000),
        accent = Color(0xFFD9480F), scrim = Color(0x33F4F1EA),
        style = "paper", radius = 16, panel = Color(0xB3FFFFFF), edge = Color(0x1A000000), label = "Paper",
    ),
    "wallpaper" to VanillaTheme(
        background = listOf(Color(0xFF1B2230), Color(0xFF10141C), Color(0xFF1B2230)),
        text = Color(0xFFF5F5F5), subText = Color(0xB3EEEEEE),
        tile = Color(0x26FFFFFF), tileFocused = Color(0x4DFFFFFF),
        accent = Color(0xFF8AB4F8), scrim = Color(0xAA000000),
        style = "glass", radius = 20, panel = Color(0x33000000), edge = Color(0x26FFFFFF), glow = Color(0x55FFFFFF), label = "From wallpaper",
    ),
    "aurora" to VanillaTheme(
        background = listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)),
        text = Color(0xFFF0FBFF), subText = Color(0xB3D6F2FA),
        tile = Color(0x26FFFFFF), tileFocused = Color(0x4DFFFFFF),
        accent = Color(0xFF7CF5C8), scrim = Color(0x88000000),
        style = "glass", radius = 22, panel = Color(0x26FFFFFF), edge = Color(0x2EFFFFFF), glow = Color(0x667CF5C8), animated = true, label = "Aurora",
    ),
    "midnight" to VanillaTheme(
        background = listOf(Color(0xFF0B0D12), Color(0xFF141826), Color(0xFF0B0D12)),
        text = Color(0xFFF2F2F2), subText = Color(0xB3E6E6E6),
        tile = Color(0x33FFFFFF), tileFocused = Color(0x55FFFFFF),
        accent = Color(0xFF8AB4F8), scrim = Color(0xCC000000), label = "Midnight",
    ),
    "graphite" to VanillaTheme(
        background = listOf(Color(0xFF111111), Color(0xFF1C1C1C), Color(0xFF111111)),
        text = Color(0xFFEDEDED), subText = Color(0xA6EDEDED),
        tile = Color(0x26FFFFFF), tileFocused = Color(0x4DFFFFFF),
        accent = Color(0xFFFFFFFF), scrim = Color(0xCC000000), label = "Graphite",
    ),
    "nord" to VanillaTheme(
        background = listOf(Color(0xFF2E3440), Color(0xFF3B4252), Color(0xFF2E3440)),
        text = Color(0xFFECEFF4), subText = Color(0xB3D8DEE9),
        tile = Color(0x334C566A), tileFocused = Color(0x6688C0D0),
        accent = Color(0xFF88C0D0), scrim = Color(0xAA2E3440), panel = Color(0x592E3440), label = "Nord",
    ),
    "forest" to VanillaTheme(
        background = listOf(Color(0xFF0E1A14), Color(0xFF16261D), Color(0xFF0E1A14)),
        text = Color(0xFFEFF5EF), subText = Color(0xB3D5E3D5),
        tile = Color(0x2EFFFFFF), tileFocused = Color(0x5591C49B),
        accent = Color(0xFF91C49B), scrim = Color(0xCC000000), label = "Forest",
    ),
    "sand" to VanillaTheme(
        background = listOf(Color(0xFFEDE6DA), Color(0xFFF6F1E8), Color(0xFFE6DDCD)),
        text = Color(0xFF26231F), subText = Color(0xA626231F),
        tile = Color(0x22000000), tileFocused = Color(0x44000000),
        accent = Color(0xFF8C6A43), scrim = Color(0x66F6F1E8), style = "paper", panel = Color(0x66FFFFFF), label = "Sand",
    ),
)

val ACCENTS = listOf("", "#FFFFFF", "#8AB4F8", "#00F0FF", "#7CF5C8", "#91C49B", "#FFD54A", "#FF8A3D", "#E5484D", "#FF2BD6", "#B388FF")

/** [wallpaperColor] is the average colour of the current wallpaper, used by the "From wallpaper" theme. */
fun themeFor(name: String, accentHex: String, wallpaperColor: Color? = null): VanillaTheme {
    var base = themes[name] ?: themes.getValue("midnight")
    if (name == "wallpaper" && wallpaperColor != null) {
        val c = wallpaperColor
        fun shade(f: Float) = Color(c.red * f, c.green * f, c.blue * f, 1f)
        val light = Color(c.red + (1 - c.red) * 0.55f, c.green + (1 - c.green) * 0.55f, c.blue + (1 - c.blue) * 0.55f, 1f)
        base = base.copy(background = listOf(shade(0.45f), shade(0.22f), shade(0.4f)), accent = light, glow = light.copy(alpha = 0.4f))
    }
    val accent = hex(accentHex) ?: return base
    return base.copy(accent = accent, glow = if (base.glow.alpha > 0f) accent.copy(alpha = base.glow.alpha) else base.glow)
}
