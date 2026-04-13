"""
FastAPI backend for Weather Tracker ML predictions + API Proxy
"""
import sys
from pathlib import Path

# Add the project root so ``lib`` is importable, and backend/ so
# lib modules can resolve ``from config import ...`` / ``from database import ...``.
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
from firebase_notifications import firebase_service
import uuid
import secrets
import string
from datetime import datetime, timezone
import logging

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

# ── ML pipeline imports (lib/) ──────────────────────────────────────
from lib.ml.ml_prediction_service import get_predictor_client, PredictorUnavailableError
from lib.ml.feature_assembly_service import assemble_live_features, FeatureAssemblyError
from lib.ml.live_weather_service import fetch_live_prediction_input, LiveWeatherError
from database import UserDevice, get_session


# ============= UTILITY FUNCTIONS =============

def generate_alert_token() -> str:
    """
    Generate a unique alert token for device identification.
    Format: UUID v4 string
    """
    return str(uuid.uuid4())


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
    """Registration request â€” only device-side facts from frontend"""
    locationPermissionStatus: bool = False
    deviceToken: str | None = None
    latitude: float | None = None
    longitude: float | None = None

class RegisterDeviceTokenRequest(BaseModel):
    """Register FCM device token"""
    device_token: str
    device_id: str
    alert_token: str | None = None
    user_id: str | None = None


class SendNotificationRequest(BaseModel):
    """Send notification request"""
    device_token: str
    title: str
    body: str
    data: dict | None = None
    notification_type: str = "alert"


class WeatherAlertNotificationRequest(BaseModel):
    """Send weather alert notification"""
    device_token: str
    location: str
    alert_type: str
    description: str


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
    alert_type: str
    severity_level: int
    created_at: str
    expires_at: str


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
        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.get(f"{WEATHER_API_BASE}/points/{lat},{lon}")
            return response.json()
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Weather API error: {str(e)}")


@app.get("/weather/forecast")
async def get_forecast(url: str):
    """
    Proxy: Get forecast from URL
    Corresponds to: GET from forecast URL
    """
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.get(url)
            return response.json()
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Forecast API error: {str(e)}")


