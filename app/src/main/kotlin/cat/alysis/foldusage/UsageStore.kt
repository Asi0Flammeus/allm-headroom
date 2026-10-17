package cat.alysis.foldusage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

/**
 * App-private persistence: pairing config (base URL + bearer token), display
 * settings, and the last fetch outcome. The token never leaves this app-private
 * file; it grants read-only usage percentages over a tailnet-only listener.
 */
class UsageStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("usage", Context.MODE_PRIVATE)

    init {
        // Installs paired with a retired bridge host rehome themselves on first use.
        Endpoint.migrated(baseUrl)?.let { prefs.edit().putString(KEY_URL, it).apply() }
    }

    var baseUrl: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_URL, Endpoint.normalize(value)).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()

    /** Background refresh cadence, one of [Refresh.CHOICES_MIN]. */
    var refreshMinutes: Int
        get() = Refresh.coerce(prefs.getInt(KEY_REFRESH_MIN, Refresh.DEFAULT_MIN))
        set(value) = prefs.edit().putInt(KEY_REFRESH_MIN, Refresh.coerce(value)).apply()

    /** Provider keys ("openai") and window keys ("anthropic/5h") the user hid from the widget. */
    var hidden: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_HIDDEN, value.toSet()).apply()

    /** When the last fetch attempt finished, success or not; 0 if never. */
    val lastAttemptAtMs: Long
        get() = prefs.getLong(KEY_ATTEMPT_AT, 0L)

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

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
            .putLong(KEY_ATTEMPT_AT, atMs)
            .remove(KEY_ERROR)
            .apply()
        recordCapacitySnapshot(body, atMs)
    }

    fun saveError(message: String, atMs: Long) {
        prefs.edit().putString(KEY_ERROR, message).putLong(KEY_ATTEMPT_AT, atMs).apply()
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
        const val KEY_ATTEMPT_AT = "last_attempt_at"
        const val KEY_REFRESH_MIN = "refresh_minutes"
        const val KEY_HIDDEN = "hidden"
    }
}

data class CapacitySnapshot(val day: String, val provider: String, val window: String, val remaining: Int) {
    fun toJson() = JSONObject().put("day", day).put("provider", provider).put("window", window).put("remaining", remaining)
    companion object { fun fromJson(o: JSONObject) = CapacitySnapshot(o.getString("day"), o.getString("provider"), o.getString("window"), o.getInt("remaining")) }
}
