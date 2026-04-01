package com.CMPS490.weathertracker.network

data class PointResponse(
    val properties: PointProperties
)

data class PointProperties(
    val forecast: String,
    val relativeLocation: RelativeLocation?
)

data class RelativeLocation(
    val properties: RelativeLocationProperties
)

data class RelativeLocationProperties(
    val city: String?,
    val state: String?
)
