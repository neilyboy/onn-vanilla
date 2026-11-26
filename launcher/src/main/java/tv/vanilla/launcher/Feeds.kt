package tv.vanilla.launcher

import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class CalEvent(val start: ZonedDateTime, val allDay: Boolean, val title: String)
data class Headline(val source: String, val title: String, val image: String = "", val at: Long = 0)
data class Alert(val event: String, val headline: String, val severity: String)

/** Upcoming events from iCal (.ics) feeds, including simple daily/weekly/monthly/yearly repeats. */
fun fetchCalendar(urls: List<String>, days: Long = 7): List<CalEvent> {
    val zone = ZoneId.systemDefault()
    val now = ZonedDateTime.now(zone)
    val end = now.plusDays(days)
    return urls.flatMap { url ->
        runCatching { parseIcs(httpGet(url.replace("webcal://", "https://"), 15000), zone) }.getOrDefault(emptyList())
    }.flatMap { (ev, rule) -> expand(ev, rule, now.toLocalDate().atStartOfDay(zone), end) }
        .filter { if (it.allDay) !it.start.toLocalDate().isBefore(now.toLocalDate()) else it.start.isAfter(now.minusMinutes(30)) }
        .sortedBy { it.start }
}

private fun expand(ev: CalEvent, rule: String?, from: ZonedDateTime, to: ZonedDateTime): List<CalEvent> {
    if (rule == null) return listOf(ev).filter { it.start.isBefore(to) && !it.start.isBefore(from.minusDays(1)) }
    val parts = rule.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
    val until = parts["UNTIL"]?.let { runCatching { parseIcsTime(it, null, ZoneId.systemDefault()).first }.getOrNull() }
    val step: (ZonedDateTime) -> ZonedDateTime = when (parts["FREQ"]) {
        "DAILY" -> { t -> t.plusDays(parts["INTERVAL"]?.toLongOrNull() ?: 1) }
        "WEEKLY" -> { t -> t.plusWeeks(parts["INTERVAL"]?.toLongOrNull() ?: 1) }
        "MONTHLY" -> { t -> t.plusMonths(parts["INTERVAL"]?.toLongOrNull() ?: 1) }
        "YEARLY" -> { t -> t.plusYears(parts["INTERVAL"]?.toLongOrNull() ?: 1) }
        else -> return listOf(ev)
    }
    val byDay = parts["BYDAY"]?.split(',')?.mapNotNull { d -> DAYS[d.takeLast(2)] }.orEmpty()
    // Monthly/yearly "2nd Sunday"-style rules aren't supported; skip them rather than show wrong dates.
    if (byDay.isNotEmpty() && parts["FREQ"] != "WEEKLY") return emptyList()
    val out = ArrayList<CalEvent>()
    var t = ev.start
    var n = 0
    val count = parts["COUNT"]?.toIntOrNull() ?: Int.MAX_VALUE
    while (t.isBefore(to) && n < count && n < 5000 && (until == null || !t.isAfter(until))) {
        val hits = if (byDay.isEmpty()) listOf(t) else byDay.map { t.with(java.time.temporal.ChronoField.DAY_OF_WEEK, it.value.toLong()) }.filter { !it.isBefore(ev.start) }.sorted()
        for (h in hits) {
            if (n >= count || (until != null && h.isAfter(until))) break
            if (!h.isBefore(from) && h.isBefore(to)) out += ev.copy(start = h)
            n++
        }
        t = step(t)
    }
    return out
}

private val DAYS = mapOf(
    "MO" to java.time.DayOfWeek.MONDAY, "TU" to java.time.DayOfWeek.TUESDAY, "WE" to java.time.DayOfWeek.WEDNESDAY,
    "TH" to java.time.DayOfWeek.THURSDAY, "FR" to java.time.DayOfWeek.FRIDAY, "SA" to java.time.DayOfWeek.SATURDAY, "SU" to java.time.DayOfWeek.SUNDAY,
)

