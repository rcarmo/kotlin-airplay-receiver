package io.carmo.airplay.receiver

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.carmo.airplay.receiver.model.NALPacket
import io.carmo.airplay.receiver.model.PCMPacket
import io.carmo.airplay.receiver.player.AudioPlayer
import io.carmo.airplay.receiver.player.VideoPlayer
import java.nio.ByteBuffer
import java.util.ArrayDeque

class RaopServer(
    private val context: Context,
    private val onConnectionStarted: () -> Unit,
    private val onVideoActivity: (Boolean) -> Unit,
    private val onTrafficSample: (Int) -> Unit,
    private val onLatencySample: (Long) -> Unit,
    private val onVideoSizeChanged: (Int, Int) -> Unit,
    private val onFrameRateChanged: (Float) -> Unit,
    private val onStreamStatusChanged: (String) -> Unit,
    private val onStateChanged: (ReceiverState) -> Unit,
    private val onAudioMetadataChanged: (AirPlayMetadata) -> Unit,
    private val onAudioCoverArtChanged: (ByteArray) -> Unit,
    initialVideoWidth: Int,
    initialVideoHeight: Int,
    initialAudioVolume: Float
) {

    @Volatile private var videoPlayer: VideoPlayer? = null
    @Volatile private var currentSurface: Surface? = null
    private val preSurfaceBuffer = ArrayDeque<NALPacket>(PRE_SURFACE_BUFFER_SIZE)
    private val preSurfaceLock = Any()
    private var audioPlayer: AudioPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var serverId: Long = 0
    @Volatile private var lastMediaPacketAtMs = 0L
    @Volatile private var lastVideoPacketAtMs = 0L
    @Volatile private var sessionStartedAtMs = 0L
    @Volatile private var videoWidth = initialVideoWidth
    @Volatile private var videoHeight = initialVideoHeight
    @Volatile private var estimatedFrameRate = DEFAULT_FRAME_RATE
    @Volatile private var fallbackAudioVolume = initialAudioVolume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
    @Volatile private var audioVolume = fallbackAudioVolume
    @Volatile private var audioSyncMs = ReceiverPreferences.audioSyncMs(context)
    @Volatile private var hasConnection = false
    @Volatile private var hasStartedVideo = false
    @Volatile private var hasSessionVolume = false
    @Volatile private var activeSenderId: String? = null
    @Volatile private var pendingPairingSenderId: String? = null
    @Volatile private var pendingPairingSenderName: String? = null
    @Volatile private var currentState = ReceiverState.IDLE_ADVERTISING

    /**
     * Cached SPS/PPS bytes from the most recent codec-config NAL we received
     * over the wire. The Mac sends SPS/PPS once at session start; if our
     * VideoPlayer is recreated mid-session we replay this into the new player.
     */
    @Volatile private var cachedCodecConfig: ByteArray? = null
    @Volatile private var firstVideoBytesAtMs = 0L
    @Volatile private var firstAudioBytesAtMs = 0L
    @Volatile private var lastVideoStatusAtMs = 0L
    @Volatile private var lastNoSurfaceVideoLogAtMs = 0L
    @Volatile private var lastRenderedFrameCountAtMs = 0L
    @Volatile private var renderedFrameCount = 0
    @Volatile private var decoderRestartCount = 0
    @Volatile private var audioUnderrunCount = 0
    @Volatile private var maxPreSurfacePacketsBuffered = 0
    @Volatile private var lastSessionStats = ReceiverSessionStats()
    private var renderWatchdogFrameCount = 0
    private var lastVideoPtsUs = 0L
    private var frameIntervalEstimateUs = 0.0
    private var frameRateSampleCount = 0
    private var lastFrameRateReportAtMs = 0L

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun onRecvVideoData(buffer: ByteBuffer, size: Int, nativePointer: Long, nalType: Int, dts: Long, pts: Long) {
        if (DEBUG_FRAMES) {
            Log.d(TAG, "onRecvVideoData dts = $dts, pts = $pts, nalType = $nalType, nal length = $size")
        }
        markMediaTraffic()
        val now = SystemClock.elapsedRealtime()
        lastVideoPacketAtMs = now
        updateFrameRateEstimate(pts)
        if (firstVideoBytesAtMs == 0L) {
            firstVideoBytesAtMs = now
            scheduleStartupWatchdog()
            reportStreamStatus("Video bytes received", now)
            emitState(ReceiverState.VIDEO_REQUESTED)
            Log.i(TAG, "first video bytes received (size=$size, nalType=$nalType)")
        } else if (!hasStartedVideo && now - lastVideoStatusAtMs >= VIDEO_STATUS_INTERVAL_MS) {
            reportStreamStatus("Video bytes receiving", now)
        }
        markVideoActivity()
        onTrafficSample(size)

        if (nalType == NAL_TYPE_CODEC_CONFIG && size > 0) {
            val copy = ByteArray(size)
            val duplicate = buffer.duplicate()
            duplicate.position(0)
            duplicate.limit(size)
            duplicate.get(copy)
            cachedCodecConfig = copy
            Log.i(TAG, "cached SPS/PPS (size=$size) for replay")
        }

        val packet = NALPacket(
            data = buffer,
            size = size,
            nativePointer = nativePointer,
            nalType = nalType,
            pts = pts,
            dts = dts,
            receivedAtMs = SystemClock.elapsedRealtime()
        )
        val player = videoPlayer ?: ensureVideoPlayer()
        if (player == null) {
            bufferPreSurfacePacket(packet)
            emitState(ReceiverState.WAITING_FOR_SURFACE)
            logMissingSurface(nalType, size)
            return
        }
        player.addPacket(packet)
    }

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun onRecvAudioData(buffer: ByteBuffer, size: Int, nativePointer: Long, pts: Long) {
        if (DEBUG_FRAMES) {
            Log.d(TAG, "onRecvAudioData pcm bytes = $size, pts = $pts")
        }
        markMediaTraffic()
        if (firstAudioBytesAtMs == 0L) {
            firstAudioBytesAtMs = SystemClock.elapsedRealtime()
            if (!hasSessionVolume) {
                audioVolume = fallbackAudioVolume
                audioPlayer?.setVolume(audioVolume)
            }
            onStreamStatusChanged("Audio bytes received")
            if (firstVideoBytesAtMs == 0L) {
                emitState(ReceiverState.AUDIO_ACTIVE)
            }
            Log.i(TAG, "first audio bytes received (size=$size, pts=$pts)")
        }
        onTrafficSample(size)
        val packet = PCMPacket(
            data = buffer,
            size = size,
            nativePointer = nativePointer,
            pts = pts,
            receivedAtMs = SystemClock.elapsedRealtime()
        )
        ensureAudioPlayer().addPacket(packet)
    }

    @Synchronized
    fun attachSurface(surface: Surface) {
        if (currentSurface === surface && videoPlayer != null) {
            return
        }
        currentSurface = surface
        if (firstVideoBytesAtMs != 0L && surface.isValid) {
            ensureVideoPlayer(surface)
        }
    }

    @Synchronized
    fun detachSurface() {
        currentSurface = null
        stopVideoPlayer()
        hasStartedVideo = false
        if (firstVideoBytesAtMs != 0L && currentState != ReceiverState.STOPPING_SESSION) {
            emitState(ReceiverState.WAITING_FOR_SURFACE)
        }
    }

    fun startServer() {
        if (serverId == 0L) {
            serverId = start()
            if (serverId != 0L) {
                onStreamStatusChanged("Receiver ready")
            }
        }
    }

    fun stopServer() {
        mainHandler.removeCallbacks(startupWatchdog)
        mainHandler.removeCallbacks(startupWatchdogFinal)
        mainHandler.removeCallbacks(renderWatchdog)
        if (serverId != 0L) {
            stop(serverId)
        }
        serverId = 0L
        stopVideoPlayer()
        audioPlayer?.stopPlay()
        audioPlayer = null
        resetSessionCounters()
        clearPreSurfaceBuffer()
        cachedCodecConfig = null
        onStreamStatusChanged("Stream idle")
    }

    fun setVideoMode(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        if (hasConnection) {
            return
        }
        stopVideoPlayer()
    }

    fun setAudioVolume(volume: Float) {
        fallbackAudioVolume = volume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
        if (!hasSessionVolume) {
            audioVolume = fallbackAudioVolume
        }
        audioPlayer?.setVolume(audioVolume)
    }

    fun setAudioSyncMs(syncMs: Int) {
        audioSyncMs = syncMs.coerceIn(ReceiverPreferences.MIN_AUDIO_SYNC_MS, ReceiverPreferences.MAX_AUDIO_SYNC_MS)
        audioPlayer?.setAudioSyncMs(audioSyncMs)
    }

    fun sessionStats(): ReceiverSessionStats {
        return if (sessionStartedAtMs != 0L || hasConnection) {
            currentSessionStats()
        } else {
            lastSessionStats
        }
    }

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun onSetAudioVolume(volumeDb: Float) {
        hasSessionVolume = true
        audioVolume = raopVolumeToLinear(volumeDb)
        audioPlayer?.setVolume(audioVolume)
        Log.i(TAG, "RAOP volume changed: db=$volumeDb, linear=$audioVolume")
    }

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun onAudioFlush() {
        Log.i(TAG, "RAOP audio flush requested")
        audioPlayer?.flushPlayback()
    }

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun onAudioMetadata(data: ByteArray) {
        val metadata = AirPlayMetadataParser.parse(data)
        if (metadata.hasTrackText) {
            onAudioMetadataChanged(metadata)
            val title = metadata.title ?: metadata.artist ?: "Audio metadata received"
            onStreamStatusChanged(title)
            Log.i(TAG, "audio metadata: title=${metadata.title}, artist=${metadata.artist}, album=${metadata.album}")
        }
    }

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun onAudioCoverArt(data: ByteArray) {
        if (data.isNotEmpty()) {
            onAudioCoverArtChanged(data)
            Log.i(TAG, "audio cover art received: ${data.size} bytes")
        }
    }

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun onAudioRemoteControlId(dacpId: String?, activeRemote: String?) {
        val senderId = activeRemote?.takeIf { it.isNotBlank() } ?: dacpId?.takeIf { it.isNotBlank() }
        if (senderId != null) {
            onAudioMetadataChanged(AirPlayMetadata(senderId = senderId, senderName = "AirPlay sender"))
            Log.i(TAG, "audio sender id received: $senderId")
        }
    }

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun onAudioProgress(start: Long, current: Long, end: Long) {
        onAudioMetadataChanged(
            AirPlayMetadata(
                progressStartMs = rtpTimeToMs(start),
                progressCurrentMs = rtpTimeToMs(current),
                progressEndMs = rtpTimeToMs(end)
            )
        )
    }

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun shouldAcceptSender(senderId: String?, senderName: String?): Boolean {
        val sanitizedId = senderId?.trim()?.takeIf { it.isNotBlank() } ?: return true
        val displayName = senderName?.trim()?.takeIf { it.isNotBlank() } ?: sanitizedId
        val blocked = SenderTrustStore.blockedDevices(context).any { it.id == sanitizedId }
        if (blocked) {
            onStreamStatusChanged("Blocked sender rejected")
            Log.w(TAG, "rejecting blocked sender $sanitizedId")
            return false
        }

        val currentSender = activeSenderId
        val hasActiveSession = hasConnection || currentState in ACTIVE_STATES
        if (hasActiveSession && currentSender != null && currentSender != sanitizedId) {
            return when (ReceiverPreferences.takeoverProtection(context)) {
                ReceiverPreferences.TAKEOVER_ALLOW -> {
                    Log.i(TAG, "takeover allowed: $currentSender -> $sanitizedId")
                    activeSenderId = sanitizedId
                    true
                }
                else -> {
                    onStreamStatusChanged("Sender rejected while another session is active")
                    Log.w(TAG, "rejecting sender $sanitizedId while $currentSender is active")
                    false
                }
            }
        }

        val trusted = SenderTrustStore.trustedDevices(context).any { it.id == sanitizedId }
        if (!trusted && ReceiverPreferences.securityMode(context) == ReceiverPreferences.SECURITY_TRUSTED_ONLY &&
            !ReceiverPreferences.guestMode(context)
        ) {
            onStreamStatusChanged("Untrusted sender rejected")
            Log.w(TAG, "rejecting untrusted sender $sanitizedId")
            return false
        }

        activeSenderId = sanitizedId
        onAudioMetadataChanged(AirPlayMetadata(senderId = sanitizedId, senderName = displayName))
        if (securityModeUsesPinPlaceholder(trusted)) {
            pendingPairingSenderId = sanitizedId
            pendingPairingSenderName = displayName
        }
        return true
    }

    fun hasPendingPairingSender(): Boolean = pendingPairingSenderId != null

    fun markPendingPairingAccepted() {
        val senderId = pendingPairingSenderId ?: return
        val senderName = pendingPairingSenderName ?: senderId
        if (!ReceiverPreferences.guestMode(context)) {
            SenderTrustStore.trustDevice(context, senderId, senderName)
        }
        pendingPairingSenderId = null
        pendingPairingSenderName = null
    }

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun getVideoWidth(): Int = videoWidth

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun getVideoHeight(): Int = videoHeight

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun isVerboseLoggingEnabled(): Boolean = ReceiverPreferences.verboseLogging(context)

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun onStreamStopped() {
        mainHandler.post {
            if (serverId != 0L) {
                handleStreamStopped()
            }
        }
    }

    val port: Int
        get() = if (serverId != 0L) getPort(serverId) else 0

    private external fun start(): Long
    private external fun stop(serverId: Long)
    private external fun getPort(serverId: Long): Int

    private fun markMediaTraffic() {
        lastMediaPacketAtMs = SystemClock.elapsedRealtime()
        if (sessionStartedAtMs == 0L) {
            sessionStartedAtMs = lastMediaPacketAtMs
        }
        if (!hasConnection) {
            hasConnection = true
        }
    }

    @Synchronized
    private fun ensureAudioPlayer(): AudioPlayer {
        val existingPlayer = audioPlayer
        if (existingPlayer != null) {
            existingPlayer.setVolume(audioVolume)
            return existingPlayer
        }
        return AudioPlayer(
            context,
            audioVolume,
            onLatencySample,
            ::handleAudioUnderrun,
            audioStableMode = ReceiverPreferences.qualityProfile(context) == ReceiverPreferences.QUALITY_AUDIO_STABLE,
            initialAudioSyncMs = audioSyncMs
        )
            .also {
                audioPlayer = it
                it.start()
            }
    }

    private fun handleStreamStopped() {
        emitState(ReceiverState.STOPPING_SESSION)
        onStreamStatusChanged("Waiting")
        lastSessionStats = currentSessionStats("Sender disconnected")
        stopVideoPlayer()
        audioPlayer?.stopPlay()
        audioPlayer = null
        resetSessionCounters()
        clearPreSurfaceBuffer()
        emitState(ReceiverState.IDLE_ADVERTISING)
    }

    private fun resetSessionCounters() {
        mainHandler.removeCallbacks(startupWatchdog)
        mainHandler.removeCallbacks(startupWatchdogFinal)
        mainHandler.removeCallbacks(renderWatchdog)
        lastMediaPacketAtMs = 0L
        lastVideoPacketAtMs = 0L
        sessionStartedAtMs = 0L
        lastRenderedFrameCountAtMs = 0L
        renderedFrameCount = 0
        decoderRestartCount = 0
        audioUnderrunCount = 0
        maxPreSurfacePacketsBuffered = 0
        renderWatchdogFrameCount = 0
        hasConnection = false
        hasStartedVideo = false
        hasSessionVolume = false
        activeSenderId = null
        pendingPairingSenderId = null
        pendingPairingSenderName = null
        audioVolume = fallbackAudioVolume
        firstVideoBytesAtMs = 0L
        firstAudioBytesAtMs = 0L
        lastVideoStatusAtMs = 0L
        lastNoSurfaceVideoLogAtMs = 0L
        lastVideoPtsUs = 0L
        frameIntervalEstimateUs = 0.0
        frameRateSampleCount = 0
        estimatedFrameRate = DEFAULT_FRAME_RATE
    }

    private fun bufferPreSurfacePacket(packet: NALPacket) {
        synchronized(preSurfaceLock) {
            if (packet.isCodecConfig) {
                packet.release()
                return
            }
            if (preSurfaceBuffer.size >= PRE_SURFACE_BUFFER_SIZE) {
                preSurfaceBuffer.removeFirst().release()
            }
            preSurfaceBuffer.addLast(packet)
            maxPreSurfacePacketsBuffered = maxOf(maxPreSurfacePacketsBuffered, preSurfaceBuffer.size)
        }
    }

    private fun drainPreSurfaceBuffer(player: VideoPlayer) {
        synchronized(preSurfaceLock) {
            cachedCodecConfig?.let { config ->
                player.addPacket(NALPacket.forCodecConfig(config, SystemClock.elapsedRealtime()))
            }
            while (preSurfaceBuffer.isNotEmpty()) {
                player.addPacket(preSurfaceBuffer.removeFirst())
            }
        }
    }

    private fun clearPreSurfaceBuffer() {
        synchronized(preSurfaceLock) {
            while (preSurfaceBuffer.isNotEmpty()) {
                preSurfaceBuffer.removeFirst().release()
            }
        }
    }

    private fun logMissingSurface(nalType: Int, size: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNoSurfaceVideoLogAtMs < NO_SURFACE_LOG_INTERVAL_MS) {
            return
        }
        lastNoSurfaceVideoLogAtMs = now
        val queued = synchronized(preSurfaceLock) { preSurfaceBuffer.size }
        Log.w(
            TAG,
            "no video surface attached; buffering video packet " +
                "(nalType=$nalType, size=$size, queued=$queued)"
        )
    }

    @Synchronized
    private fun ensureVideoPlayer(surface: Surface? = currentSurface): VideoPlayer? {
        val currentPlayer = videoPlayer
        if (currentPlayer != null) {
            return currentPlayer
        }
        val validSurface = surface
        if (validSurface == null || !validSurface.isValid) {
            return null
        }
        val newPlayer = VideoPlayer(
            validSurface,
            videoWidth,
            videoHeight,
            onLatencySample,
            ::handleVideoFrameRendered,
            onVideoSizeChanged,
            enableFrameRateHint = ReceiverPreferences.frameRateMatching(context) &&
                ReceiverPreferences.qualityProfile(context) != ReceiverPreferences.QUALITY_LOW_LATENCY,
            sourceFrameRate = estimatedFrameRate
        )
        videoPlayer = newPlayer
        onStreamStatusChanged("Decoder starting")
        emitState(ReceiverState.VIDEO_STARTING)
        newPlayer.start()
        drainPreSurfaceBuffer(newPlayer)
        return newPlayer
    }

    @Synchronized
    private fun stopVideoPlayer() {
        mainHandler.removeCallbacks(renderWatchdog)
        videoPlayer?.let { player ->
            player.stopDecode()
            try {
                player.join(VIDEO_RESTART_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        videoPlayer = null
    }

    private fun handleVideoFrameRendered() {
        renderedFrameCount++
        val now = SystemClock.elapsedRealtime()
        if (!hasStartedVideo) {
            hasStartedVideo = true
            mainHandler.removeCallbacks(startupWatchdog)
            mainHandler.removeCallbacks(startupWatchdogFinal)
            lastMediaPacketAtMs = now
            lastRenderedFrameCountAtMs = now
            onConnectionStarted()
            onStreamStatusChanged("First frame rendered")
            emitState(ReceiverState.VIDEO_ACTIVE)
            scheduleRenderWatchdog()
        } else {
            lastRenderedFrameCountAtMs = now
        }
    }

    private val renderWatchdog = object : Runnable {
        override fun run() {
            if (!hasStartedVideo || currentState == ReceiverState.STOPPING_SESSION) return
            val now = SystemClock.elapsedRealtime()
            val currentCount = renderedFrameCount
            val packetAgeMs = now - lastVideoPacketAtMs
            val renderAgeMs = now - lastRenderedFrameCountAtMs

            if (currentCount == renderWatchdogFrameCount &&
                packetAgeMs < STALL_VIDEO_TRAFFIC_MAX_AGE_MS &&
                renderAgeMs >= RENDER_STALL_MIN_AGE_MS
            ) {
                Log.w(TAG, "render watchdog: decoder stalled (renderAge=${renderAgeMs}ms, video packets active)")
                emitState(ReceiverState.VIDEO_STALLED)
                onStreamStatusChanged("Recovering video")
                restartVideoDecoderOnly()
            }
            renderWatchdogFrameCount = currentCount
            mainHandler.postDelayed(this, RENDER_WATCHDOG_INTERVAL_MS)
        }
    }

    private fun scheduleRenderWatchdog() {
        renderWatchdogFrameCount = renderedFrameCount
        mainHandler.removeCallbacks(renderWatchdog)
        mainHandler.postDelayed(renderWatchdog, RENDER_WATCHDOG_INTERVAL_MS)
    }

    @Synchronized
    private fun restartVideoDecoderOnly() {
        decoderRestartCount++
        stopVideoPlayer()
        hasStartedVideo = false
        lastRenderedFrameCountAtMs = 0L
        val surface = currentSurface
        if (surface != null && surface.isValid) {
            ensureVideoPlayer(surface)
        } else {
            emitState(ReceiverState.WAITING_FOR_SURFACE)
        }
    }

    private fun reportStreamStatus(status: String, now: Long = SystemClock.elapsedRealtime()) {
        lastVideoStatusAtMs = now
        onStreamStatusChanged(status)
    }

    private val startupWatchdog = Runnable {
        if (hasStartedVideo) {
            return@Runnable
        }
        val trafficAgeMs = SystemClock.elapsedRealtime() - lastVideoPacketAtMs
        if (lastVideoPacketAtMs == 0L || trafficAgeMs > STARTUP_WATCHDOG_TRAFFIC_MAX_AGE_MS) {
            return@Runnable
        }
        Log.w(TAG, "startup watchdog fired: ${STARTUP_WATCHDOG_MS}ms with video traffic, no rendered frame")
        onStreamStatusChanged("Decoder starting (retry)")
        emitState(ReceiverState.VIDEO_STALLED)
        restartVideoDecoderOnly()
        mainHandler.postDelayed(startupWatchdogFinal, STARTUP_WATCHDOG_MS)
    }

    private val startupWatchdogFinal = Runnable {
        if (hasStartedVideo) {
            return@Runnable
        }
        Log.e(TAG, "startup watchdog final: decoder did not recover after two attempts")
        onStreamStatusChanged("Video failed - disconnect and retry")
        handleStreamStopped()
    }

    private fun scheduleStartupWatchdog() {
        mainHandler.removeCallbacks(startupWatchdog)
        mainHandler.removeCallbacks(startupWatchdogFinal)
        mainHandler.postDelayed(startupWatchdog, STARTUP_WATCHDOG_MS)
    }

    private fun handleAudioUnderrun() {
        audioUnderrunCount++
    }

    private fun updateFrameRateEstimate(ptsUs: Long) {
        if (ptsUs <= 0L) return
        val lastPts = lastVideoPtsUs
        lastVideoPtsUs = ptsUs
        if (lastPts <= 0L || ptsUs <= lastPts) return
        val deltaUs = ptsUs - lastPts
        if (deltaUs !in MIN_FRAME_INTERVAL_US..MAX_FRAME_INTERVAL_US) return

        frameIntervalEstimateUs = if (frameIntervalEstimateUs == 0.0) {
            deltaUs.toDouble()
        } else {
            frameIntervalEstimateUs * 0.85 + deltaUs.toDouble() * 0.15
        }
        frameRateSampleCount++
        if (frameRateSampleCount < FRAME_RATE_MIN_SAMPLES) return

        val estimate = (1_000_000.0 / frameIntervalEstimateUs).toFloat()
        val snapped = snapFrameRate(estimate)
        if (kotlin.math.abs(snapped - estimatedFrameRate) < 0.5f) return

        estimatedFrameRate = snapped
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameRateReportAtMs >= FRAME_RATE_REPORT_INTERVAL_MS) {
            lastFrameRateReportAtMs = now
            Log.i(TAG, "detected video frame rate: ${snapped}fps")
            onFrameRateChanged(snapped)
        }
    }

    private fun snapFrameRate(frameRate: Float): Float {
        return COMMON_FRAME_RATES.minByOrNull { kotlin.math.abs(it - frameRate) } ?: DEFAULT_FRAME_RATE
    }

    private fun rtpTimeToMs(value: Long): Long {
        return if (value <= 0L) 0L else value * 1_000L / AUDIO_RTP_CLOCK_HZ
    }

    private fun securityModeUsesPinPlaceholder(senderTrusted: Boolean): Boolean {
        return when (ReceiverPreferences.securityMode(context)) {
            ReceiverPreferences.SECURITY_PIN_EVERY_SESSION -> true
            ReceiverPreferences.SECURITY_PIN_NEW_DEVICES -> !senderTrusted
            else -> false
        }
    }

    private fun markVideoActivity() {
        onVideoActivity(true)
    }

    private fun emitState(state: ReceiverState) {
        currentState = state
        onStateChanged(state)
    }

    private fun currentSessionStats(disconnectReason: String? = null): ReceiverSessionStats {
        val startedAt = sessionStartedAtMs
        val durationMs = if (startedAt == 0L) {
            0L
        } else {
            (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        }
        return ReceiverSessionStats(
            durationMs = durationMs,
            videoFramesRendered = renderedFrameCount,
            decoderRestarts = decoderRestartCount,
            audioUnderruns = audioUnderrunCount,
            preSurfacePacketsBuffered = maxPreSurfacePacketsBuffered,
            lastDisconnectReason = disconnectReason
        )
    }

    private fun raopVolumeToLinear(volumeDb: Float): Float {
        if (volumeDb <= MIN_RAOP_VOLUME_DB) {
            return MIN_AUDIO_VOLUME
        }
        if (volumeDb >= MAX_RAOP_VOLUME_DB) {
            return MAX_AUDIO_VOLUME
        }
        return Math.pow(10.0, volumeDb.toDouble() / 20.0)
            .toFloat()
            .coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
    }

    companion object {
        private const val TAG = "Receiver-RAOP"
        private const val DEBUG_FRAMES = false
        private const val MIN_AUDIO_VOLUME = 0.0f
        private const val MAX_AUDIO_VOLUME = 1.0f
        private const val MIN_RAOP_VOLUME_DB = -144.0f
        private const val MAX_RAOP_VOLUME_DB = 0.0f
        private const val VIDEO_RESTART_TIMEOUT_MS = 500L
        private const val VIDEO_STATUS_INTERVAL_MS = 1_000L
        private const val NO_SURFACE_LOG_INTERVAL_MS = 2_000L
        private const val PRE_SURFACE_BUFFER_SIZE = 16
        private const val STARTUP_WATCHDOG_MS = 6_000L
        private const val STARTUP_WATCHDOG_TRAFFIC_MAX_AGE_MS = 2_000L
        private const val RENDER_WATCHDOG_INTERVAL_MS = 4_000L
        private const val RENDER_STALL_MIN_AGE_MS = 300_000L
        private const val STALL_VIDEO_TRAFFIC_MAX_AGE_MS = 3_000L
        private const val NAL_TYPE_CODEC_CONFIG = 0
        private const val DEFAULT_FRAME_RATE = 60.0f
        private const val MIN_FRAME_INTERVAL_US = 8_000L
        private const val MAX_FRAME_INTERVAL_US = 50_000L
        private const val FRAME_RATE_MIN_SAMPLES = 8
        private const val FRAME_RATE_REPORT_INTERVAL_MS = 3_000L
        private const val AUDIO_RTP_CLOCK_HZ = 44_100L
        private val COMMON_FRAME_RATES = listOf(24.0f, 25.0f, 30.0f, 50.0f, 60.0f)
        private val ACTIVE_STATES = setOf(
            ReceiverState.AUDIO_ACTIVE,
            ReceiverState.VIDEO_REQUESTED,
            ReceiverState.WAITING_FOR_SURFACE,
            ReceiverState.VIDEO_STARTING,
            ReceiverState.VIDEO_ACTIVE,
            ReceiverState.VIDEO_STALLED
        )

        init {
            System.loadLibrary("raop_server")
            System.loadLibrary("play-lib")
        }
    }
}
