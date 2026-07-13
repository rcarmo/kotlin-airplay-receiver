package io.carmo.airplay.receiver

/**
 * Formal state machine for the AirPlay receiver.
 *
 * States progress in a defined order. Invalid transitions are logged and ignored.
 * The state machine is the single source of truth for receiver behavior.
 * All components observe this state rather than maintaining their own flags.
 */
enum class ReceiverState {
    /** First launch is showing onboarding before the receiver is advertised. */
    FIRST_RUN,

    /** Service not started or explicitly stopped. */
    STOPPED,

    /** Service starting up, servers not yet bound. */
    STARTING,

    /** Servers running, DNS-SD registered, waiting for a sender. */
    IDLE_ADVERTISING,

    /** A sender is being paired before media is accepted. */
    PAIRING,

    /** An audio-only RAOP session is active. No video surface needed. */
    AUDIO_ACTIVE,

    /** A video session setup has started; waiting for video packets or surface. */
    VIDEO_REQUESTED,

    /**
     * Video packets are arriving but the surface does not yet exist.
     * Packets are being buffered in the pre-surface buffer.
     */
    WAITING_FOR_SURFACE,

    /**
     * Surface is valid, decoder is initializing, SPS/PPS has been
     * replayed, waiting for the first decoded frame.
     */
    VIDEO_STARTING,

    /** Decoder has produced at least one rendered frame. Normal playback. */
    VIDEO_ACTIVE,

    /**
     * Video packets are arriving but the render count has not advanced
     * in the watchdog window. Decoder restart in progress.
     */
    VIDEO_STALLED,

    /** Teardown or socket close received; grace period running. */
    STOPPING_SESSION,

    /** A recoverable error occurred; attempting to return to IDLE_ADVERTISING. */
    ERROR_RECOVERABLE
}

/** Describes why a state transition occurred. Used for diagnostics. */
data class StateTransition(
    val from: ReceiverState,
    val to: ReceiverState,
    val reason: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class ReceiverSessionStats(
    val durationMs: Long = 0L,
    val videoFramesRendered: Int = 0,
    val decoderRestarts: Int = 0,
    val audioUnderruns: Int = 0,
    val preSurfacePacketsBuffered: Int = 0,
    val lastDisconnectReason: String? = null
)
