package com.example.weathermcpapp.network

data class PointResponse(
    val properties: Properties
)

data class Properties(
    val forecast: String
)