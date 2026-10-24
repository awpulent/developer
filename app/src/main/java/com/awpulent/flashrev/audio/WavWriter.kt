package com.awpulent.flashrev.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes 16-bit PCM mono/stereo WAV files. Pure JVM so it can be used from tests. */
object WavWriter {
    fun write(file: File, samples: ShortArray, sampleRate: Int, channels: Int = 1) {
        file.parentFile?.mkdirs()
        val dataBytes = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                       // PCM chunk size
        header.putShort(1)                      // PCM format
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * 2) // byte rate
        header.putShort((channels * 2).toShort()) // block align
        header.putShort(16)                     // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)

        BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { out ->
            out.write(header.array())
            val buf = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < samples.size) {
                buf.clear()
                val n = minOf(4096, samples.size - i)
                for (k in 0 until n) buf.putShort(samples[i + k])
                out.write(buf.array(), 0, n * 2)
                i += n
            }
        }
    }
}
