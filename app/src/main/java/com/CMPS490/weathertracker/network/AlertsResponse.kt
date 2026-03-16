package com.CMPS490.weathertracker.network

data class AlertsResponse(
    val features: List<AlertFeature>
)

data class AlertFeature(
    val properties: AlertProperties
)

data class AlertProperties(
    val event: String?,
    val headline: String?,
    val description: String?
)