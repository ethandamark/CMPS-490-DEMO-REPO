"""
Test script: Create an alert in Lafayette, LA and verify device_alert population + FCM delivery.

Usage:
    1. Make sure Supabase is running locally (supabase start)
    2. Start the backend: python app.py
    3. Run this script: python test_device_alert_flow.py
"""

import httpx
import sys
from datetime import datetime, timezone, timedelta

BACKEND_URL = "http://localhost:5000"


def main():
    print("=" * 60)
    print("  DEVICE ALERT FLOW TEST — Lafayette, LA")
    print("=" * 60)

    # Lafayette coordinates
    lat = 30.2241
    lon = -92.0198
    expires_at = (datetime.now(timezone.utc) + timedelta(hours=6)).isoformat()

    with httpx.Client(timeout=30) as client:
        # ---- Step 1: Check existing devices ----
        print("\n[1] Checking registered devices...")
        r = client.get(f"{BACKEND_URL}/health")
        if r.status_code != 200:
            print(f"    ERROR: Backend not reachable at {BACKEND_URL}")
            sys.exit(1)
        print("    Backend is healthy.")

        # ---- Step 2: Create an alert ----
        print(f"\n[2] Creating alert at Lafayette ({lat}, {lon})...")
        alert_payload = {
            "latitude": lat,
            "longitude": lon,
            "alert_type": "storm",
            "severity_level": 3,
            "expires_at": expires_at,
        }
        r = client.post(f"{BACKEND_URL}/alerts/create", json=alert_payload)
        print(f"    Status: {r.status_code}")
        result = r.json()
        print(f"    Response: {result}")

        if not result.get("success"):
            print("    ERROR: Alert creation failed!")
            sys.exit(1)

        alert_id = result["alert_id"]
        devices_targeted = result.get("devices_targeted", 0)
        print(f"    Alert ID: {alert_id}")
        print(f"    Devices targeted: {devices_targeted}")

        # ---- Step 3: Check device_alert rows for this alert ----
        print(f"\n[3] Checking device_alert delivery status for alert {alert_id[:8]}...")
        r = client.get(f"{BACKEND_URL}/device-alerts/by-alert/{alert_id}")
        print(f"    Status: {r.status_code}")
        delivery = r.json()

        if "summary" in delivery:
            summary = delivery["summary"]
            print(f"    Total:   {summary['total']}")
            print(f"    Sent:    {summary['sent']}")
            print(f"    Failed:  {summary['failed']}")
            print(f"    Pending: {summary['pending']}")

        if delivery.get("device_alerts"):
            print("\n    Device Alert Rows:")
            for row in delivery["device_alerts"]:
                print(f"      - {row['device_alert_id'][:8]}... | "
                      f"device={row['device_id'][:8]}... | "
                      f"status={row['delivery_status']} | "
                      f"sent_at={row.get('sent_at', 'N/A')}")
        else:
            print("    No device_alert rows found.")

        # ---- Step 4: Verify alert exists ----
        print(f"\n[4] Verifying alert {alert_id[:8]}... exists in alert_event...")
        r = client.get(f"{BACKEND_URL}/alerts/{alert_id}")
        if r.status_code == 200:
            alert = r.json().get("alert", {})
            print(f"    Type: {alert.get('alert_type')}")
            print(f"    Severity: {alert.get('severity_level')}")
            print(f"    Location: ({alert.get('latitude')}, {alert.get('longitude')})")
            print(f"    Expires: {alert.get('expires_at')}")
        else:
            print(f"    ERROR: Could not fetch alert: {r.text}")

    print("\n" + "=" * 60)
    print("  TEST COMPLETE")
    print("=" * 60)


if __name__ == "__main__":
    main()
"""
Test script: Create an alert in Lafayette, LA and verify device_alert population + FCM delivery.

Usage:
    1. Make sure Supabase is running locally (supabase start)
    2. Start the backend: python app.py
    3. Run this script: python test_device_alert_flow.py
"""

import httpx
import sys
from datetime import datetime, timezone, timedelta

BACKEND_URL = "http://localhost:5000"


def main():
    print("=" * 60)
    print("  DEVICE ALERT FLOW TEST — Lafayette, LA")
    print("=" * 60)

    # Lafayette coordinates
    lat = 30.2241
    lon = -92.0198
    expires_at = (datetime.now(timezone.utc) + timedelta(hours=6)).isoformat()

    with httpx.Client(timeout=30) as client:
        # ---- Step 1: Check existing devices ----
        print("\n[1] Checking registered devices...")
        r = client.get(f"{BACKEND_URL}/health")
        if r.status_code != 200:
            print(f"    ERROR: Backend not reachable at {BACKEND_URL}")
            sys.exit(1)
        print("    Backend is healthy.")

        # ---- Step 2: Create an alert ----
        print(f"\n[2] Creating alert at Lafayette ({lat}, {lon})...")
        alert_payload = {
            "latitude": lat,
            "longitude": lon,
            "alert_type": "storm",
            "severity_level": 3,
            "expires_at": expires_at,
        }
        r = client.post(f"{BACKEND_URL}/alerts/create", json=alert_payload)
        print(f"    Status: {r.status_code}")
        result = r.json()
        print(f"    Response: {result}")

        if not result.get("success"):
            print("    ERROR: Alert creation failed!")
            sys.exit(1)

        alert_id = result["alert_id"]
        devices_targeted = result.get("devices_targeted", 0)
        print(f"    Alert ID: {alert_id}")
        print(f"    Devices targeted: {devices_targeted}")

        # ---- Step 3: Check device_alert rows for this alert ----
        print(f"\n[3] Checking device_alert delivery status for alert {alert_id[:8]}...")
        r = client.get(f"{BACKEND_URL}/device-alerts/by-alert/{alert_id}")
        print(f"    Status: {r.status_code}")
        delivery = r.json()

        if "summary" in delivery:
            summary = delivery["summary"]
            print(f"    Total:   {summary['total']}")
            print(f"    Sent:    {summary['sent']}")
            print(f"    Failed:  {summary['failed']}")
            print(f"    Pending: {summary['pending']}")

        if delivery.get("device_alerts"):
            print("\n    Device Alert Rows:")
            for row in delivery["device_alerts"]:
                print(f"      - {row['device_alert_id'][:8]}... | "
                      f"device={row['device_id'][:8]}... | "
                      f"status={row['delivery_status']} | "
                      f"sent_at={row.get('sent_at', 'N/A')}")
        else:
            print("    No device_alert rows found.")

        # ---- Step 4: Verify alert exists ----
        print(f"\n[4] Verifying alert {alert_id[:8]}... exists in alert_event...")
        r = client.get(f"{BACKEND_URL}/alerts/{alert_id}")
        if r.status_code == 200:
            alert = r.json().get("alert", {})
            print(f"    Type: {alert.get('alert_type')}")
            print(f"    Severity: {alert.get('severity_level')}")
            print(f"    Location: ({alert.get('latitude')}, {alert.get('longitude')})")
            print(f"    Expires: {alert.get('expires_at')}")
        else:
            print(f"    ERROR: Could not fetch alert: {r.text}")

    print("\n" + "=" * 60)
    print("  TEST COMPLETE")
    print("=" * 60)


if __name__ == "__main__":
    main()
