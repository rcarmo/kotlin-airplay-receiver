package io.carmo.airplay.receiver

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

object ReceiverPreferences {
    const val PREFERENCES_NAME = "receiver"

    const val KEY_VIDEO_MODE = "video_mode_v2"
    const val VIDEO_MODE_AUTO = "auto"
    const val VIDEO_MODE_720P = "720p"
    const val VIDEO_MODE_1080P = "1080p"

    const val KEY_QUALITY_PROFILE = "quality_profile"
    const val QUALITY_AUTO = "auto"
    const val QUALITY_LOW_LATENCY = "low_latency"
    const val QUALITY_BALANCED = "balanced"
    const val QUALITY_BEST = "best_quality"
    const val QUALITY_COMPATIBILITY = "compatibility"
    const val QUALITY_AUDIO_STABLE = "audio_stable"

    const val KEY_SCREEN_FIT = "screen_fit"
    const val SCREEN_FIT_FIT = "fit"
    const val SCREEN_FIT_FILL = "fill"
    const val SCREEN_FIT_STRETCH = "stretch"

    const val KEY_AUDIO_SYNC_MS = "audio_sync_ms"
    const val MIN_AUDIO_SYNC_MS = -500
    const val MAX_AUDIO_SYNC_MS = 500

    const val KEY_WAKE_MODE = "wake_mode"
    const val WAKE_MODE_DEFAULT = "default"
    const val WAKE_MODE_ALWAYS = "always_awake"
    const val WAKE_MODE_ACTIVITY = "wake_on_activity"

    const val KEY_START_ON_BOOT = "start_on_boot"
    const val KEY_AFTER_DISCONNECT = "after_disconnect"
    const val AFTER_DISCONNECT_WAITING = "waiting"
    const val AFTER_DISCONNECT_HOME = "home"

    const val KEY_AUTO_VIDEO_TAKEOVER = "auto_video_takeover"
    const val KEY_AUDIO_ONLY_DISPLAY = "audio_only_display"
    const val AUDIO_ONLY_BACKGROUND = "background"
    const val AUDIO_ONLY_STATUS = "status"
    const val AUDIO_ONLY_VISUALIZER = "visualizer"
    const val AUDIO_ONLY_VISUALIZER_ONLY = "visualizer_only"
    const val AUDIO_ONLY_MINIMAL = "minimal"

    const val KEY_DIAGNOSTICS_LEVEL = "diagnostics_level"
    const val DIAGNOSTICS_OFF = "off"
    const val DIAGNOSTICS_BASIC = "basic"
    const val KEY_VERBOSE_LOGGING = "verbose_logging"

    const val KEY_CUSTOM_DEVICE_NAME = "custom_device_name"
    const val KEY_FIRST_RUN_COMPLETE = "first_run_complete"
    const val KEY_IDLE_CLOCK_ENABLED = "idle_clock_enabled"
    const val KEY_IDLE_DIM_MINUTES = "idle_dim_minutes"
    const val KEY_REDUCE_MOTION = "reduce_motion"
    const val KEY_FRAME_RATE_MATCHING = "frame_rate_matching"
    const val KEY_VISUALIZER_ENABLED = "visualizer_enabled"
    const val KEY_GUEST_MODE = "guest_mode"
    const val KEY_TAKEOVER_PROTECTION = "takeover_protection"
    const val TAKEOVER_REJECT = "reject"
    const val TAKEOVER_ASK = "ask"
    const val TAKEOVER_ALLOW = "allow"

    const val KEY_SECURITY_MODE = "security_mode"
    const val SECURITY_PIN_NEW_DEVICES = "pin_new_devices"
    const val SECURITY_PIN_EVERY_SESSION = "pin_every_session"
    const val SECURITY_OPEN = "open"
    const val SECURITY_TRUSTED_ONLY = "trusted_only"
    const val KEY_TRUSTED_DEVICES = "trusted_devices"
    const val KEY_BLOCKED_DEVICES = "blocked_devices"

    data class VideoSize(
        val width: Int,
        val height: Int,
        val modeLabel: String,
        val effectiveLabel: String
    )

    fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    fun selectedVideoSize(context: Context): VideoSize {
        return when (qualityProfile(context)) {
            QUALITY_LOW_LATENCY -> VideoSize(1280, 720, "Low Latency", "720p")
            QUALITY_COMPATIBILITY -> VideoSize(1280, 720, "Compatibility", "720p")
            QUALITY_BALANCED -> VideoSize(1920, 1080, "Balanced", "1080p")
            QUALITY_BEST -> VideoSize(1920, 1080, "Best Quality", "1080p")
            QUALITY_AUDIO_STABLE -> VideoSize(1280, 720, "Audio Stable", "720p")
            else -> detectOptimalResolution(context)
        }
    }

    fun videoModeSummary(context: Context): String {
        return selectedVideoSize(context).let { size ->
            if (size.modeLabel == "Auto") {
                "Auto - ${size.effectiveLabel}"
            } else {
                size.effectiveLabel
            }
        }
    }

    fun qualityProfile(context: Context): String {
        val preferences = prefs(context)
        val stored = preferences.getString(KEY_QUALITY_PROFILE, null)
        if (!stored.isNullOrBlank()) {
            return stored
        }
        return when (preferences.getString(KEY_VIDEO_MODE, VIDEO_MODE_AUTO)) {
            VIDEO_MODE_720P -> QUALITY_LOW_LATENCY
            VIDEO_MODE_1080P -> QUALITY_BEST
            else -> QUALITY_AUTO
        }
    }

    fun qualityProfileSummary(context: Context): String {
        val size = selectedVideoSize(context)
        return when (qualityProfile(context)) {
            QUALITY_LOW_LATENCY -> "Low Latency - 720p"
            QUALITY_BALANCED -> "Balanced - 1080p"
            QUALITY_BEST -> "Best Quality - 1080p"
            QUALITY_COMPATIBILITY -> "Compatibility - 720p"
            QUALITY_AUDIO_STABLE -> "Audio Stable - 720p"
            else -> "Auto - ${size.effectiveLabel}"
        }
    }

    fun wakeMode(context: Context): String {
        return prefs(context).getString(KEY_WAKE_MODE, WAKE_MODE_ACTIVITY) ?: WAKE_MODE_ACTIVITY
    }

    fun wakeModeSummary(context: Context): String {
        return when (wakeMode(context)) {
            WAKE_MODE_DEFAULT -> "OS default"
            WAKE_MODE_ALWAYS -> "Always on"
            else -> "Wake on activity"
        }
    }

    fun afterDisconnect(context: Context): String {
        return prefs(context).getString(KEY_AFTER_DISCONNECT, AFTER_DISCONNECT_WAITING)
            ?: AFTER_DISCONNECT_WAITING
    }

    fun afterDisconnectSummary(context: Context): String {
        return if (afterDisconnect(context) == AFTER_DISCONNECT_HOME) {
            "Exit to home"
        } else {
            "Return to ready"
        }
    }

