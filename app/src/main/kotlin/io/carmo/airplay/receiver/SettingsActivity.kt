package io.carmo.airplay.receiver

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var settingsList: ListView
    private lateinit var adapter: SettingsAdapter
    private var runtime: ReceiverRuntime? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as ReceiverForegroundService.ReceiverBinder
            runtime = binder.runtime
            isBound = true
            rebuildRows()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            runtime = null
            isBound = false
            rebuildRows()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settingsList = findViewById(R.id.settings_list)
        adapter = SettingsAdapter(this)
        settingsList.adapter = adapter
        settingsList.setOnItemClickListener { _, _, position, _ ->
            handleRow(adapter.rows[position])
        }
        settingsList.post {
            settingsList.requestFocus()
            settingsList.setSelection(0)
        }

        startReceiverForegroundService()
        bindService(Intent(this, ReceiverForegroundService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        rebuildRows()
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

    private fun rebuildRows() {
        val prefs = ReceiverPreferences.prefs(this)
        val deviceName = ReceiverPreferences.customDeviceName(this)
            ?: runtime?.deviceDisplayName
            ?: getString(R.string.app_name)
        val overlayStatus = if (canDrawOverlays()) "permission granted" else "permission needed"
        val receiverId = ReceiverIdentity.receiverId(this)
        val ipAddress = runtime?.getLocalIpAddress() ?: "unknown"
        val rows = listOf(
            SettingsRow.Section("Receiver"),
            SettingsRow.Item("name", "Receiver name", "Edit the name shown in AirPlay", deviceName),
            SettingsRow.Item(
                "idle_clock",
                "Idle screen",
                "Clock screensaver while ready",
                if (ReceiverPreferences.idleClockEnabled(this)) "Clock" else "Static ready"
            ),
            SettingsRow.Item("idle_dim", "OLED dimming", "Dim the ready screen after idle time", ReceiverPreferences.idleDimSummary(this)),
            SettingsRow.Item("display", "Display behavior", "Screen behavior while receiving", ReceiverPreferences.wakeModeSummary(this)),
            SettingsRow.Item("after_disconnect", "After disconnect", "What the TV should show after sender disconnects", ReceiverPreferences.afterDisconnectSummary(this)),
            SettingsRow.Item(
                "boot",
                "Start on boot",
                "Start receiver service after TV boot",
                if (prefs.getBoolean(ReceiverPreferences.KEY_START_ON_BOOT, true)) "On" else "Off"
            ),
            SettingsRow.Section("Security"),
            SettingsRow.Item("security_mode", "Security mode", "Saved preference; AirPlay advertises compatible open mode", ReceiverPreferences.securityModeSummary(this)),
            SettingsRow.Item(
                "guest_mode",
                "Guest mode",
                "Allow a sender for this session without trusting it",
                if (ReceiverPreferences.guestMode(this)) "On" else "Off"
            ),
            SettingsRow.Item("trusted_devices", "Trusted devices", "Stored trusted sender list", "${SenderTrustStore.trustedDevices(this).size} saved"),
            SettingsRow.Item("blocked_devices", "Blocked devices", "Stored blocked sender list", "${SenderTrustStore.blockedDevices(this).size} saved"),
            SettingsRow.Item("takeover_protection", "Takeover protection", "Behavior preference for competing senders", ReceiverPreferences.takeoverProtectionSummary(this)),
            SettingsRow.Item(
                "takeover",
                "Bring receiver to front",
                overlayStatus,
                if (ReceiverPreferences.automaticVideoTakeover(this)) "On" else "Off"
            ),
            SettingsRow.Section("Display & Video"),
            SettingsRow.Item("quality", "Quality profile", "Resolution and buffering preference", ReceiverPreferences.qualityProfileSummary(this)),
            SettingsRow.Item("screen_fit", "Screen fit", "How mirrored video fills the TV", ReceiverPreferences.screenFitSummary(this)),
            SettingsRow.Item(
                "frame_rate",
                "Frame-rate matching",
                "Use platform frame-rate hints when available",
                if (ReceiverPreferences.frameRateMatching(this)) "On" else "Off"
            ),
            SettingsRow.Section("Audio"),
            SettingsRow.Item("audio_sync", "Audio sync", "Applied to AirPlay PCM playback", ReceiverPreferences.audioSyncSummary(this)),
            SettingsRow.Item("audio_only", "Audio-only screen", "Apple Music and other audio-only sessions", ReceiverPreferences.audioOnlyDisplaySummary(this)),
            SettingsRow.Item(
                "visualizer",
                "Spectrum visualizer",
                "Optional audio-only animation",
                if (ReceiverPreferences.visualizerEnabled(this)) "On" else "Off"
            ),
            SettingsRow.Section("Network"),
            SettingsRow.Item("connection_help", "Connection help", "iPhone, iPad, Mac, and network checks", ""),
            SettingsRow.Item("restart_discovery", "Restart discovery", "Re-advertise AirPlay and RAOP services", ""),
            SettingsRow.Item("restart_receiver", "Restart receiver", "Restart local AirPlay server runtime", ""),
            SettingsRow.Section("Accessibility"),
            SettingsRow.Item(
                "reduce_motion",
                "Reduce motion",
                "Disable visualizer and idle movement",
                if (ReceiverPreferences.reduceMotion(this)) "On" else "Off"
            ),
            SettingsRow.Section("Advanced"),
            SettingsRow.Item("diagnostics_level", "Diagnostics", "Logging detail", ReceiverPreferences.diagnosticsSummary(this)),
            SettingsRow.Item(
                "verbose_logging",
                "Verbose logging",
                "Detailed native RAOP logs after receiver restart",
                if (ReceiverPreferences.verboseLogging(this)) "On" else "Off"
            ),
            SettingsRow.Item("open_diagnostics", "Open diagnostics", "Receiver ID, state history, and session stats", ""),
            SettingsRow.Item("reset_identity", "Reset receiver identity", "Apple devices will see this as a new receiver", ""),
            SettingsRow.Section("About"),
            SettingsRow.Item("about", "About", "Version ${BuildConfig.VERSION_NAME} - ID $receiverId - IP $ipAddress", "")
        )
        adapter.rows = rows
        adapter.notifyDataSetChanged()
    }

    private fun handleRow(row: SettingsRow) {
        if (row !is SettingsRow.Item) return
        when (row.id) {
            "name" -> showNameDialog()
            "idle_clock" -> toggleBoolean(ReceiverPreferences.KEY_IDLE_CLOCK_ENABLED, true)
            "idle_dim" -> cycleIdleDimming()
            "boot" -> toggleBoolean(ReceiverPreferences.KEY_START_ON_BOOT, true)
            "quality" -> cycleValue(
                ReceiverPreferences.KEY_QUALITY_PROFILE,
                listOf(
                    ReceiverPreferences.QUALITY_AUTO,
                    ReceiverPreferences.QUALITY_LOW_LATENCY,
                    ReceiverPreferences.QUALITY_BALANCED,
                    ReceiverPreferences.QUALITY_BEST,
                    ReceiverPreferences.QUALITY_COMPATIBILITY,
                    ReceiverPreferences.QUALITY_AUDIO_STABLE
                ),
                ReceiverPreferences.QUALITY_AUTO
            ) {
                ReceiverPreferences.selectedVideoSize(this).let { runtime?.setVideoMode(it.width, it.height) }
            }
            "screen_fit" -> cycleValue(
                ReceiverPreferences.KEY_SCREEN_FIT,
                listOf(
                    ReceiverPreferences.SCREEN_FIT_FIT,
                    ReceiverPreferences.SCREEN_FIT_FILL,
                    ReceiverPreferences.SCREEN_FIT_STRETCH
                ),
                ReceiverPreferences.SCREEN_FIT_FIT
            )
            "audio_sync" -> cycleAudioSync()
            "security_mode" -> cycleValue(
                ReceiverPreferences.KEY_SECURITY_MODE,
                listOf(
                    ReceiverPreferences.SECURITY_PIN_NEW_DEVICES,
                    ReceiverPreferences.SECURITY_PIN_EVERY_SESSION,
                    ReceiverPreferences.SECURITY_TRUSTED_ONLY,
                    ReceiverPreferences.SECURITY_OPEN,
                ),
                ReceiverPreferences.SECURITY_PIN_NEW_DEVICES
            ) {
                runtime?.refreshDiscovery()
                if (ReceiverPreferences.securityMode(this) != ReceiverPreferences.SECURITY_OPEN) {
                    showNativePairingCompatibilityNotice()
                }
            }
            "guest_mode" -> toggleBoolean(ReceiverPreferences.KEY_GUEST_MODE, false)
            "trusted_devices" -> showDeviceList(
                title = "Trusted devices",
                devices = SenderTrustStore.trustedDevices(this),
                emptyMessage = "No trusted devices yet. Native pairing identifiers are needed before the app can populate this automatically.",
                removeLabel = "Forget",
                onRemove = { SenderTrustStore.forgetTrustedDevice(this, it) },
                onClear = { SenderTrustStore.clearTrustedDevices(this) }
            )
            "blocked_devices" -> showDeviceList(
                title = "Blocked devices",
                devices = SenderTrustStore.blockedDevices(this),
                emptyMessage = "No blocked devices yet. Blocking needs native sender identifiers before it can reject senders.",
                removeLabel = "Unblock",
                onRemove = { SenderTrustStore.unblockDevice(this, it) },
                onClear = { SenderTrustStore.clearBlockedDevices(this) }
            )
            "takeover_protection" -> cycleValue(
                ReceiverPreferences.KEY_TAKEOVER_PROTECTION,
                listOf(
                    ReceiverPreferences.TAKEOVER_REJECT,
                    ReceiverPreferences.TAKEOVER_ASK,
                    ReceiverPreferences.TAKEOVER_ALLOW
                ),
                ReceiverPreferences.TAKEOVER_REJECT
            )
            "display" -> cycleValue(
                ReceiverPreferences.KEY_WAKE_MODE,
                listOf(ReceiverPreferences.WAKE_MODE_DEFAULT, ReceiverPreferences.WAKE_MODE_ALWAYS, ReceiverPreferences.WAKE_MODE_ACTIVITY),
                ReceiverPreferences.WAKE_MODE_ACTIVITY
            )
            "after_disconnect" -> cycleValue(
                ReceiverPreferences.KEY_AFTER_DISCONNECT,
                listOf(ReceiverPreferences.AFTER_DISCONNECT_WAITING, ReceiverPreferences.AFTER_DISCONNECT_HOME),
                ReceiverPreferences.AFTER_DISCONNECT_WAITING
            )
            "takeover" -> toggleTakeover()
            "audio_only" -> cycleValue(
                ReceiverPreferences.KEY_AUDIO_ONLY_DISPLAY,
                listOf(
                    ReceiverPreferences.AUDIO_ONLY_VISUALIZER,
                    ReceiverPreferences.AUDIO_ONLY_STATUS,
                    ReceiverPreferences.AUDIO_ONLY_VISUALIZER_ONLY,
                    ReceiverPreferences.AUDIO_ONLY_BACKGROUND,
                    ReceiverPreferences.AUDIO_ONLY_MINIMAL
                ),
                ReceiverPreferences.AUDIO_ONLY_BACKGROUND
            )
            "visualizer" -> toggleBoolean(ReceiverPreferences.KEY_VISUALIZER_ENABLED, true)
            "frame_rate" -> toggleBoolean(ReceiverPreferences.KEY_FRAME_RATE_MATCHING, true)
            "reduce_motion" -> {
                toggleBoolean(ReceiverPreferences.KEY_REDUCE_MOTION, false)
                if (ReceiverPreferences.reduceMotion(this)) {
                    ReceiverPreferences.prefs(this).edit()
                        .putBoolean(ReceiverPreferences.KEY_VISUALIZER_ENABLED, false)
                        .apply()
                }
            }
            "connection_help" -> showConnectionHelp()
            "diagnostics_level" -> cycleValue(
                ReceiverPreferences.KEY_DIAGNOSTICS_LEVEL,
                listOf(ReceiverPreferences.DIAGNOSTICS_OFF, ReceiverPreferences.DIAGNOSTICS_BASIC),
                ReceiverPreferences.DIAGNOSTICS_OFF
            )
            "verbose_logging" -> toggleBoolean(ReceiverPreferences.KEY_VERBOSE_LOGGING, false)
            "open_diagnostics" -> startActivity(Intent(this, DiagnosticsActivity::class.java))
            "restart_discovery" -> {
                runtime?.refreshDiscovery()
                Toast.makeText(this, "Discovery restarted", Toast.LENGTH_SHORT).show()
            }
            "restart_receiver" -> restartReceiver()
            "reset_identity" -> confirmIdentityReset()
            "about" -> Unit
        }
        rebuildRows()
    }

    private fun showNameDialog() {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(ReceiverPreferences.customDeviceName(this@SettingsActivity) ?: runtime?.deviceDisplayName.orEmpty())
            setSelection(0, text.length)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Receiver name")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank()) {
                    runtime?.updateDeviceName(name)
                    rebuildRows()
                }
            }
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    private fun cycleAudioSync() {
        val prefs = ReceiverPreferences.prefs(this)
        val values = listOf(-500, -250, -100, 0, 100, 250, 500)
        val current = ReceiverPreferences.audioSyncMs(this)
        val nextIndex = (values.indexOf(current).takeIf { it >= 0 } ?: 2) + 1
        prefs.edit()
            .putInt(ReceiverPreferences.KEY_AUDIO_SYNC_MS, values[nextIndex % values.size])
            .apply()
        runtime?.setAudioSyncMs(values[nextIndex % values.size])
    }

    private fun cycleIdleDimming() {
        val values = listOf(0, 5, 10, 20, 30)
        val current = ReceiverPreferences.idleDimMinutes(this)
        val nextIndex = (values.indexOf(current).takeIf { it >= 0 } ?: 1) + 1
        ReceiverPreferences.prefs(this).edit()
            .putInt(ReceiverPreferences.KEY_IDLE_DIM_MINUTES, values[nextIndex % values.size])
            .apply()
    }

    private fun showConnectionHelp() {
        AlertDialog.Builder(this)
            .setTitle("Connect with AirPlay")
            .setMessage(
                "iPhone or iPad:\n" +
                    "1. Connect to the same Wi-Fi as this TV.\n" +
                    "2. Open Control Center.\n" +
                    "3. Choose Screen Mirroring, then select this TV name.\n\n" +
                    "Mac:\n" +
                    "1. Connect to the same network.\n" +
                    "2. Open Control Center or Displays.\n" +
                    "3. Choose Screen Mirroring or AirPlay, then select this TV.\n\n" +
                    "If this TV does not appear, restart discovery here and check that guest Wi-Fi, VPN, or router isolation is not blocking local devices."
            )
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showNativePairingCompatibilityNotice() {
        AlertDialog.Builder(this)
            .setTitle("Security compatibility")
            .setMessage(
                "This setting is saved and shown in the TV UI. The receiver still advertises the compatible no-PIN AirPlay path until native PIN verification and stable sender identifiers are available."
            )
            .setPositiveButton("Understood", null)
            .show()
    }

    private fun showDeviceList(
        title: String,
        devices: List<SenderTrustStore.SenderDevice>,
        emptyMessage: String,
        removeLabel: String,
        onRemove: (String) -> Unit,
        onClear: () -> Unit
    ) {
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(emptyMessage)
                .setPositiveButton("Done", null)
                .show()
            return
        }

        val labels = devices.map { device ->
            "${device.displayName}\n${device.id}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, which ->
                val device = devices[which]
                AlertDialog.Builder(this)
                    .setTitle("${removeLabel} ${device.displayName}?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton(removeLabel) { _, _ ->
                        onRemove(device.id)
                        rebuildRows()
                    }
                    .show()
            }
            .setNegativeButton("Done", null)
            .setPositiveButton("Clear all") { _, _ ->
                onClear()
                rebuildRows()
            }
            .show()
    }

    private fun toggleBoolean(key: String, defaultValue: Boolean) {
        val prefs = ReceiverPreferences.prefs(this)
        prefs.edit().putBoolean(key, !prefs.getBoolean(key, defaultValue)).apply()
    }

    private fun cycleValue(key: String, values: List<String>, defaultValue: String, afterSave: () -> Unit = {}) {
        val prefs = ReceiverPreferences.prefs(this)
        val current = prefs.getString(key, defaultValue) ?: defaultValue
        val nextIndex = (values.indexOf(current).takeIf { it >= 0 } ?: 0) + 1
        prefs.edit().putString(key, values[nextIndex % values.size]).apply()
        afterSave()
    }

    private fun toggleTakeover() {
        toggleBoolean(ReceiverPreferences.KEY_AUTO_VIDEO_TAKEOVER, true)
        if (ReceiverPreferences.automaticVideoTakeover(this) && !canDrawOverlays()) {
            AlertDialog.Builder(this)
                .setTitle("Overlay permission")
                .setMessage(getString(R.string.permission_overlay_explanation))
                .setNegativeButton("Later", null)
                .setPositiveButton("Open Settings") { _, _ -> openOverlaySettings() }
                .show()
        }
    }

    private fun restartReceiver() {
        val boundRuntime = runtime ?: return
        val videoSize = ReceiverPreferences.selectedVideoSize(this)
        boundRuntime.stop()
        boundRuntime.start(videoSize.width, videoSize.height, loadAudioVolume())
        Toast.makeText(this, "Receiver restarted", Toast.LENGTH_SHORT).show()
    }

    private fun confirmIdentityReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset receiver identity?")
            .setMessage(
                "This will generate a new receiver ID. Apple devices that have connected\n" +
                    "before will see this as a new receiver."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ ->
                ReceiverIdentity.reset(this)
                runtime?.refreshDiscovery()
                rebuildRows()
            }
            .show()
    }

    private fun loadAudioVolume(): Float {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (maxVolume <= 0) {
            1.0f
        } else {
            (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume).coerceIn(0.0f, 1.0f)
        }
    }

    private fun canDrawOverlays(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun startReceiverForegroundService() {
        val intent = Intent(this, ReceiverForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private sealed class SettingsRow {
        data class Item(
            val id: String,
            val title: String,
            val subtitle: String,
            val value: String
        ) : SettingsRow()

        data class Section(val title: String) : SettingsRow()
    }

    private class SettingsAdapter(private val context: Context) : BaseAdapter() {
        var rows: List<SettingsRow> = emptyList()

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): SettingsRow = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = if (rows[position] is SettingsRow.Section) 1 else 0
        override fun isEnabled(position: Int): Boolean = rows[position] is SettingsRow.Item

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val inflater = LayoutInflater.from(context)
            return when (val row = rows[position]) {
                is SettingsRow.Section -> {
                    val view = convertView ?: inflater.inflate(R.layout.settings_section_item, parent, false)
                    view.findViewById<TextView>(R.id.section_title).text = row.title
                    view
                }
                is SettingsRow.Item -> {
                    val view = convertView ?: inflater.inflate(R.layout.settings_list_item, parent, false)
                    view.findViewById<TextView>(R.id.setting_title).text = row.title
                    view.findViewById<TextView>(R.id.setting_subtitle).text = row.subtitle
                    view.findViewById<TextView>(R.id.setting_value).text = row.value
                    view
                }
            }
        }
    }
}
