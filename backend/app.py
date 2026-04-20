"""
FastAPI backend for Weather Tracker API Proxy + Supabase operations
"""
import sys
from pathlib import Path

# Add the project root so ``lib`` is importable, and backend/ so
# lib modules can resolve ``from config import ...``.
_BACKEND_DIR = Path(__file__).resolve().parent
_PROJECT_ROOT = _BACKEND_DIR.parent
for _p in (_BACKEND_DIR, _PROJECT_ROOT):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import os
from dotenv import load_dotenv
import uvicorn
import httpx
import uuid
import secrets
import string
from datetime import datetime, timedelta, timezone
import logging
from math import radians, cos, sin, sqrt, atan2

# Load environment variables
load_dotenv()

logger = logging.getLogger(__name__)

app = FastAPI(title="Weather Tracker API", version="1.0.0")

# Enable CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# API Configuration
WEATHER_API_BASE = "https://api.weather.gov"
RAINVIEWER_API_BASE = "https://api.rainviewer.com"
SUPABASE_BASE = os.getenv("SUPABASE_BASE_URL", "http://localhost:54321")
SUPABASE_API_KEY = os.getenv("SUPABASE_API_KEY", "sb_publishable_ACJWlzQHlZjBrEguHvfOxg_3BJgxAaH")
WEATHER_API_HEADERS = {
    "User-Agent": os.getenv("WEATHER_API_USER_AGENT", "CMPS490WeatherTracker/1.0 (contact: dev@example.com)"),
    "Accept": "application/geo+json, application/json",
}


# ============= UTILITY FUNCTIONS =============

def upstream_error(service_name: str, response: httpx.Response) -> HTTPException:
    """
    Convert an upstream HTTP error into a FastAPI HTTPException while preserving
    the upstream status code for the Android client.
    """
    body_preview = response.text[:500]
    return HTTPException(
        status_code=response.status_code,
        detail=f"{service_name} upstream error: {body_preview}"
    )

