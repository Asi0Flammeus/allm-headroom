package cat.alysis.foldusage

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.work.ExistingPeriodicWorkPolicy
import java.net.URI
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Settings screen. Doubles as the widget's APPWIDGET_CONFIGURE activity and as
 * the app's launcher entry. Compact windows get one column; windows at least
 * 600 dp wide (the Fold's inner screen) get settings on the left and live
 * status plus a widget preview on the right (res/layout-w600dp).
 */
class ConfigActivity : Activity() {

    private lateinit var store: UsageStore
    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var tokenToggle: Button
    private lateinit var toggles: LinearLayout
    private var tokenVisible = false
    private var toggleSignature: List<String>? = null

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderStatus()
            ticker.postDelayed(this, 30_000L)
        }
    }

    /** Held strongly: SharedPreferences keeps listeners in a weak map. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        renderStatus()
        renderToggles()
    }

    private val widgetId: Int
        get() = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED) // configure flow: back == don't place the widget
        setContentView(R.layout.activity_config)
        applyInsets(findViewById(R.id.config_root))

        store = UsageStore(this)
        urlField = findViewById(R.id.field_url)
        tokenField = findViewById(R.id.field_token)
        tokenToggle = findViewById(R.id.btn_token_visibility)
        toggles = findViewById(R.id.display_toggles)

        urlField.setText(store.baseUrl.ifBlank { Endpoint.DEFAULT_URL })
        tokenField.setText(store.token)
        tokenVisible = savedInstanceState?.getBoolean(STATE_TOKEN_VISIBLE) ?: false
        applyTokenVisibility()

        findViewById<Button>(R.id.btn_paste).setOnClickListener { pasteUrl() }
        findViewById<Button>(R.id.btn_default_url).setOnClickListener { setUrl(Endpoint.DEFAULT_URL) }
        findViewById<Button>(R.id.btn_clear_url).setOnClickListener {
            setUrl("")
            urlField.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(urlField, 0)
        }
        tokenToggle.setOnClickListener {
            tokenVisible = !tokenVisible
            applyTokenVisibility()
        }
        findViewById<Button>(R.id.btn_test).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        findViewById<Button>(R.id.btn_refresh_now).setOnClickListener { refreshNow() }
        findViewById<Button>(R.id.btn_pin).setOnClickListener { requestPin() }
        bindRefreshChoice()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_TOKEN_VISIBLE, tokenVisible)
    }

    override fun onResume() {
        super.onResume()
        store.registerListener(prefsListener)
        renderToggles()
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        store.unregisterListener(prefsListener)
        ticker.removeCallbacks(tick)
    }

    /** Edge-to-edge is enforced at targetSdk 35+: pad for system bars, cutouts and the keyboard. */
    private fun applyInsets(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            root.fitsSystemWindows = true
            return
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
            )
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsets.CONSUMED
        }
    }

    // --- connection -------------------------------------------------------------------

    private fun setUrl(value: String) {
        urlField.setText(value)
        urlField.setSelection(value.length)
        urlField.error = null
    }

    private fun pasteUrl() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        setUrl(text)
    }

    private fun applyTokenVisibility() {
        val variation = if (tokenVisible) {
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        tokenField.inputType = InputType.TYPE_CLASS_TEXT or variation or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        tokenField.setSelection(tokenField.text.length)
        tokenToggle.setText(if (tokenVisible) R.string.btn_hide else R.string.btn_show)
    }

    private enum class Outcome { RUNNING, OK, FAILED }

    private fun showTest(outcome: Outcome, title: String, detail: String) {
        findViewById<View>(R.id.test_result).visibility = View.VISIBLE
        val color = getColor(
            when (outcome) {
                Outcome.RUNNING -> R.color.hr_muted
                Outcome.OK -> R.color.hr_ok
                Outcome.FAILED -> R.color.hr_bad
            },
        )
        findViewById<TextView>(R.id.test_icon).apply {
            text = when (outcome) {
                Outcome.RUNNING -> "\u2026"
                Outcome.OK -> "\u2713"
                Outcome.FAILED -> "\u2715"
            }
            setTextColor(color)
        }
        findViewById<TextView>(R.id.test_title).apply {
            text = title
            setTextColor(color)
        }
        findViewById<TextView>(R.id.test_detail).text = detail
    }

    private fun testConnection() {
        val url = Endpoint.normalize(urlField.text.toString())
        Endpoint.problem(url)?.let {
            showTest(Outcome.FAILED, "Invalid URL", it)
            return
        }
        val token = tokenField.text.toString().trim()
        showTest(Outcome.RUNNING, "Testing\u2026", "GET $url/usage")
        thread {
            val started = SystemClock.elapsedRealtime()
            val (outcome, title, detail) = try {
                val resp = Http.get("$url/usage", token)
                val ms = SystemClock.elapsedRealtime() - started
                if (resp.code == 200) {
                    val parsed = parseUsage(resp.body)
                    val windows = parsed.providers.values.sumOf { p -> p.windows.count { it.available } }
                    val stale = if (parsed.stale) " \u00b7 server data stale" else ""
                    Triple(Outcome.OK, "Connected", "$windows usage windows \u00b7 answered in $ms ms$stale")
                } else {
                    Triple(Outcome.FAILED, "Connection failed", Http.describeStatus(resp.code, resp.body))
                }
            } catch (e: Exception) {
                Triple(Outcome.FAILED, "Connection failed", Http.describeFailure(e))
            }
            runOnUiThread { if (!isDestroyed) showTest(outcome, title, detail) }
        }
    }

    private fun save() {
        val url = Endpoint.normalize(urlField.text.toString())
        val token = tokenField.text.toString().trim()
        Endpoint.problem(url)?.let {
            urlField.error = it
            urlField.requestFocus()
            return
        }
        if (token.isEmpty()) {
            tokenField.error = getString(R.string.token_required)
            tokenField.requestFocus()
            return
        }
        store.baseUrl = url
        store.token = token
        setUrl(url)
        FetchUsageWorker.schedulePeriodic(this)
        FetchUsageWorker.refreshNow(this)
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        } else {
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }
    }

    // --- refresh ----------------------------------------------------------------------

    private fun bindRefreshChoice() {
        val ids = mapOf(15 to R.id.refresh_15, 30 to R.id.refresh_30, 60 to R.id.refresh_60, 180 to R.id.refresh_180)
        val group = findViewById<RadioGroup>(R.id.refresh_group)
        group.check(ids.getValue(store.refreshMinutes))
        group.setOnCheckedChangeListener { _, checkedId ->
            val minutes = ids.entries.first { it.value == checkedId }.key
            if (minutes == store.refreshMinutes) return@setOnCheckedChangeListener
            store.refreshMinutes = minutes
            if (store.isConfigured) FetchUsageWorker.schedulePeriodic(this, ExistingPeriodicWorkPolicy.UPDATE)
            WidgetRenderer.updateAll(this)
        }
    }

    private fun refreshNow() {
        if (!store.isConfigured) {
            Toast.makeText(this, R.string.not_paired, Toast.LENGTH_SHORT).show()
            return
        }
        FetchUsageWorker.refreshNow(this)
        findViewById<TextView>(R.id.status_headline).text = "Refreshing\u2026"
    }

    // --- what the widget shows ---------------------------------------------------------

    /** Rebuilt only when the set of known windows changes, so toggling never jumps the list. */
    private fun renderToggles() {
        val parsed = store.lastBody?.let { runCatching { parseUsage(it) }.getOrNull() }
        val windows = DisplayState.PROVIDERS.associateWith { key -> parsed?.providers?.get(key)?.windows.orEmpty() }
        val signature = windows.flatMap { (key, ws) -> listOf(key) + ws.map { DisplayState.windowKey(key, it.id) } }
        if (signature == toggleSignature) return
        toggleSignature = signature

        toggles.removeAllViews()
        val hidden = store.hidden
        for ((key, ws) in windows) {
            val providerSwitch = Switch(this).apply {
                text = PROVIDER_NAMES[key] ?: key
                textSize = 15f
                setTextColor(getColor(R.color.hr_ink))
                isChecked = key !in hidden
                minHeight = dp(48)
            }
            toggles.addView(providerSwitch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val boxes = ws.map { w ->
                val windowKey = DisplayState.windowKey(key, w.id)
                CheckBox(this).apply {
                    text = w.label.ifEmpty { w.id }
                    setTextColor(getColor(R.color.hr_body))
                    isChecked = windowKey !in hidden
                    isEnabled = providerSwitch.isChecked
                    setOnCheckedChangeListener { _, shown -> setHidden(windowKey, !shown) }
                }.also { box ->
                    toggles.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(24) })
                }
            }
            providerSwitch.setOnCheckedChangeListener { _, shown ->
                setHidden(key, !shown)
                boxes.forEach { it.isEnabled = shown }
            }
        }
        if (windows.values.all { it.isEmpty() }) {
            toggles.addView(TextView(this).apply {
                setText(R.string.display_no_windows)
                textSize = 12f
                setTextColor(getColor(R.color.hr_muted))
            })
        }
    }

    private fun setHidden(key: String, hide: Boolean) {
        store.hidden = if (hide) store.hidden + key else store.hidden - key
        WidgetRenderer.updateAll(this)
    }

    // --- status + preview --------------------------------------------------------------

    private fun renderStatus() {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val parsed = store.lastBody?.let { runCatching { parseUsage(it) }.getOrNull() }
        val staleAfter = Refresh.staleAfterMs(store.refreshMinutes)
        val freshness = DisplayState.computeFreshness(
            now, store.isConfigured, parsed != null, store.lastSuccessAtMs,
            parsed?.stale ?: false, store.lastError, staleAfter,
        )
        val (headline, colorRes) = when (freshness) {
            Freshness.FRESH -> "Fresh" to R.color.hr_ok
            Freshness.STALE -> "Stale" to R.color.hr_warn
            Freshness.ERROR -> "Offline \u00b7 showing last numbers" to R.color.hr_bad
            Freshness.NO_DATA -> "No data yet" to R.color.hr_muted
            Freshness.UNCONFIGURED -> "Not paired" to R.color.hr_muted
        }
        findViewById<TextView>(R.id.status_dot).setTextColor(getColor(colorRes))
        findViewById<TextView>(R.id.status_headline).text = headline

        fun at(ms: Long) = if (ms <= 0L) "never" else "${DisplayState.formatClock(ms, zone)} \u00b7 ${DisplayState.ageText(now, ms)}"
        val attempt = maxOf(store.lastAttemptAtMs, store.lastSuccessAtMs)
        val lines = mutableListOf(
            "Last fetch: ${at(attempt)}" + if (store.lastError != null) " (failed)" else "",
            "Last success: ${at(store.lastSuccessAtMs)}",
        )
        store.lastError?.let { lines += "Error: $it" }
        if (parsed != null) {
            lines += "Server data: " + if (parsed.stale) {
                DisplayState.staleSinceText(parsed.staleSince, zone) ?: "stale"
            } else {
                "current"
            }
        }
        lines += "Refresh: every ${refreshLabel(store.refreshMinutes)} \u00b7 stale after ${refreshLabel((staleAfter / 60_000L).toInt())}"
        lines += "Bridge: " + (store.baseUrl.takeIf { it.isNotBlank() }?.let { hostPort(it) } ?: "not set")
        findViewById<TextView>(R.id.status_detail).text = lines.joinToString("\n")

        val host = findViewById<FrameLayout>(R.id.preview_host)
        host.removeAllViews()
        host.addView(WidgetRenderer.build(this).apply(this, host))
    }

    private fun refreshLabel(minutes: Int): String = if (minutes % 60 == 0) "${minutes / 60} h" else "$minutes min"

    private fun hostPort(url: String): String = runCatching {
        val uri = URI(url)
        uri.host + if (uri.port > 0) ":${uri.port}" else ""
    }.getOrNull() ?: url

    // --- home screen -------------------------------------------------------------------

    private fun requestPin() {
        val manager = AppWidgetManager.getInstance(this)
        if (!manager.isRequestPinAppWidgetSupported) {
            Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        manager.requestPinAppWidget(
            ComponentName(this, UsageWidgetProvider::class.java), null, null,
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val STATE_TOKEN_VISIBLE = "token_visible"
        val PROVIDER_NAMES = mapOf("anthropic" to "Anthropic \u00b7 Claude", "openai" to "OpenAI \u00b7 Codex")
    }
}
