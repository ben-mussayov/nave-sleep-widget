package il.nave.sleep

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val input = findViewById<EditText>(R.id.link)
        val status = findViewById<TextView>(R.id.status)

        if (Feed.token(this).isNotEmpty()) {
            input.setText(Feed.pageUrl(this))
            status.text = "מחובר. אפשר להוסיף את הווידג'ט למסך הבית."
        }

        findViewById<Button>(R.id.paste).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.let { input.setText(it) }
        }

        findViewById<Button>(R.id.save).setOnClickListener {
            val parsed = Feed.parseInput(input.text.toString())
            if (parsed == null) {
                status.text = "הקישור לא תקין. הדביקו את הקישור המלא לעמוד השינה."
                return@setOnClickListener
            }
            Feed.save(this, parsed.first, parsed.second)
            status.text = "בודק חיבור…"
            Thread {
                val j = Feed.fetch(this)
                runOnUiThread {
                    status.text = if (j != null) "מחובר. עכשיו הוסיפו את הווידג'ט: לחיצה ארוכה על מסך הבית ← ווידג'טים ← השינה של נווה."
                    else "אין גישה. בדקו את הקישור ואת החיבור לאינטרנט."
                }
                SleepWidget.renderAll(this, j ?: Feed.cached(this), stale = j == null)
            }.start()
            SleepWidget.schedulePeriodic(this)
        }

        findViewById<Button>(R.id.open).setOnClickListener {
            if (Feed.token(this).isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Feed.pageUrl(this))))
        }

        findViewById<Button>(R.id.battery).setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
    }
}
