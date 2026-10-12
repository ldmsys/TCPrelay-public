package net.ldmsys.tcprelay.connector

import net.ldmsys.tcprelay.RelayUtils
import java.net.Socket

class DirectTcpConnector : RelayConnector {
    override fun handleClient(clientSocket: Socket, destinationHost: String, destinationPort: Int): Int {
        var destinationSocket: Socket? = null
        try {
            destinationSocket = Socket(destinationHost, destinationPort).apply {
                tcpNoDelay = true
            }
            clientSocket.tcpNoDelay = true

            val dest = destinationSocket
            val client = clientSocket

            val thread = Thread {
                try {
                    RelayUtils.transfer(dest.getInputStream(), client.getOutputStream())
                } catch (e: Exception) {
                    // Expected when connection closes
                } finally {
                    try { client.close() } catch (ignored: Exception) {}
                }
            }
            thread.start()

            RelayUtils.transfer(client.getInputStream(), dest.getOutputStream())
            thread.join(500)
            return 0
        } catch (e: Exception) {
            return -2
        } finally {
            try { destinationSocket?.close() } catch (ignored: Exception) {}
        }
    }
}
