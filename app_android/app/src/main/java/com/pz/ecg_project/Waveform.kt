package com.pz.ecg_project
import org.jtransforms.fft.DoubleFFT_1D

class Waveform(var sampleRate: Int, var samples: FloatArray) {

    fun extractWindows(
        indices: List<Int>,
        windowRadius: Int = 270
    ): Array<FloatArray> {
        val windows = mutableListOf<FloatArray>()

        for (index in indices) {
            val start = index - windowRadius
            val end = index + windowRadius - 1

            if (start < 0 || end >= samples.size) continue

            val window = samples.sliceArray(start..end)
            windows.add(window)
        }

        return windows.toTypedArray()
    }

    /**
     * Resamples an input ECG signal from one sampling frequency to another using FFT-based interpolation.
     *
     * @param newFs The new sampling frequency (samples per second).
     */
    fun fftResample(newFs: Int) {
        val inputSize = samples.size
        val newSize = (inputSize * newFs / sampleRate)

        // Perform FFT
        val fft = DoubleFFT_1D(inputSize.toLong())
        val complexArray = DoubleArray(inputSize * 2)

        // Fill the complexArray with the input samples (real part, imaginary part is 0)
        for (i in samples.indices) {
            complexArray[i * 2] = samples[i].toDouble()  // Real part
            complexArray[i * 2 + 1] = 0.0  // Imaginary part
        }

        // Perform the FFT
        fft.realForward(complexArray)

        // Create a new frequency array for resampling
        val resampledArray = DoubleArray(newSize * 2)

        // Calculate frequency scaling factor
        val scalingFactor = newFs.toDouble() / sampleRate.toDouble()

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
     * @param newFs The new sampling frequency (samples per second).
     */
    fun linearResample(newFs: Int) {
        val inputSize = samples.size
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
                resampledArray[i] = samples[leftIndex]
            } else {
                // Interpolate between the two nearest samples
                val leftSample = samples[leftIndex]
                val rightSample = samples[rightIndex]
                val weight = scaledIndex - leftIndex
                resampledArray[i] = leftSample + weight * (rightSample - leftSample)
            }
        }
        this.samples = resampledArray
        this.sampleRate = newFs
    }

    /**
     * Method to detect QRS peaks in ECG signal.
     * @return indices of detected QRS peaks
     */
    fun detectQRS(): List<Int> {
        val filtered = bandpassFilter(samples)
        val derivative = derivative(filtered)
        val squared = square(derivative)
        val integrated = movingWindowIntegration(squared)

        return findPeaks(integrated, filtered)
    }

    private fun bandpassFilter(signal: FloatArray): FloatArray {
        // Simple high-pass filter (cutoff ~0.5 Hz)
        val highPass = FloatArray(signal.size)
        val alpha = 0.995f
        highPass[0] = signal[0]
        for (i in 1 until signal.size) {
            highPass[i] = alpha * (highPass[i - 1] + signal[i] - signal[i - 1])
        }

        // Simple low-pass filter (cutoff ~15 Hz)
        val lowPass = FloatArray(signal.size)
        val beta = 0.1f
        lowPass[0] = highPass[0]
        for (i in 1 until signal.size) {
            lowPass[i] = lowPass[i - 1] + beta * (highPass[i] - lowPass[i - 1])
        }

        return lowPass
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
