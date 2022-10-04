package com.example.em_ble_bridge

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.view.View
import android.location.Location
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task
import com.google.gson.Gson
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.collections.ArrayList
import kotlin.properties.Delegates
import kotlin.text.Charsets.UTF_8
import kotlin.Exception


const val TAG = "msg"

data class MQTTBridgePublish(val bridgeCoordinates: Array<Double>, val timestamp: String,
                             val bridgeName: String, val beaconMac: String, val rssi: Int,
                             val rawPdu: Double)
data class DetectedDevice(val friendlyName: String, val timestamp: String, val beaconMac: String,
                          val rssi: Int)

class MainActivity : AppCompatActivity() {
    // MQTT ----------------------------------------------------------------------------------------
    private lateinit var client: Mqtt5BlockingClient

    // GPS -----------------------------------------------------------------------------------------
    private var lastKnownLocation: Location? = null
    private val locationPermissionCode = 2
    private var locationSettingsTask: Task<LocationSettingsResponse>? = null
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private var locationPermissionGranted: Boolean = false

    // BLUETOOTH -----------------------------------------------------------------------------------
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val bluetoothScanFrequency: Long = 10000 // every x ms, app will restart bluetooth scan
    private lateinit var restartBluetoothScan: Handler

    // INTERNAL ------------------------------------------------------------------------------------
    private var scanning: Boolean = false
    private var detectedDevices: ArrayList<DetectedDevice> = ArrayList()
    private var notify: Boolean = true // will display alert dialog if true
    private var mqttHost: String = BuildConfig.MQTT_HOST
    private var mqttUsername: String = BuildConfig.MQTT_USERNAME
    private var mqttPassword: String = BuildConfig.MQTT_PASSWORD
    private var mqttPort: Int = BuildConfig.MQTT_PORT
    private lateinit var mqttTopic: String
    private var rssiFilter by Delegates.notNull<Int>()
    private lateinit var friendlyNameFilter: String
    private var bridgeName: String = utils().getRandomDeviceName()

    // THREAD FUNCTIONS ----------------------------------------------------------------------------
    private val restartBluetoothScanTask = object : Runnable {
        override fun run() {
            bluetoothAdapter?.startDiscovery()
            restartBluetoothScan.postDelayed(this, bluetoothScanFrequency)
        }
    }

