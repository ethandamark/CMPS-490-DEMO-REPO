package com.CMPS490.weathertracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "weather_cache")
data class WeatherCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "cache_id") val cacheId: String,
    @ColumnInfo(name = "temp") val temp: Double?,
    @ColumnInfo(name = "humidity") val humidity: Double?,
    @ColumnInfo(name = "wind_speed") val windSpeed: Double?,
    @ColumnInfo(name = "wind_direction") val windDirection: Double?,
    @ColumnInfo(name = "precipitation_amount") val precipitationAmount: Double?,
    @ColumnInfo(name = "pressure") val pressure: Double?,
    @ColumnInfo(name = "weather_condition") val weatherCondition: String?,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "latitude") val latitude: Double,
    @ColumnInfo(name = "longitude") val longitude: Double,
    @ColumnInfo(name = "result_level") val resultLevel: String?,
    @ColumnInfo(name = "result_type") val resultType: String?,
    @ColumnInfo(name = "is_forecast") val isForecast: Boolean = false,
    // ML columns
    @ColumnInfo(name = "dew_point_c") val dewPointC: Double?,
    @ColumnInfo(name = "elevation") val elevation: Double?,
    @ColumnInfo(name = "dist_to_coast_km") val distToCoastKm: Double?,
    @ColumnInfo(name = "nwp_cape_f3_6_max") val nwpCapeF36Max: Double?,
    @ColumnInfo(name = "nwp_cin_f3_6_max") val nwpCinF36Max: Double?,
    @ColumnInfo(name = "nwp_pwat_f3_6_max") val nwpPwatF36Max: Double?,
    @ColumnInfo(name = "nwp_srh03_f3_6_max") val nwpSrh03F36Max: Double?,
    @ColumnInfo(name = "nwp_li_f3_6_min") val nwpLiF36Min: Double?,
    @ColumnInfo(name = "nwp_lcl_f3_6_min") val nwpLclF36Min: Double?,
    @ColumnInfo(name = "nwp_available_leads") val nwpAvailableLeads: Double?,
    @ColumnInfo(name = "mrms_max_dbz_75km") val mrmsMaxDbz75km: Double?,
)
