"""
Device Location API Test Script
Tests all CRUD operations for the device_location table.

Usage:
    1. Start the backend server: python -m uvicorn app:app --host 0.0.0.0 --port 5000
    2. Make sure Supabase is running: supabase start
    3. Run tests: python test_device_location.py

Prerequisites:
    - Backend server running on http://localhost:5000
    - Supabase running and configured
    - A valid device_id in the database (or the script will create one)
"""

import httpx
import uuid
import json
from datetime import datetime, timezone

# Configuration
BACKEND_URL = "http://localhost:5000"
TEST_DEVICE_ID = None  # Will be populated by registering a new device
TEST_LOCATION_ID = None  # Will store the created location ID

def print_header(title):
    """Print a formatted header."""
    print(f"\n{'='*70}")
    print(f"  {title}")
    print(f"{'='*70}")

def print_success(msg):
    """Print success message."""
    print(f"  ✓ {msg}")

def print_error(msg):
    """Print error message."""
    print(f"  ✗ {msg}")

def print_info(msg):
    """Print info message."""
    print(f"  → {msg}")

def test_health_check():
    """Test 1: Health check endpoint."""
    print_header("TEST 1: Health Check")
    try:
        response = httpx.get(f"{BACKEND_URL}/health", timeout=10)
        print_info(f"Status: {response.status_code}")
        print_info(f"Response: {response.json()}")
        if response.status_code == 200:
            print_success("Backend is healthy!")
            return True
        else:
            print_error(f"Health check failed: {response.text}")
            return False
    except Exception as e:
        print_error(f"Exception: {str(e)}")
        return False

def test_register_device():
    """Test 2: Register a new device to get a valid device_id."""
    global TEST_DEVICE_ID
    print_header("TEST 2: Register New Device (to get device_id)")
    try:
        payload = {
            "locationPermissionStatus": True,
            "deviceToken": f"test_token_{uuid.uuid4().hex[:8]}"
        }
        print_info(f"Request payload: {json.dumps(payload, indent=2)}")
        
        response = httpx.post(
            f"{BACKEND_URL}/supabase/register",
            json=payload,
            timeout=30
        )
        print_info(f"Status: {response.status_code}")
        print_info(f"Response: {json.dumps(response.json(), indent=2)}")
        
        if response.status_code == 200 and response.json().get("success"):
            TEST_DEVICE_ID = response.json().get("deviceId")
            print_success(f"Device registered! device_id: {TEST_DEVICE_ID}")
            return True
        else:
            print_error(f"Registration failed: {response.text}")
            return False
    except Exception as e:
        print_error(f"Exception: {str(e)}")
        return False

def test_create_device_location():
    """Test 3: Create a new device location."""
    global TEST_LOCATION_ID
    print_header("TEST 3: Create Device Location")
    
    if not TEST_DEVICE_ID:
        print_error("No device_id available - skipping test")
        return False
    
    try:
        # Lafayette, Louisiana coordinates
        payload = {
            "device_id": TEST_DEVICE_ID,
            "latitude": 30.2241,
            "longitude": -92.0198
        }
        print_info(f"Request payload: {json.dumps(payload, indent=2)}")
        
        response = httpx.post(
            f"{BACKEND_URL}/device-location/create",
            json=payload,
            timeout=30
        )
        print_info(f"Status: {response.status_code}")
        print_info(f"Response: {json.dumps(response.json(), indent=2)}")
        
        if response.status_code == 200 and response.json().get("success"):
            TEST_LOCATION_ID = response.json().get("location_id")
            print_success(f"Location created! location_id: {TEST_LOCATION_ID}")
            print_success(f"  latitude:    {response.json().get('latitude')}")
            print_success(f"  longitude:   {response.json().get('longitude')}")
            print_success(f"  captured_at: {response.json().get('captured_at')}")
            return True
        else:
            print_error(f"Create failed: {response.text}")
            return False
    except Exception as e:
        print_error(f"Exception: {str(e)}")
        return False

