# Firebase Cloud Messaging Setup Guide

## Overview
This project uses Firebase Cloud Messaging (FCM) to send push notifications to Android devices. Notifications work even when the app is closed or the device is in the background.

## Architecture

```
Android App gets FCM token → BackendRepository.registerDeviceToken() → Backend stores token
                                                     ↓
Backend endpoint → Firebase Admin SDK → Firebase Cloud Messaging → Device displays notification
```

## Prerequisites

1. Google Account
2. Firebase Project (free tier available)
3. Android device or emulator with Google Play Services

---

## Part 1: Create Firebase Project

### Step 1: Create Firebase Project
1. Go to [Firebase Console](https://console.firebase.google.com)
2. Click **Create a project**
3. Enter project name: `Weather Tracker`
4. Click **Continue**
5. Disable Google Analytics (optional for development)
6. Click **Create project**

### Step 2: Create Android App in Firebase
1. In Firebase Console, click the **Android icon** to add an Android app
2. Fill in details:
   - **Android package name**: `com.CMPS490.weathertracker`
   - **App nickname**: `Weather Tracker Android`
   - Click **Register app**

### Step 3: Download `google-services.json`
1. After registering, Firebase will show: **Download google-services.json**
2. Click the download button
3. Move the downloaded file to: `frontend/app/google-services.json`

### Step 4: Skip remaining setup steps
The gradle files are already configured to use this file.

---

## Part 2: Backend Setup

### Step 1: Get Firebase Admin SDK Key
1. In Firebase Console, go to **Project Settings** (gear icon)
2. Go to **Service Accounts** tab
3. Click **Generate New Private Key**
4. A JSON file will download - **save it securely**

### Step 2: Configure Backend

Choose ONE of these options:

#### Option A: Environment Variable (Recommended)
```bash
# Convert the JSON to a single-line string and set as environment variable
# On Windows PowerShell:
$json = Get-Content "path/to/firebase-key.json" -Raw
$env:FIREBASE_CONFIG = $json

# Or in .env file:
FIREBASE_CONFIG='{"type":"service_account","project_id":"...","...":"..."}'
```

#### Option B: Use Credentials File
1. Copy the downloaded JSON to: `backend/firebase-key.json`
2. Update `.env`:
   ```
   FIREBASE_CREDENTIALS_PATH=firebase-key.json
   ```

### Step 3: Install Firebase Dependencies
```bash
cd backend
pip install -r requirements.txt
```

---

## Part 3: How It Works

### Android Side:
1. **App Launch**: Google Play Services registers device with FCM
2. **Get Token**: `FirebaseMessaging.getInstance().token` gives device's unique token
3. **Register Token** Android calls: `/notifications/register-device`
4. **Receive Notifications**: `WeatherTrackerMessagingService` handles incoming FCM messages
5. **Display**: Notification appears on device (even when app is closed)

### Backend Side:
1. **Store Token**: Backend saves the device token (in production, store in Supabase)
2. **Send Notification**: Use `/notifications/send` or `/notifications/weather-alert` endpoints
3. **Firebase Admin SDK**: Sends to FCM via Firebase Cloud Messaging
4. **Firebase**: Routes to device using stored token

---

## Usage Examples

### 1. Register Device Token (Automatic)
The app automatically gets the FCM token and registers it when launched.

```kotlin
// In MainActivityeffect when app starts:
FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
    BackendRepository.registerDeviceToken(
        deviceToken = token,
        deviceId = deviceId,
        userId = userId,
        onSuccess = { Log.d("FCM", "Token registered") },
        onError = { Log.e("FCM", it.message) }
    )
}
```

### 2. Send test Notification from Backend

**Using FastAPI Swagger UI:**
1. Start backend: `python app.py`
2. Go to: `http://localhost:8000/docs`
3. Find `/notifications/send` endpoint
4. Click **Try it out**
5. Fill in example:
   ```json
   {
     "device_token": "YOUR_FCM_TOKEN_HERE",
     "title": "Test Alert",
     "body": "This is a test notification",
     "notification_type": "alert"
   }
   ```
6. Click **Execute**

**Using Python script:**
```python
import requests

token = "YOUR_FCM_TOKEN_HERE"  # Get from app logs or device settings
response = requests.post(
    "http://localhost:5000/notifications/send",
    json={
        "device_token": token,
        "title": "Weather Alert",
        "body": "Severe weather warning in your area",
        "data": {"type": "weather_alert"},
        "notification_type": "alert"
    }
)
print(response.json())
```

### 3. Send Weather Alert Notification

```python
requests.post(
    "http://localhost:5000/notifications/weather-alert",
    json={
        "device_token": token,
        "location": "Lafayette, Louisiana",
        "alert_type": "tornado",
        "description": "Tornado warning in effect until 8:00 PM CDT"
    }
)
```

### 4. Get Device Token from App

The device token is logged when the app registers. Check Android Studio's Logcat:
```
D/FCM: Refreshed token: eJza1B2c3O4x...
```

You can also retrieve it programmatically:
```kotlin
FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
    if (task.isSuccessful) {
        val token = task.result
        Log.d("FCM_TOKEN", token)
    }
}
```

---

## Testing Notifications

### Step 1: Start Backend
```bash
cd backend
.\venv\Scripts\activate
python app.py
```

### Step 2: Run Android App
```bash
cd frontend
./gradlew installDebug
```

### Step 3: Get Device Token
1. Open the app
2. Check Android Studio Logcat for: `D/FCM: Refreshed token:`
3. Copy the token

### Step 4: Send Test Notification
Use the FastAPI Swagger UI or Python script above with the copied token.

### Step 5: Verify
- App should receive notification
- Popup appears on device
- Works even if app is in background!

---

## Troubleshooting

### Notifications Not Appearing
1. **Check Firebase credentials**: Ensure `FIREBASE_CONFIG` or `firebase-key.json` is set up correctly
2. **Check device token**: Verify token is being registered
3. **Check backend logs**: Should see "Notification sent successfully"
4. **Check device logs**: Android should show FCM message received
5. **Check app permissions**: App needs `POST_NOTIFICATIONS` permission (Android 13+)

### Token Not Registering
1. Device must have Google Play Services installed
2. For emulator: Must use emulator with Google Play
3. Check network connectivity: App must reach backend

### Backend Errors
```
Failed to initialize Firebase
```
Solution: Check that FIREBASE_CONFIG or firebase-key.json exists and is valid.

---

## Security Notes

⚠️ **Important:**
- Never commit `firebase-key.json` to version control
- Add to `.gitignore`
- Use environment variables or secure vault for credentials in production
- Tokens are device-specific and can be revoked

---

## Next Steps

1. Integrate notification handling into weather alert logic
2. Store device tokens in Supabase database
3. Send notifications when severe weather alerts are triggered
4. Create notification preferences/settings UI

---

## Firebase Documentation
- [Firebase Cloud Messaging Docs](https://firebase.google.com/docs/cloud-messaging)
- [Android FCM Setup](https://firebase.google.com/docs/cloud-messaging/android/client)
- [Firebase Admin SDK Python](https://firebase.google.com/docs/admin/setup#python)
