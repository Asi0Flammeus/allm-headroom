package cat.alysis.foldusage

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Refresh now" must run at once. With a CONNECTED constraint, JobScheduler waits for a
 * VALIDATED network: a bridge reached over the tailnet (or adb reverse) on a network without
 * internet validation never got fetched, and the widget kept its old "updated" time.
 */
class RefreshRequestTest {

    @Test
    fun manualRefreshHasNoNetworkConstraint() {
        val request = FetchUsageWorker.refreshNowRequest()
        assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
    }
}
