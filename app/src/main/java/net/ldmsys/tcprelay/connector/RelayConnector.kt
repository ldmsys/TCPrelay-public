package net.ldmsys.tcprelay.connector

import java.net.Socket

interface RelayConnector {
    fun handleClient(clientSocket: Socket, destinationHost: String, destinationPort: Int): Int
}
