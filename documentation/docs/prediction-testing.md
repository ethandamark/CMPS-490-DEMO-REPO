# Prediction Testing Commands

Quick reference for running on-device ML predictions and viewing results via ADB.

> **Prerequisites**: Emulator running, app installed, backend server on port 5000.

---

## 1. Live Force Prediction

Fetches real-time weather from Open-Meteo, stores it in Room DB, runs the ONNX model, and logs the result.

```bash
adb shell am start -n com.CMPS490.weathertracker/.DebugPredictActivity
```

Uses default coordinates: Lafayette, LA (30.2241, -92.0198).

---

## 2. Hurricane Staged Prediction (Storm Simulation)

Injects 24 hours of pre-computed Hurricane Katrina-like weather data into Room DB and runs the predictor. This is guaranteed to trigger a storm alert notification (probability ~70%, well above the 0.5045 threshold).

```bash
adb shell am start -n com.CMPS490.weathertracker/.StormSimulationActivity
```

Uses coordinates: New Orleans, LA (29.95, -90.07). Simulated conditions include:
- Pressure dropping from 990 → 920 hPa
- Winds escalating to 185 km/h
- Continuous heavy rainfall (2–42 mm/h)
- 98–99% humidity, extreme CAPE (4200 J/kg)

---

## 3. Logcat — Prediction View

View prediction results only (compact output):

```bash
adb logcat -s DebugPredict:D StormSimulation:D
```

This filters to just the prediction activity logs showing probability, alert state, threshold, and model version.

---

## 4. Logcat — Verbose Prediction View

View prediction results **plus all 33 feature values** used by the model:

```bash
adb logcat -s DebugPredict:D StormSimulation:D FeatureAssemblyService:D OnDevicePredictor:I
```

This shows every feature fed into the ONNX model (temp, pressure, humidity, wind, precipitation aggregates, NWP values, etc.) in addition to the final prediction result.

---

## Combined One-Liners

### Force predict + view results

```bash
adb logcat -c; adb shell am start -n com.CMPS490.weathertracker/.DebugPredictActivity; timeout 8; adb logcat -d -s DebugPredict:D
```

### Storm simulation + verbose results

```bash
adb logcat -c; adb shell am start -n com.CMPS490.weathertracker/.StormSimulationActivity; timeout 8; adb logcat -d -s StormSimulation:D FeatureAssemblyService:D OnDevicePredictor:I
```

### PowerShell variants (Windows)

```powershell
# Force predict + view results
adb logcat -c; adb shell am start -n com.CMPS490.weathertracker/.DebugPredictActivity; Start-Sleep -Seconds 8; adb logcat -d -s DebugPredict:D

# Storm simulation + verbose results
adb logcat -c; adb shell am start -n com.CMPS490.weathertracker/.StormSimulationActivity; Start-Sleep -Seconds 8; adb logcat -d -s StormSimulation:D FeatureAssemblyService:D OnDevicePredictor:I
```

---

## Log Tags Reference

| Tag | Source | Content |
|-----|--------|---------|
| `DebugPredict` | `DebugPredictActivity` | Live weather fetch + prediction result |
| `StormSimulation` | `StormSimulationActivity` | Katrina data injection + prediction result |
| `FeatureAssemblyService` | `FeatureAssemblyService` | All 33 feature values, snapshot count |
| `OnDevicePredictor` | `OnDevicePredictor` | ONNX model load, raw/calibrated probabilities |
| `LocationTrackingService` | `LocationTrackingService` | Hourly background predictions + location updates |
