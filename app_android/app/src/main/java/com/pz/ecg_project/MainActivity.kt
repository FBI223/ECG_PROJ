package com.pz.ecg_project

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.navigation.ui.navigateUp
import com.google.android.material.snackbar.Snackbar
import com.pz.ecg_project.databinding.ActivityMainBinding
import java.util.UUID
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothConnection: BluetoothConnection
    private val viewModel: SharedViewModel by viewModels()
    private val serviceUUID = UUID.fromString("bd37e8b4-1bcf-4f42-bdd1-bebea1a51a1a")
    private val characteristicUUID = UUID.fromString("7a1e8b7d-9a3e-4657-927b-339adddc2a5b")
    private val deviceName = "ESP32_EKG"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize the binding for the layout
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Bluetooth connection
        bluetoothConnection = BluetoothConnection(this, deviceName, serviceUUID, characteristicUUID, object : BluetoothConnection.Callback {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onDeviceFound(device: BluetoothDevice) {
                Log.d("MainActivity", "Found BLE device: ${device.name} (${device.address})")
                viewModel.updateMessage("Device found: ${device.name}")
                bluetoothConnection.stopScan()
                bluetoothConnection.connectToDevice(device)
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onConnected(gatt: BluetoothGatt) {
                Log.d("MainActivity", "Connected to BLE device: ${gatt.device.name}")
                viewModel.updateMessage("Connected to: ${gatt.device.name}")
            }

            override fun onDisconnected() {
                Log.d("MainActivity", "Disconnected from BLE device")
                viewModel.updateMessage("Disconnected.")
            }

            override fun onScanFinished() {
                Log.d("MainActivity", "BLE scan finished")
                viewModel.updateMessage("No device found.")
            }
        })

        // Request necessary permissions
        requestBluetoothPermissions()

        // Set up the toolbar as ActionBar
        setSupportActionBar(binding.toolbar)

        // Set up the ActionBar and navigation components
        val navController = findNavController(R.id.nav_host_fragment_content_main)  // Ensure we use the correct id
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        // Use the FAB button (optional)
        binding.fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                .setAction("Action", null)
                .setAnchorView(R.id.fab).show()
        }

        val ecgDataList = Collections.synchronizedList(mutableListOf<Float>())

        lifecycleScope.launch {
            bluetoothConnection.ecgFlow.collect { value ->
                viewModel.pushEcgData(value)
                ecgDataList.add(value)
            }
        }
        /*
        lifecycleScope.launch(Dispatchers.IO)  {
            Thread.sleep(20000)

            val currData: FloatArray
            synchronized(ecgDataList) {
                currData = ecgDataList.toFloatArray()
            }

            val ecgPredictor = EcgPredictor(applicationContext)

            val waveform = Waveform(128, currData)
            //waveform.fftResample(360)
            waveform.linearResample(360)

            val peaks = waveform.detectQRS()
            val windows = waveform.extractWindows(peaks)

            for (i in peaks.indices) {
                val peak = peaks[i]
                Log.d("Peaks", "QRS peak found at: $peak")
                val predictedClass = ecgPredictor.predict(windows[i])
                Log.d("Prediction", "Peak $i, Predicted class: $predictedClass")
            }
            Log.d("Prediction", "End")
        }
*/
    }

    private fun requestBluetoothPermissions() {
        val permissions = mutableListOf<String>()

        permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        permissions.add(Manifest.permission.BLUETOOTH_CONNECT)

        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val notGranted = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            bluetoothConnection.startScan()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.all { it.value }) {
            bluetoothConnection.startScan()
        } else {
            Log.e("MainActivity", "Required BLE permissions were not granted.")
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        bluetoothConnection.disconnect()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}
