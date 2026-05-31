package com.thesis.middleware

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.thesis.middleware.adaptation.ExecutionEvent
import com.thesis.middleware.adaptation.OffloadableTask
import com.thesis.middleware.context.ContextService
import com.thesis.middleware.demo.DemoTasks
import com.thesis.middleware.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Launcher Activity. Three responsibilities:
 *
 *  1. Request runtime-dangerous permissions and start [ContextService].
 *  2. Provide demo buttons that submit sample [OffloadableTask]s through
 *     [com.thesis.middleware.adaptation.ExecutionProxy].
 *  3. Subscribe to `ExecutionProxy.events` and render each MAPE decision
 *     (target + reasoning + actual wall-clock) in a scrolling log so the
 *     audience can see *why* the system chose LOCAL / EDGE / CLOUD.
 *
 * UI-scoped coroutines live on [uiScope] (cancelled in [onDestroy]).
 * Background offload calls run on the application scope so they survive
 * orientation changes and the Activity being briefly destroyed.
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var eventJob: Job? = null

    private val logBuffer: ArrayDeque<String> = ArrayDeque()
    private val timestampFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        if (hasAllPermissions()) {
            startMiddleware()
        } else {
            statusText.text = getString(R.string.main_status_permission_needed)
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        eventJob = (application as MiddlewareApp).executionProxy.events
            .onEach { event -> uiScope.launch { appendLog(event) } }
            .launchIn(uiScope)
    }

    override fun onStop() {
        super.onStop()
        eventJob?.cancel()
        eventJob = null
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && hasAllPermissions()) {
            startMiddleware()
        }
    }

    private fun startMiddleware() {
        ContextService.start(this)
        statusText.text = getString(R.string.main_status_running)
    }

    private fun hasAllPermissions(): Boolean = REQUIRED_PERMISSIONS.all { perm ->
        checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun runTask(taskFactory: () -> OffloadableTask) {
        val app = application as MiddlewareApp
        val task = taskFactory()
        app.appScope.launch {
            runCatching { app.executionProxy.run(task) }
                .onFailure { uiScope.launch { appendError(task, it) } }
        }
    }

    private fun appendLog(event: ExecutionEvent) {
        val ts = timestampFmt.format(Date())
        val tag = if (event.fellBackToLocal) "⚠ fallback→LOCAL" else event.decision.target.name
        val err = event.errorMessage?.let { " err=$it" } ?: ""
        val line = "[$ts] ${event.taskName} → $tag  ${event.actualMs}ms  ${event.resultSizeBytes}B$err\n" +
            "   ${event.decision.reasoning}"
        pushLine(line)
    }

    private fun appendError(task: OffloadableTask, t: Throwable) {
        val ts = timestampFmt.format(Date())
        pushLine("[$ts] ${task.name} ✗ ${t.javaClass.simpleName}: ${t.message}")
    }

    private fun pushLine(line: String) {
        logBuffer.addFirst(line)
        while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeLast()
        logText.text = logBuffer.joinToString("\n\n")
    }

    private fun buildLayout(): View {
        val pad = 32
        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(pad, pad, pad, pad / 2)
        }
        logText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(pad, pad, pad, pad)
            movementMethod = ScrollingMovementMethod()
            text = getString(R.string.main_log_placeholder)
        }

        fun button(textRes: Int, onClick: () -> Unit) = Button(this).apply {
            setText(textRes)
            setOnClickListener { onClick() }
        }

        val taskRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, 0, pad, 0)
            addView(button(R.string.main_task_echo) { runTask(DemoTasks::echo) })
            addView(button(R.string.main_task_sha) { runTask(DemoTasks::sha256) })
            addView(button(R.string.main_task_matrix) { runTask { DemoTasks.matrixMultiply() } })
        }

        val settingsButton = Button(this).apply {
            setText(R.string.main_open_settings)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }

        val logScroll = ScrollView(this).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f, // weight — fills remaining space
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(statusText)
            addView(taskRow)
            addView(settingsButton)
            addView(logScroll)
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private const val MAX_LOG_LINES = 10

        private val REQUIRED_PERMISSIONS: Array<String> = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }.toTypedArray()
    }
}
