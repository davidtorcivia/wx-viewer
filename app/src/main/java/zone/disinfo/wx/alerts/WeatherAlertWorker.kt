package zone.disinfo.wx.alerts

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** OS-managed baseline; foreground rain watch is only started by the user in a visible Activity. */
class WeatherAlertWorker(appContext: Context, parameters: WorkerParameters) :
    CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val report = WeatherAlertEngine(applicationContext) { isStopped }.check()
        AlertStatusStore(applicationContext).save(report.summary, System.currentTimeMillis())
        if (!isStopped) AlertScheduler.updateCadence(applicationContext, report)
        return if (report.failures > 0 && runAttemptCount < 3) Result.retry() else Result.success()
    }
}
