package com.CMPS490.weathertracker.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit instance for local FastAPI backend
 * Base URL: http://localhost:5000 (emulator) or http://10.0.2.2:5000 (Android device)
 */
object BackendRetrofitInstance {
    
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val retrofit by lazy {
        // Use 10.0.2.2 for emulator, localhost for physical devices
        // The backend should be running on http://localhost:5000
        val baseUrl = "http://10.0.2.2:5000/"
        
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    val api: BackendApi by lazy {
        retrofit.create(BackendApi::class.java)
    }
}
