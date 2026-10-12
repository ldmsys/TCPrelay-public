package net.ldmsys.tcprelay

import net.ldmsys.tcprelay.connector.RelayConnectorFactory
import net.ldmsys.tcprelay.connector.RelayMode
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RelayLogicTest {

    @Test
    fun `test transfer copies all data correctly`() {
        val testData = "Hello, TCP Relay!".toByteArray()
        val inputStream = ByteArrayInputStream(testData)
        val outputStream = ByteArrayOutputStream()

        RelayUtils.transfer(inputStream, outputStream)

        assertArrayEquals(testData, outputStream.toByteArray())
    }

    @Test
    fun `test transfer handles empty stream`() {
        val testData = ByteArray(0)
        val inputStream = ByteArrayInputStream(testData)
        val outputStream = ByteArrayOutputStream()

        RelayUtils.transfer(inputStream, outputStream)

        assertEquals(0, outputStream.size())
    }

    @Test
    fun `test transfer handles large data`() {
        val size = 1024 * 64 // 64KB
        val testData = ByteArray(size) { it.toByte() }
        val inputStream = ByteArrayInputStream(testData)
        val outputStream = ByteArrayOutputStream()

        RelayUtils.transfer(inputStream, outputStream)

        assertArrayEquals(testData, outputStream.toByteArray())
    }

    @Test
    fun `test tailcat address detection`() {
        assertTrue(TailcatUtils.isTailcatAddress("tc0123456789abcdef"))
        assertTrue(TailcatUtils.isTailcatAddress("tcom_abcdef0123456789"))
        assertTrue(TailcatUtils.isTailcatAddress("TCabcdef"))
        assertFalse(TailcatUtils.isTailcatAddress("example.com"))
        assertFalse(TailcatUtils.isTailcatAddress("192.168.1.1"))
        assertFalse(TailcatUtils.isTailcatAddress(""))
    }

    @Test
    fun `test host validation for tcp and tailcat modes`() {
        // TCP mode
        assertTrue(RelayValidator.validateHost("localhost", RelayMode.TCP))
        assertTrue(RelayValidator.validateHost("127.0.0.1", RelayMode.TCP))
        assertFalse(RelayValidator.validateHost("", RelayMode.TCP))

        // Tailcat mode
        assertTrue(RelayValidator.validateHost("tc0123456789", RelayMode.TAILCAT))
        assertFalse(RelayValidator.validateHost("google.com", RelayMode.TAILCAT))
        assertFalse(RelayValidator.validateHost("", RelayMode.TAILCAT))
    }

    @Test
    fun `test destination port validation for tcp and tailcat modes`() {
        // TCP mode
        assertTrue(RelayValidator.validateDestinationPort(443, RelayMode.TCP))
        assertFalse(RelayValidator.validateDestinationPort(null, RelayMode.TCP))
        assertFalse(RelayValidator.validateDestinationPort(0, RelayMode.TCP))

        // Tailcat mode (port is embedded, always valid)
        assertTrue(RelayValidator.validateDestinationPort(null, RelayMode.TAILCAT))
        assertTrue(RelayValidator.validateDestinationPort(443, RelayMode.TAILCAT))
    }

    @Test
    fun `test local port validation`() {
        assertTrue(RelayValidator.validateLocalPort(1024))
        assertTrue(RelayValidator.validateLocalPort(8080))
        assertTrue(RelayValidator.validateLocalPort(65535))
        
        assertFalse(RelayValidator.validateLocalPort(null))
        assertFalse(RelayValidator.validateLocalPort(80))
        assertFalse(RelayValidator.validateLocalPort(1023))
        assertFalse(RelayValidator.validateLocalPort(65536))
    }

    @Test
    fun `test relay connector factory creation`() {
        val tcpConnector = RelayConnectorFactory.create(RelayMode.TCP)
        assertNotNull(tcpConnector)

        val tailcatConnector = RelayConnectorFactory.create(RelayMode.TAILCAT)
        assertNotNull(tailcatConnector)
    }
}
