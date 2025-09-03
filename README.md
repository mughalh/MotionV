![Alt text](./icon.png)


MotionV is an Android application that transforms your Android device into a Bluetooth Low Energy (BLE) peripheral. It reads data from the device's accelerometer and gyroscope sensors and broadcasts this data over BLE to any connected central device.

This project serves as an example of how to implement a BLE peripheral service on Android, handle sensor data, and manage BLE connections and characteristics.

Features
BLE Peripheral Mode: Advertises as a connectable BLE device.
Sensor Data Broadcasting:
Reads Accelerometer data (X, Y, Z axes).
Reads Gyroscope data (X, Y, Z axes).
Custom BLE Service & Characteristics:
Dedicated GATT service for motion data.
Separate characteristics for Accelerometer and Gyroscope data.
Uses notifications to send data streams to subscribed clients.
Foreground Service: Runs the BLE communication as a foreground service to ensure reliability even when the app is not in the active view.
Status Updates: Provides UI updates on the BLE advertising status, connections, and data transmission.
Permission Handling: Demonstrates handling of necessary Bluetooth and (if applicable) location permissions for modern Android versions.
Project Structure Highlights
BlePeripheralService.kt: The core Service class responsible for:
Setting up the GATT server.
Defining BLE services and characteristics.
Managing advertising and connections.
Handling read requests and descriptor writes (for enabling notifications).
Broadcasting sensor data via notifications.
MainActivity.kt: The main user interface for:
Starting/Stopping the BLE service.
Displaying connection status and logs.
Requesting necessary permissions.
MotionSensorManager.kt (or similar, e.g., MotionSensorManagerImpl): A helper class to abstract sensor data acquisition from SensorManager.
StatusUpdater.kt (or similar): Likely an interface or enum to manage status updates between the Service and Activity.
MotionUUIDs.kt (or similar): Contains the UUID definitions for the custom BLE service and characteristics.
BLE GATT Structure
Motion Service UUID: 6e400001-b5a3-f393-e0a9-e50e24dcca9e
Accelerometer Data Characteristic UUID: 6e400002-b5a3-f393-e0a9-e50e24dcca9e
Properties: Read, Notify
Data Format: Array of 3 Floats (X, Y, Z) - Little Endian.
Gyroscope Data Characteristic UUID: 6e400003-b5a3-f393-e0a9-e50e24dcca9e
Properties: Read, Notify
Data Format: Array of 3 Floats (X, Y, Z) - Little Endian.
Client Characteristic Configuration Descriptor (CCCD): 00002902-0000-1000-8000-00805f9b34fb (Used for enabling notifications on the above characteristics).
Getting Started
Prerequisites
Android Studio (latest stable version recommended).
An Android device with Bluetooth Low Energy capabilities and motion sensors (Accelerometer, Gyroscope).
Android API Level [Your minSdkVersion or higher, e.g., API 26 (Oreo) for foreground service requirements].
