package com.pz.ecg_project

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SharedViewModel : ViewModel() {
    private val _statusMessage = MutableLiveData<String>("Waiting for device...")
    val statusMessage: LiveData<String> = _statusMessage
    val ecgValue = MutableLiveData<Float>()

    fun pushEcgData(value: Float) {
        ecgValue.postValue(value)
    }

    fun updateMessage(message: String) {
        _statusMessage.value = message
    }
}