def test_create_second_location():
    """Test 4: Create a second location for the same device."""
    print_header("TEST 4: Create Second Device Location")
    
    if not TEST_DEVICE_ID:
        print_error("No device_id available - skipping test")
        return False
    
    try:
        # Baton Rouge, Louisiana coordinates
        payload = {
            "device_id": TEST_DEVICE_ID,
            "latitude": 30.4515,
            "longitude": -91.1871
        }
        print_info(f"Request payload: {json.dumps(payload, indent=2)}")
        
        response = httpx.post(
            f"{BACKEND_URL}/device-location/create",
            json=payload,
            timeout=30
        )
        print_info(f"Status: {response.status_code}")
        print_info(f"Response: {json.dumps(response.json(), indent=2)}")
        
        if response.status_code == 200 and response.json().get("success"):
            print_success(f"Second location created! location_id: {response.json().get('location_id')}")
            return True
        else:
            print_error(f"Create failed: {response.text}")
            return False
    except Exception as e:
        print_error(f"Exception: {str(e)}")
        return False

def test_get_location_by_id():
    """Test 5: Get a specific location by ID."""
    print_header("TEST 5: Get Device Location by ID")
    
    if not TEST_LOCATION_ID:
        print_error("No location_id available - skipping test")
        return False
    
    try:
        print_info(f"Fetching location_id: {TEST_LOCATION_ID}")
        
        response = httpx.get(
            f"{BACKEND_URL}/device-location/{TEST_LOCATION_ID}",
            timeout=30
        )
        print_info(f"Status: {response.status_code}")
        print_info(f"Response: {json.dumps(response.json(), indent=2)}")
        
        if response.status_code == 200 and response.json().get("success"):
            loc = response.json().get("location", {})
            print_success(f"Location retrieved!")
            print_success(f"  location_id: {loc.get('location_id')}")
            print_success(f"  device_id:   {loc.get('device_id')}")
            print_success(f"  latitude:    {loc.get('latitude')}")
            print_success(f"  longitude:   {loc.get('longitude')}")
            print_success(f"  captured_at: {loc.get('captured_at')}")
            return True
        else:
            print_error(f"Get by ID failed: {response.text}")
            return False
    except Exception as e:
        print_error(f"Exception: {str(e)}")
        return False

def test_get_locations_by_device():
    """Test 6: Get all locations for a device."""
    print_header("TEST 6: Get All Locations by Device")
    
    if not TEST_DEVICE_ID:
        print_error("No device_id available - skipping test")
        return False
    
    try:
        print_info(f"Fetching locations for device_id: {TEST_DEVICE_ID}")
        
        response = httpx.get(
            f"{BACKEND_URL}/device-location/by-device/{TEST_DEVICE_ID}",
            timeout=30
        )
        print_info(f"Status: {response.status_code}")
        print_info(f"Response: {json.dumps(response.json(), indent=2)}")
        
        if response.status_code == 200 and response.json().get("success"):
            locations = response.json().get("locations", [])
            count = response.json().get("count", 0)
            print_success(f"Found {count} location(s) for device!")
            for i, loc in enumerate(locations):
                print_success(f"  [{i+1}] lat={loc.get('latitude')}, lon={loc.get('longitude')}")
            return True
        else:
            print_error(f"Get by device failed: {response.text}")
            return False
    except Exception as e:
        print_error(f"Exception: {str(e)}")
        return False

def test_get_latest_location():
    """Test 7: Get the latest location for a device."""
    print_header("TEST 7: Get Latest Device Location")
    
    if not TEST_DEVICE_ID:
        print_error("No device_id available - skipping test")
        return False
    
    try:
        print_info(f"Fetching latest location for device_id: {TEST_DEVICE_ID}")
        
        response = httpx.get(
            f"{BACKEND_URL}/device-location/latest/{TEST_DEVICE_ID}",
            timeout=30
        )
        print_info(f"Status: {response.status_code}")
        print_info(f"Response: {json.dumps(response.json(), indent=2)}")
        
        if response.status_code == 200 and response.json().get("success"):
            loc = response.json().get("location", {})
            print_success(f"Latest location retrieved!")
            print_success(f"  latitude:    {loc.get('latitude')}")
            print_success(f"  longitude:   {loc.get('longitude')}")
            print_success(f"  captured_at: {loc.get('captured_at')}")
            return True
        else:
            print_error(f"Get latest failed: {response.text}")
            return False
    except Exception as e:
        print_error(f"Exception: {str(e)}")
        return False

