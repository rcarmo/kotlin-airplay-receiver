package io.carmo.airplay.receiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

class DNSNotify(
    private val context: Context,
    private val onStatusChanged: (String) -> Unit = {}
) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var airplayRegister: NsdRegister? = null
    private var raopRegister: NsdRegister? = null
    val deviceName: String get() = resolveDeviceName(context)
    private val macAddress: String get() = ReceiverIdentity.receiverId(context)
    private var airplayStatus: String = "AirPlay idle"
    private var raopStatus: String = "RAOP idle"

    fun registerAirplay(port: Int) {
        Log.d(TAG, "registerAirplay port = $port, macAddress = $macAddress")
        val serviceName = airplayServiceName()
        val attributes = mapOf(
            "deviceid" to macAddress,
            "features" to "0x5A7FFFF7,0x1E",
            "srcvers" to "220.68",
            "flags" to "0x4",
            "vv" to "2",
            "model" to "AppleTV2,1",
            "pw" to ReceiverPreferences.requiresPairingPassword(context).toString(),
            "rhd" to "5.6.0.0",
            "pk" to "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7",
            "pi" to "2e388006-13ba-4041-9a67-25dd4a43d536"
        )
        val serviceType = "_airplay._tcp."
        if (airplayRegister?.matches(serviceName, serviceType, port, attributes) == true) {
            return
        }
        airplayRegister?.stop()
        updateRegistrationStatus(AIRPLAY_LABEL, "AirPlay announcing on port $port")
        airplayRegister = NsdRegister(nsdManager, serviceName, serviceType, port, attributes, AIRPLAY_LABEL, ::updateRegistrationStatus)
    }

    fun registerRaop(port: Int) {
        Log.d(TAG, "registerRaop port = $port")
        val attributes = mapOf(
            "da" to "true",
            "et" to "0,3,5",
            "vv" to "2",
            "ft" to "0x5A7FFFF7,0x1E",
            "am" to "AppleTV2,1",
            "rhd" to "5.6.0.0",
            "pw" to ReceiverPreferences.requiresPairingPassword(context).toString(),
            "sv" to "false",
            "tp" to "UDP",
            "txtvers" to "1",
            "sf" to "0x4",
            "vs" to "220.68",
            "vn" to "65537",
            "pk" to "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7",
            "ch" to "2",
            // ALAC is required for Apple Music audio-only; AAC-ELD remains
            // available for clients that negotiate it explicitly.
            "cn" to "1,3",
            "md" to "0,1,2",
            "sr" to "44100",
            "ss" to "16"
        )
        val serviceName = raopServiceName()
        val serviceType = "_raop._tcp."
        if (raopRegister?.matches(serviceName, serviceType, port, attributes) == true) {
            return
        }
        raopRegister?.stop()
        updateRegistrationStatus(RAOP_LABEL, "RAOP announcing on port $port")
        raopRegister = NsdRegister(nsdManager, serviceName, serviceType, port, attributes, RAOP_LABEL, ::updateRegistrationStatus)
    }

    private fun raopServiceName(): String {
        val prefix = ReceiverIdentity.raopPrefix(context)
        val maxNameLength = (MAX_SERVICE_NAME_LENGTH - prefix.length).coerceAtLeast(1)
        val name = if (deviceName.length > maxNameLength) {
            deviceName.substring(0, maxNameLength)
        } else {
            deviceName
        }
        return "$prefix$name"
    }

    private fun airplayServiceName(): String {
        // The AirPlay service name must be distinct from the RAOP service name
        // and from other _airplay._tcp services on the same network.
        return if (deviceName.endsWith(AIRPLAY_SERVICE_SUFFIX, ignoreCase = true)) {
            deviceName.take(MAX_SERVICE_NAME_LENGTH)
        } else {
            val maxBaseNameLength = (MAX_SERVICE_NAME_LENGTH - AIRPLAY_SERVICE_SUFFIX.length).coerceAtLeast(1)
            "${deviceName.take(maxBaseNameLength).trimEnd()}$AIRPLAY_SERVICE_SUFFIX"
        }
    }

    private fun updateRegistrationStatus(label: String, status: String) {
        if (label == AIRPLAY_LABEL) {
            airplayStatus = status
        } else {
            raopStatus = status
        }
        onStatusChanged("$airplayStatus\n$raopStatus")
    }

    fun stop() {
        airplayRegister?.stop()
        airplayRegister = null
        raopRegister?.stop()
        raopRegister = null
    }

    private class NsdRegister(
        private val nsdManager: NsdManager,
        private val serviceName: String,
        private val serviceType: String,
        private val port: Int,
        private val attributes: Map<String, String>,
        private val label: String,
        private val onStatusChanged: (String, String) -> Unit
    ) : NsdManager.RegistrationListener {
        private val lock = ReentrantLock()
        private var isStopped = false
        private var isRegistered = false
        private var hasFailed = false

        init {
            val registrationServiceName = serviceName
            val registrationServiceType = serviceType
            val registrationPort = port
            val serviceInfo = NsdServiceInfo().apply {
                this.serviceName = registrationServiceName
                this.serviceType = registrationServiceType
                this.port = registrationPort
            }
            attributes.forEach { (key, value) ->
                serviceInfo.setAttribute(key, value)
            }
            lock.lock()
            try {
                nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, this)
            } catch (e: Throwable) {
                e.printStackTrace()
                onStatusChanged(label, "$label failed: ${e.javaClass.simpleName}")
            } finally {
                lock.unlock()
            }
        }

        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "$label registered: ${serviceInfo.serviceName}.${serviceInfo.serviceType}")
            var shouldUnregister = false
            lock.lock()
            try {
                isRegistered = true
                shouldUnregister = isStopped
            } finally {
                lock.unlock()
            }
            if (shouldUnregister) {
                stop()
                return
            }
            onStatusChanged(label, "$label announced as ${serviceInfo.serviceName}")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "$label registration failed: $errorCode")
            lock.lock()
            try {
                hasFailed = true
            } finally {
                lock.unlock()
            }
            if (!isStopped) {
                onStatusChanged(label, "$label failed: $errorCode")
            }
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "$label unregistered: ${serviceInfo.serviceName}.${serviceInfo.serviceType}")
            lock.lock()
            try {
                isRegistered = false
            } finally {
                lock.unlock()
            }
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "$label unregistration failed: $errorCode")
        }

        fun stop() {
            lock.lock()
            try {
                isStopped = true
                if (isRegistered) {
                    nsdManager.unregisterService(this)
                    isRegistered = false
                }
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "$label was already unregistered")
            } finally {
                lock.unlock()
            }
        }

        fun matches(serviceName: String, serviceType: String, port: Int, attributes: Map<String, String>): Boolean {
            lock.lock()
            try {
                return !isStopped &&
                    !hasFailed &&
                    this.serviceName == serviceName &&
                    this.serviceType == serviceType &&
                    this.port == port &&
                    this.attributes == attributes
            } finally {
                lock.unlock()
            }
        }
    }

    companion object {
        private const val TAG = "Receiver-DNS"
        private const val AIRPLAY_LABEL = "AirPlay"
        private const val RAOP_LABEL = "RAOP"
        private const val MAX_SERVICE_NAME_LENGTH = 63
        private const val AIRPLAY_SERVICE_SUFFIX = " AirPlay"

        fun suggestedDeviceName(context: Context): String = resolveDeviceName(context)

        private fun resolveDeviceName(context: Context): String {
            val customName = ReceiverPreferences.customDeviceName(context)
            if (customName.isUsableName()) {
                return customName!!.sanitizeServiceName()
            }

            val configuredName = Settings.Global.getString(context.contentResolver, "device_name")
            if (configuredName.isUsableName()) {
                return configuredName.sanitizeServiceName()
            }

            val bluetoothName = Settings.Secure.getString(context.contentResolver, "bluetooth_name")
            if (bluetoothName.isUsableName()) {
                return bluetoothName.sanitizeServiceName()
            }

            val manufacturer = Build.MANUFACTURER.cleanBuildValue()
            val model = Build.MODEL.cleanBuildValue()
            return if (model.lowercase(Locale.US).startsWith(manufacturer.lowercase(Locale.US))) {
                model.sanitizeServiceName()
            } else {
                "$manufacturer $model".sanitizeServiceName()
            }
        }

        private fun String?.isUsableName(): Boolean = !this.isNullOrBlank()

        private fun String?.cleanBuildValue(): String = if (this.isNullOrBlank()) {
            "Android"
        } else {
            trim()
        }

        private fun String.sanitizeServiceName(): String {
            val name = trim().replace(Regex("\\s+"), " ")
            return if (name.length > 63) name.substring(0, 63) else name
        }
    }
}
