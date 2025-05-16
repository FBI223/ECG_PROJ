package com.pz.ecg_project

import android.util.Log
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.sign

class SignalReader(private val datFile: InputStream, private val heaFile: InputStream) {

    data class HeaderInfo(
        val signalCount: Int,
        val sampleRate: Int,
        val samplesPerSignal: Int
    )

    fun read(): Array<Waveform?> {
        val (header, signalSpecs) = readHeader()
        require(signalSpecs.all { it[0] == 212 }) { "All signals must use format 212." }
        val signals = readDatFile(header, signalSpecs)
        val waveforms = arrayOfNulls<Waveform>(signals.size)
        for ((i, signal) in signals.withIndex()) {
            waveforms[i] = Waveform(header.sampleRate, signal)
        }
        return waveforms
    }

    private fun readHeader(): Pair<HeaderInfo, List<Array<Int>>> {
        val lines = heaFile.bufferedReader().readLines()
        val mainHeader = lines.first().split(" ")
        Log.d("DAT", lines.first())
        val signalCount = mainHeader[1].toInt()
        val sampleRate = mainHeader[2].toInt()
        val samplesPerSignal = mainHeader[3].toInt()

        val signalSpecs = lines.drop(1)
            .filterNot { it.startsWith("#") }
            .take(signalCount)
            .map {
                val parts = it.split(Regex("\\s+"))
                val gainBaseline = parts.getOrElse(2) { "200" }.split(Regex("\\("), limit = 2)

                val gain = gainBaseline[0].toIntOrNull() ?: 200  // Default to 200 if not valid
                val baseline = gainBaseline.getOrElse(1) { "" }.removeSuffix(")").toIntOrNull() ?: 0

                arrayOf(parts.getOrElse(1) { "212" }.toInt(), gain, baseline)
            }

        val header = HeaderInfo(signalCount, sampleRate, samplesPerSignal)
        return header to signalSpecs
    }

    private fun readDatFile(header: HeaderInfo, signalSpecs: List<Array<Int>>): List<FloatArray> {
        val signalCount = header.signalCount
        val samplesPerSignal = header.samplesPerSignal
        val totalSamples = signalCount * samplesPerSignal

        // Each channel will get its own FloatArray
        val signals = List(signalCount) { FloatArray(samplesPerSignal) }

        var frameIndex = 0
        var signalIndex = 0
        var samplesRead = 0

        while (samplesRead < totalSamples) {
            val byte0 = datFile.read()
            val byte1 = datFile.read()
            val byte2 = datFile.read()

            if (byte2 == -1) break  // EOF

            val sample1Raw = ((byte1 and 0x0F) shl 8) or byte0
            val sample2Raw = ((byte1 and 0xF0) shl 4) or byte2

            val sample1 = (toSigned12Bit(sample1Raw).toFloat() - signalSpecs[signalIndex % signalCount][2]) / signalSpecs[signalIndex % signalCount][1]
            val sample2 = (toSigned12Bit(sample2Raw).toFloat() - signalSpecs[signalIndex % signalCount][2]) / signalSpecs[signalIndex % signalCount][1]

            // Interleaved across channels
            signals[signalIndex % signalCount][frameIndex] = sample1
            samplesRead++
            if (samplesRead < totalSamples) {
                signals[signalIndex % signalCount][frameIndex] = sample2
                samplesRead++
            }

            signalIndex++
            if (signalIndex % signalCount == 0) {
                frameIndex++
            }
            //Log.d("DAT", "Sample 1: $sample1 , Sample 2: $sample2")
        }

        return signals
    }

    private fun toSigned12Bit(value: Int): Int {
        return if (value and 0x800 != 0) {
            value or -0x1000  // Sign extend 12-bit to 32-bit
        } else {
            value
        }
    }
}
