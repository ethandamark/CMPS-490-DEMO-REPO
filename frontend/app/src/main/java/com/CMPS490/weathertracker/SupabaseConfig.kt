package com.CMPS490.weathertracker

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object SupabaseConfig {
    private const val TAG = "SupabaseConfig"
    private var retrofit: Retrofit? = null
    private var supabaseApi: SupabaseApi? = null

    fun initialize(context: Context) {
        if (retrofit != null) {
            Log.d(TAG, "Supabase already initialized, skipping...")
            return
        }

        // Determine the base URL based on whether we're running on an emulator
        val baseUrl = if (isRunningOnEmulator()) {
            Log.d(TAG, "Emulator detected - using 10.0.2.2:54321")
            "http://10.0.2.2:54321" // Emulator host alias
        } else {
            Log.d(TAG, "Physical device detected - using localhost:54321")
            "http://localhost:54321" // Local device
        }

        Log.d(TAG, "Initializing Supabase with baseUrl: $baseUrl")
        
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .addHeader("apikey", "sb_publishable_ACJWlzQHlZjBrEguHvfOxg_3BJgxAaH")
                    .addHeader("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            }
            .build()

        val gson = GsonBuilder().create()

        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

        supabaseApi = retrofit!!.create(SupabaseApi::class.java)
        Log.d(TAG, "✓ Supabase client initialized successfully")
    }

    fun getApi(): SupabaseApi {
        return supabaseApi ?: throw IllegalStateException("Supabase not initialized. Call initialize() first.")
    }

    private fun isRunningOnEmulator(): Boolean {
        // Check for common emulator identifiers
        return (Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for x86") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
                "QA".contains(Build.DEVICE?.getOrNull(0)?.toString() ?: "") ||
                (Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") ||
                Build.PRODUCT?.contains("emulator") == true ||
                Build.PRODUCT?.contains("sdk") == true)
    }
}
