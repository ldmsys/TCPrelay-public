package net.ldmsys.tcprelay.connector

object RelayConnectorFactory {
    fun create(mode: RelayMode): RelayConnector {
        return when (mode) {
            RelayMode.TCP -> NativeTcpConnector()
            RelayMode.TAILCAT -> TailcatConnector()
        }
    }
}
