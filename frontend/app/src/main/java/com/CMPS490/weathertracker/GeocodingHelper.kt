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

    /**
     * Fallback format when geocoding fails - show "Lat: X, Lon: Y"
     */
    private fun formatCoordinates(latitude: Double, longitude: Double): String {
        return "Lat: ${"%.4f".format(latitude)}, Lon: ${"%.4f".format(longitude)}"
    }
}
