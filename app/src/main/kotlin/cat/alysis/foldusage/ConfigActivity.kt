package cat.alysis.foldusage

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Pairing screen. Doubles as the widget's APPWIDGET_CONFIGURE activity and as
 * the app's launcher entry so the URL/token can be edited later.
 */
class ConfigActivity : Activity() {

    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var statusView: TextView

    private val widgetId: Int
        get() = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED) // configure flow: back == don't place the widget
        setContentView(R.layout.activity_config)

        urlField = findViewById(R.id.field_url)
        tokenField = findViewById(R.id.field_token)
        statusView = findViewById(R.id.test_status)

        val store = UsageStore(this)
        urlField.setText(store.baseUrl.ifBlank { DEFAULT_URL })
        tokenField.setText(store.token)

        findViewById<Button>(R.id.btn_test).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.btn_save).setOnClickListener { save(store) }
        findViewById<Button>(R.id.btn_pin).setOnClickListener { requestPin() }
    }

    private fun testConnection() {
        val url = urlField.text.toString().trim().trimEnd('/')
        val token = tokenField.text.toString().trim()
        statusView.text = getString(R.string.test_running)
        thread {
            val result = try {
                val resp = Http.get("$url/usage", token)
                when (resp.code) {
                    200 -> {
                        val parsed = parseUsage(resp.body)
                        val windows = parsed.providers.values.sumOf { p ->
                            p.windows.count { it.available }
                        }
                        "OK \u2713 $windows usage windows" + if (parsed.stale) " (server data stale)" else ""
                    }
                    401 -> "rejected: bad token (HTTP 401)"
                    else -> "server error: HTTP ${resp.code}"
                }
            } catch (e: Exception) {
                "unreachable: ${Http.describeFailure(e)}"
            }
            runOnUiThread { statusView.text = result }
        }
    }

    private fun save(store: UsageStore) {
        val url = urlField.text.toString().trim()
        val token = tokenField.text.toString().trim()
        if (url.isBlank() || token.isBlank()) {
            Toast.makeText(this, R.string.save_incomplete, Toast.LENGTH_SHORT).show()
            return
        }
        store.baseUrl = url
        store.token = token
        FetchUsageWorker.schedulePeriodic(this)
        FetchUsageWorker.refreshNow(this)
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
            )
            finish()
        } else {
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }
    }

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

    companion object {
        const val DEFAULT_URL = "https://crqpt-server.tail58339.ts.net:8377"
    }
}
