package io.carmo.airplay.receiver

import android.util.Log
import java.io.IOException
import java.net.ServerSocket

class AirPlayServer {

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    val port: Int
        get() = serverSocket?.localPort ?: 0

    fun startServer() {
        if (serverSocket != null) {
            return
        }

        try {
            Log.d(TAG, "starting server")
            serverSocket = ServerSocket(0)
        } catch (e: IOException) {
            e.printStackTrace()
        }

        val socket = serverSocket ?: return
        serverThread = Thread({
            while (serverSocket != null && !Thread.currentThread().isInterrupted) {
                try {
                    socket.accept().use {
                        Log.d(TAG, "AirPlay: accepted connection from ${it.inetAddress}")
                    }
                } catch (e: IOException) {
                    if (serverSocket != null) {
                        Log.d(TAG, "AirPlay accept error (server still running): ${e.message}")
                    }
                }
            }
        }, "AirPlayServer").also { it.start() }
    }

    fun stopServer() {
        try {
            Log.d(TAG, "stopping server")
            serverSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            serverThread?.interrupt()
            serverSocket = null
            serverThread = null
        }
    }

    companion object {
        private const val TAG = "Receiver-AirPlay"
    }
}
