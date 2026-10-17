package cat.alysis.foldusage

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.Duration

/**
 * Fetches /usage from the paired bridge, persists the outcome, re-renders all
 * widget instances. Always returns success: the periodic tick (user-chosen
 * cadence, 15 min by default) is the retry; explicit error state is the
 * user-visible signal.
 */
class FetchUsageWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val store = UsageStore(applicationContext)
        if (store.isConfigured) {
            fetchInto(store)
        }
        WidgetRenderer.updateAll(applicationContext)
        return Result.success()
    }

    private fun fetchInto(store: UsageStore) {
        try {
            val resp = Http.get("${store.baseUrl}/usage", store.token)
            if (resp.code == 200) {
                parseUsage(resp.body) // reject bodies the renderer can't use
                store.saveSuccess(resp.body, System.currentTimeMillis())
            } else {
                store.saveError(Http.describeStatus(resp.code, resp.body), System.currentTimeMillis())
            }
        } catch (e: Exception) {
            store.saveError(Http.describeFailure(e), System.currentTimeMillis())
        }
    }

    companion object {
        private const val PERIODIC = "usage-periodic"
        private const val NOW = "usage-now"

        /** Periodic fetch at the user's cadence (WorkManager floor: 15 min), network-gated. */
        fun schedulePeriodic(context: Context, policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP) {
            val minutes = UsageStore(context).refreshMinutes.toLong()
            val request = PeriodicWorkRequestBuilder<FetchUsageWorker>(Duration.ofMinutes(minutes))
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, policy, request)
        }

        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<FetchUsageWorker>()
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }

        private fun networkConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
