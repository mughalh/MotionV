package com.example.motionv

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages accelerometer and gyroscope sensors, processes their data,
 * and provides a combined payload when data is ready.
 *
 * @param context The application context.
 * @param onDataReady A lambda function that will be called with a ByteArray
 *                    payload containing 3 accelerometer floats (linear acceleration)
 *                    followed by 3 gyroscope floats (angular velocity).
 *                    The payload is 24 bytes, little-endian.
 */
class MotionSensorManager(
    context: Context,
    private val onDataReady: (payload: ByteArray) -> Unit
) : SensorEventListener {

    private val classTag = "MotionSensorManager" // For logging

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelSensor: Sensor? // Make nullable to handle missing sensors
    private val gyroSensor: Sensor?  // Make nullable to handle missing sensors

    // Arrays to store the latest processed sensor data
    private var currentAccelData = FloatArray(3) // Stores linear acceleration (x, y, z)
    private var currentGyroData = FloatArray(3)  // Stores angular velocity (x, y, z)

    // Flags to track if we have received at least one valid update from each sensor.
    // This helps ensure we don't send incomplete initial data.
    private var hasInitialAccelUpdate = false
    private var hasInitialGyroUpdate = false

    // Low-pass filter for accelerometer to separate gravity from linear acceleration
    private val gravityComponents = FloatArray(3) // Stores estimated gravity components
    private val lowPassFilterAlpha = 0.8f // Alpha for the low-pass filter. Closer to 1 = more smoothing.

    init {
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelSensor == null) {
            Log.w(classTag, "Accelerometer sensor (TYPE_ACCELEROMETER) not available on this device.")
        }

        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyroSensor == null) {
            Log.w(classTag, "Gyroscope sensor (TYPE_GYROSCOPE) not available on this device.")
        }
    }

    /**
     * Starts listening to sensor updates.
     * Registers listeners for accelerometer and gyroscope sensors if they are available.
     */
    fun start() {
        // --- CHOOSE SENSOR DELAY RATE ---
        // Option 1: Suitable for UI updates, typically around 10-16 Hz.
        val chosenSensorDelay = SensorManager.SENSOR_DELAY_UI

        // Option 2: Default rate, suitable for screen orientation, typically around 5 Hz. Less load.
        // val chosenSensorDelay = SensorManager.SENSOR_DELAY_NORMAL

        // Option 3: For games, typically around 50 Hz. Higher load, used in your original code.
        // val chosenSensorDelay = SensorManager.SENSOR_DELAY_GAME

        Log.d(classTag, "Starting sensor listeners with delay configuration: $chosenSensorDelay")

        // Reset flags when starting to ensure fresh initial data sync
        hasInitialAccelUpdate = false
        hasInitialGyroUpdate = false

        accelSensor?.let { // Only register if the accelerometer sensor exists
            val registered = sensorManager.registerListener(this, it, chosenSensorDelay)
            if (!registered) {
                Log.e(classTag, "Failed to register listener for Accelerometer sensor.")
            } else {
                Log.d(classTag, "Successfully registered listener for Accelerometer sensor.")
            }
        }

        gyroSensor?.let { // Only register if the gyroscope sensor exists
            val registered = sensorManager.registerListener(this, it, chosenSensorDelay)
            if (!registered) {
                Log.e(classTag, "Failed to register listener for Gyroscope sensor.")
            } else {
                Log.d(classTag, "Successfully registered listener for Gyroscope sensor.")
            }
        }
    }

    /**
     * Stops listening to all sensor updates.
     * Unregisters listeners to save battery and resources.
     */
    fun stop() {
        Log.d(classTag, "Stopping sensor listeners.")
        sensorManager.unregisterListener(this) // Unregisters for all sensors attached to this listener
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values == null) {
            Log.w(classTag, "Received sensor event with null values for ${event.sensor.name}")
            return
        }

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Apply low-pass filter to estimate gravity
                gravityComponents[0] = lowPassFilterAlpha * gravityComponents[0] + (1 - lowPassFilterAlpha) * event.values[0]
                gravityComponents[1] = lowPassFilterAlpha * gravityComponents[1] + (1 - lowPassFilterAlpha) * event.values[1]
                gravityComponents[2] = lowPassFilterAlpha * gravityComponents[2] + (1 - lowPassFilterAlpha) * event.values[2]

                // Calculate linear acceleration by removing gravity
                currentAccelData[0] = event.values[0] - gravityComponents[0]
                currentAccelData[1] = event.values[1] - gravityComponents[1]
                currentAccelData[2] = event.values[2] - gravityComponents[2]

                if (!hasInitialAccelUpdate) {
                    hasInitialAccelUpdate = true
                    Log.d(classTag, "Received initial accelerometer update.")
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                // Store angular velocity (rad/s)
                // Using System.arraycopy for potentially safer copy if event.values.size varies,
                // though for gyroscope it's typically 3.
                System.arraycopy(event.values, 0, currentGyroData, 0, currentGyroData.size.coerceAtMost(event.values.size))

                if (!hasInitialGyroUpdate) {
                    hasInitialGyroUpdate = true
                    Log.d(classTag, "Received initial gyroscope update.")
                }
            }
            else -> {
                // Should not happen if only registered for accel and gyro
                Log.w(classTag, "Received event from unexpected sensor type: ${event.sensor.type}")
                return
            }
        }

        // Only proceed to build and send payload if we have received at least one update from BOTH sensors.
        // This ensures the first payload is complete with meaningful data from accel and gyro.
        // Subsequent payloads will use the latest from each, even if one sensor updates more frequently
        // in a given short interval than the other (though with the same delay, they should be similar).
        if (hasInitialAccelUpdate && hasInitialGyroUpdate) {
            // Build binary payload (6 floats = 24 bytes, little-endian)
            // Order: Ax, Ay, Az, Gx, Gy, Gz
            val payloadBuffer = ByteBuffer.allocate(24) // 3 accel floats + 3 gyro floats = 6 floats * 4 bytes/float
            payloadBuffer.order(ByteOrder.LITTLE_ENDIAN)

            currentAccelData.forEach { payloadBuffer.putFloat(it) }
            currentGyroData.forEach { payloadBuffer.putFloat(it) }

            // Invoke the callback with the prepared byte array
            onDataReady(payloadBuffer.array())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // This callback is triggered when the accuracy of a sensor changes.
        // You might want to log this for debugging or inform the user if accuracy is low.
        val accuracyString = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "UNRELIABLE"
            else -> "UNKNOWN ($accuracy)"
        }
        Log.i(classTag, "Accuracy changed for sensor: ${sensor?.name ?: "Unknown"}, new accuracy: $accuracyString")
    }
}