package il.nave.sleep

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class SleepWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        renderAll(ctx, Feed.cached(ctx), stale = true)
        schedulePeriodic(ctx)
        refreshNow(ctx)
    }

    override fun onEnabled(ctx: Context) = schedulePeriodic(ctx)

    override fun onDisabled(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(ctx).cancelUniqueWork(EDGE)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) refreshNow(ctx)
    }

    companion object {
        const val ACTION_REFRESH = "il.nave.sleep.REFRESH"
        private const val PERIODIC = "sleep_periodic"
        private const val NOW = "sleep_now"
        const val EDGE = "sleep_edge"
        private val TZ: ZoneId = ZoneId.of("Asia/Jerusalem")
        private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(TZ)

        private val netOk = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SleepWorker>(15, TimeUnit.MINUTES).setConstraints(netOk).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun refreshNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SleepWorker>().setConstraints(netOk).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }

        /** Re-render (and refetch) exactly when the next-nap window opens, so the widget switches text on time. */
        fun scheduleEdge(ctx: Context, atMillis: Long) {
            val delay = atMillis - System.currentTimeMillis()
            if (delay <= 0) return
            val req = OneTimeWorkRequestBuilder<SleepWorker>().setInitialDelay(delay + 5000, TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(EDGE, ExistingWorkPolicy.REPLACE, req)
        }

        private fun dur(min: Long): String {
            val m = if (min < 0) 0 else min
            val h = m / 60
            val r = m % 60
            return when {
                h == 0L -> "$r דק'"
                r == 0L -> "$h ש'"
                else -> "$h ש' $r דק'"
            }
        }

        private fun ms(iso: String?): Long? = iso?.takeIf { it.isNotEmpty() && it != "null" }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

        fun renderAll(ctx: Context, j: JSONObject?, stale: Boolean) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SleepWidget::class.java))
            if (ids.isEmpty()) return
            val v = build(ctx, j, stale)
            mgr.updateAppWidget(ids, v)
        }

        private fun build(ctx: Context, j: JSONObject?, stale: Boolean): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_sleep)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

            // Tap body -> open the full page. Tap refresh -> refetch now.
            val open = Intent(Intent.ACTION_VIEW, Uri.parse(if (Feed.token(ctx).isEmpty()) "" else Feed.pageUrl(ctx)))
            val openApp = Intent(ctx, MainActivity::class.java)
            v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(ctx, 1, if (Feed.token(ctx).isEmpty()) openApp else open, flags))
            val refresh = Intent(ctx, SleepWidget::class.java).setAction(ACTION_REFRESH)
            v.setOnClickPendingIntent(R.id.refresh, PendingIntent.getBroadcast(ctx, 2, refresh, flags))

            v.setViewVisibility(R.id.chrono, View.GONE)
            v.setViewVisibility(R.id.next, View.GONE)
            v.setViewVisibility(R.id.next_chrono, View.GONE)
            v.setViewVisibility(R.id.flag, View.GONE)
            v.setViewVisibility(R.id.phase, View.GONE)

            if (Feed.token(ctx).isEmpty()) {
                v.setTextViewText(R.id.state, "לא מוגדר")
                v.setTextViewText(R.id.today, "פתחו את האפליקציה והדביקו את הקישור")
                v.setTextViewText(R.id.updated, "")
                return v
            }
            if (j == null) {
                v.setTextViewText(R.id.state, "טוען…")
                v.setTextViewText(R.id.today, "")
                v.setTextViewText(R.id.updated, "")
                return v
            }

            val now = System.currentTimeMillis()
            val state = j.optString("state")
            val since = ms(j.optString("since"))
            val sinceHm = j.optString("since_hm")

            if (state == "unknown" || since == null) {
                v.setTextViewText(R.id.state, "אין עדיין רישום")
            } else {
                val label = when {
                    state == "asleep" && j.optString("kind") == "night" -> "ישן שנת לילה מ-$sinceHm"
                    state == "asleep" -> "ישן תנומה מ-$sinceHm"
                    else -> "ער מ-$sinceHm"
                }
                v.setTextViewText(R.id.state, label)
                v.setViewVisibility(R.id.chrono, View.VISIBLE)
                v.setChronometer(R.id.chrono, SystemClock.elapsedRealtime() - (now - since), null, true)
            }

            // Phase: colour, label, gauge and countdown (podcast rules, computed by the feed).
            val ph = j.optJSONObject("phase")
            val color = runCatching { Color.parseColor(ph?.optString("color") ?: "") }.getOrDefault(Color.parseColor("#B0A69C"))
            val label = ph?.optString("label").orEmpty()
            if (ph != null && label.isNotEmpty()) {
                v.setViewVisibility(R.id.phase, View.VISIBLE)
                v.setTextViewText(R.id.phase, label)
                v.setTextColor(R.id.phase, color)
            }
            val level = if (since != null) {
                val el = (now - since) / 60000.0
                when {
                    state == "awake" -> el / (j.optDouble("window_min", 60.0) + 20.0)
                    j.optString("kind") == "nap" -> el / 120.0
                    else -> el / 750.0
                }.coerceIn(0.0, 1.0)
            } else 0.0
            v.setProgressBar(R.id.gauge, 1000, (level * 1000).toInt(), false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                v.setColorStateList(R.id.gauge, "setProgressTintList", ColorStateList.valueOf(color))
            }
            val target = ms(ph?.optString("target_at"))
            if (target != null) {
                v.setViewVisibility(R.id.next, View.VISIBLE)
                v.setViewVisibility(R.id.next_chrono, View.VISIBLE)
                v.setTextColor(R.id.next_chrono, color)
                if (target > now) {
                    v.setTextViewText(R.id.next, ph?.optString("target_label").orEmpty())
                    v.setChronometer(R.id.next_chrono, SystemClock.elapsedRealtime() + (target - now), null, true)
                    v.setChronometerCountDown(R.id.next_chrono, true)
                } else {
                    v.setTextViewText(R.id.next, "עבר מאז")
                    v.setChronometer(R.id.next_chrono, SystemClock.elapsedRealtime() - (now - target), null, true)
                    v.setChronometerCountDown(R.id.next_chrono, false)
                }
            }
            ms(ph?.optString("next_change_at"))?.let { scheduleEdge(ctx, it) }

            val t = j.optJSONObject("today")
            val naps = t?.optInt("naps", 0) ?: 0
            val dayMin: Long = t?.optLong("day_sleep_min", 0L) ?: 0L
            val pr = j.optJSONObject("progress")
            val nightMin = if (pr != null && !pr.isNull("night_min")) pr.optLong("night_min", -1L) else -1L
            val nightTxt = if (nightMin >= 0) "לילה ${dur(nightMin)}/12 ש' · " else ""
            v.setTextViewText(R.id.today, "${nightTxt}תנומות $naps/4-6 · ${dur(dayMin)}")

            val at = Feed.cachedAt(ctx)
            val upd = if (at > 0) HM.format(Instant.ofEpochMilli(at)) else ""
            v.setTextViewText(R.id.updated, if (stale) "עודכן $upd · אין חיבור" else "עודכן $upd")
            return v
        }
    }
}
