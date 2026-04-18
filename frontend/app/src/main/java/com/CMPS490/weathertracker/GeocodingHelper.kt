package com.CMPS490.weathertracker

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GeocodingHelper {
    /**
     * Convert latitude and longitude to a location name (City, State)
     * Uses reverse geocoding to get the address from coordinates
     */
    suspend fun getLocationName(
        context: Context,
        latitude: Double,
        longitude: Double
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                if (!Geocoder.isPresent()) {
                    Log.w("GeocodingHelper", "Geocoder not available on this device")
                    return@withContext formatCoordinates(latitude, longitude)
                }

                val geocoder = Geocoder(context)
                val addresses = try {
                    @Suppress("DEPRECATION") // Deprecated but works for compatibility
                    geocoder.getFromLocation(latitude, longitude, 1) ?: emptyList()
                } catch (e: Exception) {
                    Log.e("GeocodingHelper", "Geocoding error: ${e.message}")
                    return@withContext formatCoordinates(latitude, longitude)
                }

                if (addresses.isNotEmpty()) {
                    val address = addresses[0]
                    return@withContext formatAddress(address)
                } else {
                    Log.w("GeocodingHelper", "No address found for coordinates")
                    return@withContext formatCoordinates(latitude, longitude)
                }
            } catch (e: Exception) {
                Log.e("GeocodingHelper", "Unexpected geocoding error: ${e.message}", e)
                return@withContext formatCoordinates(latitude, longitude)
            }
        }
    }

    /**
     * Search for U.S. locations by name and return location options usable by the dropdown.
     */
    suspend fun searchLocations(
        context: Context,
        query: String,
        maxResults: Int = 8,
    ): List<LocationOptionUiModel> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                if (!Geocoder.isPresent()) {
                    Log.w("GeocodingHelper", "Geocoder not available for forward search")
                    return@withContext emptyList()
                }

                val geocoder = Geocoder(context)
                val addresses = try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName("$normalizedQuery, USA", maxResults) ?: emptyList()
                } catch (e: Exception) {
                    Log.e("GeocodingHelper", "Location search error: ${e.message}")
                    return@withContext emptyList()
                }

                addresses
                    .asSequence()
                    .filter { it.hasLatitude() && it.hasLongitude() }
                    .mapNotNull { address ->
                        val label = formatSearchAddress(address) ?: return@mapNotNull null
                        LocationOptionUiModel(
                            label = label,
                            latitude = address.latitude,
                            longitude = address.longitude,
                            useDeviceLocation = false,
                        )
                    }
                    .distinctBy { "${it.label}|${it.latitude}|${it.longitude}" }
                    .toList()
            } catch (e: Exception) {
                Log.e("GeocodingHelper", "Unexpected location search error: ${e.message}", e)
                emptyList()
            }
        }
    }

    /**
     * Format address object to "City, State" format
     */
    private fun formatAddress(address: Address): String {
        val city = address.locality ?: address.subAdminArea ?: "Unknown"
        val state = address.adminArea ?: ""
        
        return if (state.isNotEmpty()) {
            "$city, $state"
        } else {
            city
        }
    }

    private fun formatSearchAddress(address: Address): String? {
        val city = address.locality
            ?: address.subAdminArea
            ?: address.featureName
        val state = address.adminArea

        return when {
            !city.isNullOrBlank() && !state.isNullOrBlank() -> "$city, $state"
            !state.isNullOrBlank() -> state
            !city.isNullOrBlank() -> city
            else -> null
        }
    }

    /**
     * Fallback format when geocoding fails - show "Lat: X, Lon: Y"
     */
    private fun formatCoordinates(latitude: Double, longitude: Double): String {
        return "Lat: ${"%.4f".format(latitude)}, Lon: ${"%.4f".format(longitude)}"
    }
}
