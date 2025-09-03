package com.example.motionv

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
//import androidx.privacysandbox.tools.core.generator.build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class BlePeripheralService : Service() {

    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private lateinit var motionSensorManager: MotionSensorManagerImpl // Renamed to avoid conflict if you have an interface
    private val subscribedDevices = mutableSetOf<BluetoothDevice>()

    // Sensor data storage
    private var latestAccel = FloatArray(3)
    private var latestGyro = FloatArray(3)
    private var accelDataUpdatedSinceLastSend = false
    private var gyroDataUpdatedSinceLastSend = false

    // Throttling mechanism for BLE send
    private val bleSendHandler = Handler(Looper.getMainLooper())
    private val BLE_SEND_INTERVAL_MS = 100L // Send ~10 times per second
    private var isBleSendRunnablePosted = false


    companion object {
        private const val TAG = "BlePeripheralService"
        private const val NOTIFICATION_ID = 1234
        private const val CHANNEL_ID = "BlePeripheralServiceChannel"

        // Actions for BroadcastReceiver in MainActivity
        const val ACTION_BLE_STATUS_UPDATE = "com.example.motionv.ACTION_BLE_STATUS_UPDATE"
        const val ACTION_SENSOR_DATA_UPDATE = "com.example.motionv.ACTION_SENSOR_DATA_UPDATE"
        const val EXTRA_STATUS_ENUM = "com.example.motionv.EXTRA_STATUS_ENUM" // Will pass StatusUpdater.State ordinal
        const val EXTRA_STATUS_MESSAGE = "com.example.motionv.EXTRA_STATUS_MESSAGE"
        const val EXTRA_ACCEL_DATA = "com.example.motionv.EXTRA_ACCEL_DATA"
        const val EXTRA_GYRO_DATA = "com.example.motionv.EXTRA_GYRO_DATA"
    }

    private val motionSensorCallback = MotionSensorManagerImpl.SensorDataListener { payload ->
        try {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val newAccel = FloatArray(3) { buffer.float }
            val newGyro = FloatArray(3) { buffer.float }

            // Check if data actually changed to avoid unnecessary UI updates if using direct static access
            // For broadcasts, it's less critical here but good practice.
            if (!latestAccel.contentEquals(newAccel)) {
                latestAccel = newAccel
                accelDataUpdatedSinceLastSend = true
            }
            if (!latestGyro.contentEquals(newGyro)) {
                latestGyro = newGyro
                gyroDataUpdatedSinceLastSend = true
            }

            // Send data to MainActivity via Broadcast
            val sensorUpdateIntent = Intent(ACTION_SENSOR_DATA_UPDATE).apply {
                putExtra(EXTRA_ACCEL_DATA, latestAccel)
                putExtra(EXTRA_GYRO_DATA, latestGyro)
            }
            sendBroadcast(sensorUpdateIntent)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing sensor payload in service: ${e.message}", e)
        }
    }


    private val bleSendRunnable: Runnable = Runnable {
        if (gattServer != null && subscribedDevices.isNotEmpty()) {
            if (accelDataUpdatedSinceLastSend) {
                sendCharacteristicData(MotionUUIDs.ACCEL_DATA_CHAR_UUID, latestAccel.clone())
                accelDataUpdatedSinceLastSend = false
            }

            if (gyroDataUpdatedSinceLastSend) {
                sendCharacteristicData(MotionUUIDs.GYRO_DATA_CHAR_UUID, latestGyro.clone())
                gyroDataUpdatedSinceLastSend = false
            }
        }

        if (gattServer != null && subscribedDevices.isNotEmpty()) {
            bleSendHandler.postDelayed(bleSendRunnable, BLE_SEND_INTERVAL_MS)
            isBleSendRunnablePosted = true
        } else {
            Log.d(TAG, "bleSendRunnable: Not rescheduling. Server null or no subscribers.")
            isBleSendRunnablePosted = false
        }
    }


    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled or not available.")
            broadcastStatus(StatusUpdater.State.BLUETOOTH_OFF)
            stopSelf()
            return
        }

        motionSensorManager = MotionSensorManagerImpl(this, motionSensorCallback)

        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MotionV Sensor Active")
            .setContentText("Broadcasting Accelerometer & Gyroscope data via BLE.")
            .setSmallIcon(R.drawable.ic_bluetooth) // REPLACE with your notification icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Foreground service started.")

        startBleServerLogic()
        motionSensorManager.start()
        // The bleSendRunnable will be started when a device subscribes
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        // Ensure server is running if service is restarted
        if (gattServer == null) {
            startBleServerLogic()
        }
        if (!motionSensorManager.isRunning()) {
            motionSensorManager.start()
        }
        return START_STICKY
    }

    private fun startBleServerLogic() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Service missing Bluetooth S+ permissions.")
                broadcastStatus(StatusUpdater.State.ERROR, "Missing BLE Permissions")
                stopSelf()
                return
            }
        }
        // Pre-S permissions are typically checked before starting the service by the Activity

        try {
            advertiser = bluetoothAdapter!!.bluetoothLeAdvertiser
            if (advertiser == null) {
                Log.e(TAG, "Failed to create advertiser.")
                broadcastStatus(StatusUpdater.State.ERROR, "Advertiser init fail")
                stopSelf()
                return
            }
            gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
            if (gattServer == null) {
                Log.e(TAG, "Could not open GATT server.")
                broadcastStatus(StatusUpdater.State.ERROR, "GATT server init fail")
                stopSelf()
                return
            }
            setupGattService() // This will then trigger advertising via onServiceAdded
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in startBleServerLogic: ${e.message}")
            broadcastStatus(StatusUpdater.State.ERROR, "BLE Security Fail: ${e.message?.take(20)}")
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Exception in startBleServerLogic: ${e.message}")
            broadcastStatus(StatusUpdater.State.ERROR, "BLE Start Fail: ${e.message?.take(20)}")
            stopSelf()
        }
    }

    private fun setupGattService() {
        val service = BluetoothGattService(
            MotionUUIDs.MOTION_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val accelChar = BluetoothGattCharacteristic(
            MotionUUIDs.ACCEL_DATA_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        accelChar.addDescriptor(
            BluetoothGattDescriptor(MotionUUIDs.CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        )

        val gyroChar = BluetoothGattCharacteristic(
            MotionUUIDs.GYRO_DATA_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        gyroChar.addDescriptor(
            BluetoothGattDescriptor(MotionUUIDs.CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        )

        service.addCharacteristic(accelChar)
        service.addCharacteristic(gyroChar)

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Log.e(TAG, "setupGattService: Missing BLUETOOTH_CONNECT permission for addService.")
                broadcastStatus(StatusUpdater.State.ERROR, "Connect perm for service add")
                return
            }
            gattServer?.addService(service)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in setupGattService: ${e.message}")
            broadcastStatus(StatusUpdater.State.ERROR, "Service Add Security Fail")
        }
    }

    private fun startAdvertising() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.e(TAG, "startAdvertising: Missing BLUETOOTH_ADVERTISE permission.")
            broadcastStatus(StatusUpdater.State.ERROR, "Advertise perm missing")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(MotionUUIDs.MOTION_SERVICE_UUID))
            .setIncludeDeviceName(true) // You had this as true
            .build()
        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on startAdvertising: ${e.message}")
            broadcastStatus(StatusUpdater.State.ERROR, "Advertise Security Fail")
        }
    }

    private fun stopBleServerLogic() {
        Log.d(TAG, "Stopping BLE server logic...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Missing BLUETOOTH_ADVERTISE permission to stop advertising.")
            } else {
                advertiser?.stopAdvertising(advertiseCallback)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Missing BLUETOOTH_CONNECT permission to close GATT server.")
            } else {
                gattServer?.close()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in stopBleServerLogic: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException while stopping advertiser (already stopped?): ${e.message}")
        }
        finally {
            gattServer = null
            advertiser = null
            subscribedDevices.clear()
            if (isBleSendRunnablePosted) {
                bleSendHandler.removeCallbacks(bleSendRunnable)
                isBleSendRunnablePosted = false
            }
            Log.d(TAG, "BLE server logic stopped.")
        }
    }

    private fun sendCharacteristicData(characteristicUuid: UUID, values: FloatArray) {
        if (gattServer == null || subscribedDevices.isEmpty()) {
            //Log.v(TAG, "sendCharacteristicData: Server null or no subscribers for $characteristicUuid")
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.e(TAG, "sendCharacteristicData: Missing BLUETOOTH_CONNECT permission.")
            return
        }

        val service = gattServer?.getService(MotionUUIDs.MOTION_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(characteristicUuid)
        if (characteristic == null) {
            Log.e(TAG, "sendCharacteristicData: Characteristic $characteristicUuid not found.")
            return
        }

        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
            Log.w(TAG, "sendCharacteristicData: Characteristic $characteristicUuid does not support NOTIFY.")
            return
        }

        val payloadBuffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { payloadBuffer.putFloat(it) }
        val dataToSend = payloadBuffer.array()

        // Set value on characteristic for older Android versions
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            characteristic.value = dataToSend
        }

        val devicesToRemove = mutableSetOf<BluetoothDevice>()
        subscribedDevices.forEach { device ->
            try {
                val successCode: Int // Explicitly type the variable
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    successCode = gattServer?.notifyCharacteristicChanged(device, characteristic, false, dataToSend)
                        ?: BluetoothStatusCodes.ERROR_UNKNOWN // Using a generic error if null on API 33+
                } else {
                    // For older versions, notifyCharacteristicChanged returns a boolean
                    if (gattServer?.notifyCharacteristicChanged(device, characteristic, false) == true) {
                        successCode = BluetoothGatt.GATT_SUCCESS // Use BluetoothGatt.GATT_SUCCESS for success
                    } else {
                        // Use a relevant BluetoothGatt constant for failure
                        // GATT_WRITE_NOT_PERMITTED might not be the most accurate,
                        // as notifyCharacteristicChanged returning false can have various reasons.
                        // GATT_FAILURE is a more generic option if unsure.
                        successCode = BluetoothGatt.GATT_FAILURE // Or BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                        Log.w(TAG, "notifyCharacteristicChanged returned false for ${device.address} on pre-Tiramisu device.")
                    }
                }

                // Check against BluetoothGatt.GATT_SUCCESS (which is 0)
                // BluetoothStatusCodes.SUCCESS is also 0
                if (successCode != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Failed to notify ${device.address} for $characteristicUuid. Result code: $successCode")
                    // Consider removing device if notification fails consistently
                    // devicesToRemove.add(device)
                } else {
                    // Log.d(TAG, "Notified ${device.address} for $characteristicUuid")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException in sendCharacteristicData for ${device.address}: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Generic exception in sendCharacteristicData for ${device.address}: ${e.message}")
            }
        }
        subscribedDevices.removeAll(devicesToRemove)

    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Advertising started successfully.")
            broadcastStatus(StatusUpdater.State.ADVERTISING)
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
            broadcastStatus(StatusUpdater.State.ERROR, "Adv Fail $errorCode")
            // Consider stopping the service or attempting a restart after a delay
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Service ${service?.uuid} added successfully.")
                startAdvertising() // Start advertising once the service is added
            } else {
                Log.e(TAG, "Failed to add service ${service?.uuid}. Status: $status")
                broadcastStatus(StatusUpdater.State.ERROR, "Service Add Fail $status")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val deviceAddress = try {
                if (ActivityCompat.checkSelfPermission(this@BlePeripheralService, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "Address Hidden (No Perm)"
                } else {
                    device.name ?: device.address
                }
            } catch (e: SecurityException) { device.address }


            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Device connected: $deviceAddress (${device.address})")
                    broadcastStatus(StatusUpdater.State.CONNECTED, deviceAddress)
                    // The bleSendRunnable will be started/managed by onDescriptorWriteRequest when notifications are enabled.
                    // No need to add to subscribedDevices here, wait for CCCD write.
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Device disconnected: $deviceAddress (${device.address})")
                    subscribedDevices.remove(device)
                    broadcastStatus(StatusUpdater.State.DISCONNECTED_FROM_DEVICE, deviceAddress)

                    if (subscribedDevices.isEmpty()) {
                        Log.d(TAG, "All devices disconnected.")
                        if (isBleSendRunnablePosted) {
                            bleSendHandler.removeCallbacks(bleSendRunnable)
                            isBleSendRunnablePosted = false
                            Log.d(TAG, "bleSendRunnable removed as no subscribers.")
                        }
                        // Optionally restart advertising if it stopped due to connection limit or error
                        // if (advertiser != null && !isAdvertising()) startAdvertising()
                        // For now, assume advertising continues unless explicitly stopped.
                        broadcastStatus(StatusUpdater.State.ADVERTISING) // Update UI to show it's advertising again
                    }
                }
            } else {
                Log.w(TAG, "Connection state error for $deviceAddress. Status: $status, NewState: $newState")
                subscribedDevices.remove(device)
                broadcastStatus(StatusUpdater.State.ERROR, "Conn Error $status for $deviceAddress")
                if (subscribedDevices.isEmpty() && isBleSendRunnablePosted) {
                    bleSendHandler.removeCallbacks(bleSendRunnable)
                    isBleSendRunnablePosted = false
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            val deviceAddress = try {
                if (ActivityCompat.checkSelfPermission(this@BlePeripheralService, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "Address Hidden"
                } else {
                    device.name ?: device.address
                }
            } catch (e: SecurityException) { device.address }


            if (descriptor.uuid == MotionUUIDs.CCCD_UUID) {
                var responseSent = false
                if (BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value)) {
                    Log.i(TAG, "Notifications ENABLED by $deviceAddress for ${descriptor.characteristic.uuid}")
                    subscribedDevices.add(device)
                    if (!isBleSendRunnablePosted && gattServer != null) {
                        Log.d(TAG, "First subscriber, starting bleSendRunnable.")
                        bleSendHandler.post(bleSendRunnable) // Post immediately, it will reschedule itself
                        isBleSendRunnablePosted = true
                    }
                } else if (BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE.contentEquals(value)) {
                    Log.i(TAG, "Notifications DISABLED by $deviceAddress for ${descriptor.characteristic.uuid}")
                    subscribedDevices.remove(device)
                    if (subscribedDevices.isEmpty() && isBleSendRunnablePosted) {
                        Log.d(TAG, "Last subscriber unsubscribed, removing bleSendRunnable.")
                        bleSendHandler.removeCallbacks(bleSendRunnable)
                        isBleSendRunnablePosted = false
                    }
                }

                if (responseNeeded) {
                    try {
                        if (ActivityCompat.checkSelfPermission(this@BlePeripheralService, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Log.e(TAG, "Missing BLUETOOTH_CONNECT for sendResponse in onDescriptorWriteRequest")
                            // Cannot send response without permission on S+
                        } else {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                            responseSent = true
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException in onDescriptorWriteRequest sendResponse: ${e.message}")
                    }
                }
                if (responseNeeded && !responseSent) {
                    // If response was needed but not sent (e.g. due to missing permission on S+)
                    // This path should ideally not be hit if permissions are handled correctly.
                    Log.w(TAG, "Response needed for onDescriptorWriteRequest but not sent for $deviceAddress")
                }

            } else {
                Log.w(TAG, "Write request for unknown descriptor: ${descriptor.uuid} from $deviceAddress")
                if (responseNeeded) {
                    try {
                        if (ActivityCompat.checkSelfPermission(this@BlePeripheralService, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Log.e(TAG, "Missing BLUETOOTH_CONNECT for sendResponse on unknown descriptor")
                        } else {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REJECTED, offset, null)
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException for unknown descriptor response: ${e.message}")
                    }
                }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val deviceAddress = try {
                if (ActivityCompat.checkSelfPermission(this@BlePeripheralService, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "Address Hidden"
                } else {
                    device.name ?: device.address
                }
            } catch (e: SecurityException) { device.address }

            Log.d(TAG, "Read request for ${characteristic.uuid} from $deviceAddress")
            var dataToSend: ByteArray? = null
            if (characteristic.uuid == MotionUUIDs.ACCEL_DATA_CHAR_UUID) {
                val buffer = ByteBuffer.allocate(latestAccel.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                latestAccel.forEach { buffer.putFloat(it) }
                dataToSend = buffer.array()
            } else if (characteristic.uuid == MotionUUIDs.GYRO_DATA_CHAR_UUID) {
                val buffer = ByteBuffer.allocate(latestGyro.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                latestGyro.forEach { buffer.putFloat(it) }
                dataToSend = buffer.array()
            }

            try {
                if (ActivityCompat.checkSelfPermission(this@BlePeripheralService, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Log.e(TAG, "Missing BLUETOOTH_CONNECT for sendResponse in onCharacteristicReadRequest")
                    // Cannot send response without permission
                    return
                }

                if (dataToSend != null) {
                    val valueToSend = if (offset >= dataToSend.size) ByteArray(0) else dataToSend.sliceArray(offset until dataToSend.size)
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, valueToSend)
                } else {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException in onCharacteristicReadRequest: ${e.message}")
            }
        }


        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            //super.onNotificationSent(device, status) // Not strictly needed if not using its base impl
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val deviceAddress = try {
                    if (ActivityCompat.checkSelfPermission(this@BlePeripheralService, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        "Address Hidden"
                    } else {
                        device.name ?: device.address
                    }
                } catch (e: SecurityException) { device.address }
                Log.e(TAG, "FAILED: Notification send to $deviceAddress, status: $status")
                // Consider if this device should be removed from subscribedDevices if errors persist
            }
        }
    }

    private fun broadcastStatus(state: StatusUpdater.State, message: String? = null) {
        val intent = Intent(ACTION_BLE_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS_ENUM, state.ordinal)
            putExtra(EXTRA_STATUS_MESSAGE, message)
        }
        sendBroadcast(intent)
    }


    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        motionSensorManager.stop()
        bleSendHandler.removeCallbacksAndMessages(null) // Remove all runnables
        isBleSendRunnablePosted = false
        stopBleServerLogic()
        stopForeground(STOP_FOREGROUND_REMOVE)
        broadcastStatus(StatusUpdater.State.SERVICE_STOPPED)
        Log.d(TAG, "Service fully stopped and cleaned up.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "MotionV BLE Service Channel",
                NotificationManager.IMPORTANCE_LOW // Use LOW to be less intrusive
            ).apply {
                description = "Channel for MotionV BLE foreground service."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            Log.d(TAG, "Notification channel created.")
        }
    }



    object MotionUUIDs {
        val MOTION_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val ACCEL_DATA_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val GYRO_DATA_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") // Standard Client Characteristic Configuration Descriptor
    }


    // You need to use your actual MotionSensorManager implementation.
    // This is a placeholder structure if you rename your original MainActivity.MotionSensorManager
    class MotionSensorManagerImpl(
        private val context: Context,
        private val listener: SensorDataListener
    ) : SensorEventListener {
        private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        private val currentAccelData = FloatArray(3)
        private val currentGyroData = FloatArray(3)
        private var _isRunning = false
        fun isRunning() = _isRunning

        fun interface SensorDataListener { // Functional interface
            fun onDataReady(payload: ByteArray)
        }

        fun start() {
            if (_isRunning) return
            Log.d(TAG, "MotionSensorManagerImpl starting...")
            accelerometer?.also {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            gyroscope?.also {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            _isRunning = true
            if (accelerometer == null) Log.w(TAG, "Accelerometer not available in service's sensor manager")
            if (gyroscope == null) Log.w(TAG, "Gyroscope not available in service's sensor manager")
        }

        fun stop() {
            if (!_isRunning) return
            Log.d(TAG, "MotionSensorManagerImpl stopping...")
            sensorManager.unregisterListener(this)
            _isRunning = false
        }

        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                synchronized(this) {
                    when (it.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> System.arraycopy(it.values, 0, currentAccelData, 0, 3)
                        Sensor.TYPE_GYROSCOPE -> System.arraycopy(it.values, 0, currentGyroData, 0, 3)
                    }
                }
                val payload = ByteBuffer.allocate(6 * 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat(currentAccelData[0])
                    .putFloat(currentAccelData[1])
                    .putFloat(currentAccelData[2])
                    .putFloat(currentGyroData[0])
                    .putFloat(currentGyroData[1])
                    .putFloat(currentGyroData[2])
                    .array()
                listener.onDataReady(payload)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
}
