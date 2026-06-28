package com.thesis.middleware.settings

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.thesis.middleware.MiddlewareApp
import com.thesis.middleware.R
import com.thesis.middleware.adaptation.ExecutionMode
import com.thesis.middleware.context.ContextService

/**
 * Lets the user enter the edge / cloud base URLs at runtime so the same APK
 * can demo on any Wi-Fi network without rebuilding.
 *
 * On save:
 *  1. Persist to [EndpointsRepository] (SharedPreferences).
 *  2. Live-mutate the singleton `ConnectionManager` so the change takes
 *     effect immediately for any in-flight or future offload call.
 *  3. Restart [ContextService] so the MAPE loop picks up the new endpoints
 *     on its next probe.
 */
class SettingsActivity : Activity() {

    private lateinit var edgeField: EditText
    private lateinit var cloudField: EditText
    private lateinit var modeGroup: RadioGroup
    private lateinit var debugNetworkField: EditText
    private lateinit var debugSpeedupField: EditText
    private lateinit var debugRemoteEnergyField: EditText
    private var adaptiveId: Int = 0
    private var localOnlyId: Int = 0
    private var cloudOnlyId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings_title)
        setContentView(buildLayout())

        val app = application as MiddlewareApp
        edgeField.setText(app.endpointsRepository.edgeUrl)
        cloudField.setText(app.endpointsRepository.cloudUrl)
        modeGroup.check(modeRadioId(app.endpointsRepository.executionMode))
        app.endpointsRepository.debugNetworkScore?.let { debugNetworkField.setText("%.2f".format(it)) }
        app.endpointsRepository.debugSpeedup?.let { debugSpeedupField.setText("%.2f".format(it)) }
        app.endpointsRepository.debugRemoteEnergyMj?.let { debugRemoteEnergyField.setText("%.0f".format(it)) }
    }

    private fun onSave() {
        val edge = edgeField.text.toString().trim()
        val cloud = cloudField.text.toString().trim()
        if (!isValidUrl(edge) || !isValidUrl(cloud)) {
            Toast.makeText(this, R.string.settings_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }

        val app = application as MiddlewareApp
        app.endpointsRepository.edgeUrl = edge
        app.endpointsRepository.cloudUrl = cloud
        app.endpointsRepository.executionMode = selectedMode()

        val debugScore = debugNetworkField.text.toString().trim().toFloatOrNull()
            ?.coerceIn(0f, 1f)
        app.endpointsRepository.debugNetworkScore = debugScore
        app.contextManager.debugNetworkScore = debugScore

        val debugSpeedup = debugSpeedupField.text.toString().trim().toFloatOrNull()
            ?.coerceAtLeast(0.01f)
        app.endpointsRepository.debugSpeedup = debugSpeedup
        app.mapeLoop.debugSpeedup = debugSpeedup

        val debugRemoteEnergy = debugRemoteEnergyField.text.toString().trim().toFloatOrNull()
            ?.coerceAtLeast(0f)
        app.endpointsRepository.debugRemoteEnergyMj = debugRemoteEnergy
        app.mapeLoop.debugRemoteEnergyMj = debugRemoteEnergy

        // Live-mutate the running ConnectionManager so future probes / requests
        // use the new endpoints without waiting for a process restart.
        app.connectionManager.edgeEndpoint = app.endpointsRepository.edgeUrl
        app.connectionManager.cloudEndpoint = app.endpointsRepository.cloudUrl

        // Bounce the service so the foreground notification and any cached
        // health-probe state restart cleanly.
        ContextService.stop(this)
        ContextService.start(this)

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun modeRadioId(mode: ExecutionMode): Int = when (mode) {
        ExecutionMode.ADAPTIVE   -> adaptiveId
        ExecutionMode.LOCAL_ONLY -> localOnlyId
        ExecutionMode.CLOUD_ONLY -> cloudOnlyId
    }

    private fun selectedMode(): ExecutionMode = when (modeGroup.checkedRadioButtonId) {
        localOnlyId -> ExecutionMode.LOCAL_ONLY
        cloudOnlyId -> ExecutionMode.CLOUD_ONLY
        else        -> ExecutionMode.ADAPTIVE
    }

    private fun isValidUrl(s: String): Boolean =
        s.isNotEmpty() && (s.startsWith("http://") || s.startsWith("https://"))

    private fun buildLayout(): View {
        val pad = 48
        fun label(textRes: Int) = TextView(this).apply {
            setText(textRes)
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, pad / 2, 0, pad / 4)
        }
        fun urlField() = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            textSize = 14f
        }
        edgeField = urlField()
        cloudField = urlField()

        debugNetworkField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            textSize = 14f
            hint = getString(R.string.settings_debug_network_hint)
        }
        debugSpeedupField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            textSize = 14f
            hint = getString(R.string.settings_debug_speedup_hint)
        }
        debugRemoteEnergyField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            textSize = 14f
            hint = getString(R.string.settings_debug_remote_energy_hint)
        }

        // Execution mode radio group — Adaptive / Local-only / Cloud-only
        adaptiveId = View.generateViewId()
        localOnlyId = View.generateViewId()
        cloudOnlyId = View.generateViewId()
        modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(this@SettingsActivity).apply {
                id = adaptiveId
                text = ExecutionMode.ADAPTIVE.displayName
                textSize = 13f
            })
            addView(RadioButton(this@SettingsActivity).apply {
                id = localOnlyId
                text = ExecutionMode.LOCAL_ONLY.displayName
                textSize = 13f
            })
            addView(RadioButton(this@SettingsActivity).apply {
                id = cloudOnlyId
                text = ExecutionMode.CLOUD_ONLY.displayName
                textSize = 13f
            })
        }

        val saveButton = Button(this).apply {
            setText(R.string.settings_save)
            setOnClickListener { onSave() }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(label(R.string.settings_edge_label))
            addView(edgeField)
            addView(label(R.string.settings_cloud_label))
            addView(cloudField)
            addView(label(R.string.settings_mode_label))
            addView(modeGroup)
            addView(label(R.string.settings_debug_network_label))
            addView(debugNetworkField)
            addView(label(R.string.settings_debug_speedup_label))
            addView(debugSpeedupField)
            addView(label(R.string.settings_debug_remote_energy_label))
            addView(debugRemoteEnergyField)
            addView(saveButton)
        }
    }
}
