package cat.alysis.foldusage

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.app.AlarmManager
import android.app.PendingIntent

class UsageWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Render whatever we have immediately, then fetch fresh data.
        val views = WidgetRenderer.build(context)
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, views)
        FetchUsageWorker.schedulePeriodic(context)
        FetchUsageWorker.refreshNow(context)
        schedulePaceTick(context)
    }

    override fun onEnabled(context: Context) {
        FetchUsageWorker.schedulePeriodic(context)
        schedulePaceTick(context)
    }

    override fun onDisabled(context: Context) {
        FetchUsageWorker.cancelPeriodic(context)
        context.getSystemService(AlarmManager::class.java).cancel(tickIntent(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> FetchUsageWorker.refreshNow(context)
            // The expected pace is clock-derived, not network-derived. Repaint it
            // independently so the marker progresses between relay fetches.
            ACTION_PACE_TICK -> {
                WidgetRenderer.updateAll(context)
                schedulePaceTick(context)
            }
            // An APK update must heal a widget paired with a retired bridge host
            // without the user opening the app: UsageStore migrates the saved URL
            // on construction, then fetch from the new host right away.
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                UsageStore(context)
                FetchUsageWorker.schedulePeriodic(context)
                FetchUsageWorker.refreshNow(context)
                schedulePaceTick(context)
                WidgetRenderer.updateAll(context)
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "cat.alysis.foldusage.REFRESH"
        private const val ACTION_PACE_TICK = "cat.alysis.foldusage.PACE_TICK"

        private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 2, Intent(context, UsageWidgetProvider::class.java).setAction(ACTION_PACE_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Best-effort minute repaint; Android may defer it under Doze. */
        fun schedulePaceTick(context: Context) {
            val at = System.currentTimeMillis() / 60_000L * 60_000L + 60_000L
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, at, tickIntent(context),
            )
        }
    }
}
