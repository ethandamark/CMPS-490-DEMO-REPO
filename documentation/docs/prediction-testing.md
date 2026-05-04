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

## 2. Clear Weather Staged Prediction
```
adb shell am start -n com.CMPS490.weathertracker/.ClearSimulationActivity
```

## 3. Light Rain Staged Prediction
```
adb shell am start -n com.CMPS490.weathertracker/.LightSimulationActivity
```

## 4. Moderate Rain Staged Prediction
```
adb shell am start -n com.CMPS490.weathertracker/.ModerateSimulationActivity
```

## 5. Hurricane Staged Prediction (Storm Simulation)

Injects 24 hours of pre-computed Hurricane Katrina-like weather data into Room DB and runs the predictor. This is guaranteed to trigger a storm alert notification (predicted dBZ well above the 32.9 dBZ / tier 2 threshold).

```bash
adb shell am start -n com.CMPS490.weathertracker/.StormSimulationActivity
```

Uses coordinates: New Orleans, LA (29.95, -90.07). Simulated conditions include:
- Pressure dropping from 990 → 920 hPa
- Winds escalating to 185 km/h
- Continuous heavy rainfall (2–42 mm/h)
- 98–99% humidity, extreme CAPE (4200 J/kg)

---

## 6. Logcat — Prediction View

View prediction results only (compact output):

```bash
adb logcat -s DebugPredict:D StormSimulation:D ClearSimulation:D LightSimulation:D ModerateSimulation:D
```

This filters to just the simulation and prediction activity logs showing predicted dBZ, tier, probability, and threshold.

---

## 7. Logcat — Verbose Prediction View

View prediction results **plus all 33 feature values** used by the model:

```bash
adb logcat -s DebugPredict:D StormSimulation:D ClearSimulation:D LightSimulation:D ModerateSimulation:D FeatureAssemblyService:D OnDevicePredictor:I
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

# Clear / Light / Moderate simulations
adb logcat -c; adb shell am start -n com.CMPS490.weathertracker/.ClearSimulationActivity; Start-Sleep -Seconds 8; adb logcat -d -s ClearSimulation:D FeatureAssemblyService:D OnDevicePredictor:I
adb logcat -c; adb shell am start -n com.CMPS490.weathertracker/.LightSimulationActivity; Start-Sleep -Seconds 8; adb logcat -d -s LightSimulation:D FeatureAssemblyService:D OnDevicePredictor:I
adb logcat -c; adb shell am start -n com.CMPS490.weathertracker/.ModerateSimulationActivity; Start-Sleep -Seconds 8; adb logcat -d -s ModerateSimulation:D FeatureAssemblyService:D OnDevicePredictor:I
```

---

## Log Tags Reference

| Tag | Source | Content |
|-----|--------|---------|
| `DebugPredict` | `DebugPredictActivity` | Live weather fetch + prediction result |
| `StormSimulation` | `StormSimulationActivity` | Katrina data injection + prediction result |
| `ClearSimulation` | `ClearSimulationActivity` | Clear-sky data injection + prediction result |
| `LightSimulation` | `LightSimulationActivity` | Light-rain data injection + prediction result |
| `ModerateSimulation` | `ModerateSimulationActivity` | MCS data injection + prediction result |
| `FeatureAssemblyService` | `FeatureAssemblyService` | All 33 feature values, snapshot count |
| `OnDevicePredictor` | `OnDevicePredictor` | ONNX model load, raw/calibrated probabilities |
| `LocationTrackingService` | `LocationTrackingService` | Hourly background predictions + location updates |
