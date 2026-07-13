package io.carmo.airplay.receiver

import android.content.Context

object SenderTrustStore {
    data class SenderDevice(
        val id: String,
        val displayName: String,
        val timestampMs: Long
    )

    fun trustedDevices(context: Context): List<SenderDevice> {
        return load(context, ReceiverPreferences.KEY_TRUSTED_DEVICES)
    }

    fun blockedDevices(context: Context): List<SenderDevice> {
        return load(context, ReceiverPreferences.KEY_BLOCKED_DEVICES)
    }

    fun trustDevice(context: Context, id: String, displayName: String) {
        saveDevice(context, ReceiverPreferences.KEY_TRUSTED_DEVICES, id, displayName)
        removeDevice(context, ReceiverPreferences.KEY_BLOCKED_DEVICES, id)
    }

    fun blockDevice(context: Context, id: String, displayName: String) {
        saveDevice(context, ReceiverPreferences.KEY_BLOCKED_DEVICES, id, displayName)
        removeDevice(context, ReceiverPreferences.KEY_TRUSTED_DEVICES, id)
    }

    fun forgetTrustedDevice(context: Context, id: String) {
        removeDevice(context, ReceiverPreferences.KEY_TRUSTED_DEVICES, id)
    }

    fun unblockDevice(context: Context, id: String) {
        removeDevice(context, ReceiverPreferences.KEY_BLOCKED_DEVICES, id)
    }

    fun clearTrustedDevices(context: Context) {
        clear(context, ReceiverPreferences.KEY_TRUSTED_DEVICES)
    }

    fun clearBlockedDevices(context: Context) {
        clear(context, ReceiverPreferences.KEY_BLOCKED_DEVICES)
    }

    private fun saveDevice(context: Context, key: String, id: String, displayName: String) {
        val sanitizedId = id.trim()
        if (sanitizedId.isBlank()) return
        val devices = load(context, key).filterNot { it.id == sanitizedId }.toMutableList()
        devices.add(
            SenderDevice(
                id = sanitizedId,
                displayName = displayName.trim().ifBlank { sanitizedId },
                timestampMs = System.currentTimeMillis()
            )
        )
        save(context, key, devices)
    }

    private fun removeDevice(context: Context, key: String, id: String) {
        save(context, key, load(context, key).filterNot { it.id == id })
    }

    private fun clear(context: Context, key: String) {
        ReceiverPreferences.prefs(context).edit().remove(key).apply()
    }

    private fun load(context: Context, key: String): List<SenderDevice> {
        return ReceiverPreferences.prefs(context)
            .getStringSet(key, emptySet())
            .orEmpty()
            .mapNotNull(::decode)
            .sortedByDescending { it.timestampMs }
    }

    private fun save(context: Context, key: String, devices: List<SenderDevice>) {
        ReceiverPreferences.prefs(context).edit()
            .putStringSet(key, devices.map(::encode).toSet())
            .apply()
    }

    private fun encode(device: SenderDevice): String {
        return listOf(device.id, device.displayName, device.timestampMs.toString())
            .joinToString(FIELD_SEPARATOR)
    }

    private fun decode(encoded: String): SenderDevice? {
        val parts = encoded.split(FIELD_SEPARATOR)
        if (parts.size != 3) return null
        return SenderDevice(
            id = parts[0],
            displayName = parts[1],
            timestampMs = parts[2].toLongOrNull() ?: 0L
        )
    }

    private const val FIELD_SEPARATOR = "\u001F"
}
