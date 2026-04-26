package com.CMPS490.weathertracker.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit instance for local FastAPI backend
 * Emulator: http://10.0.2.2:5000
 * Physical device (adb reverse): http://127.0.0.1:5000
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
        val baseUrl = BackendConfig.baseUrl
        
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