@app.get("/weather/alerts")
async def get_alerts(point: str):
    """
    Proxy: Get active weather alerts for a point
    Corresponds to: GET https://api.weather.gov/alerts/active?point={point}
    """
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.get(f"{WEATHER_API_BASE}/alerts/active", params={"point": point})
            return response.json()
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Alerts API error: {str(e)}")


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
    FCM device_token is set later via /notifications/register-device.
    """
    try:
        print(f"\n[REGISTER] Received request: locationPermissionStatus={request.locationPermissionStatus}, deviceToken={'present (' + str(len(request.deviceToken)) + ' chars)' if request.deviceToken else 'NULL'}")

        async with httpx.AsyncClient(timeout=30) as client:
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
                "Prefer": "return=representation",
            }

            # --- Check if device already exists by FCM token ---
            if request.deviceToken:
                existing_response = await client.get(
                    f"{SUPABASE_BASE}/rest/v1/device?device_token=eq.{request.deviceToken}&select=device_id,anon_user_id,alert_token",
                    headers=headers,
                )
                if existing_response.status_code == 200:
                    existing = existing_response.json()
                    if existing:
                        device = existing[0]
                        device_id = device['device_id']
                        print(f"[REGISTER] Existing device found: {device_id}, returning existing credentials")
                        # Update last_seen_at
                        now = datetime.now(timezone.utc).isoformat()
                        await client.patch(
                            f"{SUPABASE_BASE}/rest/v1/device?device_id=eq.{device_id}",
                            json={"last_seen_at": now, "location_permission_status": request.locationPermissionStatus},
                            headers=headers,
                        )
                        
                        # AUTO-POPULATE DEVICE LOCATION for existing device if permission granted + coords provided
                        if request.locationPermissionStatus and request.latitude is not None and request.longitude is not None:
                            # Check if device_location already exists for this device
                            existing_loc_response = await client.get(
                                f"{SUPABASE_BASE}/rest/v1/device_location?device_id=eq.{device_id}&order=captured_at.desc&limit=1",
                                headers=headers,
                            )
                            existing_locs = existing_loc_response.json() if existing_loc_response.status_code == 200 else []
                            
                            if not existing_locs:
                                print(f"[REGISTER] Auto-creating device_location for existing device (permission=true, coords provided)")
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
                                # Update existing location with new coordinates
                                existing_loc = existing_locs[0]
                                location_id = existing_loc["location_id"]
                                print(f"[REGISTER] Updating existing device_location {location_id} with new coords")
                                print(f"[REGISTER] Old: lat={existing_loc.get('latitude')}, lon={existing_loc.get('longitude')}")
                                print(f"[REGISTER] New: lat={request.latitude}, lon={request.longitude}")
                                
                                update_response = await client.patch(
                                    f"{SUPABASE_BASE}/rest/v1/device_location?location_id=eq.{location_id}",
                                    json={
                                        "latitude": request.latitude,
                                        "longitude": request.longitude,
                                        "captured_at": now,
                                    },
                                    headers=headers,
                                )
                                if update_response.status_code in [200, 204]:
                                    print(f"✓ Device location updated: {location_id}")
                                else:
                                    print(f"✗ Failed to update device_location: {update_response.text}")
                        else:
                            print(f"[REGISTER] Skipping device_location for existing device: permission={request.locationPermissionStatus}, lat={request.latitude}, lon={request.longitude}")
                        
                        return {
                            "success": True,
                            "userId": device["anon_user_id"],
                            "deviceId": device_id,
                            "alertToken": device["alert_token"],
                        }

            # --- No existing device found, create new ---
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
            if request.deviceToken:
                device_record["device_token"] = request.deviceToken

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

@app.post("/notifications/register-device")
async def register_device_token(request: RegisterDeviceTokenRequest):
    """
    Register a device FCM token for push notifications.
    Stores the FCM token as device_token and auto-generates alert_token if not provided.
    Updates existing device or creates a new device record.
    Must be called once per device to enable notifications.
    """
    # Auto-generate alert_token if not provided
    alert_token = request.alert_token or generate_alert_token()
    
    print(f"\n[REGISTER-DEVICE] Received request:")
    print(f"  device_id: {request.device_id}")
    print(f"  device_token: {request.device_token[:20]}...")
    print(f"  alert_token: {alert_token}")
    print(f"  Supabase URL: {SUPABASE_BASE}")
    
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
                "Prefer": "return=representation"
            }
            
            # First, try to update existing device by patching device_token and alert_token
            patch_url = f"{SUPABASE_BASE}/rest/v1/device?device_id=eq.{request.device_id}"
            patch_body = {
                "device_token": request.device_token,
                "alert_token": alert_token
            }
            
            print(f"[PATCH] URL: {patch_url}")
            print(f"[PATCH] Body: {patch_body}")
            
            patch_response = await client.patch(
                patch_url,
                json=patch_body,
                headers=headers
            )
            
            print(f"[PATCH] Status: {patch_response.status_code}")
            print(f"[PATCH] Response: {patch_response.text[:200]}")
            
            # Check if PATCH succeeded AND updated rows (response is not empty)
            patch_data = patch_response.json() if patch_response.text else []
            if patch_response.status_code in [200, 201, 204] and len(patch_data) > 0:
                existing_alert_token = patch_data[0].get("alert_token") if patch_data else alert_token
                print(f"âœ“ Device tokens updated successfully for: {request.device_id}")
                return {
                    "success": True,
                    "message": "FCM token registered successfully",
                    "device_id": request.device_id,
                    "alert_token": existing_alert_token,
                    "token_registered": True
                }
            
            # If PATCH returned empty response or failed, try to INSERT a new device
            print(f"[POST] PATCH returned no rows or failed, attempting INSERT...")
            post_url = f"{SUPABASE_BASE}/rest/v1/device"
            post_body = {
                "device_id": request.device_id,
                "device_token": request.device_token,
                "alert_token": alert_token
            }
            
            post_response = await client.post(
                post_url,
                json=post_body,
                headers=headers
            )
            
            print(f"[POST] Status: {post_response.status_code}")
            print(f"[POST] Response: {post_response.text[:200]}")
            
            if post_response.status_code in [200, 201, 204]:
                print(f"âœ“ New device created with tokens for: {request.device_id}")
                return {
                    "success": True,
                    "message": "Device created and token registered",
                    "device_id": request.device_id,
                    "alert_token": alert_token,
                    "token_registered": True
                }
            else:
                print(f"âœ— Error creating device: {post_response.text}")
                return {"success": False, "error": f"Failed to register device: {post_response.text}"}
    except Exception as e:
        print(f"âœ— Exception in register_device_token: {str(e)}")
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"Error registering device: {str(e)}")


@app.post("/notifications/send")
async def send_notification(request: SendNotificationRequest):
    """
    Send a push notification to a device.

    Example payload:
    {
        "device_token": "fcm_device_token_here",
        "title": "Alert Title",
        "body": "Alert message body",
        "data": {"key": "value"},
        "notification_type": "alert"
    }
    """
    try:
        success = firebase_service.send_notification(
            device_token=request.device_token,
            title=request.title,
            body=request.body,
            data=request.data,
            notification_type=request.notification_type
        )

        return {
            "success": success,
            "message": "Notification sent" if success else "Failed to send notification",
            "device_token": request.device_token
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error sending notification: {str(e)}")


@app.post("/notifications/weather-alert")
async def send_weather_alert(request: WeatherAlertNotificationRequest):
    """
    Send a weather alert notification to a device.

    Example payload:
    {
        "device_token": "fcm_device_token_here",
        "location": "Lafayette, Louisiana",
        "alert_type": "tornado",
        "description": "Tornado warning in effect until 8:00 PM"
    }
    """
    try:
        success = firebase_service.send_weather_alert(
            device_token=request.device_token,
            location=request.location,
            alert_type=request.alert_type,
            description=request.description
        )

        return {
            "success": success,
            "message": "Weather alert sent" if success else "Failed to send alert",
            "location": request.location,
            "alert_type": request.alert_type
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error sending weather alert: {str(e)}")


# ============= ALERT EVENT API =============

@app.post("/alerts/create")
async def create_alert_event(request: CreateAlertEventRequest):
    """
    Create a new alert event. Called by backend ML pipeline.
    Backend generates: alert_id, created_at
    """
    try:
        async with httpx.AsyncClient(timeout=30) as client:
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

            print(f"[ALERT] Creating alert: {alert_record}")

            response = await client.post(
                f"{SUPABASE_BASE}/rest/v1/alert_event",
                json=alert_record,
                headers=headers,
            )

            if response.status_code in [200, 201]:
                print(f"✓ Alert created: {alert_id}")
                return {"success": True, "alert_id": alert_id, "created_at": now}
            else:
                print(f"✗ Failed to create alert: {response.text}")
                raise HTTPException(status_code=500, detail=f"Failed: {response.text}")
    except HTTPException:
        raise
    except Exception as e:
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


# ============= ML PREDICTION ENDPOINTS =============


class LivePredictionRequest(BaseModel):
    """Request a prediction using the device's current location."""
    device_id: str
    latitude: float | None = None
    longitude: float | None = None


