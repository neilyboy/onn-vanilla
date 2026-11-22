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
data class Headline(val source: String, val title: String)
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
    val out = ArrayList<CalEvent>()
    var t = ev.start
    var n = 0
    val count = parts["COUNT"]?.toIntOrNull() ?: Int.MAX_VALUE
    while (t.isBefore(to) && n < count && n < 5000 && (until == null || !t.isAfter(until))) {
        if (!t.isBefore(from)) out += ev.copy(start = t)
        t = step(t); n++
    }
    return out
}

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

private fun parseFeed(xml: String): List<Headline> {
    val p = Xml.newPullParser()
    p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    p.setInput(xml.reader())
    var source = ""
    var depthItem = false
    val out = ArrayList<Headline>()
    while (p.next() != XmlPullParser.END_DOCUMENT) {
        if (p.eventType == XmlPullParser.START_TAG) {
            when (p.name) {
                "item", "entry" -> depthItem = true
                "title" -> {
                    val t = runCatching { p.nextText() }.getOrDefault("").trim()
                    if (depthItem) { if (t.isNotBlank()) out += Headline(source, t) } else if (source.isBlank()) source = t
                }
            }
        } else if (p.eventType == XmlPullParser.END_TAG && (p.name == "item" || p.name == "entry")) depthItem = false
    }
    val short = source.substringBefore(" - ").substringBefore(" | ").substringBefore(":").trim().take(24)
    return out.map { it.copy(source = short) }
}

/** Active US National Weather Service alerts for a point (empty outside the US). */
fun fetchAlerts(lat: Double, lon: Double): List<Alert> = runCatching {
    val root = JSONObject(httpGet("https://api.weather.gov/alerts/active?point=%.4f,%.4f".format(java.util.Locale.US, lat, lon), 15000))
    val f = root.getJSONArray("features")
    (0 until f.length()).mapNotNull { f.optJSONObject(it)?.optJSONObject("properties") }.map {
        Alert(it.optString("event"), it.optString("headline"), it.optString("severity"))
    }.sortedBy { listOf("Extreme", "Severe", "Moderate", "Minor").indexOf(it.severity).let { i -> if (i < 0) 9 else i } }
}.getOrDefault(emptyList())
