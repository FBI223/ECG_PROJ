package com.pz.ecg_project

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.pz.ecg_project.databinding.FragmentSecondBinding
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import androidx.navigation.fragment.findNavController

class SecondFragment : Fragment() {

    private var _binding: FragmentSecondBinding? = null
    private lateinit var chart: LineChart
    private var record: Array<FloatArray>? = null  // To hold ECG data

    // This property is only valid between onCreateView and onDestroyView
    private val binding get() = _binding!!

    // Define the ActivityResultLauncher to pick a file
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val filePath = it.path
            if (filePath != null) {
                loadData(filePath)
            }
        } ?: run {
            Toast.makeText(requireContext(), "No file selected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentSecondBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize the LineChart
        chart = binding.lineChart

        binding.buttonSecond.setOnClickListener {
            findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
        }

        // Set up button to trigger file picker
        binding.buttonLoad.setOnClickListener {
            openFilePicker()
        }

    }

    // Open file picker to select a .dat file
    private fun openFilePicker() {
        // Launch the file picker intent
        pickFileLauncher.launch("*/*")
    }

    // Load and process .dat file
    private fun loadData(filePath: String) {
        try {
            val file = File(filePath)
            val fileInputStream = FileInputStream(file)

            // Simulate ECG reading - replace with actual .dat file parsing logic (wfdb or custom)
            val signalData = readEcgData(fileInputStream)

            if (signalData != null) {
                record = signalData
                plotEcg(record!!)
            } else {
                Toast.makeText(requireContext(), "Invalid .dat file format", Toast.LENGTH_SHORT).show()
            }

        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "Error reading .dat file", Toast.LENGTH_SHORT).show()
        }
    }

    // Plot ECG data using MPAndroidChart
    private fun plotEcg(data: Array<FloatArray>) {
        val entries = ArrayList<Entry>()
        val fs = 500
        val duration = data[0].size / fs.toFloat()

        // Populate the chart data
        for (i in data[0].indices) {
            entries.add(Entry(i / fs.toFloat(), data[0][i]))
        }

        val dataSet = LineDataSet(entries, "ECG Signal")
        dataSet.color = ContextCompat.getColor(requireContext(), R.color.holoCyanLight)
        dataSet.valueTextColor = ContextCompat.getColor(requireContext(), R.color.black)

        val lineData = LineData(dataSet)
        chart.data = lineData

        // Limit the X axis to the signal duration or 10 seconds
        chart.xAxis.axisMaximum = 10f.coerceAtMost(duration)
        chart.invalidate()  // Refresh chart

        // Setting chart properties
        chart.setVisibleXRangeMaximum(10f)
    }

    private fun readEcgData(fileInputStream: FileInputStream): Array<FloatArray>? {
        return arrayOf(
            floatArrayOf(0f, 0.5f, 1f, 0.5f, 0f, -0.5f, -1f, -0.5f, 0f) // Placeholder data
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
