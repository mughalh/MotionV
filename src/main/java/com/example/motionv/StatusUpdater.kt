package com.example.motionv

import android.widget.TextView

// Assuming your StatusUpdater looks something like this
class StatusUpdater(private val statusTextView: TextView) {
    enum class State {
        IDLE,
        REQUESTING_PERMISSIONS,
        PERMISSIONS_DENIED,
        BLUETOOTH_OFF,
        STARTING_SERVICE,
        ADVERTISING,
        CONNECTED,
        DISCONNECTED_FROM_DEVICE,
        SERVICE_STOPPED,
        ERROR
    }

    fun update(state: State, message: String? = null) {
        val statusMessage = when (state) {
            State.IDLE -> "Idle"
            State.REQUESTING_PERMISSIONS -> "Requesting permissions..."
            State.PERMISSIONS_DENIED -> "Permissions denied. Service cannot start."
            State.BLUETOOTH_OFF -> "Bluetooth is OFF. Please turn it on."
            State.STARTING_SERVICE -> "Starting BLE Service..."
            State.ADVERTISING -> "Advertising... Waiting for connection."
            State.CONNECTED -> "Connected to: ${message ?: "Unknown Device"}"
            State.DISCONNECTED_FROM_DEVICE -> "Disconnected from: ${message ?: "Device"}. Advertising."
            State.SERVICE_STOPPED -> "Service Stopped."
            State.ERROR -> "Error: ${message ?: "Unknown error"}"
        }
        statusTextView.text = "Status: $statusMessage"
        // You might want to log here too
    }
}