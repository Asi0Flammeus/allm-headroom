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
import org.json.JSONObject
import java.time.Duration

/**
 * Fetches /usage from the paired bridge, persists the outcome, re-renders all
 * widget instances. Always returns success: the 15-minute periodic tick is the
 * retry; explicit error state is the user-visible signal.
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
            when {
                resp.code == 200 -> {
                    parseUsage(resp.body) // reject bodies the renderer can't use
                    store.saveSuccess(resp.body, System.currentTimeMillis())
                }
                else -> {
                    val detail = try {
                        JSONObject(resp.body).optString("error").takeIf { it.isNotBlank() }
                    } catch (_: Exception) {
                        null
                    }
                    store.saveError("HTTP ${resp.code}" + (detail?.let { " \u00b7 $it" } ?: ""))
                }
            }
        } catch (e: Exception) {
            store.saveError(Http.describeFailure(e))
        }
    }

    companion object {
        private const val PERIODIC = "usage-periodic"
        private const val NOW = "usage-now"

        /** Refresh cadence: WorkManager periodic minimum, 15 minutes, network-gated. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<FetchUsageWorker>(Duration.ofMinutes(15))
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
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
