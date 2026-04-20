package com.CMPS490.weathertracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "model_instance")
data class ModelInstanceEntity(
    @PrimaryKey
    @ColumnInfo(name = "instance_id") val instanceId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "version") val version: String,
    @ColumnInfo(name = "latitude") val latitude: Double,
    @ColumnInfo(name = "longitude") val longitude: Double,
    @ColumnInfo(name = "result_level") val resultLevel: Int,
    @ColumnInfo(name = "result_type") val resultType: String,
    @ColumnInfo(name = "confidence_score") val confidenceScore: Float,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "synced_at") val syncedAt: Long? = null,
)
