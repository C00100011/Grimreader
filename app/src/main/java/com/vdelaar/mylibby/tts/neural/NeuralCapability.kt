package com.vdelaar.mylibby.tts.neural

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs

/** Whether this phone can run the on-device natural voice, and if not, why. */
enum class NeuralFit { OK, UNSUPPORTED_CPU, LOW_MEMORY, LOW_STORAGE }

object NeuralCapability {
    /** The model runs through ONNX Runtime; below this much RAM synthesis stalls or the app gets killed. */
    const val MIN_RAM_BYTES = 3_500_000_000L

    /** Room for the download plus the unpacked model and some working space. */
    const val MIN_FREE_BYTES = NeuralVoiceStore.TOTAL_BYTES * 2

    /** Pure decision, so it can be tested. A phone reporting "4 GB" shows about 3.7 GB, hence the margin above. */
    fun assess(abis: List<String>, totalRamBytes: Long, freeBytes: Long): NeuralFit = when {
        abis.none { it == "arm64-v8a" || it == "x86_64" } -> NeuralFit.UNSUPPORTED_CPU
        totalRamBytes in 1 until MIN_RAM_BYTES -> NeuralFit.LOW_MEMORY
        freeBytes in 0 until MIN_FREE_BYTES -> NeuralFit.LOW_STORAGE
        else -> NeuralFit.OK
    }

    fun check(context: Context): NeuralFit {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val ram = ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem
        val free = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(-1L)
        return assess(Build.SUPPORTED_ABIS.toList(), ram, free)
    }
}
