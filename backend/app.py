"""
FastAPI backend for Weather Tracker ML predictions + API Proxy
"""
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
from datetime import datetime

# Load environment variables
load_dotenv()

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

# TODO: Import and load your ML prediction model here
# from models.predictor import WeatherPredictor
# predictor = WeatherPredictor()


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


class AnonUserRequest(BaseModel):
    """Anonymous user creation request"""
    anonUserId: str
    status: str = "active"


class DeviceRequest(BaseModel):
    """Device creation request"""
    deviceId: str
    anonUserId: str
    alertToken: str | None = None
    deviceToken: str | None = None
    platform: str = "android"
    appVersion: str = "1.0"
    locationPermissionStatus: bool | None = None


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
        async with httpx.AsyncClient(follow_redirects=True) as client:
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
        async with httpx.AsyncClient(follow_redirects=True) as client:
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
        async with httpx.AsyncClient(follow_redirects=True) as client:
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
        async with httpx.AsyncClient() as client:
            response = await client.get(f"{RAINVIEWER_API_BASE}/public/weather-maps.json")
            return response.json()
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"RainViewer API error: {str(e)}")


# ============= SUPABASE API PROXY =============

@app.post("/supabase/anon-user")
async def create_anon_user(user_data: AnonUserRequest):
    """
    Proxy: Create anonymous user in Supabase
    Corresponds to: POST /rest/v1/anonymous_user
    """
    try:
        async with httpx.AsyncClient() as client:
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
            }
            response = await client.post(
                f"{SUPABASE_BASE}/rest/v1/anonymous_user",
                json=user_data.dict(),
                headers=headers
            )
            if response.status_code in [200, 201]:
                return {"success": True, "userId": user_data.anonUserId}
            return {"success": False, "error": response.text}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Supabase Error: {str(e)}")


@app.post("/supabase/device")
async def create_device(device_data: DeviceRequest):
    """
    Proxy: Create device record in Supabase
    Auto-generates an alert token if not provided (as UUID)
    Stores optional device_token (FCM) separately
    Corresponds to: POST /rest/v1/device
    """
    try:
        # Auto-generate alert token if not provided
        if not device_data.alertToken:
            device_data.alertToken = generate_alert_token()
        
        async with httpx.AsyncClient() as client:
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
            }
            response = await client.post(
                f"{SUPABASE_BASE}/rest/v1/device",
                json=device_data.dict(),
                headers=headers
            )
            if response.status_code in [200, 201]:
                return {
                    "success": True,
                    "deviceId": device_data.deviceId,
                    "alertToken": device_data.alertToken
                }
            return {"success": False, "error": response.text}
    except Exception as e:
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
        async with httpx.AsyncClient() as client:
            headers = {
                "apikey": SUPABASE_API_KEY,
                "Content-Type": "application/json",
                "Prefer": "return=representation"
            }
            
            # First, try to update existing device by patching device_token and alert_token
            # If alert_token was NULL and is now being set, update it
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
                # Extract the existing alert_token from the database response
                existing_alert_token = patch_data[0].get("alert_token") if patch_data else alert_token
                print(f"✓ Device tokens updated successfully for: {request.device_id}")
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
                print(f"✓ New device created with tokens for: {request.device_id}")
                return {
                    "success": True,
                    "message": "Device created and token registered",
                    "device_id": request.device_id,
                    "alert_token": alert_token,
                    "token_registered": True
                }
            else:
                print(f"✗ Error creating device: {post_response.text}")
                return {"success": False, "error": f"Failed to register device: {post_response.text}"}
    except Exception as e:
        print(f"✗ Exception in register_device_token: {str(e)}")
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


# ============= ML PREDICTION ENDPOINT =============

@app.post("/predict", response_model=PredictionResponse)
async def predict(request: PredictionRequest):
    """
    Prediction endpoint for ML model.
    Expected JSON payload should match your model's input requirements.
    """
    try:
        # TODO: Process input data and call your ML model
        # prediction = predictor.predict(request.dict())
        # return {"prediction": prediction}
        return {"prediction": "ML model not yet integrated"}
    except Exception as e:
        return {"prediction": f"Error: {str(e)}"}


if __name__ == '__main__':
    port = int(os.getenv('PORT', 5000))
    reload = os.getenv('DEBUG', 'False') == 'True'
    uvicorn.run(app, host='0.0.0.0', port=port, reload=reload)
