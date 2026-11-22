package tv.vanilla.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

data class DayForecast(val date: LocalDate, val code: Int, val high: Int, val low: Int)

data class HourForecast(val time: java.time.LocalDateTime, val code: Int, val temp: Int, val rain: Int)

data class Weather(val tempNow: Int, val codeNow: Int, val isDay: Boolean, val days: List<DayForecast>, val hours: List<HourForecast> = emptyList())

class WeatherRepository(context: Context) {
    private val cacheFile = File(context.filesDir, "weather.json")

    fun cached(): Weather? = runCatching { parse(cacheFile.readText()) }.getOrNull()

    fun fetch(lat: Double, lon: Double, fahrenheit: Boolean): Weather? = runCatching {
        val unit = if (fahrenheit) "fahrenheit" else "celsius"
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&hourly=temperature_2m,weather_code,precipitation_probability&forecast_hours=13" +
            "&temperature_unit=$unit&timezone=auto&forecast_days=7"
        val text = httpGet(url)
        val w = parse(text)
        cacheFile.writeText(text)
        w
    }.getOrNull()

    private fun parse(text: String): Weather {
        val root = JSONObject(text)
        val cur = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        val times = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val maxes = daily.getJSONArray("temperature_2m_max")
        val mins = daily.getJSONArray("temperature_2m_min")
        val days = (0 until times.length()).map {
            DayForecast(
                LocalDate.parse(times.getString(it)),
                codes.optInt(it),
                Math.round(maxes.optDouble(it)).toInt(),
                Math.round(mins.optDouble(it)).toInt(),
            )
        }
        val hours = root.optJSONObject("hourly")?.let { h ->
            val t = h.getJSONArray("time"); val tc = h.optJSONArray("temperature_2m"); val wc = h.optJSONArray("weather_code"); val pp = h.optJSONArray("precipitation_probability")
            (0 until t.length()).map {
                HourForecast(java.time.LocalDateTime.parse(t.getString(it)), wc?.optInt(it) ?: 0, Math.round(tc?.optDouble(it) ?: 0.0).toInt(), pp?.optInt(it) ?: 0)
            }
        } ?: emptyList()
        return Weather(
            Math.round(cur.getDouble("temperature_2m")).toInt(),
            cur.optInt("weather_code"),
            cur.optInt("is_day", 1) == 1,
            days,
            hours,
        )
    }
}

fun weatherLabel(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mostly Clear"
    2 -> "Partly Cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    51, 53, 55, 56, 57 -> "Drizzle"
    61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
    71, 73, 75, 77, 85, 86 -> "Snow"
    95, 96, 99 -> "Thunderstorms"
    else -> ""
}

/** Wallpaper manifest: either a JSON array of URLs or {"images": [...]}; relative paths resolve against the manifest URL. */
fun fetchWallpaperList(manifestUrl: String): List<String> = runCatching {
    val text = httpGet(manifestUrl).trim()
    val arr = if (text.startsWith("[")) JSONArray(text) else JSONObject(text).getJSONArray("images")
    (0 until arr.length()).map { LauncherConfig.resolve(manifestUrl, arr.getString(it)) }
}.getOrDefault(emptyList())
