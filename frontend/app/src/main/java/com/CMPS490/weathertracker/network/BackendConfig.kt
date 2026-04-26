package com.CMPS490.weathertracker.network

import android.os.Build

/**
 * Backend URL selection:
 * - Emulator: use 10.0.2.2 to reach host machine localhost
 * - Physical device (with adb reverse): use 127.0.0.1
 */
object BackendConfig {
    val baseUrl: String
        get() = if (isEmulator()) "http://10.0.2.2:5000/" else "http://127.0.0.1:5000/"

    fun endpoint(path: String): String {
        val trimmedPath = path.trimStart('/')
        return baseUrl + trimmedPath
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic") ||
            Build.PRODUCT.contains("sdk")
    }
}
