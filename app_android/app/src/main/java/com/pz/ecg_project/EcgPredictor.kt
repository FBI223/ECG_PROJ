package com.pz.ecg_project

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.pz.ecg_project.ml.ModelFold1
import com.pz.ecg_project.ml.ModelMobileSingle
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EcgPredictor(private val context: Context, private val model: String) {

    private val modelFold = ModelFold1.newInstance(context)
    private val modelMobileSingle = ModelMobileSingle.newInstance(context)

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
        val classProbabilities : FloatArray
        val probs : String
        if (model == "rhythm") {
            // Creates inputs for reference.
            val inputFeature0 = TensorBuffer.createFixedSize(intArrayOf(1, 1, 5000), DataType.FLOAT32)
            inputFeature0.loadBuffer(floatArrayToByteBuffer(inputSamples))

            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            val values = floatArrayOf(
                sharedPreferences.getString("age", "20")!!.toFloat(),
                if (sharedPreferences.getString("gender", "male") == "male") 1.0f else 0.0f
            )

            val inputFeature1 = TensorBuffer.createFixedSize(intArrayOf(1, 2), DataType.FLOAT32)
            inputFeature1.loadBuffer(floatArrayToByteBuffer(values))

            // Runs model inference and gets result.
            val outputs = modelMobileSingle.process(inputFeature0, inputFeature1)
            classProbabilities = outputs.outputFeature0AsTensorBuffer.floatArray
            probs = classProbabilities.contentToString()

        }
        else {
            // Creates inputs for reference.
            val inputFeature0 = TensorBuffer.createFixedSize(intArrayOf(1, 1, 540), DataType.FLOAT32)
            inputFeature0.loadBuffer(floatArrayToByteBuffer(inputSamples))

            // Runs model inference and gets result.
            val outputs = modelFold.process(inputFeature0)
            classProbabilities = outputs.outputFeature0AsTensorBuffer.floatArray
            probs = classProbabilities.contentToString()
        }

        Log.d("Prediction", "Class probabilities: $probs")
        val predictedClass = classProbabilities.indices.maxBy { classProbabilities[it] }

        return predictedClass

    }

    fun close() {
        modelFold.close()
    }
}
