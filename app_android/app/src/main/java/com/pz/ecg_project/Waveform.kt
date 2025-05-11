package com.pz.ecg_project
import org.jtransforms.fft.DoubleFFT_1D

class Waveform(private var sampleRate: Int, private var samples: FloatArray) {

    /**
     * Resamples an input ECG signal from one sampling frequency to another using FFT-based interpolation.
     *
     * @param inputArray The input ECG data array.
     * @param newFs The new sampling frequency (samples per second).
     */
    fun fftResample(inputArray: FloatArray, oldFs: Int, newFs: Int) {
        val inputSize = inputArray.size
        val newSize = (inputSize * newFs / oldFs)

        // Perform FFT
        val fft = DoubleFFT_1D(inputSize.toLong())
        val complexArray = DoubleArray(inputSize * 2)

        // Fill the complexArray with the input samples (real part, imaginary part is 0)
        for (i in inputArray.indices) {
            complexArray[i * 2] = inputArray[i].toDouble()  // Real part
            complexArray[i * 2 + 1] = 0.0  // Imaginary part
        }

        // Perform the FFT
        fft.realForward(complexArray)

        // Create a new frequency array for resampling
        val resampledArray = DoubleArray(newSize * 2)

        // Calculate frequency scaling factor
        val scalingFactor = newFs.toDouble() / oldFs.toDouble()

        // Rescale the frequencies by modifying the real part of the complex array
        for (i in 0 until newSize) {
            val oldIndex = (i / scalingFactor).toInt()
            if (oldIndex * 2 < complexArray.size) {
                resampledArray[i * 2] = complexArray[oldIndex * 2]  // Real part
                resampledArray[i * 2 + 1] = complexArray[oldIndex * 2 + 1]  // Imaginary part
            }
        }

        // Perform inverse FFT to get the resampled signal in the time domain
        fft.realInverse(resampledArray, true)

        // Convert the result back to a FloatArray
        val outputArray = FloatArray(newSize)
        for (i in outputArray.indices) {
            outputArray[i] = resampledArray[i * 2].toFloat()  // Real part
        }

        this.samples = outputArray
        this.sampleRate = newFs
    }


    /**
     * Resamples an input array of floats from one sampling frequency to another using linear interpolation.
     *
     * @param inputArray The input data array.
     * @param newFs The new sampling frequency (samples per second).
     */
    fun linearResample(inputArray: FloatArray, newFs: Int) {
        val inputSize = inputArray.size
        val newSize = inputSize * newFs / sampleRate  // Calculate new array size
        val resampledArray = FloatArray(newSize)

        // Resample by linear interpolation
        for (i in 0 until newSize) {
            // Find the corresponding index in the original array (scaled index)
            val scaledIndex = i * sampleRate.toFloat() / newFs.toFloat()

            // Find the two nearest indices in the original array
            val leftIndex = scaledIndex.toInt()
            val rightIndex = (leftIndex + 1).coerceAtMost(inputSize - 1)

            // If the indices are the same, just copy the value
            if (leftIndex == rightIndex) {
                resampledArray[i] = inputArray[leftIndex]
            } else {
                // Interpolate between the two nearest samples
                val leftSample = inputArray[leftIndex]
                val rightSample = inputArray[rightIndex]
                val weight = scaledIndex - leftIndex
                resampledArray[i] = leftSample + weight * (rightSample - leftSample)
            }
        }
        this.samples = resampledArray
        this.sampleRate = newFs
    }

    /**
     * Method to detect QRS peaks in ECG signal.
     * @param ecgSamples array of ECG float samples
     * @return indices of detected QRS peaks
     */
    fun detectQRS(ecgSamples: FloatArray): List<Int> {
        val filtered = bandpassFilter(ecgSamples)
        val derivative = derivative(filtered)
        val squared = square(derivative)
        val integrated = movingWindowIntegration(squared)

        return findPeaks(integrated, filtered)
    }

    private fun bandpassFilter(signal: FloatArray): FloatArray {
        val out = FloatArray(signal.size)
        for (i in 1 until signal.size) {
            out[i] = signal[i] - signal[i - 1]
        }
        return out
    }

    private fun derivative(signal: FloatArray): FloatArray {
        val out = FloatArray(signal.size)
        for (i in 2 until signal.size - 2) {
            out[i] = (2 * signal[i + 1] + signal[i + 2] - signal[i - 2] - 2 * signal[i - 1]) / 8
        }
        return out
    }

    private fun square(signal: FloatArray): FloatArray {
        return signal.map { it * it }.toFloatArray()
    }

    private fun movingWindowIntegration(signal: FloatArray): FloatArray {
        val windowSize = (0.150 * sampleRate).toInt()
        val out = FloatArray(signal.size)
        for (i in windowSize until signal.size) {
            var sum = 0f
            for (j in 0 until windowSize) {
                sum += signal[i - j]
            }
            out[i] = sum / windowSize
        }
        return out
    }

    private fun findPeaks(integrated: FloatArray, original: FloatArray): List<Int> {
        val threshold = integrated.maxOrNull()?.times(0.6f) ?: 0f
        val peakIndices = mutableListOf<Int>()
        var i = 1
        while (i < integrated.size - 1) {
            if (integrated[i] > threshold &&
                integrated[i] > integrated[i - 1] &&
                integrated[i] > integrated[i + 1]
            ) {
                // Refine peak location using the original signal
                val searchWindow = 5
                var maxVal = original[i]
                var maxIdx = i
                for (j in (i - searchWindow)..(i + searchWindow)) {
                    if (j in original.indices && original[j] > maxVal) {
                        maxVal = original[j]
                        maxIdx = j
                    }
                }
                peakIndices.add(maxIdx)
                i += sampleRate / 4  // Skip ahead to avoid double-counting the same QRS
            } else {
                i++
            }
        }
        return peakIndices
    }
}
