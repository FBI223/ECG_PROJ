package com.pz.ecg_project

import android.content.Context
import com.pz.ecg_project.ml.ModelFold1
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EcgPredictor(private val context: Context) {

    private val model = ModelFold1.newInstance(context)

    private fun floatArrayToByteBuffer(data: FloatArray): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(data.size * 4) // 4 bytes per float
        byteBuffer.order(ByteOrder.nativeOrder()) // Match the system's endianness

        for (value in data) {
            byteBuffer.putFloat(value)
        }

        byteBuffer.rewind() // Reset position to zero before use
        return byteBuffer
    }

    /**
     * Predicts the class index from the ECG sample array.
     * @param inputSamples ECG float array of fixed length.
     * @return The index of the predicted class (e.g., 0, 1, or 2).
     */
    fun predict(inputSamples: FloatArray): Int {
        // Creates inputs for reference.
        val inputFeature0 = TensorBuffer.createFixedSize(intArrayOf(1, 1, 540), DataType.FLOAT32)
        inputFeature0.loadBuffer(floatArrayToByteBuffer(inputSamples))

        // Runs model inference and gets result.
        val outputs = model.process(inputFeature0)
        val classProbabilities = outputs.outputFeature0AsTensorBuffer.floatArray

        val predictedClass = classProbabilities.indices.maxBy { classProbabilities[it] } ?: -1

        return predictedClass

    }

    fun close() {
        model.close()
    }
}
