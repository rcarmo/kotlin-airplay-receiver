package io.carmo.airplay.receiver

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.graphics.PixelFormat
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var runtime: ReceiverRuntime? = null
    private var isBound = false
    private var isSurfaceAvailable = false

    private lateinit var playbackSurface: SurfaceView
    private lateinit var readyOverlay: ComposeView
    private lateinit var waitingOverlay: View
    private lateinit var deviceNameLabel: TextView
    private lateinit var idleClockLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var discoveryStatusView: TextView
    private lateinit var versionLabel: TextView
    private lateinit var settingsButton: Button
    private lateinit var helpButton: Button
    private lateinit var audioOnlyOverlay: View
    private lateinit var audioOnlyCoverArt: ImageView
    private lateinit var audioOnlyTitle: TextView
    private lateinit var audioOnlySubtitle: TextView
    private lateinit var audioVolumeLabel: TextView
    private lateinit var audioVisualizer: SpectrumVisualizerView
    private lateinit var audioVolumeOverlay: View
    private lateinit var audioVolumeOverlayLabel: TextView
    private lateinit var audioVolumeOverlayBar: ProgressBar
    private lateinit var permissionExplanation: View
    private lateinit var permissionButton: Button
    private lateinit var streamOverlay: ComposeView
    private lateinit var quickSettingsOverlay: ComposeView
    private lateinit var streamInfoOverlay: View
    private lateinit var streamInfoOverlayText: TextView
    private lateinit var streamInfoStopButton: Button
    private lateinit var streamInfoFitButton: Button
    private lateinit var streamInfoDiagnosticsButton: Button
    private lateinit var streamInfoTrafficButton: Button
    private lateinit var trafficMonitor: TrafficMonitorView
    private lateinit var pairingOverlay: View
    private lateinit var pairingPinLabel: TextView
    private lateinit var audioManager: AudioManager
    private var audioDeviceCallback: AudioDeviceCallback? = null

    private var screenWakeLock: PowerManager.WakeLock? = null
    private var wakeNudgeLock: PowerManager.WakeLock? = null
    private var videoSize: ReceiverPreferences.VideoSize? = null
    private var wakeMode = ReceiverPreferences.WAKE_MODE_ACTIVITY
    private var audioVolume = DEFAULT_AUDIO_VOLUME
    @Volatile private var lastWakeNudgeAtMs = 0L
    @Volatile private var wakeNudgePending = false
    private var hasShownFirstRunDialog = false
    private var isStreaming = false
    private var streamVideoWidth = 0
    private var streamVideoHeight = 0
    private var receiverDeviceName = "Receiver"
    private var discoveryStatus = "Discovery starting"
    private var streamStatus = "Waiting"
    private var nowPlaying = AudioNowPlaying()
    private var detectedFrameRate = 60.0f
    private var readyUiState by mutableStateOf(ReadyUiState())
    private var streamOverlayUiState by mutableStateOf(StreamOverlayUiState())
    private var quickSettingsUiState by mutableStateOf(QuickSettingsUiState())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as ReceiverForegroundService.ReceiverBinder
            val boundRuntime = binder.runtime
            runtime = boundRuntime
            isBound = true
            registerRuntimeListeners(boundRuntime)
            receiverDeviceName = boundRuntime.deviceDisplayName
            discoveryStatus = boundRuntime.discoveryStatus
            streamStatus = boundRuntime.streamStatus
            boundRuntime.dismissVideoStartedNotification()
            syncRuntimeSettings(boundRuntime)
            attachSurfaceIfReady(boundRuntime)
            handleReceiverState(boundRuntime.state)
            checkOverlayPermission()
            maybeShowFirstRun()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            runtime?.let(::unregisterRuntimeListeners)
            runtime = null
            isBound = false
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            isSurfaceAvailable = true
            runtime?.attachSurface(holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (holder.surface.isValid) {
                isSurfaceAvailable = true
                runtime?.attachSurface(holder.surface)
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            isSurfaceAvailable = false
            runtime?.detachSurface()
        }
    }

    private val stateListener: (ReceiverState) -> Unit = { state ->
        runOnUiThread { handleReceiverState(state) }
    }

    private val discoveryStatusListener: (String) -> Unit = { status ->
        runOnUiThread {
            discoveryStatus = status
            if (!isStreaming && ::discoveryStatusView.isInitialized) {
                updateWaitingStatus()
            }
        }
    }

    private val streamStatusListener: (String) -> Unit = { status ->
        runOnUiThread { showStreamStatus(status) }
    }

    private val videoActivityListener: (Boolean) -> Unit = { hasVideoActivity ->
        handleVideoActivity(hasVideoActivity)
    }

    private val videoSizeListener: (Int, Int) -> Unit = { width, height ->
        runOnUiThread {
            streamVideoWidth = width
            streamVideoHeight = height
            updateSurfaceLayout(playbackSurface)
        }
    }

    private val frameRateListener: (Float) -> Unit = { frameRate ->
        runOnUiThread {
            detectedFrameRate = frameRate
            runtime?.let {
                streamOverlayUiState = buildStreamOverlayState(it)
                quickSettingsUiState = buildQuickSettingsState(it)
            }
        }
    }

    private val trafficListener: (Int) -> Unit = { byteCount ->
        runOnUiThread {
            trafficMonitor.recordTraffic(byteCount)
            if (::audioVisualizer.isInitialized) {
                audioVisualizer.recordSample(byteCount)
            }
        }
    }

    private val latencyListener: (Long) -> Unit = { latencyMs ->
        runOnUiThread { trafficMonitor.recordLatency(latencyMs) }
    }

    private val audioNowPlayingListener: (AudioNowPlaying) -> Unit = { current ->
        runOnUiThread {
            nowPlaying = current
            updateAudioOnlyMetadata()
        }
    }

    private val afterDisconnectListener: () -> Unit = {
        runOnUiThread { moveTaskToBack(true) }
    }

    private val hideAudioVolumeOverlay = Runnable {
        audioVolumeOverlay.visibility = View.GONE
    }

    private val hideStreamInfoOverlay = Runnable {
        if (::streamOverlay.isInitialized) {
            streamOverlay.visibility = View.GONE
        }
        streamInfoOverlay.visibility = View.GONE
    }

    private val hideQuickSettingsOverlay = Runnable {
        if (::quickSettingsOverlay.isInitialized) {
            quickSettingsOverlay.visibility = View.GONE
        }
        restoreReadyOverlayAfterQuickSettings()
    }

    private val dimIdleScreen = Runnable {
        if (!isStreaming && ::readyOverlay.isInitialized && readyOverlay.visibility == View.VISIBLE) {
            readyOverlay.alpha = IDLE_DIM_ALPHA
            versionLabel.alpha = IDLE_DIM_ALPHA
        }
    }

    private val clockFormatter = SimpleDateFormat("h:mm", Locale.US)
    private val updateIdleClock = object : Runnable {
        override fun run() {
            if (::readyOverlay.isInitialized && readyOverlay.visibility == View.VISIBLE) {
                val clockEnabled = ReceiverPreferences.idleClockEnabled(this@MainActivity)
                var offsetX = 0f
                var offsetY = 0f
                if (clockEnabled) {
                    if (!ReceiverPreferences.reduceMotion(this@MainActivity)) {
                        val tick = (SystemClock.elapsedRealtime() / CLOCK_SHIFT_INTERVAL_MS).toInt()
                        offsetX = ((tick % 5) - 2) * CLOCK_SHIFT_PX
                        offsetY = (((tick / 5) % 5) - 2) * CLOCK_SHIFT_PX
                    }
                }
                readyUiState = readyUiState.copy(
                    clockText = clockFormatter.format(Date()),
                    clockVisible = clockEnabled,
                    clockOffsetX = offsetX,
                    clockOffsetY = offsetY
                )
                readyOverlay.postDelayed(this, CLOCK_UPDATE_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        configurePlaybackWindow()

        playbackSurface = findViewById(R.id.surface)
        readyOverlay = findViewById(R.id.ready_overlay)
        waitingOverlay = findViewById(R.id.waiting_overlay)
        idleClockLabel = findViewById(R.id.idle_clock_label)
        deviceNameLabel = findViewById(R.id.device_name_label)
        statusLabel = findViewById(R.id.status_label)
        discoveryStatusView = findViewById(R.id.discovery_status)
        versionLabel = findViewById(R.id.version_label)
        settingsButton = findViewById(R.id.settings_button)
        helpButton = findViewById(R.id.help_button)
        audioOnlyOverlay = findViewById(R.id.audio_only_overlay)
        audioOnlyCoverArt = findViewById(R.id.audio_only_cover_art)
        audioOnlyTitle = findViewById(R.id.audio_only_title)
        audioOnlySubtitle = findViewById(R.id.audio_only_subtitle)
        audioVolumeLabel = findViewById(R.id.audio_volume_label)
        audioVisualizer = findViewById(R.id.audio_visualizer)
        audioVolumeOverlay = findViewById(R.id.audio_volume_overlay)
        audioVolumeOverlayLabel = findViewById(R.id.audio_volume_overlay_label)
        audioVolumeOverlayBar = findViewById(R.id.audio_volume_overlay_bar)
        permissionExplanation = findViewById(R.id.permission_explanation)
        permissionButton = findViewById(R.id.permission_button)
        streamOverlay = findViewById(R.id.stream_overlay)
        quickSettingsOverlay = findViewById(R.id.quick_settings_overlay)
        streamInfoOverlay = findViewById(R.id.stream_info_overlay)
        streamInfoOverlayText = findViewById(R.id.stream_info_text)
        streamInfoStopButton = findViewById(R.id.stream_info_stop_button)
        streamInfoFitButton = findViewById(R.id.stream_info_fit_button)
        streamInfoDiagnosticsButton = findViewById(R.id.stream_info_diagnostics_button)
        streamInfoTrafficButton = findViewById(R.id.stream_info_traffic_button)
        trafficMonitor = findViewById(R.id.traffic_monitor)
        pairingOverlay = findViewById(R.id.pairing_overlay)
        pairingPinLabel = findViewById(R.id.pairing_pin_label)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        configurePlaybackSurface(playbackSurface)
        configureReadyOverlay()
        configureQuickSettingsOverlay()
        configureStreamOverlay()
        configureControlLayer()
        configureRemoteActions()

        videoSize = ReceiverPreferences.selectedVideoSize(this)
        audioVolume = loadAudioVolume()
        wakeMode = ReceiverPreferences.wakeMode(this)
        keepSurfaceProportional(playbackSurface)
        updateAudioVolumeUi()
        checkOverlayPermission()
        updateWaitingStatus()

        startReceiverForegroundService()
        bindReceiverForegroundService()

        if (DEBUG_CODECS) {
            logSupportedCodecs()
        }
    }

    override fun onResume() {
        super.onResume()
        registerAudioRouteCallback()
        videoSize = ReceiverPreferences.selectedVideoSize(this)
        wakeMode = ReceiverPreferences.wakeMode(this)
        audioVolume = loadAudioVolume()
        updateAudioVolumeUi()
        runtime?.let {
            receiverDeviceName = it.deviceDisplayName
            it.dismissVideoStartedNotification()
            syncRuntimeSettings(it)
            if (it.state != ReceiverState.STOPPED) {
                applyWakeMode()
                it.refreshDiscovery()
            }
        }
        checkOverlayPermission()
        if (::readyOverlay.isInitialized && !isStreaming) {
            updateWaitingStatus()
            scheduleIdleDimming()
        }
    }

    override fun onPause() {
        unregisterAudioRouteCallback()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::readyOverlay.isInitialized) {
            ensureImmersiveFlags()
            if (runtime?.state != ReceiverState.STOPPED) {
                applyWakeMode()
            }
        }
    }

    override fun onDestroy() {
        runtime?.let { boundRuntime ->
            boundRuntime.detachSurface()
            unregisterRuntimeListeners(boundRuntime)
        }
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        allowScreenSaver()
        releaseWakeNudgeLock()
        unregisterAudioRouteCallback()
        stopIdleClock()
        cancelIdleDimming()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && handleRemoteKey(event.keyCode)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return handleRemoteKey(keyCode) || super.onKeyDown(keyCode, event)
    }

    private fun handleRemoteKey(keyCode: Int): Boolean {
        val readyVisible = ::readyOverlay.isInitialized && readyOverlay.visibility == View.VISIBLE
        if (::quickSettingsOverlay.isInitialized &&
            quickSettingsOverlay.visibility == View.VISIBLE &&
            !readyVisible
        ) {
            return handleQuickSettingsKey(keyCode)
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                adjustVolume(+1)
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                adjustVolume(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isReadyOverlayFocusTarget()) {
                    handleReadyOverlayKey(keyCode)
                } else if (isStreaming && streamOverlay.visibility == View.VISIBLE) {
                    handleStreamOverlayKey(keyCode)
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isStreaming && streamOverlay.visibility == View.VISIBLE) {
                    handleStreamOverlayKey(keyCode)
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (isReadyOverlayFocusTarget()) {
                    handleReadyOverlayKey(keyCode)
                } else if (!isStreaming) {
                    return false
                } else {
                    if (streamOverlay.visibility == View.VISIBLE) {
                        handleStreamOverlayKey(keyCode)
                    } else {
                        showStreamInfoOverlay()
                    }
                }
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                when {
                    streamOverlay.visibility == View.VISIBLE -> {
                        hideStreamOverlay()
                    }
                    quickSettingsOverlay.visibility == View.VISIBLE -> {
                        hideQuickSettings()
                    }
                    streamInfoOverlay.visibility == View.VISIBLE -> {
                        hideStreamOverlay()
                    }
                    isStreaming || audioOnlyOverlay.visibility == View.VISIBLE -> {
                        stopCurrentSessionAndReturnToReady()
                    }
                    else -> moveTaskToBack(true)
                }
                true
            }
            else -> false
        }
    }

    private fun registerRuntimeListeners(boundRuntime: ReceiverRuntime) {
        boundRuntime.addStateListener(stateListener)
        boundRuntime.addDiscoveryStatusListener(discoveryStatusListener)
        boundRuntime.addStreamStatusListener(streamStatusListener)
        boundRuntime.addVideoActivityListener(videoActivityListener)
        boundRuntime.addVideoSizeListener(videoSizeListener)
        boundRuntime.addFrameRateListener(frameRateListener)
        boundRuntime.addTrafficListener(trafficListener)
        boundRuntime.addLatencyListener(latencyListener)
        boundRuntime.addAudioNowPlayingListener(audioNowPlayingListener)
        boundRuntime.addAfterDisconnectListener(afterDisconnectListener)
    }

    private fun unregisterRuntimeListeners(boundRuntime: ReceiverRuntime) {
        boundRuntime.removeStateListener(stateListener)
        boundRuntime.removeDiscoveryStatusListener(discoveryStatusListener)
        boundRuntime.removeStreamStatusListener(streamStatusListener)
        boundRuntime.removeVideoActivityListener(videoActivityListener)
        boundRuntime.removeVideoSizeListener(videoSizeListener)
        boundRuntime.removeFrameRateListener(frameRateListener)
        boundRuntime.removeTrafficListener(trafficListener)
        boundRuntime.removeLatencyListener(latencyListener)
        boundRuntime.removeAudioNowPlayingListener(audioNowPlayingListener)
        boundRuntime.removeAfterDisconnectListener(afterDisconnectListener)
    }

    private fun syncRuntimeSettings(boundRuntime: ReceiverRuntime) {
        val selectedSize = ReceiverPreferences.selectedVideoSize(this)
        videoSize = selectedSize
        boundRuntime.setVideoMode(selectedSize.width, selectedSize.height)
        boundRuntime.setAudioVolume(audioVolume)
        boundRuntime.setAudioSyncMs(ReceiverPreferences.audioSyncMs(this))
    }

    private fun attachSurfaceIfReady(boundRuntime: ReceiverRuntime) {
        val surface = playbackSurface.holder.surface
        if (surface != null && surface.isValid) {
            isSurfaceAvailable = true
            boundRuntime.attachSurface(surface)
        }
    }

    private fun configurePlaybackWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        ensureImmersiveFlags()
    }

    @Suppress("DEPRECATION")
    private fun ensureImmersiveFlags() {
        val decor = window.decorView
        if (decor.systemUiVisibility != IMMERSIVE_FLAGS) {
            decor.systemUiVisibility = IMMERSIVE_FLAGS
        }
    }

    private fun configurePlaybackSurface(surfaceView: SurfaceView) {
        surfaceView.setZOrderOnTop(false)
        surfaceView.setZOrderMediaOverlay(false)
        surfaceView.holder.setFormat(PixelFormat.OPAQUE)
        surfaceView.holder.addCallback(surfaceCallback)
    }

    private fun configureControlLayer() {
        val elevation = CONTROL_OVERLAY_ELEVATION_DP * resources.displayMetrics.density
        readyOverlay.elevation = elevation
        waitingOverlay.elevation = elevation
        versionLabel.elevation = elevation
        audioOnlyOverlay.elevation = elevation
        audioVolumeOverlay.elevation = elevation
        streamOverlay.elevation = elevation
        quickSettingsOverlay.elevation = elevation
        streamInfoOverlay.elevation = elevation
        permissionExplanation.elevation = elevation
        trafficMonitor.elevation = elevation
    }

    private fun configureReadyOverlay() {
        readyOverlay.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        readyOverlay.setContent {
            ReadyOverlay(state = readyUiState)
        }
        readyOverlay.setOnKeyListener { _, keyCode, event ->
            event.action == KeyEvent.ACTION_DOWN &&
                !isStreaming &&
                handleReadyOverlayKey(keyCode)
        }
    }

    private fun handleReadyOverlayKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                moveReadySelection(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                moveReadySelection(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                activateReadyAction()
                true
            }
            else -> false
        }
    }

    private fun moveReadySelection(delta: Int) {
        val actions = ReadyAction.values()
        val currentIndex = actions.indexOf(readyUiState.selectedAction).takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + delta + actions.size) % actions.size
        readyUiState = readyUiState.copy(selectedAction = actions[nextIndex])
    }

    private fun activateReadyAction() {
        when (readyUiState.selectedAction) {
            ReadyAction.HELP -> showConnectionHelp()
            ReadyAction.QUICK -> showQuickSettingsOverlay()
            ReadyAction.SETTINGS -> openSettings()
        }
    }

    private fun configureQuickSettingsOverlay() {
        quickSettingsOverlay.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        quickSettingsOverlay.setContent {
            QuickSettingsOverlay(state = quickSettingsUiState)
        }
        quickSettingsOverlay.setOnKeyListener { _, keyCode, event ->
            event.action == KeyEvent.ACTION_DOWN &&
                quickSettingsOverlay.visibility == View.VISIBLE &&
                handleQuickSettingsKey(keyCode)
        }
    }

    private fun handleQuickSettingsKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                moveQuickSettingsSelection(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                moveQuickSettingsSelection(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                resetQuickSettingsTimer()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                activateQuickSettingsAction()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                hideQuickSettings()
                true
            }
            else -> false
        }
    }

    private fun configureStreamOverlay() {
        streamOverlay.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        streamOverlay.setContent {
            StreamOverlay(state = streamOverlayUiState)
        }
        streamOverlay.setOnKeyListener { _, keyCode, event ->
            event.action == KeyEvent.ACTION_DOWN &&
                isStreaming &&
                handleStreamOverlayKey(keyCode)
        }
    }

    private fun handleStreamOverlayKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                moveStreamOverlaySelection(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                moveStreamOverlaySelection(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                resetStreamOverlayTimer()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                activateStreamOverlayAction()
                true
            }
            else -> false
        }
    }

    private fun moveStreamOverlaySelection(delta: Int) {
        val actions = StreamAction.values()
        val currentIndex = actions.indexOf(streamOverlayUiState.selectedAction).takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + delta + actions.size) % actions.size
        streamOverlayUiState = streamOverlayUiState.copy(selectedAction = actions[nextIndex])
        resetStreamOverlayTimer()
    }

    private fun moveQuickSettingsSelection(delta: Int) {
        val actions = QuickSettingAction.values()
        val currentIndex = actions.indexOf(quickSettingsUiState.selectedAction).takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + delta + actions.size) % actions.size
        quickSettingsUiState = quickSettingsUiState.copy(selectedAction = actions[nextIndex])
        resetQuickSettingsTimer()
    }

    private fun activateQuickSettingsAction() {
        when (quickSettingsUiState.selectedAction) {
            QuickSettingAction.QUALITY -> cycleQualityProfile()
            QuickSettingAction.SCREEN_FIT -> cycleScreenFit()
            QuickSettingAction.AUDIO_SYNC -> cycleAudioSync()
            QuickSettingAction.SECURITY -> cycleSecurityMode()
            QuickSettingAction.RESTART_DISCOVERY -> runtime?.refreshDiscovery()
            QuickSettingAction.SETTINGS -> {
                hideQuickSettings()
                openSettings()
                return
            }
        }
        runtime?.let { quickSettingsUiState = buildQuickSettingsState(it) }
        resetQuickSettingsTimer()
    }

    private fun activateStreamOverlayAction() {
        when (streamOverlayUiState.selectedAction) {
            StreamAction.STOP -> stopCurrentSessionAndReturnToReady()
            StreamAction.SCREEN_FIT -> {
                cycleScreenFit()
                showStreamInfoOverlay()
            }
            StreamAction.AUDIO_SYNC -> {
                cycleAudioSync()
                showStreamInfoOverlay()
            }
            StreamAction.SETTINGS -> {
                hideStreamOverlay()
                openSettings()
            }
            StreamAction.DIAGNOSTICS -> {
                hideStreamOverlay()
                startActivity(Intent(this, DiagnosticsActivity::class.java))
            }
            StreamAction.TRAFFIC -> {
                toggleTrafficMonitor()
                showStreamInfoOverlay()
            }
        }
    }

    private fun configureRemoteActions() {
        readyOverlay.setOnClickListener { activateReadyAction() }
        readyOverlay.post { readyOverlay.requestFocus() }
        waitingOverlay.setOnClickListener { openSettings() }
        waitingOverlay.post { waitingOverlay.requestFocus() }
        settingsButton.setOnClickListener { openSettings() }
        helpButton.setOnClickListener { showConnectionHelp() }
        permissionButton.setOnClickListener { openOverlayPermissionSettings() }
        streamInfoStopButton.setOnClickListener {
            stopCurrentSessionAndReturnToReady()
        }
        streamInfoFitButton.setOnClickListener {
            cycleScreenFit()
            showStreamInfoOverlay()
        }
        streamInfoDiagnosticsButton.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        streamInfoTrafficButton.setOnClickListener {
            toggleTrafficMonitor()
            streamInfoOverlay.removeCallbacks(hideStreamInfoOverlay)
        }
        trafficMonitor.setOnClickListener { trafficMonitor.visibility = View.GONE }
    }

    private fun applyWakeMode() {
        when (wakeMode) {
            ReceiverPreferences.WAKE_MODE_DEFAULT -> {
                allowScreenSaver()
                releaseWakeNudgeLock()
            }
            ReceiverPreferences.WAKE_MODE_ALWAYS -> {
                releaseWakeNudgeLock()
                keepReceiverAwake()
            }
            else -> {
                setKeepScreenOn(false)
                releaseWakeLock()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun keepReceiverAwake() {
        setKeepScreenOn(true)
        val wakeLock = screenWakeLock ?: run {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "$packageName:$WAKE_LOCK_TAG"
            ).apply {
                setReferenceCounted(false)
                screenWakeLock = this
            }
        }

        if (!wakeLock.isHeld) {
            wakeLock.acquire()
        }
    }

    private fun allowScreenSaver() {
        setKeepScreenOn(false)
        releaseWakeLock()
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        window.decorView.keepScreenOn = enabled
        if (::playbackSurface.isInitialized) {
            playbackSurface.keepScreenOn = enabled
        }
    }

    private fun releaseWakeLock() {
        val wakeLock = screenWakeLock
        if (wakeLock?.isHeld == true) {
            wakeLock.release()
        }
    }

    private fun releaseWakeNudgeLock() {
        val wakeLock = wakeNudgeLock
        if (wakeLock?.isHeld == true) {
            wakeLock.release()
        }
    }

    @Suppress("DEPRECATION")
    private fun nudgeDisplayAwake() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeNudgeAtMs < WAKE_NUDGE_THROTTLE_MS) {
            return
        }
        lastWakeNudgeAtMs = now
        if (!hasWindowFocus()) {
            bringReceiverToFront()
        }

        val wakeLock = wakeNudgeLock ?: run {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "$packageName:$WAKE_NUDGE_LOCK_TAG"
            ).apply {
                setReferenceCounted(false)
                wakeNudgeLock = this
            }
        }
        wakeLock.acquire(WAKE_NUDGE_DURATION_MS)
    }

    private fun bringReceiverToFront() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
    }

    private fun keepSurfaceProportional(surfaceView: SurfaceView) {
        fun updateSurfaceLayout() {
            updateSurfaceLayout(surfaceView)
        }

        surfaceView.post {
            val parent = surfaceView.parent as? View ?: return@post
            parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSurfaceLayout() }
            updateSurfaceLayout()
        }
    }

    private fun updateSurfaceLayout(surfaceView: SurfaceView) {
        val parent = surfaceView.parent as? View ?: return
        val parentWidth = parent.width
        val parentHeight = parent.height
        if (parentWidth == 0 || parentHeight == 0) {
            return
        }

        val selectedSize = videoSize ?: ReceiverPreferences.selectedVideoSize(this)
        val sourceWidth = if (streamVideoWidth > 0) streamVideoWidth else selectedSize.width
        val sourceHeight = if (streamVideoHeight > 0) streamVideoHeight else selectedSize.height
        val fitMode = ReceiverPreferences.screenFit(this)
        if (fitMode == ReceiverPreferences.SCREEN_FIT_STRETCH) {
            val currentParams = surfaceView.layoutParams
            if (currentParams.width != parentWidth || currentParams.height != parentHeight) {
                surfaceView.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
            return
        }

        var width = parentWidth
        var height = width * sourceHeight / sourceWidth
        val shouldCrop = fitMode == ReceiverPreferences.SCREEN_FIT_FILL
        if ((!shouldCrop && height > parentHeight) || (shouldCrop && height < parentHeight)) {
            height = parentHeight
            width = height * sourceWidth / sourceHeight
        }

        val currentParams = surfaceView.layoutParams
        if (currentParams.width != width || currentParams.height != height) {
            surfaceView.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
        }
    }

    private fun handleReceiverState(state: ReceiverState) {
        if (::pairingOverlay.isInitialized && state != ReceiverState.PAIRING) {
            pairingOverlay.visibility = View.GONE
        }
        when (state) {
            ReceiverState.IDLE_ADVERTISING -> {
                showWaitingStatus()
                applyWakeMode()
            }
            ReceiverState.PAIRING -> showStreamStatus("Pairing")
            ReceiverState.VIDEO_ACTIVE -> hideStatus()
            ReceiverState.AUDIO_ACTIVE -> showAudioOnlyStatus()
            ReceiverState.VIDEO_STALLED -> showStreamStatus(getString(R.string.status_recovering))
            ReceiverState.FIRST_RUN,
            ReceiverState.STARTING,
            ReceiverState.STOPPED,
            ReceiverState.ERROR_RECOVERABLE -> showWaitingStatus()
            else -> Unit
        }
    }

    private fun showWaitingStatus() {
        isStreaming = false
        streamVideoWidth = 0
        streamVideoHeight = 0
        if (streamStatus.isBlank() || streamStatus == getString(R.string.status_streaming)) {
            streamStatus = "Waiting"
        }
        updateSurfaceLayout(playbackSurface)
        audioOnlyOverlay.visibility = View.GONE
        pairingOverlay.visibility = View.GONE
        audioVisualizer.configure(enabled = false, reduceMotion = true)
        audioVolumeOverlay.visibility = View.GONE
        streamOverlay.visibility = View.GONE
        quickSettingsOverlay.visibility = View.GONE
        streamInfoOverlay.visibility = View.GONE
        pairingOverlay.visibility = View.GONE
        waitingOverlay.visibility = View.GONE
        readyOverlay.visibility = View.VISIBLE
        versionLabel.visibility = View.VISIBLE
        readyOverlay.alpha = 1.0f
        versionLabel.alpha = 1.0f
        updateWaitingStatus()
        startIdleClock()
        scheduleIdleDimming()
        showControl(readyOverlay)
        if (!checkOverlayPermission()) {
            readyOverlay.requestFocus()
        }
    }

    private fun isReadyOverlayFocusTarget(): Boolean {
        return (::readyOverlay.isInitialized && readyOverlay.visibility == View.VISIBLE) ||
            (::waitingOverlay.isInitialized && waitingOverlay.visibility == View.VISIBLE) ||
            currentFocus == null ||
            currentFocus == readyOverlay ||
            currentFocus == waitingOverlay ||
            readyOverlay.hasFocus() ||
            waitingOverlay.hasFocus()
    }

    private fun showAudioOnlyStatus() {
        isStreaming = true
        streamStatus = getString(R.string.status_audio_only)
        readyOverlay.visibility = View.GONE
        hideQuickSettings()
        waitingOverlay.visibility = View.GONE
        stopIdleClock()
        cancelIdleDimming()
        versionLabel.visibility = View.GONE
        audioOnlyOverlay.visibility = View.VISIBLE
        audioVolumeOverlay.visibility = View.GONE
        permissionExplanation.visibility = View.GONE
        streamOverlay.visibility = View.GONE
        streamInfoOverlay.visibility = View.GONE
        configureAudioOnlyStyle()
        updateAudioOnlyMetadata()
        checkOverlayPermission()
        showControl(audioOnlyOverlay)
        audioOnlyOverlay.requestFocus()
    }

    private fun updateWaitingStatus() {
        versionLabel.text = "v${BuildConfig.VERSION_NAME}"
        receiverDeviceName = runtime?.deviceDisplayName ?: receiverDeviceName
        deviceNameLabel.text = receiverDeviceName
        val quality = ReceiverPreferences.qualityProfileSummary(this)
        val isAudioOnly = streamStatus == getString(R.string.status_audio_only)
        val status = if (isAudioOnly) {
            getString(R.string.status_audio_playing)
        } else {
            getString(R.string.status_waiting)
        }
        val connectionHint = if (isAudioOnly) {
            "Audio: ${currentAudioRouteSummary()}"
        } else {
            getString(R.string.connection_hint)
        }
        val qualitySummary = if (isAudioOnly) "" else "Quality: $quality"
        val securitySummary = "Security: ${ReceiverPreferences.securityModeSummary(this)}"
        statusLabel.text = if (qualitySummary.isBlank()) {
            "$status\n$connectionHint"
        } else {
            "$status\n$connectionHint\n$qualitySummary"
        }
        discoveryStatusView.text = securitySummary
        readyUiState = readyUiState.copy(
            title = getString(R.string.startup_title),
            deviceName = receiverDeviceName,
            status = status,
            connectionHint = connectionHint,
            qualitySummary = qualitySummary,
            securitySummary = securitySummary,
            settingsHint = getString(R.string.settings_hint),
            settingsButtonText = getString(R.string.settings_button),
            quickButtonText = getString(R.string.quick_settings_button),
            helpButtonText = getString(R.string.help_button)
        )
        updateAudioVolumeUi()
    }

    private fun hideStatus() {
        runOnUiThread {
            isStreaming = true
            streamStatus = getString(R.string.status_streaming)
            readyOverlay.visibility = View.GONE
            hideQuickSettings()
            waitingOverlay.visibility = View.GONE
            stopIdleClock()
            cancelIdleDimming()
            audioOnlyOverlay.visibility = View.GONE
            audioVisualizer.configure(enabled = false, reduceMotion = true)
            pairingOverlay.visibility = View.GONE
            permissionExplanation.visibility = View.GONE
            streamOverlay.visibility = View.GONE
        }
    }

    private fun showStreamStatus(status: String) {
        streamStatus = status
        if (runtime?.state == ReceiverState.PAIRING) {
            pairingPinLabel.text = runtime?.pairingPin ?: "----"
            pairingOverlay.visibility = View.VISIBLE
            showControl(pairingOverlay)
            pairingOverlay.requestFocus()
        } else if (::pairingOverlay.isInitialized) {
            pairingOverlay.visibility = View.GONE
        }
        if (::statusLabel.isInitialized && !isStreaming) {
            updateWaitingStatus()
        }
    }

    private fun startIdleClock() {
        if (::readyOverlay.isInitialized) {
            readyOverlay.removeCallbacks(updateIdleClock)
            readyOverlay.post(updateIdleClock)
        }
    }

    private fun stopIdleClock() {
        if (::readyOverlay.isInitialized) {
            readyOverlay.removeCallbacks(updateIdleClock)
            readyUiState = readyUiState.copy(clockVisible = false)
        }
        if (::idleClockLabel.isInitialized) {
            idleClockLabel.removeCallbacks(updateIdleClock)
            idleClockLabel.visibility = View.GONE
        }
    }

    private fun scheduleIdleDimming() {
        if (!::readyOverlay.isInitialized) return
        readyOverlay.removeCallbacks(dimIdleScreen)
        val minutes = ReceiverPreferences.idleDimMinutes(this)
        if (minutes <= 0) {
            readyOverlay.alpha = 1.0f
            versionLabel.alpha = 1.0f
            return
        }
        readyOverlay.postDelayed(dimIdleScreen, minutes * 60_000L)
    }

    private fun cancelIdleDimming() {
        if (!::readyOverlay.isInitialized) return
        readyOverlay.removeCallbacks(dimIdleScreen)
        readyOverlay.alpha = 1.0f
        if (::versionLabel.isInitialized) {
            versionLabel.alpha = 1.0f
        }
    }

    private fun checkOverlayPermission(): Boolean {
        if (!::permissionExplanation.isInitialized) return false
        val shouldShow = !isStreaming &&
            ReceiverPreferences.automaticVideoTakeover(this) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        permissionExplanation.visibility = if (shouldShow) View.VISIBLE else View.GONE
        if (shouldShow) {
            showControl(permissionExplanation)
            if (::permissionButton.isInitialized) {
                permissionButton.requestFocus()
            }
        }
        return shouldShow
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun maybeShowFirstRun() {
        if (hasShownFirstRunDialog || ReceiverPreferences.firstRunComplete(this)) {
            return
        }
        hasShownFirstRunDialog = true
        ReceiverPreferences.prefs(this).edit()
            .putBoolean(ReceiverPreferences.KEY_FIRST_RUN_COMPLETE, true)
            .apply()
        startActivity(Intent(this, OnboardingActivity::class.java))
    }

    private fun loadAudioVolume(): Float {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) {
            return DEFAULT_AUDIO_VOLUME
        }
        return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume)
            .coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
    }

    private fun setAudioVolume(volume: Float, persist: Boolean) {
        audioVolume = volume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
        audioVolume = applySystemAudioVolume(audioVolume, showUi = false)
        if (persist) {
            audioVolume = loadAudioVolume()
        }
        runtime?.setAudioVolume(audioVolume)
        updateAudioVolumeUi()
        if (isStreaming || audioOnlyOverlay.visibility == View.VISIBLE) {
            showAudioVolumeOverlay()
        }
    }

    private fun applySystemAudioVolume(volume: Float, showUi: Boolean): Float {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) {
            return volume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
        }
        val volumeIndex = (volume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME) * maxVolume)
            .roundToInt()
            .coerceIn(0, maxVolume)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            volumeIndex,
            if (showUi) AudioManager.FLAG_SHOW_UI else 0
        )
        return volumeIndex.toFloat() / maxVolume
    }

    private fun adjustVolume(direction: Int) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        audioVolume = loadAudioVolume()
        runtime?.setAudioVolume(audioVolume)
        updateAudioVolumeUi()
        if (isStreaming || audioOnlyOverlay.visibility == View.VISIBLE) {
            showAudioVolumeOverlay()
        }
    }

    private fun updateAudioVolumeUi() {
        if (::audioManager.isInitialized) {
            audioVolume = loadAudioVolume()
        }
        val progress = (audioVolume * 100).toInt()
        val label = "Volume $progress%"
        if (::audioVolumeLabel.isInitialized) {
            audioVolumeLabel.text = label
        }
        if (::audioVolumeOverlayLabel.isInitialized) {
            audioVolumeOverlayLabel.text = label
            audioVolumeOverlayBar.progress = progress
        }
    }

    private fun showAudioVolumeOverlay() {
        audioVolumeOverlay.visibility = View.VISIBLE
        showControl(audioVolumeOverlay)
        audioVolumeOverlay.removeCallbacks(hideAudioVolumeOverlay)
        audioVolumeOverlay.postDelayed(hideAudioVolumeOverlay, VOLUME_OVERLAY_DURATION_MS)
    }

    private fun showStreamInfoOverlay() {
        val boundRuntime = runtime ?: return
        streamOverlayUiState = buildStreamOverlayState(boundRuntime)
        streamInfoOverlay.visibility = View.GONE
        streamOverlay.visibility = View.VISIBLE
        showControl(streamOverlay)
        streamOverlay.requestFocus()
        resetStreamOverlayTimer()
    }

    private fun showQuickSettingsOverlay() {
        quickSettingsUiState = buildQuickSettingsState(runtime)
        if (!isStreaming && ::readyOverlay.isInitialized) {
            readyOverlay.visibility = View.GONE
        }
        quickSettingsOverlay.visibility = View.VISIBLE
        quickSettingsOverlay.alpha = 1.0f
        showControl(quickSettingsOverlay)
        quickSettingsOverlay.requestFocus()
        resetQuickSettingsTimer()
    }

    private fun buildQuickSettingsState(boundRuntime: ReceiverRuntime?): QuickSettingsUiState {
        return QuickSettingsUiState(
            receiverName = boundRuntime?.deviceDisplayName ?: receiverDeviceName,
            quality = ReceiverPreferences.qualityProfileSummary(this),
            screenFit = ReceiverPreferences.screenFitSummary(this),
            audioSync = ReceiverPreferences.audioSyncSummary(this),
            audioRoute = currentAudioRouteSummary(),
            security = ReceiverPreferences.securityModeSummary(this),
            selectedAction = quickSettingsUiState.selectedAction
        )
    }

    private fun buildStreamOverlayState(boundRuntime: ReceiverRuntime): StreamOverlayUiState {
        val selectedSize = videoSize ?: ReceiverPreferences.selectedVideoSize(this)
        val resolution = if (streamVideoWidth > 0 && streamVideoHeight > 0) {
            "${streamVideoWidth}x${streamVideoHeight}"
        } else {
            "${selectedSize.width}x${selectedSize.height}"
        }
        val status = if (audioOnlyOverlay.visibility == View.VISIBLE ||
            boundRuntime.state == ReceiverState.AUDIO_ACTIVE
        ) {
            getString(R.string.stream_overlay_status_audio)
        } else {
            getString(R.string.stream_overlay_status_video)
        }
        return StreamOverlayUiState(
            receiverName = boundRuntime.deviceDisplayName,
            status = status,
            resolution = getString(R.string.stream_overlay_resolution, resolution),
            quality = getString(
                R.string.stream_overlay_quality,
                ReceiverPreferences.qualityProfileSummary(this)
            ),
            screenFit = getString(
                R.string.stream_overlay_screen_fit,
                ReceiverPreferences.screenFitSummary(this)
            ),
            frameRate = getString(R.string.stream_overlay_frame_rate, "%.1f".format(Locale.US, detectedFrameRate)),
            audioRoute = getString(R.string.stream_overlay_audio_route, currentAudioRouteSummary()),
            audioSync = getString(
                R.string.stream_overlay_audio_sync,
                ReceiverPreferences.audioSyncSummary(this)
            ),
            trafficVisible = ::trafficMonitor.isInitialized && trafficMonitor.visibility == View.VISIBLE,
            selectedAction = streamOverlayUiState.selectedAction
        )
    }

    private fun resetStreamOverlayTimer() {
        if (!::streamOverlay.isInitialized) return
        streamOverlay.removeCallbacks(hideStreamInfoOverlay)
        streamOverlay.postDelayed(hideStreamInfoOverlay, STREAM_INFO_DURATION_MS)
    }

    private fun resetQuickSettingsTimer() {
        if (!::quickSettingsOverlay.isInitialized) return
        quickSettingsOverlay.removeCallbacks(hideQuickSettingsOverlay)
        quickSettingsOverlay.postDelayed(hideQuickSettingsOverlay, QUICK_SETTINGS_DURATION_MS)
    }

    private fun hideStreamOverlay() {
        if (::streamOverlay.isInitialized) {
            streamOverlay.removeCallbacks(hideStreamInfoOverlay)
            streamOverlay.visibility = View.GONE
        }
        if (::streamInfoOverlay.isInitialized) {
            streamInfoOverlay.removeCallbacks(hideStreamInfoOverlay)
            streamInfoOverlay.visibility = View.GONE
        }
    }

    private fun hideQuickSettings() {
        if (::quickSettingsOverlay.isInitialized) {
            quickSettingsOverlay.removeCallbacks(hideQuickSettingsOverlay)
            quickSettingsOverlay.visibility = View.GONE
        }
        restoreReadyOverlayAfterQuickSettings()
    }

    private fun restoreReadyOverlayAfterQuickSettings() {
        if (!isStreaming && ::readyOverlay.isInitialized && readyOverlay.visibility != View.VISIBLE) {
            readyOverlay.visibility = View.VISIBLE
            showControl(readyOverlay)
            readyOverlay.requestFocus()
        }
    }

    private fun toggleTrafficMonitor() {
        if (!::trafficMonitor.isInitialized) return
        trafficMonitor.visibility = if (trafficMonitor.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (trafficMonitor.visibility == View.VISIBLE) {
            showControl(trafficMonitor)
            trafficMonitor.requestFocus()
        }
        runtime?.let {
            streamOverlayUiState = buildStreamOverlayState(it)
        }
    }

    private fun configureAudioOnlyStyle() {
        val display = ReceiverPreferences.audioOnlyDisplay(this)
        val reduceMotion = ReceiverPreferences.reduceMotion(this)
        val visualizerEnabled = ReceiverPreferences.visualizerEnabled(this) &&
            display != ReceiverPreferences.AUDIO_ONLY_STATUS &&
            display != ReceiverPreferences.AUDIO_ONLY_MINIMAL
        audioVisualizer.configure(
            enabled = visualizerEnabled,
            reduceMotion = reduceMotion
        )
        val showMetadata = display != ReceiverPreferences.AUDIO_ONLY_VISUALIZER_ONLY &&
            display != ReceiverPreferences.AUDIO_ONLY_MINIMAL
        audioOnlyTitle.visibility = if (display == ReceiverPreferences.AUDIO_ONLY_VISUALIZER_ONLY) {
            View.GONE
        } else {
            View.VISIBLE
        }
        audioOnlySubtitle.visibility = if (showMetadata) View.VISIBLE else View.GONE
        audioOnlyCoverArt.visibility = if (showMetadata && nowPlaying.coverArt != null) View.VISIBLE else View.GONE
        audioVolumeLabel.visibility = if (display == ReceiverPreferences.AUDIO_ONLY_MINIMAL) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun updateAudioOnlyMetadata() {
        if (!::audioOnlyTitle.isInitialized || !::audioOnlySubtitle.isInitialized) return
        val metadata = nowPlaying.metadata
        val display = ReceiverPreferences.audioOnlyDisplay(this)
        val route = currentAudioRouteSummary()
        if (display == ReceiverPreferences.AUDIO_ONLY_BACKGROUND) {
            audioOnlyTitle.text = clockFormatter.format(Date())
        } else {
            audioOnlyTitle.text = metadata.title ?: getString(R.string.status_audio_playing)
        }
        val details = mutableListOf<String>()
        listOf(metadata.artist, metadata.album)
            .filterNot { it.isNullOrBlank() }
            .joinToString(" - ")
            .takeIf { it.isNotBlank() }
            ?.let { details.add(it) }
        details.add(metadata.senderName ?: runtime?.deviceDisplayName ?: receiverDeviceName)
        details.add("Audio route: $route")
        if (route == "Bluetooth") {
            details.add("Bluetooth latency may need audio sync")
        }
        audioOnlySubtitle.text = details.joinToString("\n")
        val art = nowPlaying.coverArt
        if (art != null) {
            audioOnlyCoverArt.setImageBitmap(art)
        } else {
            audioOnlyCoverArt.setImageDrawable(null)
        }
        configureAudioOnlyStyle()
    }

    private fun handleVideoActivity(hasVideoActivity: Boolean) {
        if (wakeMode != ReceiverPreferences.WAKE_MODE_ACTIVITY || !hasVideoActivity) {
            return
        }
        if (SystemClock.elapsedRealtime() - lastWakeNudgeAtMs < WAKE_NUDGE_THROTTLE_MS) {
            return
        }
        if (wakeNudgePending) {
            return
        }
        wakeNudgePending = true
        runOnUiThread {
            try {
                if (wakeMode == ReceiverPreferences.WAKE_MODE_ACTIVITY) {
                    nudgeDisplayAwake()
                }
            } finally {
                wakeNudgePending = false
            }
        }
    }

    private fun logSupportedCodecs() {
        val mediaCodecList = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
        for (mediaCodecInfo in mediaCodecList.codecInfos) {
            if (mediaCodecInfo.isEncoder) {
                continue
            }
            Log.d(TAG, "supported codec = ${mediaCodecInfo.supportedTypes.joinToString()}")
        }
    }

    private fun showControl(control: View) {
        control.bringToFront()
        control.translationZ = CONTROL_OVERLAY_ELEVATION_DP * resources.displayMetrics.density
        control.invalidate()
    }

    private fun startReceiverForegroundService() {
        val intent = Intent(this, ReceiverForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun bindReceiverForegroundService() {
        val intent = Intent(this, ReceiverForegroundService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun cycleScreenFit() {
        val values = listOf(
            ReceiverPreferences.SCREEN_FIT_FIT,
            ReceiverPreferences.SCREEN_FIT_FILL,
            ReceiverPreferences.SCREEN_FIT_STRETCH
        )
        val current = ReceiverPreferences.screenFit(this)
        val nextIndex = (values.indexOf(current).takeIf { it >= 0 } ?: 0) + 1
        ReceiverPreferences.prefs(this).edit()
            .putString(ReceiverPreferences.KEY_SCREEN_FIT, values[nextIndex % values.size])
            .apply()
        updateSurfaceLayout(playbackSurface)
    }

    private fun cycleQualityProfile() {
        val values = listOf(
            ReceiverPreferences.QUALITY_AUTO,
            ReceiverPreferences.QUALITY_LOW_LATENCY,
            ReceiverPreferences.QUALITY_BALANCED,
            ReceiverPreferences.QUALITY_BEST,
            ReceiverPreferences.QUALITY_COMPATIBILITY,
            ReceiverPreferences.QUALITY_AUDIO_STABLE
        )
        val current = ReceiverPreferences.qualityProfile(this)
        val nextIndex = (values.indexOf(current).takeIf { it >= 0 } ?: 0) + 1
        ReceiverPreferences.prefs(this).edit()
            .putString(ReceiverPreferences.KEY_QUALITY_PROFILE, values[nextIndex % values.size])
            .apply()
        val selectedSize = ReceiverPreferences.selectedVideoSize(this)
        videoSize = selectedSize
        runtime?.setVideoMode(selectedSize.width, selectedSize.height)
        updateWaitingStatus()
    }

    private fun cycleAudioSync() {
        val values = listOf(-500, -250, -100, 0, 100, 250, 500)
        val current = ReceiverPreferences.audioSyncMs(this)
        val nextIndex = (values.indexOf(current).takeIf { it >= 0 } ?: 2) + 1
        val next = values[nextIndex % values.size]
        ReceiverPreferences.prefs(this).edit()
            .putInt(ReceiverPreferences.KEY_AUDIO_SYNC_MS, next)
            .apply()
        runtime?.setAudioSyncMs(next)
    }

    private fun cycleSecurityMode() {
        val values = listOf(
            ReceiverPreferences.SECURITY_PIN_NEW_DEVICES,
            ReceiverPreferences.SECURITY_PIN_EVERY_SESSION,
            ReceiverPreferences.SECURITY_TRUSTED_ONLY,
            ReceiverPreferences.SECURITY_OPEN
        )
        val current = ReceiverPreferences.securityMode(this)
        val nextIndex = (values.indexOf(current).takeIf { it >= 0 } ?: 0) + 1
        ReceiverPreferences.prefs(this).edit()
            .putString(ReceiverPreferences.KEY_SECURITY_MODE, values[nextIndex % values.size])
            .apply()
        runtime?.refreshDiscovery()
        updateWaitingStatus()
    }

    private fun stopCurrentSessionAndReturnToReady() {
        val boundRuntime = runtime ?: return
        val selectedSize = ReceiverPreferences.selectedVideoSize(this)
        boundRuntime.stop()
        boundRuntime.start(selectedSize.width, selectedSize.height, loadAudioVolume())
        attachSurfaceIfReady(boundRuntime)
        streamInfoOverlay.visibility = View.GONE
        trafficMonitor.visibility = View.GONE
        showWaitingStatus()
    }

    private fun showConnectionHelp() {
        AlertDialog.Builder(this)
            .setTitle("Connect with AirPlay")
            .setMessage(
                "iPhone or iPad:\n" +
                    "1. Connect to the same Wi-Fi as this TV.\n" +
                    "2. Open Control Center.\n" +
                    "3. Choose Screen Mirroring, then select $receiverDeviceName.\n\n" +
                    "Mac:\n" +
                    "1. Connect to the same network.\n" +
                    "2. Open Control Center or Displays.\n" +
                    "3. Choose Screen Mirroring or AirPlay, then select $receiverDeviceName.\n\n" +
                    "If this TV does not appear, open Settings and restart discovery. Guest Wi-Fi, VPNs, and router isolation can block discovery."
            )
            .setPositiveButton("Done", null)
            .show()
    }

    private fun currentAudioRouteSummary(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "TV speakers"
        }
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val preferred = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } ?: outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_HDMI ||
                it.type == AudioDeviceInfo.TYPE_HDMI_ARC
        } ?: outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        return when (preferred?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI / ARC"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TV speakers"
            else -> "System output"
        }
    }

    private fun registerAudioRouteCallback() {
        if (audioDeviceCallback != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                refreshAudioRouteUi()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                refreshAudioRouteUi()
            }
        }
        audioDeviceCallback = callback
        audioManager.registerAudioDeviceCallback(callback, null)
    }

    private fun unregisterAudioRouteCallback() {
        val callback = audioDeviceCallback ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.unregisterAudioDeviceCallback(callback)
        }
        audioDeviceCallback = null
    }

    private fun refreshAudioRouteUi() {
        runOnUiThread {
            if (audioOnlyOverlay.visibility == View.VISIBLE) {
                updateAudioOnlyMetadata()
            }
            if (streamInfoOverlay.visibility == View.VISIBLE) {
                showStreamInfoOverlay()
            }
            if (::streamOverlay.isInitialized && streamOverlay.visibility == View.VISIBLE) {
                runtime?.let {
                    streamOverlayUiState = buildStreamOverlayState(it)
                }
            }
            if (::quickSettingsOverlay.isInitialized && quickSettingsOverlay.visibility == View.VISIBLE) {
                runtime?.let {
                    quickSettingsUiState = buildQuickSettingsState(it)
                }
            }
        }
    }

    companion object {
        private const val TAG = "Receiver"
        private const val WAKE_LOCK_TAG = "ReceiverActive"
        private const val WAKE_NUDGE_LOCK_TAG = "ReceiverActivity"
        private const val WAKE_NUDGE_DURATION_MS = 10_000L
        private const val WAKE_NUDGE_THROTTLE_MS = 5_000L
        private const val CONTROL_OVERLAY_ELEVATION_DP = 24f
        private const val VOLUME_OVERLAY_DURATION_MS = 1_500L
        private const val STREAM_INFO_DURATION_MS = 5_000L
        private const val QUICK_SETTINGS_DURATION_MS = 7_000L
        private const val CLOCK_UPDATE_MS = 1_000L
        private const val CLOCK_SHIFT_INTERVAL_MS = 30_000L
        private const val CLOCK_SHIFT_PX = 2f
        private const val IDLE_DIM_ALPHA = 0.42f
        private const val MIN_AUDIO_VOLUME = 0.0f
        private const val MAX_AUDIO_VOLUME = 1.0f
        private const val DEFAULT_AUDIO_VOLUME = 1.0f
        private const val DEBUG_CODECS = false
        @Suppress("DEPRECATION")
        private val IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}
