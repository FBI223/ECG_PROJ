package com.pz.ecg_project

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.*
import androidx.annotation.RequiresPermission
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class BluetoothConnection(
    private val context: Context,
    private val deviceName: String,
    private val serviceUUID: UUID,
    private val characteristicUUID: UUID,
    private val callback: Callback,
) {
    private val cccDescriptorUUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    interface Callback {
        fun onDeviceFound(device: BluetoothDevice)
        fun onConnected(gatt: BluetoothGatt)
        fun onDisconnected()
        fun onScanFinished()
    }
    private val incomingDataChannel = Channel<Float>(Channel.UNLIMITED)
    val ecgFlow: Flow<Float> = incomingDataChannel.receiveAsFlow()

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(BluetoothManager::class.java)
        manager?.adapter
    }

    private val scanner: BluetoothLeScanner? by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }

    private var scanning = false
    private var gatt: BluetoothGatt? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val foundName = if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return
                }
                else
                    device.name
                Log.d("BLE", "Found device: $deviceName (${device.address})")

                if (foundName != null && foundName.contains(deviceName, ignoreCase = true)) {
                    callback.onDeviceFound(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE", "Scan failed: $errorCode")
            scanning = false
            callback.onScanFinished()
        }
    }

    fun startScan(scanTimeMillis: Long = 10000L) {
        if (scanning || scanner == null) return

        val filters = emptyList<ScanFilter>() // No filters

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        scanner?.startScan(filters, settings, scanCallback)
        scanning = true
        Log.d("BLE", "Started scanning for BLE devices")

        Handler(Looper.getMainLooper()).postDelayed({
            stopScan()
        }, scanTimeMillis)
    }

    fun stopScan() {
        if (!scanning) return
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        scanner?.stopScan(scanCallback)
        scanning = false
        Log.d("BLE", "Stopped BLE scan")
        callback.onScanFinished()
    }

    fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        gatt = device.connectGatt(context, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BLE", "Connected, discovering services...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BLE", "Disconnected from GATT")
                callback.onDisconnected()
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("BLE", "Service discovery failed with status $status")
                return
            }

            val service = gatt.getService(serviceUUID)
            if (service == null) {
                Log.e("BLE", "Target service not found")
                return
            }

            val characteristic = service.getCharacteristic(characteristicUUID)
            if (characteristic == null) {
                Log.e("BLE", "Target characteristic not found")
                return
            }

            val success = gatt.setCharacteristicNotification(characteristic, true)
            if (!success) {
                Log.e("BLE", "Failed to enable characteristic notifications")
                return
            }

            val descriptor = characteristic.getDescriptor(cccDescriptorUUID)
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val writeSuccess = gatt.writeDescriptor(it)
                if (!writeSuccess) {
                    Log.e("BLE", "Failed to write descriptor for notifications")
                }
            }

            Log.d("BLE", "Subscribed to characteristic notifications")
        }

        private val metaBuffer = StringBuilder()

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value

            if (data.size == 4) {
                // Probably a float (ECG sample)
                val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val ecgValue = buffer.float
                Log.d("BLE", "📈 ECG Sample: $ecgValue")

                incomingDataChannel.trySend(ecgValue)
            } else {
                // Treat it as part of a metadata string
                val part = String(data, Charsets.UTF_8)

                if (part.startsWith("META;")) {
                    metaBuffer.clear() // New metadata message starting
                }

                metaBuffer.append(part)

                // Check for end of JSON: very basic heuristic
                if (metaBuffer.contains("{") && metaBuffer.contains("}")) {
                    val fullMeta = metaBuffer.toString()
                    Log.d("BLE", "📄 Full META received: $fullMeta")

                    try {
                        val json = fullMeta.removePrefix("META;")
                        val obj = JSONObject(json)

                        val id = obj.getInt("id")
                        val fs = obj.getInt("fs")
                        val gain = obj.getInt("gain")
                        val baseline = obj.getInt("baseline")
                        val name = obj.getString("name")

                        Log.d("BLE", "Parsed META ➜ id=$id, fs=$fs, gain=$gain, baseline=$baseline, name=$name")
                    } catch (e: Exception) {
                        Log.e("BLE", "❌ Failed to parse metadata: $e")
                    }

                    metaBuffer.clear()
                }
            }
        }


    }

    fun disconnect() {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        gatt?.close()
        gatt = null
    }
}
