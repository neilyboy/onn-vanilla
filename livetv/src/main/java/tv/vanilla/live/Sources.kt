package tv.vanilla.live
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** A text field for the remote: arrows move between fields; OK starts typing, the keyboard's Next/Done stops. */
@Composable
private fun TvField(label: String, value: String, accent: Color, secret: Boolean = false, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val fm = LocalFocusManager.current
    val kb = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(editing) { if (editing) { delay(80); kb?.show() } }
    Column {
        Text(label, color = Palette.sub, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true, readOnly = !editing,
            textStyle = TextStyle(color = Palette.text, fontSize = 17.sp), cursorBrush = SolidColor(accent),
            visualTransformation = if (secret && !editing) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onNext = { kb?.hide(); editing = false; fm.moveFocus(FocusDirection.Down) }),
            modifier = modifier
                .onFocusChanged { focused = it.isFocused; if (!it.isFocused) editing = false }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else when (e.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (editing) false else { editing = true; true }
                        Key.DirectionDown -> { kb?.hide(); fm.moveFocus(FocusDirection.Down) }
                        Key.DirectionUp -> { kb?.hide(); fm.moveFocus(FocusDirection.Up) }
                        else -> false
                    }
                }
                .width(560.dp).clip(RoundedCornerShape(10.dp)).background(Palette.card)
                .border(if (focused) 2.dp else 1.dp, if (focused) accent else Palette.sub.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
        )
    }
}

/** Checks an Xtream login or M3U link before saving, so a typo shows up right away. */
private fun checkSource(s: Source): String? = runCatching {
    if (s.kind == "xtream") {
        val o = org.json.JSONObject(httpGet(s.xc()!!.api("").substringBefore("&action="), 20000))
        val u = o.optJSONObject("user_info") ?: return "That server didn't answer like an Xtream Codes server."
        if (u.optInt("auth", 0) != 1) return "Wrong username or password."
        if (u.optString("status").let { it.isNotBlank() && !it.equals("Active", true) }) return "Account status: ${u.optString("status")}"
        null
    } else {
        val (conn, st) = httpOpen(s.url.trim(), 20000)
        val head = try { String(st.readNBytes(4096)) } finally { conn.disconnect() }
        if (!head.contains("#EXTM3U") && !head.contains("#EXTINF")) "That link isn't an M3U playlist." else null
    }
}.getOrElse { "Couldn't reach it: ${it.message?.take(80)}" }

@Composable
fun SourceForm(a: MainActivity, kind: String, accent: Color) {
    val xtream = kind == "xtream"
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("http://") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val save = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = { a.sourceForm.value = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Palette.bg).border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(20.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (xtream) "Add Xtream Codes login" else "Add M3U playlist", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("Press OK on a field to type. Saved only on this TV.", color = Palette.sub, fontSize = 13.sp)
            TvField("Name (shown in the guide)", name, accent, modifier = Modifier.focusRequester(first)) { name = it }
            TvField(if (xtream) "Server, e.g. http://line.example.com:8080" else "M3U link", url, accent) { url = it }
            if (xtream) {
                TvField("Username", user, accent) { user = it }
                TvField("Password", pass, accent, secret = true) { pass = it }
            }
            TvField("Guide (XMLTV) link, optional", epg, accent, modifier = Modifier.focusProperties { down = save }) { epg = it }
            if (msg.isNotBlank()) Text(msg, color = if (busy) Palette.sub else Color(0xFFFF8A80), fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(enabled = !busy, modifier = Modifier.focusRequester(save), onClick = {
                    val o = org.json.JSONObject().put("name", name).put("type", kind).put("url", url).put("username", user).put("password", pass).put("epg", epg)
                    val src = Source.fromJson(o) ?: run { msg = if (xtream) "Fill in the server, username and password." else "Fill in the M3U link."; return@Button }
                    busy = true; msg = "Checking…"
                    scope.launch {
                        val err = withContext(Dispatchers.IO) { checkSource(src) }
                        busy = false
                        if (err != null) msg = err else { a.sourceForm.value = null; a.addSource(src) }
                    }
                }) { Text("Save") }
                Button(onClick = { a.sourceForm.value = null }) { Text("Cancel") }
            }
        }
    }
}

/** The "IPTV sources" block of Settings. */
fun androidx.compose.foundation.lazy.LazyListScope.sourceRows(a: MainActivity, accent: Color, armed: MutableState<String?>, row: @Composable (String, String, () -> Unit) -> Unit) {
    val c = a.config.value
    item { Text("IPTV sources", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp)) }
    if (c?.m3uUrl?.isNotBlank() == true) item { row("Dispatcharr", "From setup app") {} }
    c?.sources?.forEach { s ->
        item(s.m3u()) {
            val kind = if (s.kind == "xtream") "Xtream" else "M3U"
            row(s.name, when { s.fromSetup -> "$kind · from setup app"; armed.value == s.m3u() -> "Press OK again to remove"; else -> "$kind · OK to remove" }) {
                if (s.fromSetup) return@row
                if (armed.value == s.m3u()) { armed.value = null; a.removeSource(s) } else armed.value = s.m3u()
            }
        }
    }
    item { row("Add Xtream Codes login", "") { a.sourceForm.value = "xtream" } }
    item { row("Add M3U playlist", "") { a.sourceForm.value = "m3u" } }
}