def test_update_location():
    """Test 8: Update a device location."""
    print_header("TEST 8: Update Device Location")
    
    if not TEST_LOCATION_ID:
        print_error("No location_id available - skipping test")
        return False
    
    try:
        # Update to New Orleans coordinates
        payload = {
            "latitude": 29.9511,
            "longitude": -90.0715
        }
        print_info(f"Updating location_id: {TEST_LOCATION_ID}")
        print_info(f"Request payload: {json.dumps(payload, indent=2)}")
        
        response = httpx.patch(
            f"{BACKEND_URL}/device-location/{TEST_LOCATION_ID}",
            json=payload,
            timeout=30
        )
        print_info(f"Status: {response.status_code}")
        print_info(f"Response: {json.dumps(response.json(), indent=2)}")
        
        if response.status_code == 200 and response.json().get("success"):
            loc = response.json().get("location", {})
            print_success(f"Location updated!")
            print_success(f"  New latitude:  {loc.get('latitude')}")
            print_success(f"  New longitude: {loc.get('longitude')}")
            return True
        else:
            print_error(f"Update failed: {response.text}")
            return False
    except Exception as e:
        print_error(f"Exception: {str(e)}")
        return False

def test_delete_location():
    """Test 9: Delete a device location."""
    print_header("TEST 9: Delete Device Location")
    
    if not TEST_LOCATION_ID:
        print_error("No location_id available - skipping test")
        return False
    
    try:
        print_info(f"Deleting location_id: {TEST_LOCATION_ID}")
        
        response = httpx.delete(
            f"{BACKEND_URL}/device-location/{TEST_LOCATION_ID}",
            timeout=30
        )
        print_info(f"Status: {response.status_code}")
        print_info(f"Response: {json.dumps(response.json(), indent=2)}")
        
        if response.status_code == 200 and response.json().get("success"):
            print_success(f"Location deleted!")
            
            # Verify deletion by trying to GET the location
            print_info("Verifying deletion...")
            verify_response = httpx.get(
                f"{BACKEND_URL}/device-location/{TEST_LOCATION_ID}",
                timeout=30
            )
            if verify_response.status_code == 404:
                print_success("Verification: Location no longer exists (404)")
            else:
                print_info(f"Verification response: {verify_response.status_code}")
            
            return True
        else:
            print_error(f"Delete failed: {response.text}")
            return False
    except Exception as e:
        print_error(f"Exception: {str(e)}")
        return False

def run_all_tests():
    """Run all tests and print summary."""
    print("\n" + "="*70)
    print("  DEVICE LOCATION API TEST SUITE")
    print("  Backend URL: " + BACKEND_URL)
    print("="*70)
    
    results = {}
    
    # Run tests in order
    tests = [
        ("Health Check", test_health_check),
        ("Register Device", test_register_device),
        ("Create Device Location", test_create_device_location),
        ("Create Second Location", test_create_second_location),
        ("Get Location by ID", test_get_location_by_id),
        ("Get Locations by Device", test_get_locations_by_device),
        ("Get Latest Location", test_get_latest_location),
        ("Update Location", test_update_location),
        ("Delete Location", test_delete_location),
    ]
    
    for name, test_func in tests:
        try:
            results[name] = test_func()
        except Exception as e:
            print_error(f"Test '{name}' crashed: {str(e)}")
            results[name] = False
    
    # Print summary
    print_header("TEST SUMMARY")
    passed = 0
    failed = 0
    
    for name, result in results.items():
        if result:
            print_success(f"PASSED: {name}")
            passed += 1
        else:
            print_error(f"FAILED: {name}")
            failed += 1
    
    print(f"\n  Total: {passed + failed} tests")
    print(f"  Passed: {passed}")
    print(f"  Failed: {failed}")
    
    if failed == 0:
        print("\n  🎉 ALL TESTS PASSED! Device Location API is working correctly.")
    else:
        print(f"\n  ⚠️  {failed} test(s) failed. Check the output above for details.")
    
    return failed == 0

if __name__ == "__main__":
    import sys
    success = run_all_tests()
    sys.exit(0 if success else 1)
