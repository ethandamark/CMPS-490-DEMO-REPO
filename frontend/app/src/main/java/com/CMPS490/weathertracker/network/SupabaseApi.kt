package com.CMPS490.weathertracker.network

import com.CMPS490.weathertracker.AnonymousUserRecord
import com.CMPS490.weathertracker.DeviceRecord
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.POST

interface SupabaseApi {
    @POST("rest/v1/anonymous_users")
    suspend fun createAnonUser(@Body record: AnonymousUserRecord): ResponseBody

    @POST("rest/v1/devices")
    suspend fun createDevice(@Body record: DeviceRecord): ResponseBody
}
