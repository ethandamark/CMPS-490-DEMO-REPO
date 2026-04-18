package com.CMPS490.weathertracker

import retrofit2.http.Body
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Query

interface SupabaseApi {
    @POST("/rest/v1/anonymous_user")
    suspend fun createAnonUser(@Body record: AnonymousUserRecord): AnonymousUserRecord

    @POST("/rest/v1/device")
    suspend fun createDevice(@Body record: DeviceRecord): DeviceRecord

    @PATCH("/rest/v1/device")
    suspend fun updateDevice(
        @Query("device_id") deviceIdEqFilter: String,
        @Body record: DeviceUpdateRecord
    ): DeviceRecord
}
