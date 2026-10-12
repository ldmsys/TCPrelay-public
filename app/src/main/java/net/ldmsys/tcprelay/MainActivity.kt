package net.ldmsys.tcprelay

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import net.ldmsys.tcprelay.connector.RelayMode
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {
    private lateinit var protocolRadioGroup: RadioGroup
    private lateinit var radioTcp: RadioButton
    private lateinit var radioTailcat: RadioButton
    private lateinit var destinationHostInput: EditText
    private lateinit var destinationPortInput: EditText
    private lateinit var localPortInput: EditText
    private lateinit var startServerButton: Button
    private lateinit var rememberDetailsCheckBox: CheckBox
    private lateinit var ipAddressTextView: TextView

    private var tcpRelayService: TcpRelayService? = null
    private var isServiceBound = false
    private var currentRelayMode: RelayMode = RelayMode.TCP
    private var isAutoSwitchingMode = false

    private val requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startRelay()
        } else {
            val deniedPermissions = permissions.filter { !it.value }.keys.toList()
            showPermissionDeniedDialog(deniedPermissions)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            tcpRelayService = (binder as TcpRelayService.LocalBinder).getService()
            isServiceBound = true
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
            tcpRelayService = null
            updateUI()
        }
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TcpRelayService.ACTION_UPDATE_UI) {
                intent.getStringExtra(TcpRelayService.EXTRA_ERROR)?.let {
                    Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                }
                updateUI()
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateIpAddress()
        override fun onLost(network: Network) = updateIpAddress()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        protocolRadioGroup = findViewById(R.id.protocolRadioGroup)
        radioTcp = findViewById(R.id.radioTcp)
        radioTailcat = findViewById(R.id.radioTailcat)
        destinationHostInput = findViewById(R.id.destinationHost)
        destinationPortInput = findViewById(R.id.destinationPort)
        localPortInput = findViewById(R.id.localPort)
        startServerButton = findViewById(R.id.startServerButton)
        rememberDetailsCheckBox = findViewById(R.id.rememberDetailsCheckBox)
        ipAddressTextView = findViewById(R.id.ipAddressTextView)

        protocolRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (isAutoSwitchingMode) return@setOnCheckedChangeListener
            currentRelayMode = if (checkedId == R.id.radioTailcat) RelayMode.TAILCAT else RelayMode.TCP
            updateModeUI()
        }

        destinationHostInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                if (TailcatUtils.isTailcatAddress(text) && currentRelayMode != RelayMode.TAILCAT) {
                    isAutoSwitchingMode = true
                    radioTailcat.isChecked = true
                    currentRelayMode = RelayMode.TAILCAT
                    isAutoSwitchingMode = false
                    updateModeUI()
                }
            }
        })

        loadSettings()

        startServerButton.setOnClickListener {
            if (tcpRelayService?.isRelayServerRunning() == true) {
                stopRelayService()
            } else {
                startRelayWithPermissionCheck()
            }
            Handler(Looper.getMainLooper()).postDelayed({ updateUI() }, 250)
        }

        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        ContextCompat.registerReceiver(this, updateReceiver, IntentFilter(TcpRelayService.ACTION_UPDATE_UI), ContextCompat.RECEIVER_NOT_EXPORTED)
        Intent(this, TcpRelayService::class.java).also { startService(it) }
        updateIpAddress()
    }

    private fun updateModeUI() {
        if (currentRelayMode == RelayMode.TAILCAT) {
            destinationPortInput.visibility = View.GONE
            destinationPortInput.setText("")
            destinationHostInput.hint = "Tailcat Ephemeral Address (e.g. tc...)"
        } else {
            destinationPortInput.visibility = View.VISIBLE
            destinationHostInput.hint = "Destination Host (e.g. example.org)"
        }
    }

    private fun updateIpAddress() {
        val ipAddress = getLocalIpAddress() ?: "Not available"
        runOnUiThread {
            ipAddressTextView.text = "IP: $ipAddress"
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
            Log.e("MainActivity", "Failed to get IP address", e)
            return "IP lookup failed"
        }
    }

    private fun startRelayWithPermissionCheck() {
        val permissionsNeeded = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 36) {
            permissionsNeeded.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val missingPermissions = permissionsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startRelay()
        } else {
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startRelay() {
        val destinationHost = destinationHostInput.text.toString().trim()
        val destinationPort = if (currentRelayMode == RelayMode.TAILCAT) 0 else destinationPortInput.text.toString().toIntOrNull()
        val localPort = localPortInput.text.toString().toIntOrNull()

        if (!validateInputs(destinationHost, destinationPort, localPort)) {
            updateUI()
            return
        }

        Intent(this, TcpRelayService::class.java).apply {
            action = TcpRelayService.ACTION_START_RELAY
            putExtra(TcpRelayService.EXTRA_DEST_HOST, destinationHost)
            putExtra(TcpRelayService.EXTRA_DEST_PORT, destinationPort ?: 0)
            putExtra(TcpRelayService.EXTRA_LOCAL_PORT, localPort!!)
            putExtra(TcpRelayService.EXTRA_RELAY_MODE, currentRelayMode.name)
        }.also { startService(it) }

        if (rememberDetailsCheckBox.isChecked) {
            saveSettings(destinationHost, destinationPort?.toString() ?: "", localPort.toString(), currentRelayMode.name)
        }
    }

    private fun stopRelayService() {
        Intent(this, TcpRelayService::class.java).apply {
            action = TcpRelayService.ACTION_STOP_RELAY
        }.also { startService(it) }
    }

    private fun validateInputs(destinationHost: String, destinationPort: Int?, localPort: Int?): Boolean {
        if (!RelayValidator.validateHost(destinationHost, currentRelayMode)) {
            destinationHostInput.error = if (currentRelayMode == RelayMode.TAILCAT) {
                "Invalid Tailcat address (must start with tc...)."
            } else {
                "Destination host cannot be empty."
            }
            return false
        }
        if (!RelayValidator.validateDestinationPort(destinationPort, currentRelayMode)) {
            destinationPortInput.error = "Invalid destination port. (Valid: 1-65535)"
            return false
        }
        if (!RelayValidator.validateLocalPort(localPort)) {
            localPortInput.error = "Invalid local port. (Valid: 1024-65535)"
            return false
        }
        return true
    }

    private fun showPermissionDeniedDialog(deniedPermissions: List<String>) {
        val message = StringBuilder("This app requires the following permissions to function:\n\n")
        
        deniedPermissions.forEach { permission ->
            when (permission) {
                Manifest.permission.POST_NOTIFICATIONS -> 
                    message.append("• Notifications: To show the relay status in the notification bar.\n")
                Manifest.permission.NEARBY_WIFI_DEVICES -> 
                    message.append("• Nearby devices: Required on Android 16+ to access the local network for relaying.\n")
            }
        }
        
        message.append("\nPlease grant these permissions in the app settings to start the relay.")

        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(message.toString())
            .setPositiveButton("Go to Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ -> 
                dialog.dismiss()
                updateUI() 
            }
            .show()
    }

    override fun onStart() {
        super.onStart()
        Intent(this, TcpRelayService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        unregisterReceiver(updateReceiver)
    }

    private fun updateUI() {
        val isRunning = tcpRelayService?.isRelayServerRunning() == true
        startServerButton.text = if (isRunning) "Stop Server" else "Start Server"
        startServerButton.isEnabled = true
        toggleInputFields(!isRunning)
    }

    private fun toggleInputFields(enabled: Boolean) {
        protocolRadioGroup.isEnabled = enabled
        radioTcp.isEnabled = enabled
        radioTailcat.isEnabled = enabled
        destinationHostInput.isEnabled = enabled
        destinationPortInput.isEnabled = enabled
        localPortInput.isEnabled = enabled
        rememberDetailsCheckBox.isEnabled = enabled
    }

    private fun getPrefs(): SharedPreferences = getSharedPreferences("TCP_RELAY_PREFS", MODE_PRIVATE)

    private fun saveSettings(destinationHost: String, destinationPort: String, localPort: String, relayMode: String) {
        getPrefs().edit()
            .putString("DESTINATION_HOST", destinationHost)
            .putString("DESTINATION_PORT", destinationPort)
            .putString("LOCAL_PORT", localPort)
            .putString("RELAY_MODE", relayMode)
            .apply()
    }

    private fun loadSettings() {
        val modeStr = getPrefs().getString("RELAY_MODE", RelayMode.TCP.name)
        currentRelayMode = RelayMode.fromString(modeStr)
        isAutoSwitchingMode = true
        if (currentRelayMode == RelayMode.TAILCAT) {
            radioTailcat.isChecked = true
        } else {
            radioTcp.isChecked = true
        }
        isAutoSwitchingMode = false
        updateModeUI()

        getPrefs().getString("DESTINATION_HOST", "")?.also { destinationHostInput.setText(it) }
        getPrefs().getString("DESTINATION_PORT", "")?.also { destinationPortInput.setText(it) }
        getPrefs().getString("LOCAL_PORT", "")?.also { localPortInput.setText(it) }
    }
}
