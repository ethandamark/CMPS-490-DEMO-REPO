package com.CMPS490.weathertracker

import com.CMPS490.weathertracker.network.RainViewerResponse
import com.CMPS490.weathertracker.network.RainViewerRetrofitInstance
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

    RainViewerRetrofitInstance.api.getWeatherMaps()
        .enqueue(object : Callback<RainViewerResponse> {
            override fun onResponse(
                call: Call<RainViewerResponse>,
                response: Response<RainViewerResponse>
            ) {
                val body = response.body()
                if (body == null) {
                    RainViewerApiCache.getFrames()?.let { cachedFrames ->
                        onSuccess(cachedFrames)
                        return
                    }
                    onFailure()
                    return
                }

                val frames = buildRainViewerFrames(body)
                if (frames.isEmpty()) {
                    RainViewerApiCache.getFrames()?.let { cachedFrames ->
                        onSuccess(cachedFrames)
                        return
                    }
                    onFailure()
                    return
                }

                RainViewerApiCache.putFrames(frames)
                onSuccess(frames)
            }

            override fun onFailure(call: Call<RainViewerResponse>, t: Throwable) {
                RainViewerApiCache.getFrames()?.let { cachedFrames ->
                    onSuccess(cachedFrames)
                    return
                }
                onFailure()
            }
        })
}
