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

def generate_alert_token() -> str:
    """
    Generate a unique alert token for device identification.
    Format: UUID v4 string
    """
    return str(uuid.uuid4())


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
    latitude: float | None = None
    longitude: float | None = None


# ============= ALERT EVENT MODELS =============

from typing import Literal

class CreateAlertEventRequest(BaseModel):
    """Create alert from ML prediction - backend only"""
    instance_id: str | None = None
    latitude: float
    longitude: float
    alert_type: Literal["storm", "flood"]
    severity_level: int
    expires_at: str


class AlertEventResponse(BaseModel):
    """Alert event data"""
    alert_id: str
    instance_id: str | None
    latitude: float
    longitude: float
class DeviceUpdateRequest(BaseModel):
    """Update device attributes"""
    device_id: str
    location_permission_status: bool | None = None
    last_seen_at: str | None = None


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
    Backend generates ALL identifiers: anon_user_id, device_id, alert_token.
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
            alert_token = generate_alert_token()
            now = datetime.now(timezone.utc).isoformat()

            # --- Step 1: Create anonymous user ---
            user_record = {
                "anon_user_id": anon_user_id,
                "status": "active",
                "created_at": now,
                "last_active_at": now,
                "notification_opt_in": True,
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
                "alert_token": alert_token,
                "platform": "android",
                "app_version": "1.0",
                "location_permission_status": request.locationPermissionStatus,
                "last_seen_at": now,
                "created_at": now,
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
                    "alertToken": alert_token,
                }
            else:
                print(f"✗ Error creating device: {device_response.text}")
                return {"success": False, "error": f"Device creation failed: {device_response.text}"}
    except Exception as e:
        print(f"âœ— Exception in register_device: {str(e)}")
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"Supabase Error: {str(e)}")


# ============= FIREBASE NOTIFICATIONS =============

@app.post("/alerts/create")
async def create_alert_event(request: CreateAlertEventRequest):
    """
    Create a new alert event. Called by backend ML pipeline.
    After creating the alert, finds eligible devices and populates device_alert,
    then attempts push notifications and updates delivery status.
    """
    try:
        async with httpx.AsyncClient(timeout=60) as client:
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
                "Prefer": "return=representation",
            }

            alert_id = str(uuid.uuid4())
            now = datetime.now(timezone.utc).isoformat()

            alert_record = {
                "alert_id": alert_id,
                "instance_id": request.instance_id,
                "latitude": request.latitude,
                "longitude": request.longitude,
                "alert_type": request.alert_type,
                "severity_level": request.severity_level,
                "created_at": now,
                "expires_at": request.expires_at,
            }

            logger.info(f"[ALERT] Creating alert: {alert_record}")

            # Insert alert_event
            response = await client.post(
                f"{SUPABASE_BASE}/rest/v1/alert_event",
                json=alert_record,
                headers=headers,
            )

            if response.status_code not in [200, 201]:
                logger.error(f"✗ Failed to create alert: {response.text}")
                raise HTTPException(status_code=500, detail=f"Failed: {response.text}")

            logger.info(f"✓ Alert created: {alert_id}")

            return {
                "success": True,
                "alert_id": alert_id,
                "created_at": now,
            }
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Error creating alert")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/alerts/active")
async def get_active_alerts(lat: float | None = None, lon: float | None = None, radius_km: float = 50.0):
    """Get all non-expired alerts, optionally filtered by location."""
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            headers = {"apikey": SUPABASE_API_KEY, "Content-Type": "application/json"}
            now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
            
            response = await client.get(
                f"{SUPABASE_BASE}/rest/v1/alert_event?expires_at=gt.{now}&order=severity_level.desc",
                headers=headers,
            )

            if response.status_code == 200:
                alerts = response.json()
                
                if lat is not None and lon is not None:
                    from math import radians, cos, sin, sqrt, atan2
                    def haversine(lat1, lon1, lat2, lon2):
                        R = 6371
                        dlat, dlon = radians(lat2-lat1), radians(lon2-lon1)
                        a = sin(dlat/2)**2 + cos(radians(lat1))*cos(radians(lat2))*sin(dlon/2)**2
                        return 2 * R * atan2(sqrt(a), sqrt(1-a))
                    alerts = [a for a in alerts if haversine(lat, lon, float(a["latitude"]), float(a["longitude"])) <= radius_km]

                return {"alerts": alerts, "count": len(alerts)}
            else:
                raise HTTPException(status_code=500, detail=response.text)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/alerts/{alert_id}")
