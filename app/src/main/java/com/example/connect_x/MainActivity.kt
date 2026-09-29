package com.example.connect_x
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import android.os.Handler
import android.os.Looper
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import java.net.HttpURLConnection
import java.net.URL
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var wifiManager: WifiManager

    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val networks = mutableStateListOf<WifiNetwork>()

    private var message by mutableStateOf("Press CHECK NETWORK")
    private var internetStatus by mutableStateOf("Checking...")
    private var latency by mutableStateOf("-- ms")
    private val monitorHandler = Handler(Looper.getMainLooper())

    private val networkMonitor = object : Runnable {
        override fun run() {

            checkPermissionsAndScan()

            monitorHandler.postDelayed(this, 10000)
        }
    }
    private var notificationSent = false

    private fun checkCurrentNetworkQuality() {

        val currentInfo = wifiManager.connectionInfo
        val currentSignal = currentInfo.rssi

        val currentScore =
            ((currentSignal + 100) * 100 / 70).coerceIn(0, 100)

        val bestNetwork = networks.maxByOrNull { it.score }

        if (currentScore <= 70 && bestNetwork != null) {

            if (bestNetwork.score >= currentScore + 10 && !notificationSent) {

                message = "Better network available: ${bestNetwork.name}"

                sendBetterNetworkNotification(
                    bestNetwork.name,
                    bestNetwork.score
                )

                notificationSent = true
            }

        } else if (currentScore > 35) {

            notificationSent = false
        }
    }
    private fun sendBetterNetworkNotification(
        networkName: String,
        score: Int
    ) {
        val channelId = "connectx_network"

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "ConnectX Network Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )

            notificationManager.createNotificationChannel(channel)
        }
        val intent = Intent(this, MainActivity::class.java)
        val switchIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("SWITCH_NETWORK", networkName)
        }

        val switchPendingIntent = PendingIntent.getActivity(
            this,
            1,
            switchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Better Network Available")
            .setContentText("$networkName is available with score $score/100")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(
                android.R.drawable.ic_menu_directions,
                "SWITCH NOW",
                switchPendingIntent
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }
    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val fineGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true

            val nearbyGranted =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions[Manifest.permission.NEARBY_WIFI_DEVICES] == true
                } else {
                    true
                }

            if (fineGranted || nearbyGranted) {
                startWifiScan()
            } else {
                message = "Wi-Fi permission denied"
            }
        }

    private val wifiScanReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {

            val success =
                intent?.getBooleanExtra(
                    WifiManager.EXTRA_RESULTS_UPDATED,
                    false
                ) ?: false

            if (success) {
                showScanResults()
            } else {
                message = "Scan failed. Try again."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                200
            )
        }
        monitorHandler.post(networkMonitor)
        wifiManager =
            applicationContext.getSystemService(Context.WIFI_SERVICE)
                    as WifiManager

        val filter =
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                wifiScanReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(wifiScanReceiver, filter)
        }

        setContent {
            ConnectXApp(
                networks = networks,
                message = message,
                internetStatus = internetStatus,
                latency = latency,
                onCheckNetwork = {
                    checkPermissionsAndScan()
                }
            )
        }
    }

    private fun checkPermissionsAndScan() {
        checkInternetAndLatency()
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        }

        permissions.add(
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        permissions.add(
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val needPermission = permissions.any {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (needPermission) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            startWifiScan()
        }
    }

    private fun startWifiScan() {

        if (!wifiManager.isWifiEnabled) {
            message = "Please turn ON Wi-Fi"
            return
        }

        message = "Scanning Wi-Fi networks..."

        val success = wifiManager.startScan()

        if (!success) {
            message = "Scan request failed. Try again."
        }
    }

    private fun showScanResults() {

        try {

            val results: List<ScanResult> =
                wifiManager.scanResults

            networks.clear()

            results
                .filter { it.SSID.isNotBlank() }
                .distinctBy { it.SSID }
                .sortedByDescending { it.level }
                .forEach { result ->

                    val signal = result.level.coerceIn(-100, -30)

                    val score =
                        ((signal + 100) * 100 / 70)
                            .coerceIn(0, 100)

                    val status =
                        when {
                            score >= 70 -> "GOOD 🟢"
                            score >= 40 -> "MEDIUM 🟡"
                            else -> "WEAK 🔴"
                        }

                    networks.add(
                        WifiNetwork(
                            name = result.SSID,
                            signal = result.level,
                            score = score,
                            status = status
                        )
                    )
                }
            checkCurrentNetworkQuality()
            message =
                if (networks.isEmpty()) {
                    "No Wi-Fi networks found"
                } else {
                    "${networks.size} networks found"
                }

        } catch (e: SecurityException) {

            message = "Permission required for Wi-Fi scan"

        }
    }
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestWifiConnection(ssid: String) {

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val callback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                runOnUiThread {
                    message = "Connected to $ssid"
                }
            }

            override fun onUnavailable() {
                runOnUiThread {
                    message = "Could not connect to $ssid"
                }
            }
        }

        connectivityManager.requestNetwork(request, callback)
    }
    private fun checkInternetAndLatency() {

        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        val hasInternet =
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        internetStatus = if (hasInternet) {
            "Internet Available"
        } else {
            "No Internet"
        }

        Thread {
            var result = "-- ms"

            if (hasInternet) {
                try {
                    val startTime = System.currentTimeMillis()

                    val connection =
                        URL("https://www.google.com/generate_204").openConnection()
                                as HttpURLConnection

                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    connection.requestMethod = "GET"

                    connection.connect()

                    val endTime = System.currentTimeMillis()
                    result = "${endTime - startTime} ms"

                    connection.disconnect()

                } catch (e: Exception) {
                    result = "Failed"
                }
            }

            runOnUiThread {
                latency = result
            }
        }.start()
    }
    override fun onDestroy() {
        super.onDestroy()

        try {
            unregisterReceiver(wifiScanReceiver)
        } catch (_: Exception) {
        }
    }
}

