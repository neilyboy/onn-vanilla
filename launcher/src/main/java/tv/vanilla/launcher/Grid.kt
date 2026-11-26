package tv.vanilla.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

/** A widget's spot on the home screen grid, in cells. */
data class Cell(val id: String, val x: Int, val y: Int, val w: Int, val h: Int) {
    fun overlaps(o: Cell) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
    val fits get() = x >= 0 && y >= 0 && w >= 1 && h >= 1 && x + w <= COLS && y + h <= ROWS
}

const val COLS = 12
const val ROWS = 6

data class WidgetInfo(val id: String, val name: String, val w: Int, val h: Int)

val CATALOG = listOf(
    WidgetInfo("scores", "Scores", 4, 2),
    WidgetInfo("onnow", "On now", 5, 2),
    WidgetInfo("weather", "Weather & radar", 3, 4),
    WidgetInfo("calendar", "Calendar", 3, 3),
    WidgetInfo("news", "Headlines", 4, 2),
    WidgetInfo("resume", "Continue watching", 4, 3),
    WidgetInfo("newvod", "New on Movies & Series", 5, 3),
    WidgetInfo("radio", "Radio", 3, 2),
    WidgetInfo("rooms", "Rooms", 3, 2),
    WidgetInfo("ha", "Home Assistant", 3, 2),
    WidgetInfo("cameras", "Cameras", 4, 2),
)

fun widgetName(id: String) = CATALOG.firstOrNull { it.id == id }?.name ?: id

/** Turns the setup app's left / center / right columns into grid cells. */
fun defaultCells(specs: List<WidgetSpec>): List<Cell> {
    val hasL = specs.any { it.pos == "left" }
    val hasR = specs.any { it.pos == "right" }
    val cols = listOf(
        Triple("left", 0, 3),
        Triple("center", if (hasL) 3 else 0, COLS - (if (hasL) 3 else 0) - (if (hasR) 3 else 0)),
        Triple("right", COLS - 3, 3),
    )
    return cols.flatMap { (pos, x, w) ->
        val ids = specs.filter { it.pos == pos }.map { it.id }.distinct()
        // A wide column with many widgets splits into two side by side, so each stays at least 2 rows tall.
        if (ids.size > 3 && w >= 6) {
            val half = (ids.size + 1) / 2
            stack(ids.take(half), x, w / 2) + stack(ids.drop(half), x + w / 2, w - w / 2)
        } else stack(ids, x, w)
    }
}

private fun stack(ids: List<String>, x: Int, w: Int): List<Cell> {
    val list = ids.take(ROWS)
    if (list.isEmpty()) return emptyList()
    val base = ROWS / list.size
    var extra = ROWS % list.size
    var y = 0
    return list.map { id ->
        val h = base + if (extra-- > 0) 1 else 0
        Cell(id, x, y, w, h).also { y += h }
    }
}

/** The first spot where a [w]x[h] widget fits, shrinking it down to 2x1 if needed. */
fun firstFree(cells: List<Cell>, id: String, w: Int, h: Int): Cell? {
    for (ww in w downTo 2) for (hh in h downTo 1) for (y in 0..ROWS - hh) for (x in 0..COLS - ww) {
        val c = Cell(id, x, y, ww, hh)
        if (cells.none { it.overlaps(c) }) return c
    }
    return null
}

/** Adds [w] to the grid. When it's full, the tallest widgets give up a row each until it fits. */
fun placeNew(cells: List<Cell>, w: WidgetInfo): List<Cell>? {
    var list = cells
    repeat(ROWS * 2) {
        firstFree(list, w.id, w.w, w.h)?.let { return list + it }
        val tall = list.filter { it.h > 1 }.maxWithOrNull(compareBy({ it.h }, { it.w })) ?: return null
        list = list.map { if (it == tall) it.copy(h = it.h - 1) else it }
    }
    return null
}

/** Moves (or, with [resize], grows/shrinks) [id] by dx/dy. Moving onto a neighbour swaps the two when both fit. */
fun nudge(cells: List<Cell>, id: String, dx: Int, dy: Int, resize: Boolean): List<Cell> {
    val c = cells.firstOrNull { it.id == id } ?: return cells
    val others = cells.filter { it.id != id }
    if (resize) {
        val n = c.copy(w = c.w + dx, h = c.h + dy)
        return if (n.fits && n.w >= 2 && others.none { it.overlaps(n) }) others + n else cells
    }
    val n = c.copy(x = c.x + dx, y = c.y + dy)
    if (!n.fits) return cells
    val hit = others.filter { it.overlaps(n) }
    if (hit.isEmpty()) return others + n
    if (hit.size == 1) {
        val o = hit[0]
        val moved = if (dx != 0) o.copy(x = if (dx > 0) c.x else c.x + c.w - o.w) else o.copy(y = if (dy > 0) c.y else c.y + c.h - o.h)
        val n2 = if (dx != 0) n.copy(x = if (dx > 0) moved.x + moved.w else moved.x - c.w) else n.copy(y = if (dy > 0) moved.y + moved.h else moved.y - c.h)
        val rest = others.filter { it.id != o.id }
        if (moved.fits && n2.fits && !moved.overlaps(n2) && rest.none { it.overlaps(moved) || it.overlaps(n2) }) return rest + moved + n2
    }
    return cells
}

fun cellsToJson(cells: List<Cell>) = JSONArray().apply {
    cells.forEach { put(JSONObject().put("id", it.id).put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h)) }
}

fun cellsFromJson(a: JSONArray?): List<Cell> = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }
    .map { Cell(it.optString("id"), it.optInt("x"), it.optInt("y"), it.optInt("w", 3), it.optInt("h", 2)) }
    .filter { it.id.isNotBlank() && it.fits }

/** Parts of the day that can each have their own layout. */
val PERIODS = linkedMapOf("morning" to "Morning (5–11)", "day" to "Daytime (11–5)", "evening" to "Evening (5–10)", "night" to "Night (10–5)")

fun periodNow(t: LocalTime = LocalTime.now()) = when (t.hour) {
    in 5..10 -> "morning"
    in 11..16 -> "day"
    in 17..21 -> "evening"
    else -> "night"
}
