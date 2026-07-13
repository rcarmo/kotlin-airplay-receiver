package io.carmo.airplay.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.session.MediaSession
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Surface
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.ArrayDeque
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the AirPlay receiver runtime.
 *
 * Lifecycle: created by ReceiverForegroundService. Lives as long as the service.
 * MainActivity binds to the service and calls attachSurface / detachSurface when
 * it starts and stops.
 */
class ReceiverRuntime(private val context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var airPlayServer: AirPlayServer? = null
    private var raopServer: RaopServer? = null
    private var dnsNotify: DNSNotify? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var mediaSession: MediaSession? = null
    private val transitionHistory = ArrayDeque<StateTransition>(MAX_TRANSITION_HISTORY)

    @Volatile private var isSurfaceAttached = false
    @Volatile private var lastUiLaunchAttemptAtMs = 0L
    @Volatile private var currentAudioVolume = 1.0f

    @Volatile var state: ReceiverState = ReceiverState.STOPPED
        private set

    @Volatile var discoveryStatus: String = "Discovery starting"
        private set

    @Volatile var streamStatus: String = "Waiting"
        private set

    @Volatile var pairingPin: String? = null
        private set

    private val stateListeners = CopyOnWriteArrayList<(ReceiverState) -> Unit>()
    private val discoveryStatusListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val streamStatusListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val videoActivityListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val videoSizeListeners = CopyOnWriteArrayList<(Int, Int) -> Unit>()
    private val frameRateListeners = CopyOnWriteArrayList<(Float) -> Unit>()
    private val trafficListeners = CopyOnWriteArrayList<(Int) -> Unit>()
    private val latencyListeners = CopyOnWriteArrayList<(Long) -> Unit>()
    private val audioNowPlayingListeners = CopyOnWriteArrayList<(AudioNowPlaying) -> Unit>()
    private val afterDisconnectListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var currentAudioMetadata = AirPlayMetadata()
    @Volatile private var currentCoverArt: Bitmap? = null
    @Volatile private var currentFrameRate = 60.0f

    val identity: ReceiverIdentity get() = ReceiverIdentity
    val audioNowPlaying: AudioNowPlaying get() = AudioNowPlaying(currentAudioMetadata, currentCoverArt)

    /** The display name used for AirPlay advertisement, e.g. "Living Room TV". */
    val deviceDisplayName: String get() = dnsNotify?.deviceName ?: "Receiver"

    fun addStateListener(listener: (ReceiverState) -> Unit) {
        stateListeners.add(listener)
        listener(state)
    }

    fun removeStateListener(listener: (ReceiverState) -> Unit) = stateListeners.remove(listener)

    fun addDiscoveryStatusListener(listener: (String) -> Unit) {
        discoveryStatusListeners.add(listener)
        listener(discoveryStatus)
    }

    fun removeDiscoveryStatusListener(listener: (String) -> Unit) = discoveryStatusListeners.remove(listener)

    fun addStreamStatusListener(listener: (String) -> Unit) {
        streamStatusListeners.add(listener)
        listener(streamStatus)
    }

    fun removeStreamStatusListener(listener: (String) -> Unit) = streamStatusListeners.remove(listener)

    fun addVideoActivityListener(l: (Boolean) -> Unit) = videoActivityListeners.add(l)
    fun removeVideoActivityListener(l: (Boolean) -> Unit) = videoActivityListeners.remove(l)
    fun addVideoSizeListener(l: (Int, Int) -> Unit) = videoSizeListeners.add(l)
    fun removeVideoSizeListener(l: (Int, Int) -> Unit) = videoSizeListeners.remove(l)
    fun addFrameRateListener(l: (Float) -> Unit) = frameRateListeners.add(l)
    fun removeFrameRateListener(l: (Float) -> Unit) = frameRateListeners.remove(l)
    fun addTrafficListener(l: (Int) -> Unit) = trafficListeners.add(l)
    fun removeTrafficListener(l: (Int) -> Unit) = trafficListeners.remove(l)
    fun addLatencyListener(l: (Long) -> Unit) = latencyListeners.add(l)
    fun removeLatencyListener(l: (Long) -> Unit) = latencyListeners.remove(l)
    fun addAudioNowPlayingListener(l: (AudioNowPlaying) -> Unit) {
        audioNowPlayingListeners.add(l)
        l(audioNowPlaying)
    }
    fun removeAudioNowPlayingListener(l: (AudioNowPlaying) -> Unit) = audioNowPlayingListeners.remove(l)
    fun addAfterDisconnectListener(l: () -> Unit) = afterDisconnectListeners.add(l)
    fun removeAfterDisconnectListener(l: () -> Unit) = afterDisconnectListeners.remove(l)

    @Synchronized
    fun start(videoWidth: Int, videoHeight: Int, audioVolume: Float) {
        if (state != ReceiverState.STOPPED) {
            Log.d(TAG, "start() called in state $state; refreshing discovery")
            refreshDiscovery()
            return
        }
        currentAudioVolume = audioVolume.coerceIn(0.0f, 1.0f)
        transitionTo(ReceiverState.STARTING, "runtime start")

        acquireMulticastLock()

        val dns = DNSNotify(appContext) { status ->
            discoveryStatus = status
            discoveryStatusListeners.forEach { it(status) }
        }
        dnsNotify = dns

        val raop = RaopServer(
            context = appContext,
            onConnectionStarted = ::onConnectionStarted,
            onVideoActivity = ::onVideoActivity,
            onTrafficSample = ::onTrafficSample,
            onLatencySample = ::onLatencySample,
            onVideoSizeChanged = ::onVideoSizeChanged,
            onFrameRateChanged = ::onFrameRateChanged,
            onStreamStatusChanged = ::onStreamStatusChanged,
            onStateChanged = ::onRaopStateChanged,
            onAudioMetadataChanged = ::onAudioMetadataChanged,
            onAudioCoverArtChanged = ::onAudioCoverArtChanged,
            initialVideoWidth = videoWidth,
            initialVideoHeight = videoHeight,
            initialAudioVolume = audioVolume
        )
        raopServer = raop
        raop.startServer()

        val airplay = AirPlayServer()
        airPlayServer = airplay
        airplay.startServer()

        val airplayPort = airplay.port
        val raopPort = raop.port

        if (airplayPort != 0) {
            dns.registerAirplay(airplayPort)
        } else {
            Log.e(TAG, "AirPlay server failed to bind")
            setDiscoveryStatus("AirPlay failed: port unavailable")
        }
        if (raopPort != 0) {
            dns.registerRaop(raopPort)
        } else {
            Log.e(TAG, "RAOP server failed to bind")
            setDiscoveryStatus("RAOP failed: port unavailable")
        }

        if (state == ReceiverState.STARTING) {
            transitionTo(ReceiverState.IDLE_ADVERTISING, "servers started")
        }
        Log.i(TAG, "runtime started; device=${dns.deviceName}, airplayPort=$airplayPort, raopPort=$raopPort")
    }

    @Synchronized
    fun stop() {
        if (state == ReceiverState.STOPPED) return
        transitionTo(ReceiverState.STOPPED, "runtime stop")
        dnsNotify?.stop()
        dnsNotify = null
        airPlayServer?.stopServer()
        airPlayServer = null
        raopServer?.stopServer()
        raopServer = null
        isSurfaceAttached = false
        releaseMediaSession()
        dismissVideoStartedNotification()
        releaseMulticastLock()
        setStreamStatus("Waiting")
        setDiscoveryStatus("Discovery stopped")
        Log.i(TAG, "runtime stopped")
    }

    /**
     * Attaches a rendering surface. Called by MainActivity when surfaceCreated fires.
     * Safe to call multiple times; idempotent if the same surface is passed.
     */
    fun attachSurface(surface: Surface) {
        isSurfaceAttached = true
        raopServer?.attachSurface(surface)
    }

    /**
     * Detaches the rendering surface. Called by MainActivity when surfaceDestroyed fires
     * or when the activity is being destroyed.
     */
    fun detachSurface() {
        isSurfaceAttached = false
        raopServer?.detachSurface()
    }

    fun setVideoMode(width: Int, height: Int) {
        raopServer?.setVideoMode(width, height)
    }

    fun setAudioVolume(volume: Float) {
        currentAudioVolume = volume.coerceIn(0.0f, 1.0f)
        raopServer?.setAudioVolume(currentAudioVolume)
    }

    fun setAudioSyncMs(syncMs: Int) {
        raopServer?.setAudioSyncMs(syncMs)
    }

    fun updateDeviceName(name: String) {
        val sanitized = name.trim()
        val prefs = appContext.getSharedPreferences(ReceiverPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(ReceiverPreferences.KEY_CUSTOM_DEVICE_NAME, sanitized).apply()
        refreshDiscovery()
    }

    fun getLocalIpAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    fun dismissVideoStartedNotification() {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(VIDEO_START_NOTIFICATION_ID)
    }

    fun buildDiagnosticsReport(): String {
        val currentDns = dnsNotify
        val deviceName = currentDns?.deviceName ?: deviceDisplayName
        val receiverId = ReceiverIdentity.receiverId(appContext)
        val stats = raopServer?.sessionStats() ?: ReceiverSessionStats()
        val transitions = synchronized(transitionHistory) { transitionHistory.toList() }
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)

        return buildString {
            appendLine("App version: ${BuildConfig.VERSION_NAME}")
            appendLine("Receiver ID: $receiverId")
            appendLine("Device name: $deviceName")
            appendLine("AirPlay name: ${airplayServiceNameFor(deviceName)}")
            appendLine("RAOP name: ${ReceiverIdentity.raopPrefix(appContext)}$deviceName")
            appendLine()
            appendLine("Network:")
            networkDiagnosticsLines().forEach { appendLine("  $it") }
            appendLine("State: $state")
            appendLine("Discovery status:")
            discoveryStatus.lineSequence().forEach { appendLine("  $it") }
            appendLine()
            appendLine("Settings:")
            appendLine("  Quality: ${ReceiverPreferences.qualityProfileSummary(appContext)}")
            appendLine("  Screen fit: ${ReceiverPreferences.screenFitSummary(appContext)}")
            appendLine("  Audio sync: ${ReceiverPreferences.audioSyncSummary(appContext)}")
            appendLine("  Security: ${ReceiverPreferences.securityModeSummary(appContext)}")
            appendLine("  Takeover: ${ReceiverPreferences.takeoverProtectionSummary(appContext)}")
            appendLine("  Idle dimming: ${ReceiverPreferences.idleDimSummary(appContext)}")
            appendLine("  Verbose logging: ${if (ReceiverPreferences.verboseLogging(appContext)) "on" else "off"}")
            appendLine()
            appendLine("Playback:")
            appendLine("  Detected frame rate: ${"%.1f".format(Locale.US, currentFrameRate)}fps")
            appendLine("  Audio metadata: ${currentAudioMetadata.title ?: "n/a"}")
            appendLine("  Audio artist: ${currentAudioMetadata.artist ?: "n/a"}")
            appendLine("  Cover art: ${if (currentCoverArt != null) "present" else "n/a"}")
            appendLine()
            appendLine("Last session:")
            appendLine("  Duration: ${formatDuration(stats.durationMs)}")
            appendLine("  Video frames rendered: ${stats.videoFramesRendered}")
            appendLine("  Decoder restarts: ${stats.decoderRestarts}")
            appendLine("  Audio underruns: ${stats.audioUnderruns}")
            appendLine("  Pre-surface packets buffered: ${stats.preSurfacePacketsBuffered}")
            appendLine("  Disconnect reason: ${stats.lastDisconnectReason ?: "n/a"}")
            appendLine()
            appendLine("Suggestions:")
            appendLine("  ${diagnosticSuggestion(stats)}")
            networkWarning()?.let { appendLine("  $it") }
            appendLine()
            appendLine("Recent events:")
            if (transitions.isEmpty()) {
                appendLine("  none")
            } else {
                transitions.forEach { transition ->
                    append("  ")
                    append(formatter.format(Date(transition.timestampMs)))
                    append("  ")
                    append(transition.from)
                    append(" -> ")
                    append(transition.to)
                    append(" (")
                    append(transition.reason)
                    appendLine(")")
                }
            }
        }
    }

    fun refreshDiscovery() {
        val airplay = airPlayServer ?: return
        val raop = raopServer ?: return
        val dns = dnsNotify ?: return
        acquireMulticastLock()
        if (airplay.port != 0) dns.registerAirplay(airplay.port)
        if (raop.port != 0) dns.registerRaop(raop.port)
    }

    private fun onConnectionStarted() {
        mainHandler.post {
            setStreamStatus("Streaming")
        }
    }

    private fun onVideoActivity(hasActivity: Boolean) {
        if (hasActivity && !isSurfaceAttached && ReceiverPreferences.automaticVideoTakeover(appContext)) {
            bringReceiverToFront()
        }
        videoActivityListeners.forEach { it(hasActivity) }
    }

    private fun onTrafficSample(bytes: Int) {
        trafficListeners.forEach { it(bytes) }
    }

    private fun onVideoSizeChanged(width: Int, height: Int) {
        videoSizeListeners.forEach { it(width, height) }
    }

    private fun onFrameRateChanged(frameRate: Float) {
        currentFrameRate = frameRate
        frameRateListeners.forEach { it(frameRate) }
    }

    private fun onLatencySample(latencyMs: Long) {
        latencyListeners.forEach { it(latencyMs) }
    }

    private fun onAudioMetadataChanged(update: AirPlayMetadata) {
        mainHandler.post {
            currentAudioMetadata = currentAudioMetadata.mergeWith(update)
            notifyAudioNowPlayingChanged()
            updateMediaSessionMetadata()
        }
    }

    private fun onAudioCoverArtChanged(data: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return
        mainHandler.post {
            currentCoverArt = bitmap
            notifyAudioNowPlayingChanged()
            updateMediaSessionMetadata()
        }
    }

    private fun notifyAudioNowPlayingChanged() {
        val nowPlaying = audioNowPlaying
        audioNowPlayingListeners.forEach { it(nowPlaying) }
    }

    private fun onStreamStatusChanged(status: String) {
        mainHandler.post {
            setStreamStatus(status)
        }
    }

    private fun onRaopStateChanged(newState: ReceiverState) {
        mainHandler.post {
            if (newState == ReceiverState.IDLE_ADVERTISING) {
                setStreamStatus("Waiting")
            }
            if (shouldShowPairingPlaceholder(newState)) {
                beginPairingPlaceholder(newState)
                return@post
            }
            transitionTo(newState, "raop state change")
        }
    }

    private fun shouldShowPairingPlaceholder(newState: ReceiverState): Boolean {
        if (raopServer?.hasPendingPairingSender() != true) return false
        if (state != ReceiverState.IDLE_ADVERTISING) return false
        return newState in setOf(
            ReceiverState.AUDIO_ACTIVE,
            ReceiverState.VIDEO_REQUESTED,
            ReceiverState.WAITING_FOR_SURFACE,
            ReceiverState.VIDEO_STARTING
        )
    }

    private fun beginPairingPlaceholder(nextState: ReceiverState) {
        pairingPin = "%04d".format(PAIRING_RANDOM.nextInt(10_000))
        setStreamStatus("Pairing PIN: $pairingPin")
        transitionTo(ReceiverState.PAIRING, "pairing placeholder")
        mainHandler.postDelayed({
            if (state == ReceiverState.PAIRING) {
                raopServer?.markPendingPairingAccepted()
                pairingPin = null
                transitionTo(nextState, "pairing placeholder accepted")
            }
        }, PAIRING_PLACEHOLDER_MS)
    }

    private fun setDiscoveryStatus(status: String) {
        discoveryStatus = status
        discoveryStatusListeners.forEach { it(status) }
    }

    private fun setStreamStatus(status: String) {
        streamStatus = status
        streamStatusListeners.forEach { it(status) }
    }

    private fun bringReceiverToFront() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiLaunchAttemptAtMs < UI_LAUNCH_THROTTLE_MS) {
            return
        }
        lastUiLaunchAttemptAtMs = now
        val intent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        try {
            PendingIntent.getActivity(
                appContext,
                VIDEO_ACTIVITY_REQUEST_CODE,
                intent,
                pendingIntentFlags()
            ).send()
        } catch (e: Throwable) {
            Log.w(TAG, "could not bring receiver UI to front for video stream", e)
        }
    }

    private fun pendingIntentFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    private fun canDrawOverlays(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(appContext)
    }

    private fun postVideoStartedNotification() {
        createVideoNotificationChannel()
        val launchIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            VIDEO_START_REQUEST_CODE,
            launchIntent,
            pendingIntentFlags()
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(appContext, VIDEO_NOTIFY_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(appContext)
        }

        @Suppress("DEPRECATION")
        val notification = builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(appContext.getString(R.string.app_name))
            .setContentText(appContext.getString(R.string.notification_video_started))
            .setContentIntent(pendingIntent)
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setAutoCancel(true)
            .build()

        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(VIDEO_START_NOTIFICATION_ID, notification)
    }

    private fun createVideoNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            VIDEO_NOTIFY_CHANNEL_ID,
            appContext.getString(R.string.notification_channel_video),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    private fun createMediaSession() {
        if (mediaSession != null) {
            updateMediaSessionState(PlaybackState.STATE_PLAYING)
            return
        }
        val session = MediaSession(appContext, "ReceiverAudio")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPause() {
                // AirPlay sender transport is authoritative; do not locally mute RAOP.
                updateMediaSessionState(PlaybackState.STATE_PLAYING)
            }

            override fun onPlay() {
                updateMediaSessionState(PlaybackState.STATE_PLAYING)
            }

            override fun onStop() {
                updateMediaSessionState(PlaybackState.STATE_STOPPED)
            }
        })
        mediaSession = session
        session.isActive = true
        updateMediaSessionMetadata()
        updateMediaSessionState(PlaybackState.STATE_PLAYING)
    }

    private fun updateMediaSessionMetadata() {
        val trackMetadata = currentAudioMetadata
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, trackMetadata.title ?: "AirPlay Audio")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, trackMetadata.artist ?: trackMetadata.senderName ?: deviceDisplayName)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, trackMetadata.album ?: appContext.getString(R.string.app_name))
            .apply {
                currentCoverArt?.let { art ->
                    putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                    putBitmap(MediaMetadata.METADATA_KEY_ART, art)
                }
                trackMetadata.progressEndMs?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
            }
            .build()
        mediaSession?.setMetadata(metadata)
    }

    private fun updateMediaSessionState(state: Int) {
        val playbackState = PlaybackState.Builder()
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_STOP
            )
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    private fun releaseMediaSession() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
    }

    private fun airplayServiceNameFor(deviceName: String): String {
        return if (deviceName.endsWith(AIRPLAY_SERVICE_SUFFIX, ignoreCase = true)) {
            deviceName.take(MAX_SERVICE_NAME_LENGTH)
        } else {
            val maxBaseLength = (MAX_SERVICE_NAME_LENGTH - AIRPLAY_SERVICE_SUFFIX.length).coerceAtLeast(1)
            "${deviceName.take(maxBaseLength).trimEnd()}$AIRPLAY_SERVICE_SUFFIX"
        }
    }

    private fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "0s"
        val totalSeconds = durationMs / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) {
            "${minutes}m ${seconds}s"
        } else {
            "${seconds}s"
        }
    }

    private fun diagnosticSuggestion(stats: ReceiverSessionStats): String {
        return when {
            stats.decoderRestarts >= 2 -> "Repeated decoder restarts detected. Try Compatibility quality profile."
            stats.audioUnderruns >= 3 -> "Audio underruns detected. Try Audio Stable quality profile or reduce Bluetooth latency."
            state == ReceiverState.ERROR_RECOVERABLE -> "Receiver is recovering. Restart discovery or restart the receiver if it does not return to ready."
            else -> "No specific suggestion."
        }
    }

    private fun networkDiagnosticsLines(): List<String> {
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val caps = network?.let { connectivity.getNetworkCapabilities(it) }
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ssid = wifiManager.connectionInfo?.ssid
            ?.trim('"')
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            ?: "unknown"
        val transports = mutableListOf<String>()
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) transports.add("Wi-Fi")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) transports.add("Ethernet")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) transports.add("VPN")
        if (transports.isEmpty()) transports.add("unknown")
        return listOf(
            "IP address: ${getLocalIpAddress()}",
            "SSID: $ssid",
            "Transport: ${transports.joinToString(", ")}",
            "Internet validated: ${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}",
            "Captive portal: ${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true}",
            "Multicast lock: ${multicastLock?.isHeld == true}"
        )
    }

    private fun networkWarning(): String? {
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        @Suppress("DEPRECATION")
        val ssid = (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .connectionInfo
            ?.ssid
            ?.trim('"')
            ?.lowercase(Locale.US)
            .orEmpty()
        return when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ->
                "VPN detected. AirPlay discovery may not cross the VPN route."
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true ->
                "Captive portal detected. Finish network sign-in before using AirPlay."
            "guest" in ssid ->
                "Guest Wi-Fi detected. Router isolation may block AirPlay discovery."
            getLocalIpAddress() == "unknown" ->
                "No local IPv4 address detected. Check the TV network connection."
            else -> null
        }
    }

    private fun acquireMulticastLock() {
        val lock = multicastLock ?: run {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.createMulticastLock("${appContext.packageName}:receiver")
                .apply { setReferenceCounted(false) }
                .also { multicastLock = it }
        }
        if (!lock.isHeld) lock.acquire()
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
    }

    private fun transitionTo(newState: ReceiverState, reason: String) {
        val old = state
        if (old == newState) return
        if (!isValidTransition(old, newState)) {
            Log.w(TAG, "invalid state transition ignored: $old -> $newState ($reason)")
            return
        }
        state = newState
        recordTransition(old, newState, reason)
        Log.d(TAG, "state: $old -> $newState ($reason)")
        stateListeners.forEach { it(newState) }
        handleStateSideEffects(old, newState)
    }

    private fun recordTransition(old: ReceiverState, newState: ReceiverState, reason: String) {
        synchronized(transitionHistory) {
            if (transitionHistory.size >= MAX_TRANSITION_HISTORY) {
                transitionHistory.removeFirst()
            }
            transitionHistory.addLast(StateTransition(old, newState, reason))
        }
    }

    private fun handleStateSideEffects(old: ReceiverState, newState: ReceiverState) {
        if (newState == ReceiverState.AUDIO_ACTIVE && old != ReceiverState.AUDIO_ACTIVE) {
            createMediaSession()
            if (ReceiverPreferences.audioOnlyDisplay(appContext) != ReceiverPreferences.AUDIO_ONLY_BACKGROUND) {
                bringReceiverToFront()
            }
        } else if (old == ReceiverState.AUDIO_ACTIVE && newState != ReceiverState.AUDIO_ACTIVE) {
            releaseMediaSession()
            currentAudioMetadata = AirPlayMetadata()
            currentCoverArt = null
            notifyAudioNowPlayingChanged()
        }

        if (newState == ReceiverState.VIDEO_REQUESTED) {
            if (ReceiverPreferences.automaticVideoTakeover(appContext) && !canDrawOverlays()) {
                postVideoStartedNotification()
            }
        }

        if (newState in setOf(
                ReceiverState.IDLE_ADVERTISING,
                ReceiverState.STOPPING_SESSION,
                ReceiverState.STOPPED,
                ReceiverState.ERROR_RECOVERABLE
            )
        ) {
            pairingPin = null
            dismissVideoStartedNotification()
        }

        if (old == ReceiverState.STOPPING_SESSION && newState == ReceiverState.IDLE_ADVERTISING) {
            val afterDisconnect = ReceiverPreferences.afterDisconnect(appContext)
            if (afterDisconnect == ReceiverPreferences.AFTER_DISCONNECT_HOME) {
                afterDisconnectListeners.forEach { it() }
            }
        }
    }

    private fun isValidTransition(old: ReceiverState, new: ReceiverState): Boolean {
        if (new == ReceiverState.STOPPED || new == ReceiverState.ERROR_RECOVERABLE) return true
        return when (old) {
            ReceiverState.FIRST_RUN -> new in setOf(
                ReceiverState.STOPPED,
                ReceiverState.STARTING,
                ReceiverState.IDLE_ADVERTISING
            )
            ReceiverState.STOPPED -> new == ReceiverState.STARTING
            ReceiverState.STARTING -> new in setOf(
                ReceiverState.IDLE_ADVERTISING,
                ReceiverState.PAIRING,
                ReceiverState.AUDIO_ACTIVE,
                ReceiverState.VIDEO_REQUESTED,
                ReceiverState.WAITING_FOR_SURFACE,
                ReceiverState.VIDEO_STARTING
            )
            ReceiverState.IDLE_ADVERTISING -> new in setOf(
                ReceiverState.PAIRING,
                ReceiverState.AUDIO_ACTIVE,
                ReceiverState.VIDEO_REQUESTED,
                ReceiverState.WAITING_FOR_SURFACE,
                ReceiverState.VIDEO_STARTING,
                ReceiverState.STOPPING_SESSION
            )
            ReceiverState.PAIRING -> new in setOf(
                ReceiverState.IDLE_ADVERTISING,
                ReceiverState.AUDIO_ACTIVE,
                ReceiverState.VIDEO_REQUESTED,
                ReceiverState.WAITING_FOR_SURFACE,
                ReceiverState.VIDEO_STARTING,
                ReceiverState.STOPPING_SESSION
            )
            ReceiverState.AUDIO_ACTIVE -> new in setOf(
                ReceiverState.IDLE_ADVERTISING,
                ReceiverState.VIDEO_REQUESTED,
                ReceiverState.STOPPING_SESSION
            )
            ReceiverState.VIDEO_REQUESTED -> new in setOf(
                ReceiverState.WAITING_FOR_SURFACE,
                ReceiverState.VIDEO_STARTING,
                ReceiverState.STOPPING_SESSION,
                ReceiverState.IDLE_ADVERTISING
            )
            ReceiverState.WAITING_FOR_SURFACE -> new in setOf(
                ReceiverState.VIDEO_STARTING,
                ReceiverState.STOPPING_SESSION,
                ReceiverState.IDLE_ADVERTISING
            )
            ReceiverState.VIDEO_STARTING -> new in setOf(
                ReceiverState.VIDEO_ACTIVE,
                ReceiverState.VIDEO_STALLED,
                ReceiverState.WAITING_FOR_SURFACE,
                ReceiverState.STOPPING_SESSION,
                ReceiverState.IDLE_ADVERTISING
            )
            ReceiverState.VIDEO_ACTIVE -> new in setOf(
                ReceiverState.VIDEO_STALLED,
                ReceiverState.WAITING_FOR_SURFACE,
                ReceiverState.STOPPING_SESSION,
                ReceiverState.IDLE_ADVERTISING
            )
            ReceiverState.VIDEO_STALLED -> new in setOf(
                ReceiverState.VIDEO_STARTING,
                ReceiverState.VIDEO_ACTIVE,
                ReceiverState.WAITING_FOR_SURFACE,
                ReceiverState.STOPPING_SESSION,
                ReceiverState.IDLE_ADVERTISING
            )
            ReceiverState.STOPPING_SESSION -> new == ReceiverState.IDLE_ADVERTISING
            ReceiverState.ERROR_RECOVERABLE -> new in setOf(
                ReceiverState.STARTING,
                ReceiverState.IDLE_ADVERTISING
            )
        }
    }

    companion object {
        private const val TAG = "Receiver-Runtime"
        private const val UI_LAUNCH_THROTTLE_MS = 5_000L
        private const val VIDEO_ACTIVITY_REQUEST_CODE = 2001
        private const val VIDEO_START_REQUEST_CODE = 2002
        private const val VIDEO_START_NOTIFICATION_ID = 2003
        private const val VIDEO_NOTIFY_CHANNEL_ID = "video_started"
        private const val MAX_TRANSITION_HISTORY = 20
        private const val MAX_SERVICE_NAME_LENGTH = 63
        private const val AIRPLAY_SERVICE_SUFFIX = " AirPlay"
        private const val PAIRING_PLACEHOLDER_MS = 4_000L
        private val PAIRING_RANDOM = SecureRandom()
    }
}
