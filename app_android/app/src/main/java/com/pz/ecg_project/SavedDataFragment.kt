package com.pz.ecg_project

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.pz.ecg_project.databinding.FragmentSecondBinding
import java.io.IOException
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SavedDataFragment : Fragment() {

    private var _binding: FragmentSecondBinding? = null
    private lateinit var chart: LineChart
    private var isLoading = false

    // This property is only valid between onCreateView and onDestroyView
    private val binding get() = _binding!!

    private var datUri: Uri? = null
    private var heaUri: Uri? = null

    private val pickDatLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        datUri = uri
        checkReadyToLoad()
    }

    private val pickHeaLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        heaUri = uri
        checkReadyToLoad()
    }

    private fun checkReadyToLoad() {
        if (datUri != null && heaUri != null) {
            loadData(datUri!!, heaUri!!)
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

        // Set up button to trigger file picker
        binding.buttonLoad.setOnClickListener {
            if (!isLoading) {
                openFilePicker()
            } else {
                Toast.makeText(requireContext(), "Loading in progress. Please wait...", Toast.LENGTH_SHORT).show()
            }
        }

    }

    private fun openFilePicker() {
        Toast.makeText(requireContext(), "Select the .hea file", Toast.LENGTH_LONG).show()
        pickDatLauncher.launch("*/*")

        Toast.makeText(requireContext(), "Then, select the corresponding .dat file", Toast.LENGTH_LONG).show()
        pickHeaLauncher.launch("*/*")
    }

    private fun loadData(datUri: Uri, heaUri: Uri) {
        if (isLoading) {
            Toast.makeText(requireContext(), "Please wait, waveform is still loading...", Toast.LENGTH_SHORT).show()
            return
        }

        isLoading = true
        Toast.makeText(requireContext(), "Loading signal data...", Toast.LENGTH_LONG).show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val waveforms = withContext(Dispatchers.IO) {
                    val datStream = requireContext().contentResolver.openInputStream(datUri)
                    val heaStream = requireContext().contentResolver.openInputStream(heaUri)

                    if (datStream != null && heaStream != null) {
                        SignalReader(datStream, heaStream).read()
                    } else null
                }

                if (waveforms != null && waveforms[0] != null) {
                    plotEcg(waveforms[0]!!.samples)
                } else {
                    Toast.makeText(requireContext(), "Failed to open input streams", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Log.d("DAT", "Stack: ${Log.getStackTraceString(e)}")
                Toast.makeText(requireContext(), "Error reading signal data", Toast.LENGTH_SHORT).show()
            }
            finally {
                isLoading = false
            }
        }
    }

    // Plot ECG data using MPAndroidChart
    private fun plotEcg(data: FloatArray) {
        resetChart(chart)

        val entries = ArrayList<Entry>()
        val fs = 360
        val duration = data.size / fs.toFloat()

        // Populate the chart data
        for (i in data.indices) {
            entries.add(Entry(i / fs.toFloat(), data[i]))
        }

        val dataSet = LineDataSet(entries, "ECG Signal")
        dataSet.setDrawCircles(false)
        dataSet.setDrawCircleHole(false)

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
