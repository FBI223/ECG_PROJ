package com.pz.ecg_project

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.documentfile.provider.DocumentFile
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.charts.LineChart
import com.pz.ecg_project.databinding.FragmentFirstBinding
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class LiveFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SharedViewModel by activityViewModels()
    private lateinit var chart: LineChart
    private lateinit var dataSet: LineDataSet
    private var xValue = 0f

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            requireContext().contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            saveFolderUri(treeUri)
            saveWaveformFiles(treeUri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.statusMessage.observe(viewLifecycleOwner) { message ->
            binding.statusTextView.text = message
        }

        chart = binding.lineChart

        dataSet = LineDataSet(mutableListOf(), "ECG Signal").apply {
            color = resources.getColor(R.color.purple_500, null)
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 2f
            mode = LineDataSet.Mode.LINEAR
        }

        val lineData = LineData(dataSet)
        chart.data = lineData
        chart.setTouchEnabled(false)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.xAxis.isEnabled = false
        chart.axisLeft.axisMinimum = -2f
        chart.axisLeft.axisMaximum = 2f

        viewModel.ecgValue.observe(viewLifecycleOwner) { value ->
            addEcgEntry(value)
        }

        // Set up the folder picker button click
        binding.btnSaveWaveform.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        val table = view.findViewById<TableLayout>(R.id.predictionTable)

        viewModel.predictionResult.observe(viewLifecycleOwner) { result ->
            val table = view.findViewById<TableLayout>(R.id.predictionTable)
            table.removeAllViews()

            for (i in result.freqs.indices) {
                val row = TableRow(requireContext())

                val labelView = TextView(requireContext()).apply {
                    text = result.labels[i]
                    textSize = 16f
                }

                val valueView = TextView(requireContext()).apply {
                    text = result.freqs[i].toString()
                    textSize = 16f
                }

                row.addView(labelView)
                row.addView(valueView)
                table.addView(row)
            }
        }

    }

    fun addEcgEntry(value: Float) {
        dataSet.addEntry(Entry(xValue, value))
        xValue += 1f

        chart.data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(100f)
        chart.moveViewToX(xValue)
    }

    private fun saveFolderUri(uri: Uri) {
        requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE).edit {
            putString("folder_uri", uri.toString())
        }
    }

    private fun saveWaveformFiles(folderUri: Uri) {
        val ecgArray = viewModel.getEcgDataAsArray()
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val samplingRateStr = sharedPreferences.getString("sampling_rate", "128")
        val samplingRate = samplingRateStr?.toIntOrNull() ?: 128

        val waveform = Waveform(sampleRate = samplingRate, samples = ecgArray)

        val dateFormat = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault())
        val recordName = "record_${dateFormat.format(Date())}"

        val pickedDir = DocumentFile.fromTreeUri(requireContext(), folderUri)
        if (pickedDir == null || !pickedDir.isDirectory) {
            Snackbar.make(requireView(), "Invalid folder selected", Snackbar.LENGTH_LONG).show()
            return
        }

        val heaFile = pickedDir.createFile("application/octet-stream", "$recordName.hea")
        if (heaFile != null) {
            requireContext().contentResolver.openOutputStream(heaFile.uri)?.use { output ->
                output.write(waveform.buildHeaderText("record").toByteArray())
            }
        } else {
            Snackbar.make(requireView(), "Failed to create .hea file", Snackbar.LENGTH_LONG).show()
            return
        }

        val datFile = pickedDir.createFile("application/octet-stream", "$recordName.dat")
        if (datFile != null) {
            requireContext().contentResolver.openOutputStream(datFile.uri)?.use { output ->
                waveform.writeDatFile(output)
            }
        } else {
            Snackbar.make(requireView(), "Failed to create .dat file", Snackbar.LENGTH_LONG).show()
            return
        }

        Snackbar.make(requireView(), "Waveform saved successfully!", Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // Prevent memory leaks
    }}
