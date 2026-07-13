package io.carmo.airplay.receiver

import android.content.Context
import java.util.UUID

/**
 * Stable receiver identity that survives reboots and app updates.
 *
 * On first launch a random 48-bit ID is generated and stored in SharedPreferences.
 * It never changes unless the user explicitly resets it.
 *
 * This is critical: Apple sender devices cache receiver identities. A changing
 * identity causes discovery confusion, duplicate entries in the AirPlay picker,
 * and failed reconnects.
 */
object ReceiverIdentity {

    private const val PREFS_NAME = "receiver_identity"
    private const val KEY_RECEIVER_ID = "receiver_id"

    /**
     * Returns the stable receiver ID as a colon-separated hex string,
     * e.g. "A1:B2:C3:D4:E5:F6". Generates and persists one on first call.
     */
    fun receiverId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_RECEIVER_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val generated = generateReceiverId()
        prefs.edit().putString(KEY_RECEIVER_ID, generated).apply()
        return generated
    }

    /**
     * Resets the receiver identity. The next call to receiverId() will generate
     * a new one. Use only when the user explicitly requests a reset in settings.
     * After calling this, the caller must re-register DNS-SD services.
     */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_RECEIVER_ID)
            .apply()
    }

    /**
     * The RAOP service name prefix. Format: "AABBCCDDEEFF@".
     * This is what appears before the device name in the _raop._tcp advertisement.
     */
    fun raopPrefix(context: Context): String {
        return receiverId(context).replace(":", "") + "@"
    }

    private fun generateReceiverId(): String {
        val uuid = UUID.randomUUID()
        val msb = uuid.mostSignificantBits
        return buildString {
            for (i in 5 downTo 0) {
                if (i < 5) append(":")
                append("%02X".format((msb shr (i * 8)) and 0xFF))
            }
        }
    }
}
