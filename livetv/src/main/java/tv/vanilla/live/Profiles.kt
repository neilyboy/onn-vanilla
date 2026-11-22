@file:OptIn(ExperimentalTvMaterial3Api::class)

package tv.vanilla.live

import android.view.KeyEvent.KEYCODE_0
import android.view.KeyEvent.KEYCODE_9
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.delay

val PROFILE_COLORS = listOf(
    Color(0xFFE5484D), Color(0xFF3E9BFF), Color(0xFFF5A524), Color(0xFF8E6CEF),
    Color(0xFF30A46C), Color(0xFFE86FB4), Color(0xFF12A5A5),
)

fun profileColor(p: Profile, i: Int) = parseColor(p.color, PROFILE_COLORS[i.coerceAtLeast(0) % PROFILE_COLORS.size])

@Composable
fun Avatar(p: Profile, i: Int, size: Dp) {
    val c = profileColor(p, i)
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(c, lerp(c, Color.Black, 0.45f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(p.name.trim().take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.44f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ProfilePicker(a: MainActivity) {
    val list = a.config.value?.profiles ?: DEFAULT_PROFILES
    val current = list.indexOfFirst { it.name == a.profile.value.name }.coerceAtLeast(0)
    val focus = remember { FocusRequester() }
    BackHandler { a.showPicker.value = false }
    LaunchedEffect(Unit) { delay(80); runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxSize().background(Palette.bg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Who's watching?", color = Palette.text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(44.dp))
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), contentPadding = PaddingValues(horizontal = 48.dp)) {
            itemsIndexed(list) { i, p ->
                var focused by remember { mutableStateOf(false) }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(124.dp)) {
                    Surface(
                        onClick = { a.requestProfile(p) },
                        modifier = Modifier.size(96.dp).onFocusChanged { focused = it.isFocused }
                            .then(if (i == current) Modifier.focusRequester(focus) else Modifier),
                        shape = ClickableSurfaceDefaults.shape(CircleShape),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Palette.text), inset = 4.dp, shape = CircleShape)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                    ) { Avatar(p, i, 96.dp) }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        p.name, color = if (focused) Palette.text else Palette.sub, fontSize = 16.sp, maxLines = 2,
                        textAlign = TextAlign.Center, lineHeight = 19.sp,
                        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    val tag = listOfNotNull("Kids".takeIf { p.kids }, "PIN".takeIf { p.pin.isNotBlank() }).joinToString(" · ")
                    Text(tag.ifBlank { " " }, color = Palette.sub, fontSize = 12.sp)
                }
            }
        }
    }
}

/** D-pad friendly PIN entry: up/down change the digit, left/right move, OK submits. Number keys work too. */
@Composable
fun PinPad(title: String, length: Int, accent: Color, onSubmit: (String) -> Boolean, onCancel: () -> Unit) {
    val n = length.coerceIn(4, 8)
    var digits by remember { mutableStateOf(List(n) { 0 }) }
    var pos by remember { mutableIntStateOf(0) }
    var wrong by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    BackHandler { onCancel() }
    LaunchedEffect(Unit) { delay(80); runCatching { focus.requestFocus() } }
    fun submit() { if (!onSubmit(digits.joinToString(""))) { wrong = true; digits = List(n) { 0 }; pos = 0 } }
    fun set(d: Int) { digits = digits.toMutableList().also { it[pos] = d }; wrong = false }
    Column(
        Modifier.fillMaxSize().background(Palette.bg)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                when {
                    e.key == Key.DirectionUp -> { set((digits[pos] + 1) % 10); true }
                    e.key == Key.DirectionDown -> { set((digits[pos] + 9) % 10); true }
                    e.key == Key.DirectionLeft -> { pos = (pos - 1).coerceAtLeast(0); true }
                    e.key == Key.DirectionRight -> { pos = (pos + 1).coerceAtMost(n - 1); true }
                    code in KEYCODE_0..KEYCODE_9 -> { set(code - KEYCODE_0); if (pos < n - 1) pos++ else submit(); true }
                    e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter -> { submit(); true }
                    else -> false
                }
            }
            .focusRequester(focus).focusable(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = Palette.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            digits.forEachIndexed { i, d ->
                val sel = i == pos
                Box(
                    Modifier.size(width = 64.dp, height = 80.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (sel) Palette.cardFocused else Palette.card)
                        .border(2.dp, if (sel) accent else Color.Transparent, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (sel) d.toString() else if (i < pos) "•" else "–", color = Palette.text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            if (wrong) "Wrong PIN, try again" else "▲ ▼ change digit    ◀ ▶ move    OK unlock    Back cancel",
            color = if (wrong) accent else Palette.sub, fontSize = 15.sp,
        )
    }
}