def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Return distance in km between two lat/lon points."""
    R = 6371
    dlat, dlon = radians(lat2 - lat1), radians(lon2 - lon1)
    a = sin(dlat / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(dlon / 2) ** 2
    return 2 * R * atan2(sqrt(a), sqrt(1 - a))


def empty_alerts_response() -> dict:
    """
    Return an empty alert collection when the upstream alerts service is unavailable.
    This keeps the Android client usable instead of failing the whole screen.
    """
    return {
        "type": "FeatureCollection",
        "features": []
    }


# ============= REQUEST/RESPONSE MODELS =============

class HealthResponse(BaseModel):
    """Health check response"""
    status: str


class PredictionRequest(BaseModel):
    """Prediction request model"""
    # TODO: Update with your model's required input fields
    pass


class PredictionResponse(BaseModel):
    """Prediction response model"""
    # TODO: Update with your model's output fields
    prediction: str = "ML model not yet integrated"


class RegisterRequest(BaseModel):
    """Registration request - only device-side facts from frontend"""
    locationPermissionStatus: bool = False
    notificationsEnabled: bool = False
    latitude: float | None = None
    longitude: float | None = None



class DeviceUpdateRequest(BaseModel):
    """Update device attributes"""
    device_id: str
    location_permission_status: bool | None = None
    notifications_enabled: bool | None = None


# ============= DEVICE LOCATION MODELS =============

class CreateDeviceLocationRequest(BaseModel):
    """Create device location record"""
    device_id: str
    latitude: float
    longitude: float
    captured_at: str | None = None  # Auto-generated if not provided


class UpdateDeviceLocationRequest(BaseModel):
    """Update device location record"""
    latitude: float | None = None
    longitude: float | None = None
    captured_at: str | None = None


class DeviceLocationResponse(BaseModel):
    """Device location data"""
    location_id: str
    device_id: str
    latitude: float
    longitude: float
    captured_at: str


# ============= HEALTH CHECK =============

@app.get("/health", response_model=HealthResponse)
async def health_check():
    """Health check endpoint"""
    return {"status": "healthy"}


# ============= WEATHER API PROXY =============

@app.get("/weather/points/{lat}/{lon}")
async def get_weather_points(lat: float, lon: float):
    """
    Proxy: Get weather point data from NWS
    Corresponds to: GET https://api.weather.gov/points/{lat},{lon}
    """
    try:
        async with httpx.AsyncClient(timeout=30, follow_redirects=True) as client:
            response = await client.get(
                f"{WEATHER_API_BASE}/points/{lat},{lon}",
                headers=WEATHER_API_HEADERS
            )
            if response.is_error:
                raise upstream_error("Weather points", response)
            return response.json()
    except Exception as e:
        if isinstance(e, HTTPException):
            raise e
        raise HTTPException(status_code=500, detail=f"Weather API error: {str(e)}")


@app.get("/weather/forecast")
async def get_forecast(url: str):
    """
    Proxy: Get forecast from URL
    Corresponds to: GET from forecast URL
    """
    try:
        async with httpx.AsyncClient(timeout=30, follow_redirects=True) as client:
            response = await client.get(url, headers=WEATHER_API_HEADERS)
            if response.is_error:
                raise upstream_error("Forecast", response)
            return response.json()
    except Exception as e:
        if isinstance(e, HTTPException):
            raise e
        raise HTTPException(status_code=500, detail=f"Forecast API error: {str(e)}")


@app.get("/weather/alerts")
async def get_alerts(point: str):
    """
    Proxy: Get active weather alerts for a point
    Corresponds to: GET https://api.weather.gov/alerts/active?point={point}
    """
    try:
        async with httpx.AsyncClient(timeout=30, follow_redirects=True) as client:
            response = await client.get(
                f"{WEATHER_API_BASE}/alerts/active",
                params={"point": point},
                headers=WEATHER_API_HEADERS
            )
            if response.is_error:
                body_preview = response.text[:500]
                print(f"Alerts upstream error for point={point}: {response.status_code} {body_preview}")
                return empty_alerts_response()
            return response.json()
    except Exception as e:
        print(f"Alerts API error for point={point}: {str(e)}")
        return empty_alerts_response()


# ============= RAINVIEWER API PROXY =============

@app.get("/rainviewer/maps")
async def get_weather_maps():
    """
    Proxy: Get RainViewer weather maps
    Corresponds to: GET https://api.rainviewer.com/public/weather-maps.json
    """
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.get(f"{RAINVIEWER_API_BASE}/public/weather-maps.json")
            return response.json()
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"RainViewer API error: {str(e)}")


# ============= SUPABASE API PROXY =============

@app.post("/supabase/register")
async def register_device(request: RegisterRequest):
    """
    Register a new anonymous user + device in one call.
    Backend generates ALL identifiers: anon_user_id, device_id.
    Frontend only sends locationPermissionStatus (a device-side fact).
    """
    try:

        async with httpx.AsyncClient(timeout=30) as client:
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
                "Prefer": "return=representation",
            }

            anon_user_id = str(uuid.uuid4())
            device_id = str(uuid.uuid4())
            now = datetime.now(timezone.utc).isoformat()

            # --- Step 1: Create anonymous user ---
            user_record = {
                "anon_user_id": anon_user_id,
                "status": "active",
                "created_at": now,
                "last_active_at": now,
            }

            print(f"[REGISTER] Creating NEW anonymous user: {user_record}")

            user_response = await client.post(
                f"{SUPABASE_BASE}/rest/v1/anonymous_user",
                json=user_record,
                headers=headers,
            )

            print(f"[REGISTER] User status: {user_response.status_code}")
            print(f"[REGISTER] User response: {user_response.text[:200]}")

            if user_response.status_code not in [200, 201]:
                print(f"âœ— Error creating user: {user_response.text}")
                return {"success": False, "error": f"User creation failed: {user_response.text}"}

            # --- Step 2: Create device linked to user ---
            device_record = {
                "device_id": device_id,
                "anon_user_id": anon_user_id,
                "platform": "android",
                "app_version": "1.0",
                "location_permission_status": request.locationPermissionStatus,
                "notifications_enabled": request.notificationsEnabled,
            }

            print(f"[REGISTER] Creating device: {device_record}")

            device_response = await client.post(
                f"{SUPABASE_BASE}/rest/v1/device",
                json=device_record,
                headers=headers,
            )

            print(f"[REGISTER] Device status: {device_response.status_code}")
            print(f"[REGISTER] Device response: {device_response.text[:200]}")

            if device_response.status_code in [200, 201]:
                print(f"✓ Registered: user={anon_user_id}, device={device_id}")
                
                # AUTO-POPULATE DEVICE LOCATION if permission granted + coords provided
                if request.locationPermissionStatus and request.latitude is not None and request.longitude is not None:
                    print(f"[REGISTER] Auto-creating device_location (permission=true, coords provided)")
                    location_record = {
                        "location_id": str(uuid.uuid4()),
                        "device_id": device_id,
                        "latitude": request.latitude,
                        "longitude": request.longitude,
                        "captured_at": now,
                    }
                    print(f"[REGISTER] location_record: {location_record}")
                    loc_response = await client.post(
                        f"{SUPABASE_BASE}/rest/v1/device_location",
                        json=location_record,
                        headers=headers,
                    )
                    if loc_response.status_code in [200, 201]:
                        print(f"✓ Device location auto-created: {location_record['location_id']}")
                    else:
                        print(f"✗ Failed to auto-create device_location: {loc_response.text}")
                else:
                    print(f"[REGISTER] Skipping device_location: permission={request.locationPermissionStatus}, lat={request.latitude}, lon={request.longitude}")
                
                return {
                    "success": True,
                    "userId": anon_user_id,
                    "deviceId": device_id,
                }
            else:
                print(f"✗ Error creating device: {device_response.text}")
                return {"success": False, "error": f"Device creation failed: {device_response.text}"}
    except Exception as e:
        print(f"âœ— Exception in register_device: {str(e)}")
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"Supabase Error: {str(e)}")


# ============= DEVICE LOCATION API ============

@app.post("/device-location/create")
async def create_device_location(request: CreateDeviceLocationRequest):
    """
    Create a new device location record.
    Backend generates: location_id, captured_at (if not provided)
    """
    try:
        print(f"\n{'='*60}")
        print(f"[DEVICE-LOCATION CREATE] Incoming request:")
        print(f"  device_id:   {request.device_id}")
        print(f"  latitude:    {request.latitude}")
        print(f"  longitude:   {request.longitude}")
        print(f"  captured_at: {request.captured_at or 'AUTO-GENERATE'}")
        print(f"{'='*60}")

        async with httpx.AsyncClient(timeout=30) as client:
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
                "Prefer": "return=representation",
            }

            location_id = str(uuid.uuid4())
            captured_at = request.captured_at or datetime.now(timezone.utc).isoformat()

            location_record = {
                "location_id": location_id,
                "device_id": request.device_id,
                "latitude": request.latitude,
                "longitude": request.longitude,
                "captured_at": captured_at,
            }

            print(f"[DEVICE-LOCATION CREATE] Sending to Supabase:")
            print(f"  URL:    {SUPABASE_BASE}/rest/v1/device_location")
            print(f"  Record: {location_record}")

            response = await client.post(
                f"{SUPABASE_BASE}/rest/v1/device_location",
                json=location_record,
                headers=headers,
            )

            print(f"[DEVICE-LOCATION CREATE] Supabase response:")
            print(f"  Status: {response.status_code}")
            print(f"  Body:   {response.text[:500] if response.text else 'EMPTY'}")

            if response.status_code in [200, 201]:
                print(f"✓ SUCCESS: Device location created with ID: {location_id}")
                return {
                    "success": True,
                    "location_id": location_id,
                    "device_id": request.device_id,
                    "latitude": request.latitude,
                    "longitude": request.longitude,
                    "captured_at": captured_at,
                }
            else:
                print(f"✗ FAILED to create device location: {response.text}")
                raise HTTPException(status_code=500, detail=f"Failed: {response.text}")
    except HTTPException:
        raise
    except Exception as e:
        print(f"✗ EXCEPTION in create_device_location: {str(e)}")
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/device-location/by-device/{device_id}")
async def get_device_locations_by_device(device_id: str, limit: int = 100):
    """Get all location records for a specific device, ordered by most recent."""
    try:
        print(f"\n{'='*60}")
        print(f"[DEVICE-LOCATION BY-DEVICE] GET request:")
        print(f"  device_id: {device_id}")
        print(f"  limit:     {limit}")
        print(f"{'='*60}")

        async with httpx.AsyncClient(timeout=30) as client:
            headers = {"apikey": SUPABASE_API_KEY, "Content-Type": "application/json"}
            
            url = f"{SUPABASE_BASE}/rest/v1/device_location?device_id=eq.{device_id}&order=captured_at.desc&limit={limit}"
            print(f"[DEVICE-LOCATION BY-DEVICE] Querying: {url}")

            response = await client.get(url, headers=headers)

            print(f"[DEVICE-LOCATION BY-DEVICE] Response:")
            print(f"  Status: {response.status_code}")

            if response.status_code == 200:
                locations = response.json()
                print(f"✓ SUCCESS: Found {len(locations)} locations for device {device_id}")
                for i, loc in enumerate(locations[:5]):  # Print first 5
                    print(f"  [{i+1}] lat={loc.get('latitude')}, lon={loc.get('longitude')}, captured_at={loc.get('captured_at')}")
                if len(locations) > 5:
                    print(f"  ... and {len(locations) - 5} more")
                return {"success": True, "locations": locations, "count": len(locations)}
            else:
                print(f"✗ FAILED: {response.text}")
                raise HTTPException(status_code=500, detail=response.text)
    except HTTPException:
        raise
    except Exception as e:
        print(f"✗ EXCEPTION in get_device_locations_by_device: {str(e)}")
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/device-location/{location_id}")
async def get_device_location_by_id(location_id: str):
    """Get a specific device location by ID."""
    try:
        print(f"\n{'='*60}")
        print(f"[DEVICE-LOCATION GET-BY-ID] Request:")
        print(f"  location_id: {location_id}")
        print(f"{'='*60}")

        async with httpx.AsyncClient(timeout=30) as client:
            headers = {"apikey": SUPABASE_API_KEY, "Content-Type": "application/json"}
            
            url = f"{SUPABASE_BASE}/rest/v1/device_location?location_id=eq.{location_id}"
            print(f"[DEVICE-LOCATION GET-BY-ID] Querying: {url}")

            response = await client.get(url, headers=headers)

            print(f"[DEVICE-LOCATION GET-BY-ID] Response:")
            print(f"  Status: {response.status_code}")
            print(f"  Body:   {response.text[:500] if response.text else 'EMPTY'}")

            if response.status_code == 200:
                locations = response.json()
                if locations:
                    print(f"✓ SUCCESS: Found location {location_id}")
                    return {"success": True, "location": locations[0]}
                print(f"✗ NOT FOUND: Location {location_id} does not exist")
                raise HTTPException(status_code=404, detail="Device location not found")
            print(f"✗ FAILED: {response.text}")
            raise HTTPException(status_code=500, detail=response.text)
    except HTTPException:
        raise
    except Exception as e:
        print(f"✗ EXCEPTION in get_device_location_by_id: {str(e)}")
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=str(e))


@app.patch("/device-location/{location_id}")
async def update_device_location(location_id: str, request: UpdateDeviceLocationRequest):
    """Update a device location record."""
    try:
        print(f"\n{'='*60}")
        print(f"[DEVICE-LOCATION UPDATE] Request:")
        print(f"  location_id: {location_id}")
        print(f"  latitude:    {request.latitude}")
        print(f"  longitude:   {request.longitude}")
        print(f"  captured_at: {request.captured_at}")
        print(f"{'='*60}")

        async with httpx.AsyncClient(timeout=30) as client:
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
                "Prefer": "return=representation",
            }

            # Build update payload with only provided fields
            update_data = {}
            if request.latitude is not None:
                update_data["latitude"] = request.latitude
            if request.longitude is not None:
                update_data["longitude"] = request.longitude
            if request.captured_at is not None:
                update_data["captured_at"] = request.captured_at

            if not update_data:
                print(f"✗ ERROR: No fields to update")
                raise HTTPException(status_code=400, detail="No fields to update")

            url = f"{SUPABASE_BASE}/rest/v1/device_location?location_id=eq.{location_id}"
            print(f"[DEVICE-LOCATION UPDATE] Sending PATCH to: {url}")
            print(f"[DEVICE-LOCATION UPDATE] Update data: {update_data}")

            response = await client.patch(url, json=update_data, headers=headers)

            print(f"[DEVICE-LOCATION UPDATE] Response:")
            print(f"  Status: {response.status_code}")
            print(f"  Body:   {response.text[:500] if response.text else 'EMPTY'}")

            if response.status_code in [200, 204]:
                result = response.json() if response.text else []
                if result:
                    print(f"✓ SUCCESS: Updated location {location_id}")
                    return {"success": True, "location": result[0]}
                print(f"✗ NOT FOUND: Location {location_id} does not exist")
                raise HTTPException(status_code=404, detail="Device location not found")
            print(f"✗ FAILED: {response.text}")
            raise HTTPException(status_code=500, detail=response.text)
    except HTTPException:
        raise
    except Exception as e:
        print(f"✗ EXCEPTION in update_device_location: {str(e)}")
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=str(e))


@app.delete("/device-location/{location_id}")
async def delete_device_location(location_id: str):
    """Delete a device location record."""
    try:
        print(f"\n{'='*60}")
        print(f"[DEVICE-LOCATION DELETE] Request:")
        print(f"  location_id: {location_id}")
        print(f"{'='*60}")

        async with httpx.AsyncClient(timeout=30) as client:
            headers = {"apikey": SUPABASE_API_KEY, "Prefer": "return=representation"}
            
            url = f"{SUPABASE_BASE}/rest/v1/device_location?location_id=eq.{location_id}"
            print(f"[DEVICE-LOCATION DELETE] Sending DELETE to: {url}")

            response = await client.delete(url, headers=headers)

            print(f"[DEVICE-LOCATION DELETE] Response:")
            print(f"  Status: {response.status_code}")
            print(f"  Body:   {response.text[:500] if response.text else 'EMPTY'}")

            if response.status_code in [200, 204]:
                print(f"✓ SUCCESS: Deleted location {location_id}")
                return {"success": True, "message": f"Device location {location_id} deleted"}
            print(f"✗ FAILED: {response.text}")
            raise HTTPException(status_code=500, detail=response.text)
    except HTTPException:
        raise
    except Exception as e:
        print(f"✗ EXCEPTION in delete_device_location: {str(e)}")
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/device-location/latest/{device_id}")
async def get_latest_device_location(device_id: str):
    """Get the most recent location for a device."""
    try:
        print(f"\n{'='*60}")
        print(f"[DEVICE-LOCATION LATEST] Request:")
        print(f"  device_id: {device_id}")
        print(f"{'='*60}")

        async with httpx.AsyncClient(timeout=30) as client:
            headers = {"apikey": SUPABASE_API_KEY, "Content-Type": "application/json"}
            
            url = f"{SUPABASE_BASE}/rest/v1/device_location?device_id=eq.{device_id}&order=captured_at.desc&limit=1"
            print(f"[DEVICE-LOCATION LATEST] Querying: {url}")

            response = await client.get(url, headers=headers)

            print(f"[DEVICE-LOCATION LATEST] Response:")
            print(f"  Status: {response.status_code}")
            print(f"  Body:   {response.text[:500] if response.text else 'EMPTY'}")

            if response.status_code == 200:
                locations = response.json()
                if locations:
                    loc = locations[0]
                    print(f"✓ SUCCESS: Latest location for {device_id}:")
                    print(f"  location_id: {loc.get('location_id')}")
                    print(f"  latitude:    {loc.get('latitude')}")
                    print(f"  longitude:   {loc.get('longitude')}")
                    print(f"  captured_at: {loc.get('captured_at')}")
                    return {"success": True, "location": locations[0]}
                print(f"✗ NOT FOUND: No location found for device {device_id}")
                raise HTTPException(status_code=404, detail="No location found for device")
            print(f"✗ FAILED: {response.text}")
            raise HTTPException(status_code=500, detail=response.text)
    except HTTPException:
        raise
    except Exception as e:
        print(f"✗ EXCEPTION in get_latest_device_location: {str(e)}")
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=str(e))


@app.patch("/supabase/device")
async def update_device(request: DeviceUpdateRequest):
    """
    Update device attributes and refresh anonymous_user.last_active_at.
    Also reactivates the account if it was marked inactive.
    """
    try:
        print(f"\n[UPDATE-DEVICE] Received request:")
        print(f"  device_id: {request.device_id}")
        if request.location_permission_status is not None:
            print(f"  location_permission_status: {request.location_permission_status}")
        print(f"  Supabase URL: {SUPABASE_BASE}")

        now = datetime.now(timezone.utc).isoformat()

        async with httpx.AsyncClient() as client:
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
                "Prefer": "return=representation"
            }

            # --- 1. Update device-specific fields (if any) ---
            device_body = {}
            if request.location_permission_status is not None:
                device_body["location_permission_status"] = request.location_permission_status
            if request.notifications_enabled is not None:
                device_body["notifications_enabled"] = request.notifications_enabled

            if device_body:
                patch_url = f"{SUPABASE_BASE}/rest/v1/device?device_id=eq.{request.device_id}"
                print(f"[PATCH device] URL: {patch_url}")
                print(f"[PATCH device] Body: {device_body}")

                patch_response = await client.patch(
                    patch_url, json=device_body, headers=headers
                )
                print(f"[PATCH device] Status: {patch_response.status_code}")
                if patch_response.status_code not in [200, 201, 204]:
                    print(f"✗ Error updating device: {patch_response.text}")
                    return {"success": False, "error": f"Failed to update device: {patch_response.text}"}

            # --- 2. Look up anon_user_id from device ---
            lookup_url = f"{SUPABASE_BASE}/rest/v1/device?device_id=eq.{request.device_id}&select=anon_user_id"
            lookup_resp = await client.get(lookup_url, headers=headers)
            devices = lookup_resp.json()
            if not devices:
                return {"success": False, "error": "Device not found"}
            anon_user_id = devices[0]["anon_user_id"]

            # --- 3. Update anonymous_user.last_active_at & reactivate ---
            user_url = f"{SUPABASE_BASE}/rest/v1/anonymous_user?anon_user_id=eq.{anon_user_id}"
            user_body = {"last_active_at": now, "status": "active"}
            print(f"[PATCH user] URL: {user_url}")
            print(f"[PATCH user] Body: {user_body}")

            user_resp = await client.patch(user_url, json=user_body, headers=headers)
            print(f"[PATCH user] Status: {user_resp.status_code}")
            if user_resp.status_code not in [200, 201, 204]:
                print(f"✗ Error updating anonymous_user: {user_resp.text}")
                return {"success": False, "error": f"Failed to update user: {user_resp.text}"}

            print(f"✓ Device updated, last_active_at refreshed for user {anon_user_id}")
            return {
                "success": True,
                "message": "Device updated successfully",
                "device_id": request.device_id
            }
    except Exception as e:
        print(f"✗ Exception in update_device: {str(e)}")
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"Error updating device: {str(e)}")

@app.post("/device-location/update-current")
async def update_current_device_location(request: CreateDeviceLocationRequest):
    """
    Update or create the current location for a device (upsert behavior).
    Used by background location tracking service.
    - If a location exists for the device, updates it
    - If no location exists, creates a new one
    """
    try:
        print(f"\n{'='*60}")
        print(f"[DEVICE-LOCATION UPDATE-CURRENT] Request:")
        print(f"  device_id: {request.device_id}")
        print(f"  latitude:  {request.latitude}")
        print(f"  longitude: {request.longitude}")
        print(f"{'='*60}")

        async with httpx.AsyncClient(timeout=30) as client:
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
                "Prefer": "return=representation",
            }
            
            now = datetime.now(timezone.utc).isoformat()
            
            # Check if location exists for this device
            existing_response = await client.get(
                f"{SUPABASE_BASE}/rest/v1/device_location?device_id=eq.{request.device_id}&order=captured_at.desc&limit=1",
                headers=headers,
            )
            existing_locs = existing_response.json() if existing_response.status_code == 200 else []
            
            if existing_locs:
                # Update existing location
                existing_loc = existing_locs[0]
                location_id = existing_loc["location_id"]
                print(f"[UPDATE-CURRENT] Updating existing location: {location_id}")
                print(f"  Old: lat={existing_loc.get('latitude')}, lon={existing_loc.get('longitude')}")
                print(f"  New: lat={request.latitude}, lon={request.longitude}")
                
                response = await client.patch(
                    f"{SUPABASE_BASE}/rest/v1/device_location?location_id=eq.{location_id}",
                    json={
                        "latitude": request.latitude,
                        "longitude": request.longitude,
                        "captured_at": now,
                    },
                    headers=headers,
                )
                
                if response.status_code in [200, 204]:
                    print(f"✓ Location updated: {location_id}")
                    return {
                        "success": True,
                        "action": "updated",
                        "location_id": location_id,
                        "device_id": request.device_id,
                        "latitude": request.latitude,
                        "longitude": request.longitude,
                        "captured_at": now,
                    }
                else:
                    print(f"✗ Failed to update: {response.text}")
                    raise HTTPException(status_code=500, detail=response.text)
            else:
                # Create new location
                location_id = str(uuid.uuid4())
                print(f"[UPDATE-CURRENT] Creating new location: {location_id}")
                
                location_record = {
                    "location_id": location_id,
                    "device_id": request.device_id,
                    "latitude": request.latitude,
                    "longitude": request.longitude,
                    "captured_at": now,
                }
                
                response = await client.post(
                    f"{SUPABASE_BASE}/rest/v1/device_location",
                    json=location_record,
                    headers=headers,
                )
                
                if response.status_code in [200, 201]:
                    print(f"✓ Location created: {location_id}")
                    return {
                        "success": True,
                        "action": "created",
                        "location_id": location_id,
                        "device_id": request.device_id,
                        "latitude": request.latitude,
                        "longitude": request.longitude,
                        "captured_at": now,
                    }
                else:
                    print(f"✗ Failed to create: {response.text}")
                    raise HTTPException(status_code=500, detail=response.text)
    except HTTPException:
        raise
    except Exception as e:
        print(f"✗ EXCEPTION in update_current_device_location: {str(e)}")
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=str(e))




# ============= SYNC ENDPOINTS =============

from pydantic import BaseModel as _BaseModel
from typing import Optional as _Optional
import json as _json


class SyncSnapshotsRequest(_BaseModel):
    """Device sends weather_data (list of cache rows) bundled into one snapshot."""
    weather_data: list[dict]
    snapshot_type: str = "sync"


@app.post("/devices/{device_id}/sync-snapshots")
async def sync_snapshots(device_id: str, request: SyncSnapshotsRequest):
    """
    Create ONE snapshot row in Supabase with the device's weather_data
    archived as JSONB.  No individual weather_cache rows are written.
    """
    if not request.weather_data:
        return {"success": True, "upserted": 0}

    try:
        async with httpx.AsyncClient(timeout=30) as client:
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
                "Prefer": "return=representation,resolution=merge-duplicates",
            }

            now_iso = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
            snapshot_id = str(uuid.uuid4())

            snap_row = {
                "weather_id": snapshot_id,
                "device_id": device_id,
                "synced_at": now_iso,
                "is_current": True,
                "weather_data": request.weather_data,
                "snapshot_type": request.snapshot_type,
            }

            resp = await client.post(
                f"{SUPABASE_BASE}/rest/v1/offline_weather_snapshot",
                json=[snap_row],
                headers=headers,
            )
            if resp.status_code not in [200, 201]:
                logger.error("Snapshot upsert failed: %s %s", resp.status_code, resp.text[:500])
                raise HTTPException(
                    status_code=502,
                    detail=f"snapshot insert failed: {resp.status_code} — {resp.text[:300]}",
                )

            logger.info("Synced snapshot %s (%d weather rows) for device %s",
                        snapshot_id, len(request.weather_data), device_id)
            return {
                "success": True,
                "upserted": len(request.weather_data),
                "snapshot_id": snapshot_id,
            }
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Error syncing snapshots")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/devices/{device_id}/snapshots")
async def get_device_snapshots(device_id: str, since: str | None = None):
    """
    Return offline_weather_snapshot rows for this device.
    Each row carries its own weather_data JSONB — no join needed.
    """
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            headers = {"apikey": SUPABASE_API_KEY, "Content-Type": "application/json"}

            snapshot_url = (
                f"{SUPABASE_BASE}/rest/v1/offline_weather_snapshot"
                f"?device_id=eq.{device_id}"
                f"&order=synced_at.desc&limit=168"
            )
            if since:
                snapshot_url += f"&synced_at=gte.{since}"

            resp = await client.get(snapshot_url, headers=headers)
            if resp.status_code == 200:
                return {"success": True, "snapshots": resp.json()}
            raise HTTPException(status_code=500, detail=resp.text)
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Error fetching device snapshots")
        raise HTTPException(status_code=500, detail=str(e))

# ============= WEATHER HISTORY SEED =============

class SeedWeatherHistoryRequest(BaseModel):
    """Seed request — device sends its current coordinates."""
    latitude: float
    longitude: float


@app.post("/devices/{device_id}/seed-weather-history")
async def seed_weather_history(device_id: str, request: SeedWeatherHistoryRequest):
    """
    Fetch the past 24 hours of hourly weather from Open-Meteo for the
    device's location, store ONE snapshot with weather_data JSONB in Supabase,
    and return the rows so the device can seed its local Room DB.
    """
    try:
        latitude = request.latitude
        longitude = request.longitude
        print(f"\n{'='*60}")
        print(f"[SEED] Fetching 24h weather history for device={device_id}")
        print(f"[SEED] Coordinates: ({latitude}, {longitude})")
        print(f"{'='*60}")

        async with httpx.AsyncClient(timeout=30) as client:
            # Fetch 24h of hourly historical weather from Open-Meteo
            url = (
                f"https://api.open-meteo.com/v1/forecast"
                f"?latitude={latitude}&longitude={longitude}"
                f"&hourly=temperature_2m,relative_humidity_2m,dew_point_2m,"
                f"precipitation,pressure_msl,wind_speed_10m,wind_direction_10m"
                f"&past_hours=24&forecast_hours=0"
                f"&timezone=UTC&wind_speed_unit=kmh"
            )
            resp = await client.get(url)
            if resp.status_code != 200:
                print(f"[SEED] \u2717 Open-Meteo error: {resp.text}")
                raise HTTPException(status_code=502, detail=f"Open-Meteo error: {resp.text}")

            data = resp.json()
            elevation = data.get("elevation")
            hourly = data.get("hourly", {})
            times = hourly.get("time", [])
            temps = hourly.get("temperature_2m", [])
            humidities = hourly.get("relative_humidity_2m", [])
            dew_points = hourly.get("dew_point_2m", [])
            precips = hourly.get("precipitation", [])
            pressures = hourly.get("pressure_msl", [])
            winds = hourly.get("wind_speed_10m", [])
            wind_dirs = hourly.get("wind_direction_10m", [])

            print(f"[SEED] Open-Meteo returned {len(times)} hourly rows")

            # ── Fetch 7-day hourly forecast from Open-Meteo ──
            forecast_url = (
                f"https://api.open-meteo.com/v1/forecast"
                f"?latitude={latitude}&longitude={longitude}"
                f"&hourly=temperature_2m,relative_humidity_2m,dew_point_2m,"
                f"precipitation,pressure_msl,wind_speed_10m,wind_direction_10m"
                f"&forecast_days=7&past_hours=0"
                f"&timezone=UTC&wind_speed_unit=kmh"
            )
            fc_resp = await client.get(forecast_url)
            fc_hourly = {}
            if fc_resp.status_code == 200:
                fc_data = fc_resp.json()
                fc_hourly = fc_data.get("hourly", {})
                print(f"[SEED] Open-Meteo returned {len(fc_hourly.get('time', []))} forecast rows")
            else:
                print(f"[SEED] ⚠ Forecast fetch failed ({fc_resp.status_code}), continuing with observations only")

            fc_times = fc_hourly.get("time", [])
            fc_temps = fc_hourly.get("temperature_2m", [])
            fc_humidities = fc_hourly.get("relative_humidity_2m", [])
            fc_dew_points = fc_hourly.get("dew_point_2m", [])
            fc_precips = fc_hourly.get("precipitation", [])
            fc_pressures = fc_hourly.get("pressure_msl", [])
            fc_winds = fc_hourly.get("wind_speed_10m", [])
            fc_wind_dirs = fc_hourly.get("wind_direction_10m", [])

            # Build weather_cache rows
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
                "Prefer": "return=representation,resolution=merge-duplicates",
            }

            # Ensure the simulation sentinel snapshot exists (survives DELETE sweeps)
            await client.post(
                f"{SUPABASE_BASE}/rest/v1/offline_weather_snapshot",
                json=[{
                    "weather_id": "00000000-0000-0000-0000-000000000000",
                    "device_id": None,
                    "synced_at": None,
                    "is_current": False,
                    "weather_data": [],
                    "snapshot_type": "seed",
                }],
                headers=headers,
            )

            weather_rows = []
            for i, time_str in enumerate(times):
                cache_id = str(uuid.uuid4())
                # Parse ISO time to epoch millis (Open-Meteo returns UTC)
                from datetime import datetime as _dt
                parsed_time = _dt.fromisoformat(time_str).replace(tzinfo=timezone.utc)
                recorded_at_ms = int(parsed_time.timestamp() * 1000)

                def _safe(lst, idx):
                    return lst[idx] if idx < len(lst) and lst[idx] is not None else None

                # Deterministic cache_id: same (lat, lon, time, type) always gets same UUID
                cache_id = str(uuid.uuid5(
                    uuid.NAMESPACE_URL,
                    f"{latitude:.6f}_{longitude:.6f}_{recorded_at_ms}_o"
                ))

                row = {
                    "cache_id": cache_id,
                    "temp": _safe(temps, i),
                    "humidity": _safe(humidities, i),
                    "wind_speed": _safe(winds, i),
                    "wind_direction": _safe(wind_dirs, i),
                    "precipitation_amount": _safe(precips, i),
                    "pressure": _safe(pressures, i),
                    "recorded_at": time_str,
                    "latitude": latitude,
                    "longitude": longitude,
                    "is_forecast": False,
                    "dew_point_c": _safe(dew_points, i),
                    "elevation": elevation,
                    "recorded_at_ms": recorded_at_ms,
                }
                weather_rows.append(row)

            # Build forecast weather_cache rows
            for i, time_str in enumerate(fc_times):
                from datetime import datetime as _dt
                parsed_time = _dt.fromisoformat(time_str).replace(tzinfo=timezone.utc)
                recorded_at_ms = int(parsed_time.timestamp() * 1000)

                def _safe(lst, idx):
                    return lst[idx] if idx < len(lst) and lst[idx] is not None else None

                cache_id = str(uuid.uuid5(
                    uuid.NAMESPACE_URL,
                    f"{latitude:.6f}_{longitude:.6f}_{recorded_at_ms}_f"
                ))

                row = {
                    "cache_id": cache_id,
                    "temp": _safe(fc_temps, i),
                    "humidity": _safe(fc_humidities, i),
                    "wind_speed": _safe(fc_winds, i),
                    "wind_direction": _safe(fc_wind_dirs, i),
                    "precipitation_amount": _safe(fc_precips, i),
                    "pressure": _safe(fc_pressures, i),
                    "recorded_at": time_str,
                    "latitude": latitude,
                    "longitude": longitude,
                    "is_forecast": True,
                    "dew_point_c": _safe(fc_dew_points, i),
                    "elevation": elevation,
                    "recorded_at_ms": recorded_at_ms,
                }
                weather_rows.append(row)

            print(f"[SEED] Total rows (obs + forecast): {len(weather_rows)}")

            # ── Create ONE snapshot with all weather rows as JSONB archive ──
            if weather_rows:
                now = datetime.now(timezone.utc).isoformat()
                snapshot_id = str(uuid.uuid4())

                snap_row = {
                    "weather_id": snapshot_id,
                    "device_id": device_id,
                    "synced_at": now,
                    "is_current": True,
                    "weather_data": weather_rows,          # full archive as JSONB
                    "snapshot_type": "seed",
                }
                snap_resp = await client.post(
                    f"{SUPABASE_BASE}/rest/v1/offline_weather_snapshot",
                    json=[snap_row],
                    headers=headers,
                )
                if snap_resp.status_code not in [200, 201]:
                    logger.error("[SEED] snapshot insert failed: %s — %s", snap_resp.status_code, snap_resp.text[:300])
                    raise HTTPException(status_code=502, detail=f"snapshot seed failed: {snap_resp.status_code}")
                print(f"[SEED] ✓ Stored 1 snapshot ({len(weather_rows)} weather rows as JSONB)")

            print(f"[SEED] ✓ Done — returning {len(weather_rows)} rows to device")
            return {
                "success": True,
                "count": len(weather_rows),
                "weather_rows": weather_rows,
                "elevation": elevation,
            }
    except HTTPException:
        raise
    except Exception as e:
        print(f"[SEED] \u2717 Exception: {e}")
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=str(e))


# ============= MODEL INSTANCE =============

class ModelInstanceRequest(BaseModel):
    version: str = "v1.0.0"
    latitude: float
    longitude: float
    result_level: int
    result_type: str = "storm"
    confidence_score: float
    weather_id: str | None = None   # caller can supply (e.g. dummy UUID for simulations)


class ModelInstanceBatchItem(BaseModel):
    instance_id: str
    version: str = "v1.0.0"
    latitude: float
    longitude: float
    result_level: int
    result_type: str = "storm"
    confidence_score: float
    created_at: int  # epoch-ms from the device


class ModelInstanceBatchRequest(BaseModel):
    instances: list[ModelInstanceBatchItem]


@app.post("/devices/{device_id}/model-instance")
async def create_model_instance(device_id: str, request: ModelInstanceRequest):
    """
    Record a model prediction result in the model_instance table.
    Called by the Android client after each on-device ML prediction.
    Automatically links to the most recent offline_weather_snapshot for this device.
    """
    try:
        record = {
            "instance_id": str(uuid.uuid4()),
            "version": request.version,
            "latitude": request.latitude,
            "longitude": request.longitude,
            "result_level": max(0, min(5, request.result_level)),
            "result_type": request.result_type,
            "confidence_score": round(request.confidence_score, 4),
            "created_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        }

        async with httpx.AsyncClient(timeout=15) as client:
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
                "Prefer": "return=representation",
            }

            # Use caller-supplied weather_id (e.g. dummy UUID for simulations)
            # or fall back to the most recent snapshot for this device.
            if request.weather_id:
                record["weather_id"] = request.weather_id
                logger.info("Using caller-supplied weather_id %s", request.weather_id)
            else:
                snap_resp = await client.get(
                    f"{SUPABASE_BASE}/rest/v1/offline_weather_snapshot"
                    f"?device_id=eq.{device_id}&order=synced_at.desc,weather_id.desc&limit=1"
                    f"&select=weather_id",
                    headers={"apikey": SUPABASE_API_KEY},
                )
                if snap_resp.status_code == 200:
                    rows = snap_resp.json()
                    if rows:
                        record["weather_id"] = rows[0]["weather_id"]
                        logger.info("Linked model_instance to snapshot %s", record["weather_id"])

            resp = await client.post(
                f"{SUPABASE_BASE}/rest/v1/model_instance",
                json=record,
                headers=headers,
            )
            if resp.status_code not in [200, 201]:
                logger.error("model_instance insert failed: %s %s", resp.status_code, resp.text[:300])
                raise HTTPException(status_code=502, detail=f"Supabase error: {resp.text[:200]}")

        logger.info("model_instance created for device %s: level=%d, confidence=%.4f",
                     device_id, request.result_level, request.confidence_score)
        return {"success": True, "instance_id": record["instance_id"]}

    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Error creating model_instance")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/devices/{device_id}/sync-model-instances")
async def sync_model_instances(device_id: str, request: ModelInstanceBatchRequest):
    """
    Batch-sync locally queued model instances from the Android client.
    Accepts an array of model instance records and upserts them to Supabase.
    """
    if not request.instances:
        return {"success": True, "synced": 0}

    try:
        records = []
        for item in request.instances:
            created_at = datetime.fromtimestamp(
                item.created_at / 1000.0, tz=timezone.utc
            ).strftime("%Y-%m-%dT%H:%M:%SZ")
            records.append({
                "instance_id": item.instance_id,
                "version": item.version,
                "latitude": item.latitude,
                "longitude": item.longitude,
                "result_level": max(0, min(5, item.result_level)),
                "result_type": item.result_type,
                "confidence_score": round(item.confidence_score, 4),
                "created_at": created_at,
            })

        async with httpx.AsyncClient(timeout=30) as client:
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
                "Prefer": "return=representation,resolution=merge-duplicates",
            }

            # Look up the most recent snapshot for this device to link all instances
            snap_resp = await client.get(
                f"{SUPABASE_BASE}/rest/v1/offline_weather_snapshot"
                f"?device_id=eq.{device_id}&order=synced_at.desc,weather_id.desc&limit=1"
                f"&select=weather_id",
                headers={"apikey": SUPABASE_API_KEY},
            )
            if snap_resp.status_code == 200:
                rows = snap_resp.json()
                if rows:
                    snap_id = rows[0]["weather_id"]
                    for rec in records:
                        rec["weather_id"] = snap_id

            resp = await client.post(
                f"{SUPABASE_BASE}/rest/v1/model_instance",
                json=records,
                headers=headers,
            )
            if resp.status_code not in [200, 201]:
                logger.error("model_instance batch insert failed: %s %s",
                             resp.status_code, resp.text[:300])
                raise HTTPException(status_code=502, detail=f"Supabase error: {resp.text[:200]}")

        logger.info("Synced %d model instances for device %s", len(records), device_id)
        return {"success": True, "synced": len(records)}

    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Error syncing model instances")
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == '__main__':
    port = int(os.getenv('PORT', 5000))
    reload = os.getenv('DEBUG', 'False') == 'True'
    # Use 0.0.0.0 to accept connections from Android emulator (10.0.2.2)
    uvicorn.run(app, host='0.0.0.0', port=port, reload=reload)
