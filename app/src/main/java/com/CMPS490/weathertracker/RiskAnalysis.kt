package com.CMPS490.weathertracker

import com.google.android.gms.maps.model.LatLng

/**
 * Represents a simple circular hazard zone.
 */
data class HazardZone(
    val center: LatLng,
    val radiusMeters: Double,
    val severity: String
)
