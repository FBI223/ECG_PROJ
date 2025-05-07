package com.pz.ecg_project

class PeakDetection(private val sampleRate: Int = 200) {

    /**
     * Main method to detect QRS peaks in ECG signal.
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
