package il.nave.sleep

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.Data
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
        WorkManager.getInstance(ctx).cancelUniqueWork(TICK)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) refreshNow(ctx)
    }

    companion object {
        const val ACTION_REFRESH = "il.nave.sleep.REFRESH"
        private const val PERIODIC = "sleep_periodic"
        private const val NOW = "sleep_now"
        private const val TICK = "sleep_tick"
        val TZ: ZoneId = ZoneId.of("Asia/Jerusalem")
        val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(TZ)

        private val netOk = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SleepWorker>(15, TimeUnit.MINUTES).setConstraints(netOk).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun refreshNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SleepWorker>().setConstraints(netOk).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }

        /** Re-colour every few minutes from the cache (no network), so the gradient moves with time. */
        private fun scheduleTick(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SleepWorker>()
                .setInitialDelay(3, TimeUnit.MINUTES)
                .setInputData(Data.Builder().putBoolean("render_only", true).build()).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(TICK, ExistingWorkPolicy.REPLACE, req)
        }

        fun renderAll(ctx: Context, j: JSONObject?, stale: Boolean) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SleepWidget::class.java))
            if (ids.isEmpty()) return
            mgr.updateAppWidget(ids, build(ctx, j, stale))
            scheduleTick(ctx)
        }

        private fun action(ctx: Context, what: String, code: Int): PendingIntent {
            val i = Intent(ctx, ActionActivity::class.java).putExtra("action", what)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun build(ctx: Context, j: JSONObject?, stale: Boolean): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_sleep)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val refresh = PendingIntent.getBroadcast(ctx, 2, Intent(ctx, SleepWidget::class.java).setAction(ACTION_REFRESH), flags)
            val openApp = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), flags)
            v.setOnClickPendingIntent(R.id.root, refresh)

            if (Feed.token(ctx).isEmpty()) {
                v.setTextViewText(R.id.state, "לא מוגדר")
                v.setTextViewText(R.id.since, "הגדרה")
                v.setOnClickPendingIntent(R.id.since, openApp)
                v.setOnClickPendingIntent(R.id.btn_sleep, openApp)
                v.setOnClickPendingIntent(R.id.btn_wake, openApp)
                v.setViewVisibility(R.id.gauge, View.INVISIBLE)
                return v
            }

            val now = System.currentTimeMillis()
            val look = Palette.look(j, now)
            v.setInt(R.id.bg, "setColorFilter", look.bg)
            v.setTextColor(R.id.state, look.fg)
            v.setTextColor(R.id.chrono, look.fg)

            val state = j?.optString("state") ?: ""
            val kind = j?.optString("kind") ?: ""
            val since = Palette.ms(j?.optString("since"))
            v.setTextViewText(R.id.state, when {
                j == null -> "טוען…"
                state == "asleep" && kind == "night" -> "שנת לילה"
                state == "asleep" -> "תנומה"
                state == "awake" && kind == "night_waking" -> "ער בלילה"
                state == "awake" -> "ער"
                else -> "אין רישום"
            })
            if (since != null) {
                v.setViewVisibility(R.id.chrono, View.VISIBLE)
                v.setChronometer(R.id.chrono, SystemClock.elapsedRealtime() - (now - since), null, true)
                v.setTextViewText(R.id.since, "מאז " + HM.format(Instant.ofEpochMilli(since)))
                v.setOnClickPendingIntent(R.id.since, action(ctx, "edit", 10))
            } else {
                v.setChronometer(R.id.chrono, SystemClock.elapsedRealtime(), null, false)
                v.setTextViewText(R.id.since, "--:--")
                v.setOnClickPendingIntent(R.id.since, refresh)
            }

            v.setViewVisibility(R.id.gauge, if (look.showBar) View.VISIBLE else View.INVISIBLE)
            v.setProgressBar(R.id.gauge, 1000, (look.level * 1000).toInt(), false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                v.setColorStateList(R.id.gauge, "setProgressTintList", ColorStateList.valueOf(look.bar))
                v.setColorStateList(R.id.gauge, "setProgressBackgroundTintList", ColorStateList.valueOf(0x66FFFFFF))
            }

            // Only the button that makes sense right now is active.
            val asleep = state == "asleep"
            val sleepOn = !asleep
            val wakeOn = asleep
            v.setInt(R.id.btn_sleep, "setBackgroundResource", if (sleepOn) R.drawable.btn_sleep else R.drawable.btn_sleep_off)
            v.setInt(R.id.btn_wake, "setBackgroundResource", if (wakeOn) R.drawable.btn_wake else R.drawable.btn_wake_off)
            v.setTextColor(R.id.btn_sleep, if (sleepOn) 0xFFFFFFFF.toInt() else 0xB3FFFFFF.toInt())
            v.setTextColor(R.id.btn_wake, if (wakeOn) 0xFFFFFFFF.toInt() else 0xB3FFFFFF.toInt())
            v.setOnClickPendingIntent(R.id.btn_sleep, if (sleepOn) action(ctx, "sleep", 11) else refresh)
            v.setOnClickPendingIntent(R.id.btn_wake, if (wakeOn) action(ctx, "wake", 12) else refresh)

            if (stale && Feed.cachedAt(ctx) > 0 && now - Feed.cachedAt(ctx) > 20 * 60000) {
                v.setViewVisibility(R.id.updated, View.VISIBLE)
                v.setTextColor(R.id.updated, look.fg)
                v.setTextViewText(R.id.updated, "אין חיבור · עודכן " + HM.format(Instant.ofEpochMilli(Feed.cachedAt(ctx))))
            } else v.setViewVisibility(R.id.updated, View.GONE)
            return v
        }
    }
}
