package com.pz.ecg_project

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.github.mikephil.charting.charts.LineChart

class SharedViewModel : ViewModel() {
    data class PredictionResult(val freqs: IntArray, val labels: Array<String>)

    private val _statusMessage = MutableLiveData("Waiting for device...")
    val statusMessage: LiveData<String> = _statusMessage

    val ecgValue = MutableLiveData<Float>()
    private val ecgHistory = mutableListOf<Float>()
    private val _predictionResult = MutableLiveData<PredictionResult>()
    val predictionResult: LiveData<PredictionResult> = _predictionResult

    fun setPredictionResults(freqs: IntArray, labels: Array<String>) {
        _predictionResult.postValue(PredictionResult(freqs, labels))
    }
    fun pushEcgData(value: Float) {
        ecgValue.postValue(value)
        synchronized(ecgHistory) {
            ecgHistory.add(value)
        }
    }

    fun updateMessage(message: String) {
        _statusMessage.value = message
    }

    fun getEcgDataAsArray(): FloatArray {
        return synchronized(ecgHistory) {
            ecgHistory.toFloatArray()
        }
    }

    fun clearEcgData() {
        synchronized(ecgHistory) {
            ecgHistory.clear()
        }
    }

}
fun resetChart(chart: LineChart) {
    chart.clear()
    chart.highlightValues(null)
    chart.fitScreen()

    // Optional full cleanup
    chart.description.text = ""
    chart.legend.isEnabled = false
    chart.marker = null

    // Reset axes and view port
    chart.xAxis.resetAxisMinimum()
    chart.xAxis.resetAxisMaximum()
    chart.axisLeft.resetAxisMinimum()
    chart.axisLeft.resetAxisMaximum()
    chart.axisRight.resetAxisMinimum()
    chart.axisRight.resetAxisMaximum()

    chart.data = null
    chart.invalidate()
}

