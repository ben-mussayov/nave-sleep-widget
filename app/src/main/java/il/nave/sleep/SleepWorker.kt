package il.nave.sleep

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class SleepWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val fresh = Feed.fetch(applicationContext)
        SleepWidget.renderAll(applicationContext, fresh ?: Feed.cached(applicationContext), stale = fresh == null)
        return Result.success()
    }
}
