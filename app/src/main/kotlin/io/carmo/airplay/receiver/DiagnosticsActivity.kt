package io.carmo.airplay.receiver

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsActivity : Activity() {

    private lateinit var diagnosticsText: TextView
    private lateinit var copyButton: Button
    private lateinit var restartDiscoveryButton: Button
    private lateinit var exportDiagnosticsButton: Button
    private var runtime: ReceiverRuntime? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as ReceiverForegroundService.ReceiverBinder
            runtime = binder.runtime
            isBound = true
            refreshDiagnostics()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            runtime = null
            isBound = false
            refreshDiagnostics()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        diagnosticsText = findViewById(R.id.diagnostics_text)
        copyButton = findViewById(R.id.copy_diagnostics_button)
        restartDiscoveryButton = findViewById(R.id.restart_discovery_button)
        exportDiagnosticsButton = findViewById(R.id.export_diagnostics_button)
        copyButton.setOnClickListener { copyDiagnostics() }
        restartDiscoveryButton.setOnClickListener { restartDiscovery() }
        exportDiagnosticsButton.setOnClickListener { exportDiagnostics() }
        copyButton.post { copyButton.requestFocus() }

        startReceiverForegroundService()
        bindService(Intent(this, ReceiverForegroundService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        refreshDiagnostics()
    }

    override fun onResume() {
        super.onResume()
        refreshDiagnostics()
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun refreshDiagnostics() {
        diagnosticsText.text = runtime?.buildDiagnosticsReport() ?: "Receiver service starting..."
    }

    private fun copyDiagnostics() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Receiver diagnostics", diagnosticsText.text))
        Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    private fun restartDiscovery() {
        runtime?.refreshDiscovery()
        refreshDiagnostics()
        Toast.makeText(this, R.string.restart_discovery, Toast.LENGTH_SHORT).show()
    }

    private fun exportDiagnostics() {
        val report = diagnosticsText.text?.toString().orEmpty()
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val directory = File(getExternalFilesDir(null) ?: filesDir, "diagnostics").apply {
            mkdirs()
        }
        val reportFile = File(directory, "receiver-diagnostics-$timestamp.txt")
        reportFile.writeText(report)
        Toast.makeText(
            this,
            "${getString(R.string.diagnostics_saved)}: ${reportFile.absolutePath}",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun startReceiverForegroundService() {
        val intent = Intent(this, ReceiverForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
