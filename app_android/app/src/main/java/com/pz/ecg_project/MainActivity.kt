package com.pz.ecg_project

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import com.pz.ecg_project.databinding.ActivityMainBinding
import java.util.UUID
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Collections
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.preference.PreferenceManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothConnection: BluetoothConnection
    private val viewModel: SharedViewModel by viewModels()
    private val serviceUUID = UUID.fromString("bd37e8b4-1bcf-4f42-bdd1-bebea1a51a1a")
    private val characteristicUUID = UUID.fromString("7a1e8b7d-9a3e-4657-927b-339adddc2a5b")
    private val deviceName = "ESP32_EKG"
    private val enableBtLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    class ViewPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> LiveFragment()
                1 -> SavedDataFragment()
                else -> throw IllegalStateException("Unexpected position $position")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize the binding for the layout
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Swiping
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        viewPager.adapter = ViewPagerAdapter(this)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Live Data"
                1 -> "Saved Data"
                else -> "Tab ${position + 1}"
            }
        }.attach()

        // Start settings automatically on first start
        val sharedPref = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val isFirstLaunch = sharedPref.getBoolean("first_launch", true)

        if (isFirstLaunch) {
            // Launch SettingsActivity
            startActivity(Intent(this, SettingsActivity::class.java))

            // Mark as not first launch
            sharedPref.edit { putBoolean("first_launch", false) }
        }

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

        // Set up toolbar manually
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        val ecgDataList = Collections.synchronizedList(mutableListOf<Float>())

        // Use the FAB button
        binding.fab.setOnClickListener { _ ->
            lifecycleScope.launch(Dispatchers.IO)  {
                if (bluetoothConnection.isSubscribed()) {
                    bluetoothConnection.unsubscribe()

                    val currData: FloatArray
                    synchronized(ecgDataList) {
                        currData = ecgDataList.toFloatArray()
                    }

                    if (currData.isEmpty()) return@launch

                    val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
                    val modelValue = sharedPreferences.getString("model", "peak")
                    val samplingValue = sharedPreferences.getString("sampling_rate", "128")!!.toInt()

                    val ecgPredictor = EcgPredictor(applicationContext, modelValue.toString())

                    runOnUiThread {
                        val table = findViewById<TableLayout>(R.id.predictionTable)
                        table.removeAllViews()
                    }

                    if (modelValue == "rhythm") {
                        val sample = currData.copyOf(samplingValue * 10)
                        val waveform = Waveform(samplingValue, sample)
                        waveform.linearResample(500)

                        val predictedClass = ecgPredictor.predict(waveform.samples)
                        Log.d("Prediction", "Predicted class: $predictedClass")

                        val freqs = IntArray(8)
                        val labels = arrayOf("NSR ", "AF_FLUTTER ", "PAC ", "PVC ", "BBB ", "SVT ", "AV_BLOCK ", "TORSADES ")
                        freqs[predictedClass]++

                        val maxI = freqs.indices.maxBy { freqs[it] }
                        viewModel.setPredictionResults(intArrayOf(freqs[maxI]), arrayOf(labels[maxI]))
                    }
                    else {
                        val waveform = Waveform(samplingValue, currData)
                        waveform.linearResample(360)

                        val peaks = waveform.detectQRS()
                        val windows = waveform.extractWindows(peaks)

                        val freqs = IntArray(5)
                        val labels = arrayOf("Normal (N)", "Supraventricular (S)", "Ventricular (V)", "Fusion (F)", "Unknown (Q)")

                        for (i in windows.indices) {
                            val predictedClass = ecgPredictor.predict(windows[i])
                            Log.d("Prediction", "Peak $i, Predicted class: $predictedClass")
                            freqs[predictedClass]++

                        }
                        viewModel.setPredictionResults(freqs, labels)
                        Log.d("Prediction", "End")
                    }
                }
                else {
                    synchronized(ecgDataList) {
                        ecgDataList.clear()
                        viewModel.clearEcgData()
                    }
                    bluetoothConnection.resubscribe()
                }
            }
        }

        lifecycleScope.launch {
            bluetoothConnection.ecgFlow.collect { value ->
                viewModel.pushEcgData(value)
                ecgDataList.add(value)
            }
        }


    }

    private fun requestBluetoothPermissions() {
        val permissions = mutableListOf<String>()

        permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        permissions.add(Manifest.permission.BLUETOOTH_CONNECT)

        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (bluetoothConnection.bluetoothAdapter?.isEnabled == false) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }

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
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }


}
