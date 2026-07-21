package dev.stefan.kyf42launcher

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.CalendarContract
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Populates the home info card: next alarm, next calendar event, weather. */
class HomeWidgets(
    private val activity: Activity,
    private val card: View,
    private val rowWeather: View,
    private val wxIcon: ImageView,
    private val wxTemp: TextView,
    private val wxCond: TextView,
    private val rowEvent: View,
    private val evText: TextView,
    private val rowAlarm: View,
    private val alText: TextView,
) {
    fun refresh() {
        updateAlarm()
        updateCalendar()
        updateWeather()   // async
        updateCardVisibility()
    }

    private fun updateCardVisibility() {
        val any = rowWeather.visibility == View.VISIBLE ||
            rowEvent.visibility == View.VISIBLE ||
            rowAlarm.visibility == View.VISIBLE
        card.visibility = if (any) View.VISIBLE else View.GONE
    }

    // --- Next alarm (no permission needed) ---
    private fun updateAlarm() {
        val am = activity.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val next = am?.nextAlarmClock
        if (next != null) {
            alText.text = SimpleDateFormat("EEE  h:mm a", Locale.getDefault())
                .format(Date(next.triggerTime))
            rowAlarm.visibility = View.VISIBLE
        } else {
            rowAlarm.visibility = View.GONE
        }
    }

    // --- Next calendar event in the next 36h ---
    private fun updateCalendar() {
        if (activity.checkSelfPermission(Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            rowEvent.visibility = View.GONE
            return
        }
        val now = LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = LocalDateTime.now().atZone(ZoneId.systemDefault())
            .withHour(23)
            .withMinute(59)
            .withSecond(0)
            .withNano(0)
            .toInstant()
            .toEpochMilli()

        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN
        )
        var bestBegin = Long.MAX_VALUE
        var bestTitle: String? = null
        try {
            CalendarContract.Instances.query(activity.contentResolver, proj, now, end)?.use { c ->
                val iTitle = c.getColumnIndex(CalendarContract.Instances.TITLE)
                val iBegin = c.getColumnIndex(CalendarContract.Instances.BEGIN)
                while (c.moveToNext()) {
                    val begin = c.getLong(iBegin)
                    if (begin in now until bestBegin) {
                        bestBegin = begin
                        bestTitle = c.getString(iTitle)
                    }
                }
            }
        } catch (_: Exception) { }

        if (bestTitle != null) {
            val t = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(bestBegin))
            evText.text = "$t  ${bestTitle}"
            rowEvent.visibility = View.VISIBLE
        } else {
            rowEvent.visibility = View.GONE
        }
    }

    // --- Weather via Open-Meteo (no API key). Location: last-known or IP. ---
    private fun updateWeather() {
        Thread {
            try {
                val loc = latLon() ?: return@Thread
                val url = "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=${loc.first}&longitude=${loc.second}" +
                    "&current=temperature_2m,weather_code"
                val obj = JSONObject(httpGet(url) ?: return@Thread).getJSONObject("current")
                val temp = obj.getDouble("temperature_2m").roundToInt()
                val code = obj.getInt("weather_code")
                activity.runOnUiThread {
                    wxTemp.text = "$temp°"
                    wxCond.text = wmoText(code)
                    wxIcon.setImageResource(wmoIcon(code))
                    rowWeather.visibility = View.VISIBLE
                    updateCardVisibility()
                }
            } catch (_: Exception) { /* offline / no location: leave hidden */ }
        }.start()
    }

    private fun latLon(): Pair<Double, Double>? {
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val lm = activity.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            for (p in listOf(
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )) {
                try {
                    lm?.getLastKnownLocation(p)?.let { return it.latitude to it.longitude }
                } catch (_: Exception) { }
            }
        }
        // IP fallback (https, no permission)
        return try {
            val o = JSONObject(httpGet("https://ipapi.co/json/") ?: return null)
            o.getDouble("latitude") to o.getDouble("longitude")
        } catch (_: Exception) { null }
    }

    private fun httpGet(spec: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(spec).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "GET"
            }
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun wmoIcon(code: Int): Int = when (code) {
        0 -> R.drawable.ic_wx_clear
        1, 2 -> R.drawable.ic_wx_partly
        3, 45, 48 -> R.drawable.ic_wx_cloud
        in 71..77, 85, 86 -> R.drawable.ic_wx_snow
        else -> R.drawable.ic_wx_rain   // drizzle/rain/showers/thunder
    }

    private fun wmoText(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Showers"
        85, 86 -> "Snow showers"
        in 95..99 -> "Thunderstorm"
        else -> ""
    }
}
