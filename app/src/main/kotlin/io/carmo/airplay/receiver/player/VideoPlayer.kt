package io.carmo.airplay.receiver.player

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.carmo.airplay.receiver.model.NALPacket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class VideoPlayer(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val onLatencySample: (Long) -> Unit = {},
    private val onFrameRendered: () -> Unit = {},
    private val onOutputSizeChanged: (Int, Int) -> Unit = { _, _ -> },
    private val enableFrameRateHint: Boolean = false,
    private val sourceFrameRate: Float = DEFAULT_FRAME_RATE
) : Thread("ReceiverVideoPlayer") {

    private val bufferInfo = MediaCodec.BufferInfo()
    private val packets = ArrayBlockingQueue<NALPacket>(MAX_BUFFERED_FRAMES)
    private var decoder: MediaCodec? = null
    @Volatile private var pendingCodecConfig: NALPacket? = null
    @Volatile private var isStopped = false
    @Volatile private var hasRenderedFirstFrame = false

    // Diagnostic flags — log critical lifecycle events exactly once per player.
    private var hasLoggedFirstRender = false
    private var hasLoggedDecoderMissing = false
    private var droppedFrames = 0L
    private var renderedFrames = 0L
    private var lastDropLogAtMs = 0L
    private var lastRenderLogAtMs = 0L
    private var lastOutputWidth = 0
    private var lastOutputHeight = 0

    /** True once the decoder has produced and rendered at least one frame. */
    fun hasRenderedFirstFrame(): Boolean = hasRenderedFirstFrame

    fun addPacket(packet: NALPacket) {
        if (packet.isCodecConfig) {
            val old = pendingCodecConfig
            pendingCodecConfig = packet
            old?.release()
            synchronized(packets) {
                offerPendingCodecConfigLocked()
            }
            return
        }

        synchronized(packets) {
            offerPendingCodecConfigLocked()
            trimBacklog()
            offerPendingCodecConfigLocked()
            if (!packets.offer(packet)) {
                packet.release()
            }
        }
    }

    private fun offerPendingCodecConfigLocked() {
        val config = pendingCodecConfig ?: return
        if (packets.offer(config)) {
            pendingCodecConfig = null
        } else {
            Log.w(TAG, "video queue full; codec config pending until SPS/PPS replay")
        }
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        initDecoder()
        while (!isStopped) {
            try {
                packets.poll(250, TimeUnit.MILLISECONDS)?.let(::decode)
            } catch (e: InterruptedException) {
                if (isStopped) {
                    break
                }
            }
        }
        releaseDecoder()
    }

    fun stopDecode() {
        isStopped = true
        interrupt()
        drainPackets()
    }

    private fun initDecoder() {
        try {
            val videoFormat = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
            val codec = MediaCodec.createDecoderByType(MIME_TYPE)

            val decoderInfo = codec.codecInfo
            Log.i(TAG, "decoder selected = ${decoderInfo.name}, size = ${width}x${height}")

            if (decoderSupportsAdaptivePlayback(decoderInfo, MIME_TYPE)) {
                videoFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, width)
                videoFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, height)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                videoFormat.setInteger(MediaFormat.KEY_PRIORITY, 0)
                videoFormat.setFloat(MediaFormat.KEY_OPERATING_RATE, DEFAULT_FRAME_RATE)
            }

            codec.configure(videoFormat, surface, null, 0)
            applyFrameRateHint()
            codec.setVideoScalingMode(chooseScalingMode(width, height, width, height))
            codec.start()
            decoder = codec
        } catch (e: Exception) {
            Log.e(TAG, "failed to initialise decoder for ${width}x${height}", e)
            releaseDecoder()
        }
    }

    private fun applyFrameRateHint() {
        if (!enableFrameRateHint || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        try {
            surface.setFrameRate(
                sourceFrameRate.takeIf { it in MIN_FRAME_RATE..MAX_FRAME_RATE } ?: DEFAULT_FRAME_RATE,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
            )
            Log.i(TAG, "surface frame-rate hint set to ${sourceFrameRate}fps")
        } catch (e: Throwable) {
            Log.w(TAG, "surface frame-rate hint failed", e)
        }
    }

    private fun decode(packet: NALPacket) {
        var codec = decoder
        if (codec == null) {
            initDecoder()
            codec = decoder
        }
        if (codec == null) {
            if (!hasLoggedDecoderMissing) {
                Log.w(TAG, "decoder unavailable; dropping packet (size=${packet.size}, config=${packet.isCodecConfig})")
                hasLoggedDecoderMissing = true
            }
            packet.release()
            return
        }

        try {
            if (drainOutput(codec, 0L)) {
                signalFrameRendered()
            }

            val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_USEC)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                if (inputBuffer == null || packet.size > inputBuffer.capacity()) {
                    if (DEBUG_FRAMES) {
                        Log.d(TAG, "dropping oversized NAL: ${packet.size}")
                    }
                    codec.queueInputBuffer(inputBufferIndex, 0, 0, packet.pts, 0)
                    return
                }

                inputBuffer.clear()
                packet.data.position(0)
                packet.data.limit(packet.size)
                inputBuffer.put(packet.data)
                codec.queueInputBuffer(inputBufferIndex, 0, packet.size, packet.presentationTimeUs, packet.codecFlags)
            } else if (DEBUG_FRAMES) {
                Log.d(TAG, "dequeueInputBuffer failed")
            }

            if (!packet.isCodecConfig && drainOutput(codec, TIMEOUT_USEC)) {
                signalFrameRendered()
                onLatencySample(SystemClock.elapsedRealtime() - packet.receivedAtMs)
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "decode error", e)
        } catch (e: Exception) {
            Log.e(TAG, "decode error", e)
        } finally {
            packet.release()
        }
    }

    private fun signalFrameRendered() {
        if (!hasRenderedFirstFrame) {
            hasRenderedFirstFrame = true
        }
        renderedFrames += 1
        if (!hasLoggedFirstRender) {
            Log.i(TAG, "first frame rendered to surface")
            hasLoggedFirstRender = true
            lastRenderLogAtMs = SystemClock.elapsedRealtime()
        } else {
            logRenderProgress()
        }
        onFrameRendered()
    }

    private fun logRenderProgress() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRenderLogAtMs >= RENDER_LOG_INTERVAL_MS) {
            lastRenderLogAtMs = now
            Log.i(TAG, "video rendering: frames=$renderedFrames queued=${packets.size}")
        }
    }

    private fun drainOutput(codec: MediaCodec, timeoutUsec: Long): Boolean {
        var pendingRenderIndex = -1

        outputLoop@ while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUsec)
            when {
                outputBufferIndex >= 0 -> {
                    if (pendingRenderIndex >= 0) {
                        codec.releaseOutputBuffer(pendingRenderIndex, false)
                    }
                    pendingRenderIndex = outputBufferIndex
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    handleOutputFormatChanged(codec.outputFormat)
                    if (DEBUG_FRAMES) {
                        Log.d(TAG, "output format changed: ${codec.outputFormat}")
                    }
                }
                else -> {
                    break@outputLoop
                }
            }
        }

        if (pendingRenderIndex >= 0) {
            codec.releaseOutputBuffer(pendingRenderIndex, true)
            return true
        }
        return false
    }

    private fun handleOutputFormatChanged(format: MediaFormat) {
        val outputWidth = outputDimension(
            format = format,
            sizeKey = MediaFormat.KEY_WIDTH,
            cropStartKey = FORMAT_KEY_CROP_LEFT,
            cropEndKey = FORMAT_KEY_CROP_RIGHT
        )
        val outputHeight = outputDimension(
            format = format,
            sizeKey = MediaFormat.KEY_HEIGHT,
            cropStartKey = FORMAT_KEY_CROP_TOP,
            cropEndKey = FORMAT_KEY_CROP_BOTTOM
        )
        if (outputWidth <= 0 || outputHeight <= 0) {
            return
        }
        if (outputWidth == lastOutputWidth && outputHeight == lastOutputHeight) {
            return
        }
        lastOutputWidth = outputWidth
        lastOutputHeight = outputHeight
        Log.i(TAG, "decoder output size = ${outputWidth}x${outputHeight}")
        onOutputSizeChanged(outputWidth, outputHeight)
    }

    private fun outputDimension(
        format: MediaFormat,
        sizeKey: String,
        cropStartKey: String,
        cropEndKey: String
    ): Int {
        if (format.containsKey(cropStartKey) && format.containsKey(cropEndKey)) {
            val start = format.getInteger(cropStartKey)
            val end = format.getInteger(cropEndKey)
            if (end >= start) {
                return end - start + 1
            }
        }
        return if (format.containsKey(sizeKey)) format.getInteger(sizeKey) else 0
    }

    private fun trimBacklog() {
        while (packets.size >= MAX_QUEUED_FRAMES) {
            val iterator = packets.iterator()
            while (iterator.hasNext()) {
                val packet = iterator.next()
                if (packet.isCodecConfig) {
                    continue
                }
                iterator.remove()
                packet.release()
                logDroppedFrame()
                break
            }
            if (packets.size >= MAX_QUEUED_FRAMES && packets.all { it.isCodecConfig }) {
                packets.poll()?.release() ?: return
                logDroppedFrame()
            }
        }
    }

    private fun logDroppedFrame() {
        droppedFrames += 1
        val now = SystemClock.elapsedRealtime()
        if (now - lastDropLogAtMs >= DROP_LOG_INTERVAL_MS) {
            lastDropLogAtMs = now
            Log.w(TAG, "video queue pressure: dropped=$droppedFrames queued=${packets.size}")
        }
    }

    private fun drainPackets() {
        synchronized(packets) {
            while (true) {
                packets.poll()?.release() ?: break
            }
            pendingCodecConfig?.release()
            pendingCodecConfig = null
        }
    }

    private fun releaseDecoder() {
        val codec = decoder ?: return
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        try {
            codec.release()
        } catch (_: Exception) {
        }
        decoder = null
    }

    private fun chooseScalingMode(
        surfaceWidth: Int,
        surfaceHeight: Int,
        videoWidth: Int,
        videoHeight: Int
    ): Int {
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
        val surfaceRatio = surfaceWidth.toFloat() / surfaceHeight
        val videoRatio = videoWidth.toFloat() / videoHeight
        val ratioDiff = kotlin.math.abs(surfaceRatio - videoRatio)
        return if (surfaceWidth >= 1280 && ratioDiff < 0.05f) {
            MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        } else {
            MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
    }

    companion object {
        private const val TAG = "Receiver-Video"
        private const val DEBUG_FRAMES = false
        private const val MAX_BUFFERED_FRAMES = 96
        private const val MAX_QUEUED_FRAMES = 72
        private const val DROP_LOG_INTERVAL_MS = 2_000L
        private const val RENDER_LOG_INTERVAL_MS = 5_000L
        private const val MIME_TYPE = "video/avc"
        private const val DEFAULT_FRAME_RATE = 60.0f
        private const val MIN_FRAME_RATE = 23.0f
        private const val MAX_FRAME_RATE = 120.0f
        private const val TIMEOUT_USEC = 1000L
        private const val FORMAT_KEY_CROP_LEFT = "crop-left"
        private const val FORMAT_KEY_CROP_RIGHT = "crop-right"
        private const val FORMAT_KEY_CROP_TOP = "crop-top"
        private const val FORMAT_KEY_CROP_BOTTOM = "crop-bottom"

        private fun decoderSupportsAdaptivePlayback(decoderInfo: MediaCodecInfo, mimeType: String): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                try {
                    if (decoderInfo.getCapabilitiesForType(mimeType)
                            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)
                    ) {
                        Log.i(TAG, "adaptive playback supported")
                        return true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            Log.w(TAG, "adaptive playback not supported")
            return false
        }
    }
}