async def get_alert_by_id(alert_id: str):
    """Get a specific alert by ID."""
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            headers = {"apikey": SUPABASE_API_KEY, "Content-Type": "application/json"}
            response = await client.get(
                f"{SUPABASE_BASE}/rest/v1/alert_event?alert_id=eq.{alert_id}",
                headers=headers,
            )
            if response.status_code == 200:
                alerts = response.json()
                if alerts:
                    return {"success": True, "alert": alerts[0]}
                raise HTTPException(status_code=404, detail="Alert not found")
            raise HTTPException(status_code=500, detail=response.text)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.delete("/alerts/{alert_id}")
async def delete_alert(alert_id: str):
    """Delete an alert event."""
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            headers = {"apikey": SUPABASE_API_KEY, "Prefer": "return=representation"}
            response = await client.delete(
                f"{SUPABASE_BASE}/rest/v1/alert_event?alert_id=eq.{alert_id}",
                headers=headers,
            )
            if response.status_code in [200, 204]:
                return {"success": True, "message": f"Alert {alert_id} deleted"}
            raise HTTPException(status_code=500, detail=response.text)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ============= DEVICE ALERT API =============

@app.get("/device-alerts/{device_id}")
async def get_device_alerts(device_id: str):
    """Get all device_alert records for a specific device."""
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            headers = {"apikey": SUPABASE_API_KEY, "Content-Type": "application/json"}
            response = await client.get(
                f"{SUPABASE_BASE}/rest/v1/device_alert?device_id=eq.{device_id}&order=sent_at.desc",
                headers=headers,
            )
            if response.status_code == 200:
                rows = response.json()
                return {"device_alerts": rows, "count": len(rows)}
            raise HTTPException(status_code=500, detail=response.text)
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Error getting device alerts")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/device-alerts/by-alert/{alert_id}")
async def get_alert_delivery_status(alert_id: str):
    """Get delivery status for all devices targeted by a specific alert."""
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            headers = {"apikey": SUPABASE_API_KEY, "Content-Type": "application/json"}
            response = await client.get(
                f"{SUPABASE_BASE}/rest/v1/device_alert?alert_id=eq.{alert_id}",
                headers=headers,
            )
            if response.status_code == 200:
                rows = response.json()
                summary = {
                    "total": len(rows),
                    "sent": sum(1 for r in rows if r.get("delivery_status") == "sent"),
                    "failed": sum(1 for r in rows if r.get("delivery_status") == "failed"),
                    "pending": sum(1 for r in rows if r.get("delivery_status") == "pending"),
                }
                return {"device_alerts": rows, "summary": summary}
            raise HTTPException(status_code=500, detail=response.text)
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Error getting delivery status")
        raise HTTPException(status_code=500, detail=str(e))


