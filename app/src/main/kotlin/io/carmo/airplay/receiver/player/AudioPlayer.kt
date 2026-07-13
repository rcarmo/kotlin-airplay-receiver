package io.carmo.airplay.receiver.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.carmo.airplay.receiver.model.PCMPacket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class AudioPlayer(
    context: Context,
    initialVolume: Float = DEFAULT_VOLUME,
    private val onLatencySample: (Long) -> Unit = {},
    private val onAudioUnderrun: () -> Unit = {},
    audioStableMode: Boolean = false,
    initialAudioSyncMs: Int = 0
) : Thread("ReceiverAudioPlayer") {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val packets = ArrayBlockingQueue<PCMPacket>(MAX_BUFFERED_PACKETS)
    private val maxQueuedPackets = if (audioStableMode) AUDIO_STABLE_MAX_QUEUED_PACKETS else MAX_QUEUED_PACKETS
    private val prebufferPackets = if (audioStableMode) AUDIO_STABLE_PREBUFFER_PACKETS else PREBUFFER_PACKETS
    private val audioBufferMultiplier = if (audioStableMode) AUDIO_STABLE_BUFFER_MULTIPLIER else AUDIO_BUFFER_MULTIPLIER
    private val playbackLock = Any()
    @Volatile private var volume = initialVolume.coerceIn(MIN_VOLUME, MAX_VOLUME)
    private var track: AudioTrack? = createAudioTrack()
    private var audioFocusRequest: Any? = null
    @Volatile private var isStopped = false
    private var hasStartedPlayback = false
    private var hasAudioFocus = false
    private var hasLoggedFirstWrite = false
    private var droppedPackets = 0L
    private var lastDropLogAtMs = 0L
    private var lastUnderrunLogAtMs = 0L
    private var lastShortWriteLogAtMs = 0L
    private var lastAudioPacketWrittenAtMs = 0L
    @Volatile private var audioSyncMs = initialAudioSyncMs.coerceIn(MIN_AUDIO_SYNC_MS, MAX_AUDIO_SYNC_MS)
    private var pendingInitialSilenceBytes = syncMsToBytes(audioSyncMs.coerceAtLeast(0))
    private var pendingInitialDropBytes = syncMsToBytes((-audioSyncMs).coerceAtLeast(0))

    fun addPacket(packet: PCMPacket) {
        val trimmed = trimBacklog()
        if (trimmed > 0) {
            logDroppedPackets(trimmed)
        }
        if (!packets.offer(packet)) {
            packets.poll()?.release()
            logDroppedPackets(1)
            if (!packets.offer(packet)) {
                packet.release()
                logDroppedPackets(1)
            }
        }
    }

    fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(MIN_VOLUME, MAX_VOLUME)
        synchronized(playbackLock) {
            track?.setVolume(this.volume)
        }
    }

    fun setAudioSyncMs(syncMs: Int) {
        val coerced = syncMs.coerceIn(MIN_AUDIO_SYNC_MS, MAX_AUDIO_SYNC_MS)
        if (coerced == audioSyncMs) return
        audioSyncMs = coerced
        flushPlayback()
    }

    fun flushPlayback() {
        drainPackets()
        droppedPackets = 0L
        lastDropLogAtMs = 0L
        lastUnderrunLogAtMs = 0L
        synchronized(playbackLock) {
            track?.let { audioTrack ->
                try {
                    if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack.pause()
                    }
                } catch (_: Exception) {
                }
                try {
                    audioTrack.flush()
                } catch (_: Exception) {
                }
                audioTrack.setVolume(volume)
            }
            hasStartedPlayback = false
            hasLoggedFirstWrite = false
            lastAudioPacketWrittenAtMs = 0L
            resetSyncStateLocked()
        }
        Log.i(TAG, "AudioTrack flushed")
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (!isStopped) {
            try {
                if (!hasStartedPlayback && packets.size < prebufferPackets) {
                    Thread.sleep(PREBUFFER_WAIT_MS)
                    continue
                }
                val packet = packets.poll(250, TimeUnit.MILLISECONDS)
                if (packet == null) {
                    if (hasStartedPlayback) {
                        logUnderrun()
                    }
                    continue
                }
                if (!hasStartedPlayback && !hasAudioFocus) {
                    hasAudioFocus = requestAudioFocus()
                    if (!hasAudioFocus) {
                        Log.w(TAG, "audio focus was not granted")
                    }
                }
                play(packet)
            } catch (e: InterruptedException) {
                if (isStopped) {
                    break
                }
            }
        }
        abandonAudioFocus()
    }

    fun stopPlay() {
        isStopped = true
        interrupt()
        drainPackets()
        synchronized(playbackLock) {
            track?.run {
                try {
                    if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                        stop()
                    }
                } catch (_: Exception) {
                }
                try {
                    flush()
                } catch (_: Exception) {
                }
                try {
                    release()
                } catch (_: Exception) {
                }
            }
            track = null
        }
        abandonAudioFocus()
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> track?.setVolume(0f)
                        AudioManager.AUDIOFOCUS_GAIN -> track?.setVolume(volume)
                    }
                }
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (audioFocusRequest as? AudioFocusRequest)?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioFocusRequest = null
        hasAudioFocus = false
    }

    private fun play(packet: PCMPacket) {
        try {
            val writeOffset = consumeInitialDrop(packet.size)
            if (writeOffset >= packet.size) {
                return
            }
            packet.data.position(writeOffset)
            packet.data.limit(packet.size)
            val written = synchronized(playbackLock) {
                val audioTrack = track ?: return
                audioTrack.setVolume(volume)
                if (!hasStartedPlayback) {
                    audioTrack.play()
                    hasStartedPlayback = true
                    writeInitialSilenceLocked(audioTrack)
                }
                audioTrack.write(packet.data, packet.size - writeOffset, AudioTrack.WRITE_BLOCKING)
            }
            if (written < 0) {
                Log.w(TAG, "AudioTrack write failed with code $written")
            } else if (written != packet.size - writeOffset) {
                handleShortWrite(written, packet.size - writeOffset)
            } else {
                if (!hasLoggedFirstWrite) {
                    hasLoggedFirstWrite = true
                    Log.i(
                        TAG,
                        "first audio packet written: size=$written, volume=$volume, " +
                            "sync=${audioSyncMs}ms, queued=${packets.size}"
                    )
                }
                lastAudioPacketWrittenAtMs = SystemClock.elapsedRealtime()
                onLatencySample(SystemClock.elapsedRealtime() - packet.receivedAtMs)
            }
        } finally {
            packet.release()
        }
    }

    private fun consumeInitialDrop(packetSize: Int): Int {
        val pending = pendingInitialDropBytes
        if (pending <= 0) {
            return 0
        }
        val drop = pending.coerceAtMost(packetSize)
        pendingInitialDropBytes -= drop
        if (pendingInitialDropBytes == 0) {
            Log.i(TAG, "audio sync advanced by dropping ${bytesToMs(drop)}ms of initial PCM")
        }
        return drop
    }

    private fun writeInitialSilenceLocked(audioTrack: AudioTrack) {
        var remaining = pendingInitialSilenceBytes
        if (remaining <= 0) return
        val silence = ByteArray(SILENCE_CHUNK_BYTES)
        while (remaining > 0 && !isStopped) {
            val chunk = remaining.coerceAtMost(silence.size)
            val written = audioTrack.write(silence, 0, chunk)
            if (written <= 0) {
                break
            }
            remaining -= written
        }
        pendingInitialSilenceBytes = remaining
        if (remaining == 0) {
            Log.i(TAG, "audio sync delayed by ${audioSyncMs}ms of initial silence")
        }
    }

    private fun resetSyncStateLocked() {
        pendingInitialSilenceBytes = syncMsToBytes(audioSyncMs.coerceAtLeast(0))
        pendingInitialDropBytes = syncMsToBytes((-audioSyncMs).coerceAtLeast(0))
    }

    private fun handleShortWrite(written: Int, expected: Int) {
        if (written == 0) {
            synchronized(playbackLock) {
                if (track?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    hasStartedPlayback = false
                }
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastShortWriteLogAtMs >= AUDIO_LOG_INTERVAL_MS) {
            lastShortWriteLogAtMs = now
            Log.w(TAG, "short AudioTrack write: wrote $written of $expected bytes")
        }
    }

    private fun trimBacklog(): Int {
        var trimmed = 0
        while (packets.size >= maxQueuedPackets) {
            packets.poll()?.release() ?: return trimmed
            trimmed += 1
        }
        return trimmed
    }

    private fun drainPackets() {
        while (true) {
            packets.poll()?.release() ?: break
        }
    }

    private fun createAudioTrack(): AudioTrack {
        var minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNELS, AUDIO_FORMAT)
        if (minBufferSize <= 0) {
            minBufferSize = SAMPLE_RATE / 10 * 2 * 2
        }
        val bufferSize = minBufferSize * audioBufferMultiplier

        val audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNELS)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, CHANNELS, AUDIO_FORMAT, bufferSize, AudioTrack.MODE_STREAM)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioTrack.setBufferSizeInFrames(bufferSize / BYTES_PER_FRAME)
        }
        audioTrack.setVolume(volume)
        Log.i(
            TAG,
            "AudioTrack ready: minBuffer=$minBufferSize bytes, buffer=$bufferSize bytes, " +
                "bufferMs=${bytesToMs(bufferSize)}, prebuffer=$prebufferPackets packets, " +
                "prebufferMs=${packetsToMs(prebufferPackets)}, queueLimit=$maxQueuedPackets packets"
        )
        return audioTrack
    }


    private fun bytesToMs(bytes: Int): Int {
        return bytes / BYTES_PER_FRAME * 1_000 / SAMPLE_RATE
    }

    private fun packetsToMs(packetCount: Int): Int {
        return packetCount * RAOP_PACKET_BYTES / BYTES_PER_FRAME * 1_000 / SAMPLE_RATE
    }

    private fun syncMsToBytes(syncMs: Int): Int {
        val frames = SAMPLE_RATE * syncMs / 1_000
        return frames * BYTES_PER_FRAME
    }

    private fun logDroppedPackets(count: Int) {
        droppedPackets += count.toLong()
        val now = SystemClock.elapsedRealtime()
        if (now - lastDropLogAtMs >= AUDIO_LOG_INTERVAL_MS) {
            lastDropLogAtMs = now
            Log.w(TAG, "audio queue pressure: dropped=$droppedPackets queued=${packets.size}")
        }
    }

    private fun logUnderrun() {
        val now = SystemClock.elapsedRealtime()
        if (lastAudioPacketWrittenAtMs == 0L || now - lastAudioPacketWrittenAtMs > RECENT_AUDIO_PACKET_WINDOW_MS) {
            return
        }
        onAudioUnderrun()
        if (now - lastUnderrunLogAtMs >= AUDIO_LOG_INTERVAL_MS) {
            lastUnderrunLogAtMs = now
            Log.w(TAG, "audio underrun: queue empty after playback started")
        }
    }

    companion object {
        private const val TAG = "Receiver-Audio"
        private const val DEFAULT_VOLUME = 1.0f
        private const val MIN_VOLUME = 0.0f
        private const val MAX_VOLUME = 1.0f
        private const val MAX_BUFFERED_PACKETS = 128
        private const val MAX_QUEUED_PACKETS = 96
        private const val PREBUFFER_PACKETS = 24
        private const val AUDIO_STABLE_MAX_QUEUED_PACKETS = 112
        private const val AUDIO_STABLE_PREBUFFER_PACKETS = 32
        private const val AUDIO_STABLE_BUFFER_MULTIPLIER = 10
        private const val PREBUFFER_WAIT_MS = 10L
        private const val AUDIO_BUFFER_MULTIPLIER = 8
        private const val AUDIO_LOG_INTERVAL_MS = 5_000L
        private const val RECENT_AUDIO_PACKET_WINDOW_MS = 2_000L
        private const val MIN_AUDIO_SYNC_MS = -500
        private const val MAX_AUDIO_SYNC_MS = 500
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_FRAME = 4
        private const val RAOP_PACKET_BYTES = 1920
        private const val SILENCE_CHUNK_BYTES = 4096
    }
}