data class WifiNetwork(
    val name: String,
    val signal: Int,
    val score: Int,
    val status: String
)

@androidx.compose.runtime.Composable
fun ConnectXApp(
    networks: List<WifiNetwork>,
    message: String,
    internetStatus: String,
    latency: String,
    onCheckNetwork: () -> Unit
) {

    MaterialTheme {

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF6F7FB)
        ) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {

                Text(
                    text = "CONNECTX",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp
                    ),
                    color = Color(0xFF24204F)
                )
                Text(
                    text = "Smart Network Manager",
                    style = MaterialTheme.typography.bodyLarge
                )

                Spacer(
                    modifier = Modifier.height(20.dp)
                )

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {

                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {

                        Text(
                            text = "Wi-Fi Scanner",
                            style = MaterialTheme.typography.titleLarge
                        )

                        Spacer(
                            modifier = Modifier.height(8.dp)
                        )

                        Text(text = message)
                        Spacer(
                            modifier = Modifier.height(8.dp)
                        )

                        Text(
                            text = "🌐 Internet: $internetStatus"
                        )

                        Text(
                            text = "⚡ Latency: $latency"
                        )
                    }
                }

                Spacer(
                    modifier = Modifier.height(16.dp)
                )

                Button(
                    onClick = onCheckNetwork,
                    modifier = Modifier.fillMaxWidth()
                ) {

                    Text(
                        text = "CHECK NETWORK"
                    )
                }

                Spacer(
                    modifier = Modifier.height(20.dp)
                )
                if (networks.isNotEmpty()) {

                    val bestNetwork = networks.maxByOrNull { it.score }

                    if (bestNetwork != null) {

                        Spacer(
                            modifier = Modifier.height(16.dp)
                        )

                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {

                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {

                                Text(
                                    text = "⭐ Recommended Network",
                                    style = MaterialTheme.typography.titleLarge
                                )

                                Spacer(
                                    modifier = Modifier.height(8.dp)
                                )

                                Text(
                                    text = "📶 ${bestNetwork.name}"
                                )

                                Text(
                                    text = "Signal: ${bestNetwork.signal} dBm"
                                )

                                Text(
                                    text = "Score: ${bestNetwork.score}/100"
                                )
                                Spacer(
                                    modifier = Modifier.height(12.dp)
                                )

                                Button(
                                    onClick = {
                                        // Connection action will be added next
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "🔄 CONNECT / SWITCH"
                                    )
                                }
                                Text(
                                    text = "Status: ${bestNetwork.status}"
                                )
                            }
                        }
                    }
                }
                Text(
                    text = "Available Networks",
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(
                    modifier = Modifier.height(10.dp)
                )

                LazyColumn {

                    items(networks) { network ->

                        NetworkCard(network)

                        Spacer(
                            modifier = Modifier.height(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
fun NetworkCard(
    network: WifiNetwork
) {

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {

            Column {

                Text(
                    text = "📶 ${network.name}"
                )

                Text(
                    text = "Signal: ${network.signal} dBm"
                )
            }

            Column {

                Text(
                    text = "${network.score}/100"
                )

                Text(
                    text = network.status
                )
            }
        }
    }
}