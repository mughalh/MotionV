package com.example.motionv

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivityUI"

    private lateinit var startServiceButton: Button
    private lateinit var stopServiceButton: Button
    private lateinit var statusTextView: TextView // Combined status text
    private lateinit var accelText: TextView
    private lateinit var gyroText: TextView

    private lateinit var statusUpdater: StatusUpdater // Your existing class

    // For Android 13+ Notification Permission
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.d(TAG, "POST_NOTIFICATIONS permission granted.")
                checkAndRequestBlePermissions() // Proceed to BLE permissions
            } else {
                Toast.makeText(this, "Notification permission is recommended for service status.", Toast.LENGTH_LONG).show()
                // Can still proceed to BLE permissions, but notification won't show on Android 13+ without it
                checkAndRequestBlePermissions()
            }
        }

    private val requestBlePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var allGranted = true
            var blePermissionsActuallyGranted = true // Track only essential BLE perms

            permissions.entries.forEach {
                Log.d(TAG, "Permission ${it.key} granted: ${it.value}")
                if (!it.value) {
                    allGranted = false
                    if (it.key.startsWith("android.permission.BLUETOOTH_") || it.key == Manifest.permission.ACCESS_FINE_LOCATION) {
                        blePermissionsActuallyGranted = false
                    }
                }
            }

            if (blePermissionsActuallyGranted) {
                Log.d(TAG, "All required BLE permissions granted.")
                statusUpdater.update(StatusUpdater.State.IDLE, "BLE Permissions OK")
                checkBluetoothAndStartService()
            } else {
                Log.w(TAG, "Not all essential BLE permissions were granted.")
                statusUpdater.update(StatusUpdater.State.PERMISSIONS_DENIED, "Essential BLE permissions missing.")
                Toast.makeText(this, "Bluetooth permissions are required for BLE features.", Toast.LENGTH_LONG).show()
                showPermissionRationaleDialog("Bluetooth permissions are essential for this app's core functionality. Please grant them in settings.")
            }
        }

    private val bleServiceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BlePeripheralService.ACTION_BLE_STATUS_UPDATE -> {
                    val statusOrdinal = intent.getIntExtra(BlePeripheralService.EXTRA_STATUS_ENUM, -1)
                    val message = intent.getStringExtra(BlePeripheralService.EXTRA_STATUS_MESSAGE)
                    if (statusOrdinal != -1) {
                        try {
                            val state = StatusUpdater.State.entries[statusOrdinal]
                            statusUpdater.update(state, message)
                            // Update button states based on service advertising/connected state
                            when(state) {
                                StatusUpdater.State.ADVERTISING, StatusUpdater.State.CONNECTED -> {
                                    updateButtonStates(isServiceExpectedToRun = true)
                                }
                                StatusUpdater.State.SERVICE_STOPPED, StatusUpdater.State.ERROR, StatusUpdater.State.IDLE, StatusUpdater.State.BLUETOOTH_OFF -> {
                                    updateButtonStates(isServiceExpectedToRun = false)
                                }
                                else -> {} // Do nothing for other states
                            }
                        } catch (e: IndexOutOfBoundsException) {
                            Log.e(TAG, "Received invalid status ordinal: $statusOrdinal")
                        }
                    }
                }
                BlePeripheralService.ACTION_SENSOR_DATA_UPDATE -> {
                    val newAccel = IntentCompat.getParcelableArrayExtra(intent, BlePeripheralService.EXTRA_ACCEL_DATA, FloatArray::class.java)
                    val newGyro = IntentCompat.getParcelableArrayExtra(intent, BlePeripheralService.EXTRA_GYRO_DATA, FloatArray::class.java)


                    // For some reason getParcelableArrayExtra is not working as expected for float arrays,
                    // let's try getFloatArrayExtra directly
                    val accelData = intent.getFloatArrayExtra(BlePeripheralService.EXTRA_ACCEL_DATA)
                    val gyroData = intent.getFloatArrayExtra(BlePeripheralService.EXTRA_GYRO_DATA)


                    if (accelData != null) {
                        accelText.text = getString(
                            R.string.accelerometer_data_format, // Ensure this string resource exists
                            accelData.getOrElse(0) { 0f },
                            accelData.getOrElse(1) { 0f },
                            accelData.getOrElse(2) { 0f }
                        )
                    }
                    if (gyroData != null) {
                        gyroText.text = getString(
                            R.string.gyroscope_data_format, // Ensure this string resource exists
                            gyroData.getOrElse(0) { 0f },
                            gyroData.getOrElse(1) { 0f },
                            gyroData.getOrElse(2) { 0f }
                        )
                    }
                }
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Ensure this layout exists with the IDs

        startServiceButton = findViewById(R.id.startServiceButton)
        stopServiceButton = findViewById(R.id.stopServiceButton)
        statusTextView = findViewById(R.id.textViewStatus) // Your main status display
        accelText = findViewById(R.id.textViewAccelerometerData)
        gyroText = findViewById(R.id.textViewGyroscopeData)

        // Initialize your StatusUpdater
        statusUpdater = StatusUpdater(statusTextView)
        statusUpdater.update(StatusUpdater.State.IDLE) // Initial state

        startServiceButton.setOnClickListener {
            statusUpdater.update(StatusUpdater.State.REQUESTING_PERMISSIONS)
            checkAndRequestInitialPermissions()
        }

        stopServiceButton.setOnClickListener {
            stopBleService()
        }
        updateButtonStates(isServiceExpectedToRun = false) // Initial button state
    }

    override fun onResume() {
        super.onResume()
        val intentFilter = IntentFilter().apply {
            addAction(BlePeripheralService.ACTION_BLE_STATUS_UPDATE)
            addAction(BlePeripheralService.ACTION_SENSOR_DATA_UPDATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bleServiceStateReceiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(bleServiceStateReceiver, intentFilter)
        }
        // You might want to query the service's current state here if it was already running
        // For simplicity, we assume buttons reflect the last action taken by this activity instance.
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(bleServiceStateReceiver)
    }

    private fun checkAndRequestInitialPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                checkAndRequestBlePermissions() // Notification perm already granted
            }
        } else {
            checkAndRequestBlePermissions() // No notification perm needed for < Android 13
        }
    }

    private fun checkAndRequestBlePermissions() {
        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else { // Below Android 12
            // BLUETOOTH and BLUETOOTH_ADMIN are normal permissions before S, granted at install time if in manifest.
            // ACCESS_FINE_LOCATION is the main runtime permission needed for BLE scans.
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (requiredPermissions.isNotEmpty()) {
            Log.d(TAG, "Requesting BLE permissions: $requiredPermissions")
            requestBlePermissionsLauncher.launch(requiredPermissions.toTypedArray())
        } else {
            Log.d(TAG, "All required BLE permissions already granted.")
            statusUpdater.update(StatusUpdater.State.IDLE, "BLE Permissions OK")
            checkBluetoothAndStartService()
        }
    }

    private fun checkBluetoothAndStartService() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            statusUpdater.update(StatusUpdater.State.ERROR, "Bluetooth not supported")
            Toast.makeText(this, "Bluetooth not supported on this device.", Toast.LENGTH_LONG).show()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            statusUpdater.update(StatusUpdater.State.BLUETOOTH_OFF)
            Toast.makeText(this, "Bluetooth is not enabled. Please enable Bluetooth.", Toast.LENGTH_LONG).show()
            // Intent to request Bluetooth enable
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            // You'll need a launcher for this if you want to handle the result.
            // For now, just prompt the user.
            startActivity(enableBtIntent)
            return
        }
        startBleService()
    }

    private fun startBleService() {
        statusUpdater.update(StatusUpdater.State.STARTING_SERVICE)
        val serviceIntent = Intent(this, BlePeripheralService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.i(TAG, "BLE Service start requested.")
            // Status will be updated by broadcast from service (e.g., to ADVERTISING)
            updateButtonStates(isServiceExpectedToRun = true) // Tentative
        } catch (e: Exception) {
            Log.e(TAG, "Could not start BLE service", e)
            statusUpdater.update(StatusUpdater.State.ERROR, "Failed to start service: ${e.message}")
            Toast.makeText(this, "Error starting BLE service: ${e.message}", Toast.LENGTH_LONG).show()
            updateButtonStates(isServiceExpectedToRun = false)
        }
    }

    private fun stopBleService() {
        val serviceIntent = Intent(this, BlePeripheralService::class.java)
        stopService(serviceIntent) // Service's onDestroy will broadcast SERVICE_STOPPED
        Log.i(TAG, "BLE Service stop requested.")
        updateButtonStates(isServiceExpectedToRun = false)
        // Explicitly update status here as onDestroy broadcast might be delayed or if service wasn't running
        statusUpdater.update(StatusUpdater.State.SERVICE_STOPPED)
        accelText.text = getString(R.string.accelerometer_data_format, 0f, 0f, 0f)
        gyroText.text = getString(R.string.gyroscope_data_format, 0f, 0f, 0f)
    }

    private fun updateButtonStates(isServiceExpectedToRun: Boolean) {
        startServiceButton.isEnabled = !isServiceExpectedToRun
        stopServiceButton.isEnabled = isServiceExpectedToRun
    }

    private fun showPermissionRationaleDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(message)
            .setPositiveButton("Go to Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                statusUpdater.update(StatusUpdater.State.PERMISSIONS_DENIED, "User cancelled settings.")
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // If the service is meant to stop when the main UI is destroyed:
        // stopBleService() // Uncomment this if that's the desired behavior
        Log.d(TAG, "MainActivity onDestroy")
    }
}
