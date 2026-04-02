package com.CMPS490.weathertracker.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Retrofit instance for local FastAPI backend
 * Base URL: http://localhost:5000 (emulator) or http://10.0.2.2:5000 (Android device)
 */
object BackendRetrofitInstance {
    
    private val retrofit by lazy {
        // Use 10.0.2.2 for emulator, localhost for physical devices
        // The backend should be running on http://localhost:5000
        val baseUrl = "http://10.0.2.2:5000/"
        
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    val api: BackendApi by lazy {
        retrofit.create(BackendApi::class.java)
    }
}
