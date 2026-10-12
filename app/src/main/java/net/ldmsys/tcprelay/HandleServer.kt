package net.ldmsys.tcprelay

import android.util.Log
import net.ldmsys.tcprelay.connector.RelayConnectorFactory
import net.ldmsys.tcprelay.connector.RelayMode
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class HandleServer(
    private val destinationHost: String,
    private val destinationPort: Int,
    private val relayMode: RelayMode,
    private val serverSocket: ServerSocket,
    private val onConnectionOpened: () -> Unit,
    private val onConnectionClosed: () -> Unit
) : Thread() {

    private val executor = Executors.newFixedThreadPool(128)

    override fun run() {
        while (!isInterrupted) {
            try {
                val client = serverSocket.accept()
                client.tcpNoDelay = true
                executor.submit { handle(client) }
            } catch (e: Exception) {
                if (serverSocket.isClosed) {
                    break // Server is shutting down
                }
                Log.e(TAG, "Error accepting connection", e)
            }
        }
        executor.shutdown()
    }

    private fun handle(client: Socket) {
        try {
            onConnectionOpened()
            Log.i(TAG, "Relaying connection from ${client.remoteSocketAddress} to $destinationHost via ${relayMode.displayName}")
            val connector = RelayConnectorFactory.create(relayMode)
            val result = connector.handleClient(client, destinationHost, destinationPort)
            if (result != 0) {
                Log.e(TAG, "Native relay ended with status $result for ${client.remoteSocketAddress}")
            }
        } catch (e: Exception) {
            if (!serverSocket.isClosed) {
                Log.e(TAG, "Relay failed for ${client.remoteSocketAddress}", e)
            }
        } finally {
            try { client.close() } catch (ignored: Exception) {}
            onConnectionClosed()
        }
    }

    fun shutdown() {
        interrupt()
        try {
            serverSocket.close()
        } catch (e: Exception) {
            // Ignore
        }
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "HandleServer"
    }
}
