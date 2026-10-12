package net.ldmsys.tcprelay.connector

import net.ldmsys.tcprelay.tailcat.TailcatBridge
import java.net.Socket

class TailcatConnector : RelayConnector {
    override fun handleClient(clientSocket: Socket, destinationHost: String, destinationPort: Int): Int {
        val clientFd = SocketFdUtils.getFd(clientSocket)
        return TailcatBridge.nativeStartRelay(clientFd, destinationHost, destinationPort)
    }
}
