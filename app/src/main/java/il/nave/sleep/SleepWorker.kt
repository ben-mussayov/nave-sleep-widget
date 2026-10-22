package il.nave.sleep

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class SleepWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        if (inputData.getBoolean("render_only", false)) {
            SleepWidget.renderAll(applicationContext, Feed.cached(applicationContext), stale = true)
            return Result.success()
        }
        val fresh = Feed.fetch(applicationContext)
        SleepWidget.renderAll(applicationContext, fresh ?: Feed.cached(applicationContext), stale = fresh == null)
        return Result.success()
    }
}
