package com.pz.ecg_project

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.pz.ecg_project.databinding.FragmentFirstBinding

import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.charts.LineChart

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SharedViewModel by activityViewModels()
    private lateinit var chart: LineChart
    private lateinit var dataSet: LineDataSet
    private var xValue = 0f

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Example button click navigation to SecondFragment
        binding.buttonFirst.setOnClickListener {
            findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment)
        }
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

    }

    fun addEcgEntry(value: Float) {
        dataSet.addEntry(Entry(xValue, value))
        xValue += 1f

        chart.data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(100f)
        chart.moveViewToX(xValue)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // Prevent memory leaks
    }
}
