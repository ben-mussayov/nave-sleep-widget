package il.nave.sleep

import android.app.Activity
import android.app.TimePickerDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime

/** Small popup opened from the widget: logs sleep/wake, asks night-or-nap in the evening, and edits the start time. */
class ActionActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var picked = 0L
    private var floor = 0L

    private fun hm(ms: Long) = SleepWidget.HM.format(Instant.ofEpochMilli(ms))
    private fun isNightHours(): Boolean { val h = ZonedDateTime.now(SleepWidget.TZ).hour; return h >= 19 || h < 7 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_action)
        val j = Feed.cached(this)
        val state = j?.optString("state") ?: ""
        val kind = j?.optString("kind") ?: ""
        val title = findViewById<TextView>(R.id.title)
        val opt1 = findViewById<TextView>(R.id.opt1)
        val opt2 = findViewById<TextView>(R.id.opt2)

        when (intent.getStringExtra("action")) {
            "sleep" -> {
                if (state == "awake" && kind == "night_waking") return send(JSONObject().put("action", "sleep"))
                if (!isNightHours()) return send(JSONObject().put("action", "sleep").put("kind", "nap"))
                title.text = "שינת לילה?"
                opt1.text = "לילה"; opt2.text = "תנומה"
                opt1.setOnClickListener { send(JSONObject().put("action", "sleep").put("kind", "night")) }
                opt2.setOnClickListener { send(JSONObject().put("action", "sleep").put("kind", "nap")) }
            }
            "wake" -> {
                if (!(state == "asleep" && kind == "night" && isNightHours())) return send(JSONObject().put("action", "wake"))
                title.text = "התעורר בלילה?"
                opt1.text = "בלילה"; opt2.text = "קם לבוקר"
                opt2.setBackgroundResource(R.drawable.btn_wake)
                opt1.setOnClickListener { send(JSONObject().put("action", "wake").put("night_waking", true)) }
                opt2.setOnClickListener { send(JSONObject().put("action", "wake")) }
            }
            "edit" -> showEdit(j, state)
            else -> finish()
        }
    }

    private fun showEdit(j: JSONObject?, state: String) {
        val since = Palette.ms(j?.optString("since")) ?: return finish()
        floor = Palette.ms(j?.optString("prev_since")) ?: 0L
        picked = since
        findViewById<View>(R.id.ask).visibility = View.GONE
        findViewById<View>(R.id.edit).visibility = View.VISIBLE
        findViewById<TextView>(R.id.title).text = if (state == "asleep") "שעת הירדמות" else "שעת התעוררות"
        val time = findViewById<TextView>(R.id.time)
        val msg = findViewById<TextView>(R.id.msg)
        fun show() { time.text = hm(picked); msg.visibility = View.GONE }
        show()
        findViewById<View>(R.id.minus).setOnClickListener { picked -= 5 * 60000; show() }
        findViewById<View>(R.id.plus).setOnClickListener { picked += 5 * 60000; show() }
        time.setOnClickListener {
            val z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(picked), SleepWidget.TZ)
            TimePickerDialog(this, { _, h, m ->
                var t = z.withHour(h).withMinute(m).withSecond(0).withNano(0).toInstant().toEpochMilli()
                if (t > System.currentTimeMillis() + 60000) t -= 86400000L
                picked = t; show()
            }, z.hour, z.minute, true).show()
        }
        findViewById<View>(R.id.cancel).setOnClickListener { finish() }
        findViewById<View>(R.id.ok).setOnClickListener {
            when {
                picked > System.currentTimeMillis() + 60000 -> error("השעה עוד לא הגיעה")
                floor > 0 && picked <= floor -> error("לפני תחילת המצב הקודם (" + hm(floor) + ")")
                else -> send(JSONObject().put("action", "set_time").put("at", Instant.ofEpochMilli(picked).toString()), keepOpen = true)
            }
        }
    }

    private fun error(text: String) {
        val msg = findViewById<TextView>(R.id.msg)
        msg.text = text; msg.visibility = View.VISIBLE
    }

    private fun send(body: JSONObject, keepOpen: Boolean = false) {
        findViewById<View>(R.id.ask).visibility = View.GONE
        if (!keepOpen) findViewById<View>(R.id.edit).visibility = View.GONE
        findViewById<TextView>(R.id.title).text = "שומר…"
        Thread {
            val res = Feed.post(this, body)
            ui.post {
                val err = res.optString("error", "")
                if (err.isEmpty()) {
                    SleepWidget.renderAll(this, res, stale = false)
                    finish()
                } else {
                    findViewById<TextView>(R.id.title).text = "לא נשמר"
                    error(when (err) {
                        "future" -> "השעה עוד לא הגיעה"
                        "before_prev" -> "השעה לפני תחילת המצב הקודם"
                        "offline" -> "אין חיבור. נסו שוב."
                        else -> "משהו השתבש. נסו שוב."
                    })
                    SleepWidget.refreshNow(this)
                    if (!keepOpen) ui.postDelayed({ finish() }, 2500)
                }
            }
        }.start()
    }
}
