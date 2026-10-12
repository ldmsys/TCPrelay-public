package net.ldmsys.tcprelay

import net.ldmsys.tcprelay.connector.RelayMode
import java.io.InputStream
import java.io.OutputStream

object RelayUtils {
    /**
     * Copies data from an input stream to an output stream until EOF or error.
     * Does not close the streams.
     */
    fun transfer(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
        }
    }
}

object TailcatUtils {
    fun isTailcatAddress(address: String): Boolean {
        val trimmed = address.trim()
        return trimmed.startsWith("tc", ignoreCase = true) || trimmed.startsWith("tcom", ignoreCase = true)
    }
}

object RelayValidator {
    fun validateHost(host: String, mode: RelayMode = RelayMode.TCP): Boolean {
        if (host.isEmpty()) return false
        return when (mode) {
            RelayMode.TAILCAT -> TailcatUtils.isTailcatAddress(host)
            RelayMode.TCP -> true
        }
    }

    fun validateDestinationPort(port: Int?, mode: RelayMode = RelayMode.TCP): Boolean {
        return when (mode) {
            RelayMode.TAILCAT -> true // Port is embedded in Tailcat ephemeral address
            RelayMode.TCP -> port != null && port in 1..65535
        }
    }

    fun validateLocalPort(port: Int?): Boolean =
        port != null && port in 1024..65535
}
