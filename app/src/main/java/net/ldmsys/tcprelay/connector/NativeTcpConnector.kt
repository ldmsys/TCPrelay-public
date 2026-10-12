package net.ldmsys.tcprelay.connector

import java.net.Socket

class NativeTcpConnector : RelayConnector {
    companion object {
        init {
            try {
                System.loadLibrary("tcprelay")
            } catch (e: UnsatisfiedLinkError) {
                // Expected on local unit tests running on desktop JVM
            }
        }

        @JvmStatic
        private external fun nativeStartRelay(clientFd: Int, host: String, port: Int): Int
    }

    override fun handleClient(clientSocket: Socket, destinationHost: String, destinationPort: Int): Int {
        val clientFd = SocketFdUtils.getFd(clientSocket)
        return try {
            nativeStartRelay(clientFd, destinationHost, destinationPort)
        } catch (e: UnsatisfiedLinkError) {
            -2
        }
    }
}