@app.post("/predict/live")
async def predict_live(request: LivePredictionRequest):
    """
    End-to-end live prediction:
    1. Fetch current weather from Open-Meteo for the device's location.
    2. Assemble the full ML feature vector (with history from the DB).
    3. Run the prediction through the ML model.
    """
    db = get_session()
    try:
        # Resolve device
        user = db.query(UserDevice).filter(UserDevice.id == request.device_id).first()
        if user is None:
            raise HTTPException(status_code=404, detail="Device not found")

        lat = request.latitude or user.last_lat
        lon = request.longitude or user.last_lon
        if lat is None or lon is None:
            raise HTTPException(
                status_code=400,
                detail="Location is required (provide latitude/longitude or ensure device has a saved location)",
            )

        # 1. Fetch live weather
        live_input = fetch_live_prediction_input(latitude=lat, longitude=lon)

        # 2. Assemble features
        weather_data = assemble_live_features(db=db, user=user, request_data=live_input)
        db.commit()

        # 3. Predict
        predictor = get_predictor_client()
        result = predictor.predict(weather_data=weather_data)

        return {"success": True, **result}

    except (LiveWeatherError, FeatureAssemblyError) as e:
        db.rollback()
        logger.error("Prediction input error: %s", e)
        raise HTTPException(status_code=422, detail=str(e))
    except PredictorUnavailableError as e:
        db.rollback()
        logger.error("ML predictor unavailable: %s", e)
        raise HTTPException(status_code=503, detail=str(e))
    except HTTPException:
        db.rollback()
        raise
    except Exception as e:
        db.rollback()
        logger.exception("Unexpected error in /predict/live")
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        db.close()


@app.get("/predict/health")
async def predict_health():
    """Health check for the ML predictor (local or remote)."""
    try:
        predictor = get_predictor_client()
        return predictor.health()
    except PredictorUnavailableError as e:
        raise HTTPException(status_code=503, detail=str(e))


@app.get("/predict/metadata")
async def predict_metadata():
    """Return model metadata: feature columns, target definitions, etc."""
    try:
        predictor = get_predictor_client()
        return predictor.metadata()
    except PredictorUnavailableError as e:
        raise HTTPException(status_code=503, detail=str(e))


if __name__ == '__main__':
    port = int(os.getenv('PORT', 5000))
    reload = os.getenv('DEBUG', 'False') == 'True'
    # Use 0.0.0.0 to accept connections from Android emulator (10.0.2.2)
    uvicorn.run(app, host='0.0.0.0', port=port, reload=reload)
