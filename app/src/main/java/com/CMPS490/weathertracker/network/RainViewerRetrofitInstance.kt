package com.CMPS490.weathertracker.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RainViewerRetrofitInstance {
    private val retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.rainviewer.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val api: RainViewerApi by lazy {
        retrofit.create(RainViewerApi::class.java)
    }
}