    fun automaticVideoTakeover(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_VIDEO_TAKEOVER, true)
    }

    fun audioOnlyDisplay(context: Context): String {
        return prefs(context).getString(KEY_AUDIO_ONLY_DISPLAY, AUDIO_ONLY_VISUALIZER)
            ?: AUDIO_ONLY_VISUALIZER
    }

    fun audioOnlyDisplaySummary(context: Context): String {
        return when (audioOnlyDisplay(context)) {
            AUDIO_ONLY_STATUS -> "Metadata"
            AUDIO_ONLY_VISUALIZER -> "Metadata + visualizer"
            AUDIO_ONLY_VISUALIZER_ONLY -> "Visualizer only"
            AUDIO_ONLY_MINIMAL -> "Minimal black"
            else -> "Clock + visualizer"
        }
    }

    fun diagnosticsLevel(context: Context): String {
        return prefs(context).getString(KEY_DIAGNOSTICS_LEVEL, DIAGNOSTICS_OFF) ?: DIAGNOSTICS_OFF
    }

    fun diagnosticsSummary(context: Context): String {
        return if (diagnosticsLevel(context) == DIAGNOSTICS_BASIC) "Basic" else "Off"
    }

    fun customDeviceName(context: Context): String? {
        return prefs(context).getString(KEY_CUSTOM_DEVICE_NAME, null)?.trim()?.takeIf { it.isNotBlank() }
    }

    fun screenFit(context: Context): String {
        return prefs(context).getString(KEY_SCREEN_FIT, SCREEN_FIT_FIT) ?: SCREEN_FIT_FIT
    }

    fun screenFitSummary(context: Context): String {
        return when (screenFit(context)) {
            SCREEN_FIT_FILL -> "Fill - crop edges"
            SCREEN_FIT_STRETCH -> "Stretch"
            else -> "Fit - no crop"
        }
    }

    fun audioSyncMs(context: Context): Int {
        return prefs(context).getInt(KEY_AUDIO_SYNC_MS, 0).coerceIn(MIN_AUDIO_SYNC_MS, MAX_AUDIO_SYNC_MS)
    }

    fun audioSyncSummary(context: Context): String {
        val value = audioSyncMs(context)
        return when {
            value > 0 -> "+${value}ms"
            value < 0 -> "${value}ms"
            else -> "0ms"
        }
    }

    fun securityMode(context: Context): String {
        return prefs(context).getString(KEY_SECURITY_MODE, SECURITY_PIN_NEW_DEVICES)
            ?: SECURITY_PIN_NEW_DEVICES
    }

    fun securityModeSummary(context: Context): String {
        return when (securityMode(context)) {
            SECURITY_OPEN -> "Open - no pairing"
            SECURITY_PIN_EVERY_SESSION -> "PIN every session - compatibility mode"
            SECURITY_TRUSTED_ONLY -> "Trusted only - compatibility mode"
            else -> "PIN for new devices - compatibility mode"
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun requiresPairingPassword(context: Context): Boolean {
        // Native PIN verification is not wired yet. Keep DNS-SD compatible with
        // the working receiver path until the RAOP/AirPlay pairing exchange can
        // actually enforce the selected security mode.
        return false
    }

    fun firstRunComplete(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_FIRST_RUN_COMPLETE, false)
    }

    fun idleClockEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_IDLE_CLOCK_ENABLED, true)
    }

    fun idleDimMinutes(context: Context): Int {
        return prefs(context).getInt(KEY_IDLE_DIM_MINUTES, 10).coerceIn(0, 60)
    }

    fun idleDimSummary(context: Context): String {
        val minutes = idleDimMinutes(context)
        return if (minutes <= 0) "Off" else "After ${minutes}m"
    }

    fun reduceMotion(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_REDUCE_MOTION, false)
    }

    fun frameRateMatching(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_FRAME_RATE_MATCHING, true)
    }

    fun visualizerEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_VISUALIZER_ENABLED, true)
    }

    fun verboseLogging(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_VERBOSE_LOGGING, false)
    }

    fun guestMode(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_GUEST_MODE, false)
    }

    fun takeoverProtection(context: Context): String {
        return prefs(context).getString(KEY_TAKEOVER_PROTECTION, TAKEOVER_REJECT)
            ?: TAKEOVER_REJECT
    }

    fun takeoverProtectionSummary(context: Context): String {
        return when (takeoverProtection(context)) {
            TAKEOVER_ASK -> "Ask on TV"
            TAKEOVER_ALLOW -> "Allow takeover"
            else -> "Reject while active"
        }
    }

    @Suppress("DEPRECATION")
    fun detectOptimalResolution(context: Context): VideoSize {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val display = windowManager?.defaultDisplay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && display != null) {
            val hasFullHd = display.supportedModes.any {
                it.physicalWidth >= 1920 && it.physicalHeight >= 1080
            }
            if (hasFullHd) {
                return VideoSize(1920, 1080, "Auto", "1080p")
            }
        }

        val metrics = DisplayMetrics()
        display?.getMetrics(metrics)
        return if (metrics.widthPixels >= 1920 || metrics.heightPixels >= 1080) {
            VideoSize(1920, 1080, "Auto", "1080p")
        } else {
            VideoSize(1280, 720, "Auto", "720p")
        }
    }
}
