package net.ldmsys.tcprelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import net.ldmsys.tcprelay.connector.RelayMode
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class TcpRelayService : Service() {
    private val binder = LocalBinder()
    @Volatile
    private var serverThread: HandleServer? = null
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var serverRunning: Boolean = false
    private lateinit var notificationManager: NotificationManager
    private val activeConnections = AtomicInteger(0)

    @Volatile
    private var currentDestinationHost: String? = null
    @Volatile
    private var currentDestinationPort: Int = -1
    @Volatile
    private var currentLocalPort: Int = -1
    @Volatile
    private var currentRelayMode: RelayMode = RelayMode.TCP

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNotification()
        override fun onLost(network: Network) = updateNotification()
    }

    inner class LocalBinder : Binder() {
        fun getService(): TcpRelayService = this@TcpRelayService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RELAY -> {
                val destinationHost = intent.getStringExtra(EXTRA_DEST_HOST)
                val destinationPort = intent.getIntExtra(EXTRA_DEST_PORT, -1)
                val localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, -1)
                val relayModeName = intent.getStringExtra(EXTRA_RELAY_MODE)
                val relayMode = RelayMode.fromString(relayModeName)
                if (destinationHost != null && localPort != -1) {
                    startRelayServer(destinationHost, destinationPort, localPort, relayMode)
                }
            }
            ACTION_STOP_RELAY -> stopRelayServer()
        }
        return START_STICKY
    }

    private fun startRelayServer(destinationHost: String, destinationPort: Int, localPort: Int, relayMode: RelayMode) {
        if (serverRunning) return

        var error: String? = null
        try {
            serverSocket = ServerSocket(localPort)
            this.currentDestinationHost = destinationHost
            this.currentDestinationPort = destinationPort
            this.currentLocalPort = localPort
            this.currentRelayMode = relayMode

            serverThread = HandleServer(
                destinationHost, destinationPort, relayMode, serverSocket!!,
                onConnectionOpened = { 
                    activeConnections.incrementAndGet()
                    updateNotification()
                },
                onConnectionClosed = { 
                    activeConnections.decrementAndGet()
                    updateNotification()
                }
            ).apply { start() }
            serverRunning = true
            activeConnections.set(0)
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            serverRunning = false
            error = when(e) {
                is java.net.BindException -> "Port $localPort is already in use."
                else -> e.message ?: "An unknown error occurred while starting the server."
            }
            Log.e(TAG, "Failed to start server", e)
        } finally {
            sendUpdateBroadcast(error)
        }
    }

    private fun stopRelayServer() {
        if (!serverRunning) return

        serverThread?.shutdown()
        serverRunning = false
        serverThread = null
        serverSocket = null
        activeConnections.set(0)
        currentDestinationHost = null
        currentDestinationPort = -1
        currentLocalPort = -1
        currentRelayMode = RelayMode.TCP

        stopForeground(STOP_FOREGROUND_REMOVE)
        sendUpdateBroadcast()
    }

    fun isRelayServerRunning(): Boolean = serverRunning

    override fun onDestroy() {
        super.onDestroy()
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        if (serverRunning) {
            stopRelayServer()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "TCP Relay Service Channel", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val localAddresses = mutableListOf<String>()
            val publicAddresses = mutableListOf<String>()
            for (intf in interfaces) {
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        if (addr.isSiteLocalAddress) {
                            addr.hostAddress?.let { localAddresses.add(it) }
                        } else {
                            addr.hostAddress?.let { publicAddresses.add(it) }
                        }
                    }
                }
            }
            return localAddresses.firstOrNull() ?: publicAddresses.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IP address", e)
            return "IP lookup failed"
        }
    }

    private fun getAppIconBitmap(): Bitmap {
        val drawable = ContextCompat.getDrawable(this, R.mipmap.ic_launcher)!!
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun buildNotification(): Notification {
        val localIp = getLocalIpAddress() ?: "Unknown IP"
        val title = if (serverRunning) "TCP Relay Active (${currentRelayMode.displayName})" else "TCP Relay Inactive"
        val destDesc = if (currentRelayMode == RelayMode.TAILCAT) {
            currentDestinationHost
        } else {
            "$currentDestinationHost:$currentDestinationPort"
        }
        val text = if (serverRunning) {
            "$localIp:$currentLocalPort -> $destDesc (${activeConnections.get()} connections)"
        } else {
            "Relay is not running."
        }
        val largeIcon = getAppIconBitmap()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setLargeIcon(largeIcon)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        if (!serverRunning) return
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun sendUpdateBroadcast(error: String? = null) {
        val intent = Intent(ACTION_UPDATE_UI).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_RUNNING, serverRunning)
            error?.let { putExtra(EXTRA_ERROR, it) }
        }
        sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "TcpRelayService"
        const val ACTION_UPDATE_UI = "net.ldmsys.tcprelay.UPDATE_UI"
        const val ACTION_START_RELAY = "net.ldmsys.tcprelay.START_RELAY"
        const val ACTION_STOP_RELAY = "net.ldmsys.tcprelay.STOP_RELAY"
        const val EXTRA_IS_RUNNING = "net.ldmsys.tcprelay.IS_RUNNING"
        const val EXTRA_ERROR = "net.ldmsys.tcprelay.ERROR"
        const val EXTRA_DEST_HOST = "net.ldmsys.tcprelay.DEST_HOST"
        const val EXTRA_DEST_PORT = "net.ldmsys.tcprelay.DEST_PORT"
        const val EXTRA_LOCAL_PORT = "net.ldmsys.tcprelay.LOCAL_PORT"
        const val EXTRA_RELAY_MODE = "net.ldmsys.tcprelay.RELAY_MODE"
        private const val CHANNEL_ID = "TcpRelayServiceChannel"
        private const val NOTIFICATION_ID = 1
    }
}