@app.delete("/device-alerts/cleanup")
async def cleanup_old_device_alerts(retention_days: int = 30):
    """
    Delete device_alert rows whose associated alert_event expired more than
    retention_days ago. Default retention: 30 days.
    """
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
                "Prefer": "return=representation",
            }

            cutoff = (datetime.now(timezone.utc) - timedelta(days=retention_days)).strftime("%Y-%m-%dT%H:%M:%SZ")

            # Find expired alerts older than cutoff
            alert_resp = await client.get(
                f"{SUPABASE_BASE}/rest/v1/alert_event?expires_at=lt.{cutoff}&select=alert_id",
                headers=headers,
            )
            if alert_resp.status_code != 200:
                raise HTTPException(status_code=500, detail=f"Failed to query alerts: {alert_resp.text}")

            expired_alerts = alert_resp.json()
            if not expired_alerts:
                return {"success": True, "deleted": 0, "message": "No expired alerts to clean up"}

            expired_ids = [a["alert_id"] for a in expired_alerts]

            # Delete device_alert rows for those expired alerts
            deleted_count = 0
            for aid in expired_ids:
                del_resp = await client.delete(
                    f"{SUPABASE_BASE}/rest/v1/device_alert?alert_id=eq.{aid}",
                    headers=headers,
                )
                if del_resp.status_code in [200, 204]:
                    rows = del_resp.json() if del_resp.text else []
                    deleted_count += len(rows) if isinstance(rows, list) else 0

            logger.info(f"[CLEANUP] Deleted {deleted_count} device_alert rows (cutoff: {cutoff})")
            return {"success": True, "deleted": deleted_count, "retention_days": retention_days}
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Error cleaning up device alerts")
        raise HTTPException(status_code=500, detail=str(e))


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
    Update device attributes like location_permission_status and last_seen_at.
    Only updates fields that are provided (not None).
    Corresponds to: PATCH /rest/v1/device
    """
    try:
        print(f"\n[UPDATE-DEVICE] Received request:")
        print(f"  device_id: {request.device_id}")
        if request.location_permission_status is not None:
            print(f"  location_permission_status: {request.location_permission_status}")
        if request.last_seen_at is not None:
            print(f"  last_seen_at: {request.last_seen_at}")
        print(f"  Supabase URL: {SUPABASE_BASE}")
        
        async with httpx.AsyncClient() as client:
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
                "Prefer": "return=representation"
            }
            
            # Build update body with only provided fields
            update_body = {}
            if request.location_permission_status is not None:
                update_body["location_permission_status"] = request.location_permission_status
            if request.last_seen_at is not None:
                update_body["last_seen_at"] = request.last_seen_at
            
            if not update_body:
                return {
                    "success": False,
                    "error": "No fields to update"
                }
            
            patch_url = f"{SUPABASE_BASE}/rest/v1/device?device_id=eq.{request.device_id}"
            
            print(f"[PATCH] URL: {patch_url}")
            print(f"[PATCH] Body: {update_body}")
            
            patch_response = await client.patch(
                patch_url,
                json=update_body,
                headers=headers
            )
            
            print(f"[PATCH] Status: {patch_response.status_code}")
            print(f"[PATCH] Response: {patch_response.text[:200]}")
            
            if patch_response.status_code in [200, 201, 204]:
                print(f"✓ Device updated successfully for: {request.device_id}")
                return {
                    "success": True,
                    "message": "Device updated successfully",
                    "device_id": request.device_id
                }
            else:
                print(f"✗ Error updating device: {patch_response.text}")
                return {
                    "success": False,
                    "error": f"Failed to update device: {patch_response.text}"
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

class SyncSnapshotItem(_BaseModel):
    weather_cache: dict
    offline_snapshot: dict

class SyncSnapshotsRequest(_BaseModel):
    snapshots: list[SyncSnapshotItem]


def _epoch_ms_to_iso(value) -> str | None:
    """Convert epoch-millisecond long to ISO 8601 string for Postgres TIMESTAMP columns."""
    if value is None:
        return None
    try:
        ts = float(value)
        # If the value looks like epoch milliseconds (> year-3000 as seconds), convert
        if ts > 1e12:
            ts = ts / 1000.0
        return datetime.fromtimestamp(ts, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    except (TypeError, ValueError, OSError):
        # Already a string / ISO — pass through
        return str(value)


@app.post("/devices/{device_id}/sync-snapshots")
async def sync_snapshots(device_id: str, request: SyncSnapshotsRequest):
    """
    Upsert weather_cache rows then offline_weather_snapshot rows from the client.
    Returns a success flag.
    """
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
                "Prefer": "return=representation,resolution=merge-duplicates",
            }

            upserted = 0
            errors = []
            for item in request.snapshots:
                # Convert epoch-ms timestamps to ISO for Postgres TIMESTAMP columns
                wc = dict(item.weather_cache)
                if "recorded_at" in wc:
                    wc["recorded_at"] = _epoch_ms_to_iso(wc["recorded_at"])

                wc_resp = await client.post(
                    f"{SUPABASE_BASE}/rest/v1/weather_cache",
                    json=wc,
                    headers=headers,
                )
                if wc_resp.status_code in [200, 201]:
                    upserted += 1
                else:
                    errors.append(f"weather_cache {wc.get('cache_id')}: {wc_resp.status_code} {wc_resp.text[:200]}")
                    logger.warning("Failed to upsert weather_cache: %s %s", wc_resp.status_code, wc_resp.text[:300])

                # Upsert offline_weather_snapshot row
                snap = {**item.offline_snapshot, "device_id": device_id}
                # Always set synced_at to now (server-side timestamp)
                snap["synced_at"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

                snap_resp = await client.post(
                    f"{SUPABASE_BASE}/rest/v1/offline_weather_snapshot",
                    json=snap,
                    headers=headers,
                )
                if snap_resp.status_code not in [200, 201]:
                    errors.append(f"snapshot {snap.get('offline_weather_id')}: {snap_resp.status_code} {snap_resp.text[:200]}")
                    logger.warning("Failed to upsert snapshot: %s %s", snap_resp.status_code, snap_resp.text[:300])

            if errors:
                logger.error("Sync errors for device %s: %s", device_id, errors)
                raise HTTPException(status_code=502, detail={"sync_errors": errors, "upserted": upserted})

            return {"success": True, "upserted": upserted}
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Error syncing snapshots")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/devices/{device_id}/snapshots")
async def get_device_snapshots(device_id: str, since: str | None = None):
    """
    Return offline_weather_snapshot rows joined with weather_cache for this device.
    Optionally filter by recorded_at >= since (ISO 8601).
    """
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            headers = {"apikey": SUPABASE_API_KEY, "Content-Type": "application/json"}

            snapshot_url = (
                f"{SUPABASE_BASE}/rest/v1/offline_weather_snapshot"
                f"?device_id=eq.{device_id}&select=*,weather_cache(*)"
                f"&order=weather_cache(recorded_at).desc&limit=168"
            )
            if since:
                snapshot_url += f"&weather_cache.recorded_at=gte.{since}"

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
    device's location, store in Supabase weather_cache + offline_weather_snapshot,
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

            # Build weather_cache rows
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
                "Prefer": "return=representation,resolution=merge-duplicates",
            }

            weather_rows = []
            for i, time_str in enumerate(times):
                cache_id = str(uuid.uuid4())
                # Parse ISO time to epoch millis (Open-Meteo returns UTC)
                from datetime import datetime as _dt
                parsed_time = _dt.fromisoformat(time_str).replace(tzinfo=timezone.utc)
                recorded_at_ms = int(parsed_time.timestamp() * 1000)

                def _safe(lst, idx):
                    return lst[idx] if idx < len(lst) and lst[idx] is not None else None

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

            # Upsert into Supabase weather_cache
            if weather_rows:
                supabase_rows = [
                    {k: v for k, v in row.items() if k != "recorded_at_ms"}
                    for row in weather_rows
                ]
                cache_resp = await client.post(
                    f"{SUPABASE_BASE}/rest/v1/weather_cache",
                    json=supabase_rows,
                    headers=headers,
                )
                if cache_resp.status_code in [200, 201]:
                    print(f"[SEED] \u2713 Stored {len(supabase_rows)} rows in Supabase weather_cache")
                else:
                    print(f"[SEED] \u26a0 weather_cache upsert: {cache_resp.status_code} — {cache_resp.text[:200]}")

                # Create offline_weather_snapshot records
                now = datetime.now(timezone.utc).isoformat()
                snapshot_rows = [
                    {
                        "offline_weather_id": str(uuid.uuid4()),
                        "device_id": device_id,
                        "cache_id": row["cache_id"],
                        "synced_at": now,
                        "is_current": (i == len(weather_rows) - 1),
                    }
                    for i, row in enumerate(weather_rows)
                ]
                snap_resp = await client.post(
                    f"{SUPABASE_BASE}/rest/v1/offline_weather_snapshot",
                    json=snapshot_rows,
                    headers=headers,
                )
                if snap_resp.status_code in [200, 201]:
                    print(f"[SEED] \u2713 Stored {len(snapshot_rows)} snapshot links")
                else:
                    print(f"[SEED] \u26a0 snapshot upsert: {snap_resp.status_code} — {snap_resp.text[:200]}")

            print(f"[SEED] \u2713 Done — returning {len(weather_rows)} rows to device")
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


@app.post("/devices/{device_id}/model-instance")
async def create_model_instance(device_id: str, request: ModelInstanceRequest):
    """
    Record a model prediction result in the model_instance table.
    Called by the Android client after each on-device ML prediction.
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


if __name__ == '__main__':
    port = int(os.getenv('PORT', 5000))
    reload = os.getenv('DEBUG', 'False') == 'True'
    # Use 0.0.0.0 to accept connections from Android emulator (10.0.2.2)
    uvicorn.run(app, host='0.0.0.0', port=port, reload=reload)
