package cat.alysis.foldusage

import org.json.JSONObject

/** One usage window as served by sub-usage-bridge (`/usage` → providers.*.windows[]). */
data class UsageWindow(
    val id: String,
    val label: String,
    val available: Boolean,
    val usedPercent: Int?,
    val remainingPercent: Int?,
    val resetsAt: String?, // ISO-8601 with offset, e.g. 2026-08-25T06:59:59+00:00
)

data class ProviderUsage(val ok: Boolean, val windows: List<UsageWindow>)

/** Normalized bridge response. `stale`/`fetchError` are the SERVER's freshness verdict. */
data class UsageResponse(
    val fetchedAt: String?,
    val stale: Boolean,
    val fetchError: String?,
    val providers: Map<String, ProviderUsage>,
    val staleSince: String? = null, // ISO-8601Z: when the server's numbers stopped being current
)

private fun JSONObject.intOrNull(key: String): Int? =
    if (isNull(key)) null else optInt(key)

private fun JSONObject.strOrNull(key: String): String? =
    if (isNull(key)) null else optString(key)

/** Parse a bridge `/usage` 200 body. Throws on structurally invalid JSON. */
fun parseUsage(body: String): UsageResponse {
    val o = JSONObject(body)
    val providersJson = o.getJSONObject("providers")
    val providers = mutableMapOf<String, ProviderUsage>()
    for (name in providersJson.keys()) {
        val p = providersJson.getJSONObject(name)
        val windowsJson = p.optJSONArray("windows")
        val windows = mutableListOf<UsageWindow>()
        if (windowsJson != null) {
            for (i in 0 until windowsJson.length()) {
                val w = windowsJson.getJSONObject(i)
                windows.add(
                    UsageWindow(
                        id = w.optString("id"),
                        label = w.optString("label"),
                        available = w.optBoolean("available", false),
                        usedPercent = w.intOrNull("used_percent"),
                        remainingPercent = w.intOrNull("remaining_percent"),
                        resetsAt = w.strOrNull("resets_at"),
                    )
                )
            }
        }
        providers[name] = ProviderUsage(ok = p.optBoolean("ok", false), windows = windows)
    }
    return UsageResponse(
        fetchedAt = o.strOrNull("fetched_at"),
        stale = o.optBoolean("stale", false),
        fetchError = o.strOrNull("fetch_error"),
        providers = providers,
        staleSince = o.strOrNull("stale_since"),
    )
}