    private val uncaughtHandler = object : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(th: Thread, ex: Throwable){
            displayInDebugLog("Failed to send mqtt message: $ex")
            displayInDebugLog("reconnecting...")
            mqttConnect()
        }
    }

    // Create a BroadcastReceiver for ACTION_FOUND.
    private val receiver = object : BroadcastReceiver() {
        @RequiresApi(Build.VERSION_CODES.O) // for timestamp info
        override fun onReceive(context: Context, intent: Intent) {
            if (lastKnownLocation?.latitude == null || lastKnownLocation?.longitude == null) {
                displayInDebugLog("No location yet")
                Toast.makeText(this@MainActivity,"Message not sent, no location yet", Toast.LENGTH_SHORT).show()
                return
            }
            when(intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    processDetectedBluetoothDevice(intent)
                }
            }
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            for (location in locationResult.locations) {
                lastKnownLocation = location
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler(CustomExceptionHandler(this))
        setPreferencesFromShared()
        notify = true
        // request location services asap because it might take a min
        createLocationRequest()
        // hides the default app behavior
        supportActionBar?.hide()
        initBluetooth()
        setContentView(R.layout.activity_main)
        // setup looper for restarting bluetooth scan every interval
        restartBluetoothScan = Handler(Looper.getMainLooper())
        // Set Location provider and callback for when location becomes available
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        startLocationUpdates()

        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        }

        // Register for broadcasts when a device is discovered.
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(receiver, filter)

        // connect to our hive broker.
        mqttConnect()
    }

    // initializes devices bluetooth adapter, and exits app if it does not exist. (device doesn't have a bluetooth adapter)
    private fun initBluetooth() {
        val manager: BluetoothManager = this.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        if (bluetoothAdapter == null) {
            displayInDebugLog("Device doesn't support bluetooth")
            utils().setUIError("Device doesn't support bluetooth", "This app requires bluetooth, now exiting", this)
            this.finishAffinity()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun processDetectedBluetoothDevice(intent: Intent) {
        if (!scanning) return
        // Collect info from the bluetooth device and current conditions
        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
        // default values in case any properties are unknown. It is common to see devices
        // without a name. Never seen one without a mac, but could be possible
        val deviceName: String = if (device?.name != null) device.name else "Unknown"
        val deviceHardwareAddress: String = if (device?.address != null) device.address else "Unknown"
        val ts: LocalDateTime = LocalDateTime.now()

        val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        // rssi filter
        val rssiFilter: Int = preferences.getInt("rssiFilter", getString(R.string.defaultRssiFilter).toInt())
        if (rssi < rssiFilter && rssiFilter != -100) {
            displayInDebugLog("Device $deviceHardwareAddress filtered w/ rssi $rssi")
            return
        }
        // friendly name filter
        val friendlyNameFilter: String? = preferences.getString("friendlyNameFilter", getString(R.string.defaultFriendlyNameFilter))
        if (friendlyNameFilter != null) {
            val p: Pattern = Pattern.compile("${friendlyNameFilter}.*")
            val m: Matcher? = p.matcher(deviceName)
            if (m != null && !m.matches()) {
                displayInDebugLog("Device $deviceName filtered w/ regex filter $friendlyNameFilter")
                return
            }
        }

        sendMqttMessage(MQTTBridgePublish(
            bridgeCoordinates = arrayOf<Double>(lastKnownLocation!!.latitude, lastKnownLocation!!.longitude),
            bridgeName = bridgeName,
            beaconMac = deviceHardwareAddress,
            rssi = rssi,
            rawPdu = 0.0,
            timestamp = ts.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")),
        ))
        // only storing data this app actually needs. Data for web ui sent over mqtt
        val detectedDevice = DetectedDevice(
            friendlyName = deviceName,
            beaconMac = deviceHardwareAddress,
            rssi = rssi,
            timestamp = ts.format(DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss"))
        )
        // if we haven't detected this device, add it to the list of detected devices, otherwise, update its info
        val deviceAlreadySeen: DetectedDevice? = detectedDevices.firstOrNull{ it.beaconMac == detectedDevice.beaconMac }
        if (deviceAlreadySeen == null) {
            detectedDevices.add(detectedDevice)
            updateUIDevicesAdapter()
            return
        }
        detectedDevices[detectedDevices.indexOf(deviceAlreadySeen)] = detectedDevice
        updateUIDevicesAdapter()
    }

    private fun setPreferencesFromShared() {
        val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        displayInDebugLog(preferences.all.toString())
        // preferences defaults not secure
        val rssiFilter: Int = preferences.getInt("rssiFilter", getString(R.string.defaultRssiFilter).toInt())
        val mqttTopic: String? = preferences.getString("mqttTopic", getString(R.string.defaultMQTTTopic))
        val friendlyNameFilter: String? = preferences.getString("friendlyNameFilter", getString(R.string.defaultFriendlyNameFilter))
        val bridgeName: String? = preferences.getString("bridgeName", this.bridgeName)
        // secure defaults
        val mqttPort: Int = preferences.getInt("mqttPort", BuildConfig.MQTT_PORT)
        val mqttHost: String? = preferences.getString("mqttHost", BuildConfig.MQTT_HOST)
        val mqttUsername: String? = preferences.getString("mqttUsername", BuildConfig.MQTT_USERNAME)
        val mqttPassword: String? = preferences.getString("mqttPassword", BuildConfig.MQTT_PASSWORD)

        if (mqttTopic == null || friendlyNameFilter == null || bridgeName == null || mqttHost == null ||
                mqttUsername == null || mqttPassword == null) {
            utils().setUIError("Error fetching preferences", "Cannot continue", this)
            return
        }
        this.rssiFilter = rssiFilter
        this.mqttTopic = mqttTopic
        this.friendlyNameFilter = friendlyNameFilter
        this.bridgeName = bridgeName
        this.mqttPort = mqttPort
        this.mqttHost = mqttHost
        this.mqttUsername = mqttUsername
        this.mqttPassword = mqttPassword
    }

    // Create connection to hive broker
    private fun mqttConnect() {
        try {
            //create an MQTT client
            client = MqttClient.builder()
                .useMqttVersion5()
                .serverHost(mqttHost)
                .serverPort(mqttPort)
                .sslWithDefaultConfig()
                .buildBlocking()

            //connect to HiveMQ Cloud with TLS and username/pw
            client.connectWith()
                .simpleAuth()
                .username(mqttUsername)
                .password(UTF_8.encode(mqttPassword))
                .applySimpleAuth()
                .keepAlive(60)
                .send()
        }
        catch (e: Exception) {
            displayInDebugLog("MQTT connection failed with error: $e")
            e.message?.let { utils().setUIError("Error connecting to mqtt broker", it, this) }
            return
        }
        displayInDebugLog("Connected success")
    }

    // when we go to a different view, make sure to stop doing stuff
    override fun onDestroy() {
        super.onDestroy()
        client.disconnect()
        unregisterReceiver(receiver)
        fusedLocationProviderClient.removeLocationUpdates(locationCallback)
        // don't need onPause here
    }

    // util for debug
    private fun displayInDebugLog(message: String) {
        Log.i(TAG, message)
    }

    // request location services from android. Configure intervals here
    private fun createLocationRequest() {
        locationRequest = LocationRequest.create().apply {
            interval = 0
            fastestInterval = 0
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        val client: SettingsClient = LocationServices.getSettingsClient(this)
        locationSettingsTask = client.checkLocationSettings(builder.build())
    }

    // this is called when we want to start getting location updates and sending mqtt messages
    override fun onResume() {
        super.onResume()
        if (scanning) restartBluetoothScan.post(restartBluetoothScanTask)
    }

    // actually starts the location updates
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), locationPermissionCode)
            return
        }
        fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    // stops the location updates and mqtt publishing
    override fun onPause() {
        super.onPause()
        restartBluetoothScan.removeCallbacks(restartBluetoothScanTask)
    }

    /**
     * Handles the result of the request for location permissions.
     */
    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>,
                                            grantResults: IntArray) {
        locationPermissionGranted = false
        when (requestCode) {
            PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION -> {

                // If request is cancelled, the result arrays are empty.
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    locationPermissionGranted = true
                }
            }
        }
    }

    // util function for changing the text on the scan button
    private fun switchScanButtonText() {
        val x: Button? = findViewById(R.id.scan_button) as? Button
        if (x?.text == null) return
        if (scanning) x.text = "Stop Scan" else x.text = "Start Scan"
    }

    // when to stop the location service, when to start it
    private fun handlePauseResumeResourceAllocation() {
        if (locationSettingsTask != null && locationSettingsTask!!.isSuccessful && scanning) {
            detectedDevices = ArrayList()
            updateUIDevicesAdapter()
            onResume()
            displayInDebugLog("Requesting location updates and sending mqtt message")
            return
        }
        if (!scanning) {
            onPause()
            displayInDebugLog("Pausing location updates and mqtt messages")
            return
        }
        displayInDebugLog("Location request not yet fulfilled")
    }

    // converts data class to json and sends mqtt message for device update
    private fun sendMqttMessage(data: MQTTBridgePublish) {
        val outputJSON: String = Gson().toJson(data)
        displayInDebugLog(outputJSON)

        var th = Thread() {
            client.publishWith()
                    .topic(mqttTopic)
                    .payload(UTF_8.encode(outputJSON))
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .send()
                displayInDebugLog("message sent")
        }
        th.setUncaughtExceptionHandler(uncaughtHandler)
        th.start()

        displayInDebugLog("sendMqttMessage function exit")
    }

    // updates recycler view for new data in detectedDevices. Should only be called when
    // detectedDevices changes
    private fun updateUIDevicesAdapter() {
        // Lookup the recyclerview in activity layout
        // Save state so it doesn't mess up your scroll position
        val recyclerViewState: Parcelable?
        val rvBeacons = findViewById<View>(R.id.rvBeacons) as RecyclerView
        recyclerViewState = rvBeacons.layoutManager?.onSaveInstanceState()

        val adapter = DevicesAdapter(detectedDevices)
        // Attach the adapter to the recyclerview to populate items
        rvBeacons.adapter = adapter
        // Set layout manager to position the items
        rvBeacons.layoutManager = LinearLayoutManager(this)
        rvBeacons.layoutManager?.onRestoreInstanceState(recyclerViewState)
    }

    // called when scan button is clicked
    fun handleScanClick(view: View) {
        scanning = !scanning
        switchScanButtonText()
        handlePauseResumeResourceAllocation()
    }

    // called when setup button is clicked
    fun handleSetupClick(view: View) {
        utils().goToSetupPage(this)
        scanning = false
        handlePauseResumeResourceAllocation()
    }

    companion object {
        private const val PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION = 1
        private const val REQUEST_ENABLE_BT = 1
    }
}