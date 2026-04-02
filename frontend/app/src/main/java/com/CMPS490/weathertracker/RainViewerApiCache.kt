package com.CMPS490.weathertracker

import android.util.Log
import com.CMPS490.weathertracker.network.RainViewerResponse
import com.CMPS490.weathertracker.network.RainViewerRetrofitInstance
import com.CMPS490.weathertracker.network.RainViewerRadar
import com.CMPS490.weathertracker.network.RainViewerFrame
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

private const val RAIN_VIEWER_CACHE_TTL_MS = 5 * 60 * 1000L

private data class RainViewerCacheEntry(
    val cachedAtMillis: Long,
    val frames: List<RainViewerRadarFrame>
)

object RainViewerApiCache {
    @Volatile
    private var entry: RainViewerCacheEntry? = null

    fun getFrames(): List<RainViewerRadarFrame>? {
        val cacheEntry = entry ?: return null
        val ageMillis = System.currentTimeMillis() - cacheEntry.cachedAtMillis
        if (ageMillis > RAIN_VIEWER_CACHE_TTL_MS) {
            entry = null
            return null
        }
        return cacheEntry.frames
    }

    fun putFrames(frames: List<RainViewerRadarFrame>) {
        if (frames.isEmpty()) {
            return
        }
        entry = RainViewerCacheEntry(
            cachedAtMillis = System.currentTimeMillis(),
            frames = frames
        )
    }
}

fun fetchRainViewerFramesCached(
    forceRefresh: Boolean = false,
    onSuccess: (List<RainViewerRadarFrame>) -> Unit,
    onFailure: () -> Unit
) {
    if (!forceRefresh) {
        RainViewerApiCache.getFrames()?.let { cachedFrames ->
            onSuccess(cachedFrames)
            return
        }
    }

    // Use BackendRepository to fetch weather maps
    BackendRepository.getWeatherMaps(
        onSuccess = { response ->
            try {
                val version = response.get("version")?.asString ?: ""
                val generated = response.get("generated")?.asLong ?: 0
                val host = response.get("host")?.asString ?: ""
                val radarObj = response.getAsJsonObject("radar")
                
                val radar = if (radarObj != null) {
                    RainViewerRadar(
                        past = radarObj.getAsJsonArray("past")?.mapNotNull { element ->
                            val frameObj = element.asJsonObject
                            RainViewerFrame(
                                time = frameObj.get("time")?.asLong ?: 0,
                                path = frameObj.get("path")?.asString ?: ""
                            )
                        } ?: emptyList(),
                        nowcast = radarObj.getAsJsonArray("nowcast")?.mapNotNull { element ->
                            val frameObj = element.asJsonObject
                            RainViewerFrame(
                                time = frameObj.get("time")?.asLong ?: 0,
                                path = frameObj.get("path")?.asString ?: ""
                            )
                        } ?: emptyList()
                    )
                } else null
                
                val rainViewerResponse = RainViewerResponse(
                    version = version,
                    generated = generated,
                    host = host,
                    radar = radar
                )
                
                val frames = buildRainViewerFrames(rainViewerResponse)
                
                if (frames.isEmpty()) {
                    RainViewerApiCache.getFrames()?.let { cachedFrames ->
                        onSuccess(cachedFrames)
                        return@getWeatherMaps
                    }
                    onFailure()
                    return@getWeatherMaps
                }

                RainViewerApiCache.putFrames(frames)
                onSuccess(frames)
            } catch (e: Exception) {
                Log.e("RainViewerApiCache", "Error parsing RainViewer response: ${e.message}")
                RainViewerApiCache.getFrames()?.let { cachedFrames ->
                    onSuccess(cachedFrames)
                } ?: onFailure()
            }
        },
        onError = { error ->
            Log.e("RainViewerApiCache", "Backend connection error: ${error.message}")
            RainViewerApiCache.getFrames()?.let { cachedFrames ->
                onSuccess(cachedFrames)
            } ?: onFailure()
        }
    )
}
