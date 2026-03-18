package com.CMPS490.weathertracker

import com.CMPS490.weathertracker.network.RainViewerResponse

const val RAIN_VIEWER_MIN_ZOOM = 0
const val RAIN_VIEWER_MAX_ZOOM = 7

data class RainViewerRadarFrame(
    val epochSeconds: Long,
    val tileTemplate: String
)

fun buildRainViewerFrames(response: RainViewerResponse): List<RainViewerRadarFrame> {
    val host = response.host
    val pastFrames = response.radar?.past.orEmpty().map { frame ->
        RainViewerRadarFrame(
            epochSeconds = frame.time,
            tileTemplate = "${host}${frame.path}/256/{z}/{x}/{y}/2/1_1.png"
        )
    }
    val nowcastFrames = response.radar?.nowcast.orEmpty().map { frame ->
        RainViewerRadarFrame(
            epochSeconds = frame.time,
            tileTemplate = "${host}${frame.path}/256/{z}/{x}/{y}/2/1_1.png"
        )
    }
    return pastFrames + nowcastFrames
}

fun buildRainViewerTileTemplate(response: RainViewerResponse): String? {
    return buildRainViewerFrames(response).lastOrNull()?.tileTemplate
}