private fun parseIcsTime(v: String, tzid: String?, zone: ZoneId): Pair<ZonedDateTime, Boolean> {
    if (v.length == 8) return LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(zone) to true
    val local = LocalDateTime.parse(v.removeSuffix("Z"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
    val z = when {
        v.endsWith("Z") -> local.atZone(ZoneOffset.UTC)
        tzid != null -> runCatching { local.atZone(ZoneId.of(tzid)) }.getOrElse { local.atZone(zone) }
        else -> local.atZone(zone)
    }
    return z.withZoneSameInstant(zone) to false
}

private fun parseIcs(text: String, zone: ZoneId): List<Pair<CalEvent, String?>> {
    val lines = text.replace("\r\n ", "").replace("\n ", "").replace("\r\n\t", "").lines()
    val out = ArrayList<Pair<CalEvent, String?>>()
    var start: Pair<ZonedDateTime, Boolean>? = null
    var title = ""
    var rule: String? = null
    var inEvent = false
    for (raw in lines) {
        val line = raw.trimEnd('\r')
        when {
            line == "BEGIN:VEVENT" -> { inEvent = true; start = null; title = ""; rule = null }
            line == "END:VEVENT" -> { inEvent = false; start?.let { out += CalEvent(it.first, it.second, title) to rule } }
            !inEvent -> {}
            line.startsWith("DTSTART") -> {
                val key = line.substringBefore(':')
                val tz = key.split(';').firstOrNull { it.startsWith("TZID=") }?.substringAfter('=')
                start = runCatching { parseIcsTime(line.substringAfter(':'), tz, zone) }.getOrNull()
            }
            line.startsWith("SUMMARY") -> title = line.substringAfter(':').replace("\\,", ",").replace("\\;", ";").replace("\\n", " ")
            line.startsWith("RRULE:") -> rule = line.substringAfter(':')
        }
    }
    return out
}

/** Latest headlines from RSS or Atom feeds, interleaved so no single feed dominates. */
fun fetchNews(urls: List<String>, perFeed: Int = 8): List<Headline> {
    val lists = urls.map { url -> runCatching { parseFeed(httpGet(url, 15000)).take(perFeed) }.getOrDefault(emptyList()) }
    return (0 until perFeed).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
}

private val TRACKER = Regex("""pixel|tracking|1x1|spacer""", RegexOption.IGNORE_CASE)
private val IMG = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

private fun parseFeed(xml: String): List<Headline> {
    val p = Xml.newPullParser()
    p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    p.setInput(xml.reader())
    var source = ""
    var inItem = false
    var title = ""; var image = ""; var at = 0L
    val out = ArrayList<Headline>()
    // A malformed tail (bad entity etc.) keeps whatever parsed before it.
    runCatching {
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) {
                val name = p.name
                when {
                    name == "item" || name == "entry" -> { inItem = true; title = ""; image = ""; at = 0L }
                    name == "title" -> {
                        val t = runCatching { p.nextText() }.getOrDefault("").trim()
                        if (inItem) title = t else if (source.isBlank()) source = t
                    }
                    !inItem -> Unit
                    name == "media:content" || name == "media:thumbnail" || name == "enclosure" -> {
                        val url = p.getAttributeValue(null, "url") ?: ""
                        val type = p.getAttributeValue(null, "type") ?: p.getAttributeValue(null, "medium") ?: "image"
                        if (image.isBlank() && url.isNotBlank() && type.startsWith("image")) image = url
                    }
                    name == "description" || name == "content:encoded" || name == "content" || name == "summary" -> {
                        val t = runCatching { p.nextText() }.getOrDefault("")
                        if (image.isBlank()) IMG.find(t)?.let { image = it.groupValues[1].replace("&amp;", "&") }
                    }
                    name == "pubDate" || name == "published" || name == "updated" -> {
                        val t = runCatching { p.nextText() }.getOrDefault("").trim()
                        if (at == 0L) at = parseFeedDate(t)
                    }
                }
            } else if (p.eventType == XmlPullParser.END_TAG && (p.name == "item" || p.name == "entry")) {
                if (title.isNotBlank()) out += Headline(source, title, image.takeUnless { TRACKER.containsMatchIn(it) } ?: "", at)
                inItem = false
            }
        }
    }
    val short = source.substringBefore(" - ").substringBefore(" | ").substringBefore(":").trim().take(24)
    return out.map { it.copy(source = short) }
}

private fun parseFeedDate(s: String): Long = runCatching {
    java.time.ZonedDateTime.parse(s, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
}.recoverCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrDefault(0L)

/** Active US National Weather Service alerts for a point (empty outside the US). */
fun fetchAlerts(lat: Double, lon: Double): List<Alert> = runCatching {
    val root = JSONObject(httpGet("https://api.weather.gov/alerts/active?point=%.4f,%.4f".format(java.util.Locale.US, lat, lon), 15000))
    val f = root.getJSONArray("features")
    (0 until f.length()).mapNotNull { f.optJSONObject(it)?.optJSONObject("properties") }.map {
        Alert(it.optString("event"), it.optString("headline"), it.optString("severity"))
    }.sortedBy { listOf("Extreme", "Severe", "Moderate", "Minor").indexOf(it.severity).let { i -> if (i < 0) 9 else i } }
}.getOrDefault(emptyList())

/** RainViewer radar frames for the last ~2 hours as (tile base URL, unix time), oldest first. */
fun fetchRadarFrames(): List<Pair<String, Long>> = runCatching {
    val r = JSONObject(httpGet("https://api.rainviewer.com/public/weather-maps.json", 15000))
    val past = r.getJSONObject("radar").getJSONArray("past")
    (0 until past.length()).map { past.getJSONObject(it).let { f -> r.getString("host") + f.getString("path") to f.optLong("time") } }
}.getOrDefault(emptyList())

/** Latest RainViewer radar frame, e.g. "https://tilecache.rainviewer.com/v2/radar/1700000000". */
fun fetchRadarBase(): String? = runCatching {
    val r = JSONObject(httpGet("https://api.rainviewer.com/public/weather-maps.json", 15000))
    val past = r.getJSONObject("radar").getJSONArray("past")
    r.getString("host") + past.getJSONObject(past.length() - 1).getString("path")
}.getOrNull()
