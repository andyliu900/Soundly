package com.ideacode.soundly_core.util

import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

object FileUtils {

    /**
     * 将 Float PCM 写成 16bit PCM文件
     */
    fun writePcmToFile(
        pcm: FloatArray,
        sampleRate: Int
    ): File {
        val file = File.createTempFile("processed_", ".pcm")

        FileOutputStream(file).use { fos ->
            pcm.forEach { sample ->
                val s = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
                fos.write(s and 0xff)
                fos.write((s shr 8) and 0xff)
            }
        }

        return file
    }

    // Float PCM -> Short PCM
    fun floatToPcm16(floatPcm: FloatArray): ShortArray {
        val pcm16 = ShortArray(floatPcm.size)
        for (i in floatPcm.indices) {
            val v = (floatPcm[i] * Short.MAX_VALUE)
                .coerceIn(
                    Short.MIN_VALUE.toFloat(),
                    Short.MAX_VALUE.toFloat()
                )
            pcm16[i] = v.toInt().toShort()
        }
        return pcm16
    }

}