package cat.alysis.foldusage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

/**
 * App-private persistence: pairing config (base URL + bearer token) and the
 * last fetch outcome. The token never leaves this app-private file; it grants
 * read-only usage percentages over a tailnet-only listener.
 */
class UsageStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("usage", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_URL, value.trimEnd('/')).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()

    /** Raw JSON body of the last 200 response that parsed cleanly; null if never. */
    val lastBody: String?
        get() = prefs.getString(KEY_BODY, null)

    val lastSuccessAtMs: Long
        get() = prefs.getLong(KEY_SUCCESS_AT, 0L)

    /** Non-null iff the MOST RECENT attempt failed. */
    val lastError: String?
        get() = prefs.getString(KEY_ERROR, null)

    fun saveSuccess(body: String, atMs: Long) {
        prefs.edit()
            .putString(KEY_BODY, body)
            .putLong(KEY_SUCCESS_AT, atMs)
            .remove(KEY_ERROR)
            .apply()
        recordCapacitySnapshot(body, atMs)
    }

    fun saveError(message: String) {
        prefs.edit().putString(KEY_ERROR, message).apply()
    }

    /** One local daily capacity observation per provider/window, kept for 30 days.
     * This is deliberately capacity history, not fabricated token-by-model data. */
    fun capacityHistory(): List<CapacitySnapshot> = try {
        val a = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        (0 until a.length()).map { i -> CapacitySnapshot.fromJson(a.getJSONObject(i)) }
    } catch (_: Exception) { emptyList() }

    private fun recordCapacitySnapshot(body: String, atMs: Long) {
        val day = Instant.ofEpochMilli(atMs).atZone(ZoneOffset.UTC).toLocalDate().toString()
        val entries = try {
            parseUsage(body).providers.flatMap { (provider, usage) -> usage.windows.mapNotNull { w ->
                w.remainingPercent?.let { CapacitySnapshot(day, provider, w.label.ifBlank { w.id }, it) }
            } }
        } catch (_: Exception) { return }
        val old = capacityHistory().filterNot { it.day == day }
        val merged = (old + entries).takeLast(30 * 8)
        prefs.edit().putString(KEY_HISTORY, JSONArray(merged.map { it.toJson() }).toString()).apply()
    }

    private companion object {
        const val KEY_URL = "base_url"
        const val KEY_TOKEN = "token"
        const val KEY_BODY = "last_body"
        const val KEY_SUCCESS_AT = "last_success_at"
        const val KEY_ERROR = "last_error"
        const val KEY_HISTORY = "capacity_history"
    }
}

data class CapacitySnapshot(val day: String, val provider: String, val window: String, val remaining: Int) {
    fun toJson() = JSONObject().put("day", day).put("provider", provider).put("window", window).put("remaining", remaining)
    companion object { fun fromJson(o: JSONObject) = CapacitySnapshot(o.getString("day"), o.getString("provider"), o.getString("window"), o.getInt("remaining")) }
}
