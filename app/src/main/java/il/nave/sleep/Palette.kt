package il.nave.sleep

import android.graphics.Color
import org.json.JSONObject
import java.time.Instant

/** Colour per state, interpolated by elapsed minutes. No text - colour carries the meaning. */
object Palette {
    data class Look(val bg: Int, val fg: Int, val bar: Int, val level: Double, val showBar: Boolean)

    private fun c(h: String) = Color.parseColor(h)
    private val AWAKE = Triple(listOf(c("#EAF3DE"), c("#FAEEDA"), c("#FCEBEB")), listOf(c("#27500A"), c("#633806"), c("#791F1F")), listOf(c("#639922"), c("#EF9F27"), c("#E24B4A")))
    private val NAP = Triple(listOf(c("#EEEDFE"), c("#FAEEDA"), c("#FCEBEB")), listOf(c("#26215C"), c("#633806"), c("#791F1F")), listOf(c("#7F77DD"), c("#EF9F27"), c("#E24B4A")))
    private val NIGHT = Triple(listOf(c("#26215C"), c("#633806"), c("#791F1F")), listOf(c("#EEEDFE"), c("#FAEEDA"), c("#FCEBEB")), listOf(c("#7F77DD"), c("#EF9F27"), c("#E24B4A")))

    private fun mix(a: Int, b: Int, t: Double): Int {
        val k = t.coerceIn(0.0, 1.0)
        fun ch(x: Int, y: Int) = (x + (y - x) * k).toInt()
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    private fun at(cols: List<Int>, stops: DoubleArray, el: Double): Int = when {
        el <= stops[0] -> cols[0]
        el < stops[1] -> mix(cols[0], cols[1], (el - stops[0]) / (stops[1] - stops[0]))
        el < stops[2] -> mix(cols[1], cols[2], (el - stops[1]) / (stops[2] - stops[1]))
        else -> cols[2]
    }

    fun ms(iso: String?): Long? = iso?.takeIf { it.isNotEmpty() && it != "null" }?.let { runCatching { Instant.parse(it.replace("+00:00", "Z")).toEpochMilli() }.getOrNull() }

    fun look(j: JSONObject?, now: Long): Look {
        val scale = j?.optJSONObject("scale")
        val mode = scale?.optString("mode") ?: "unknown"
        val since = ms(j?.optString("scale_since")) ?: ms(j?.optString("since"))
        if (j == null || since == null || mode == "unknown") return Look(c("#F1EFE8"), c("#2C2C2A"), c("#888780"), 0.0, false)
        if (mode == "night_waking") return Look(c("#3C3489"), c("#EEEDFE"), c("#AFA9EC"), 0.0, false)
        val arr = scale.optJSONArray("stops")
        val stops = DoubleArray(3) { arr?.optDouble(it, 0.0) ?: 0.0 }
        val el = (now - since) / 60000.0
        val pal = when (mode) { "nap" -> NAP; "night" -> NIGHT; else -> AWAKE }
        val level = if (stops[2] > 0) (el / stops[2]).coerceIn(0.0, 1.0) else 0.0
        return Look(at(pal.first, stops, el), at(pal.second, stops, el), at(pal.third, stops, el), level, true)
    }
}
