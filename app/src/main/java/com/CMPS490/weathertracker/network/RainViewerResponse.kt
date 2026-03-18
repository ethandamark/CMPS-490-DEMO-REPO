package com.CMPS490.weathertracker.network

data class RainViewerResponse(
    val version: String,
    val generated: Long,
    val host: String,
    val radar: RainViewerRadar?
)

data class RainViewerRadar(
    val past: List<RainViewerFrame> = emptyList(),
    val nowcast: List<RainViewerFrame> = emptyList()
)

data class RainViewerFrame(
    val time: Long,
    val path: String
)
