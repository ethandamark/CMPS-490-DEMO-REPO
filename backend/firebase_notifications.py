"""
Firebase Cloud Messaging (FCM) notification service.
Handles sending push notifications to registered devices.
"""

import firebase_admin
from firebase_admin import credentials, messaging
from typing import Optional, Dict, List
import os
import json
import logging
from dotenv import load_dotenv

# Load environment variables
load_dotenv()

logger = logging.getLogger(__name__)
FCM_SERVER_KEY = os.getenv("FCM_SERVER_KEY")


class FirebaseNotificationService:
    """Service for sending Firebase Cloud Messaging notifications."""
    
    _instance = None
    _initialized = False
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super(FirebaseNotificationService, cls).__new__(cls)
        return cls._instance
    
    def __init__(self):
        """Initialize Firebase Admin SDK if not already initialized."""
        if not self._initialized:
            try:
                # Try to load from environment variable first
                firebase_json = os.getenv("FIREBASE_CONFIG")
                
                if firebase_json:
                    # Parse JSON from environment variable
                    config_dict = json.loads(firebase_json)
                    cred = credentials.Certificate(config_dict)
                else:
                    # Try to load from file
                    cred_path = os.getenv("FIREBASE_CREDENTIALS_PATH", "firebase-key.json")
                    if os.path.exists(cred_path):
                        cred = credentials.Certificate(cred_path)
                    else:
                        if FCM_SERVER_KEY:
                            logger.warning("Firebase credentials file not found. Using FCM Server Key for notifications.")
                            self._initialized = True
                            return
                        else:
                            logger.warning("Firebase credentials not found and no FCM Server Key provided. Notifications disabled.")
                            self._initialized = True
                            return
                
                # Initialize Firebase app
                firebase_admin.initialize_app(cred)
                self._initialized = True
                logger.info("Firebase Admin SDK initialized successfully")
                if FCM_SERVER_KEY:
                    logger.info("✓ FCM Server Key loaded and available for notifications")
            except Exception as e:
                logger.error(f"Failed to initialize Firebase: {e}")
                if FCM_SERVER_KEY:
                    logger.warning("FCM Server Key is available as fallback for sending notifications")
                self._initialized = True
    
    @staticmethod
    def send_notification(
        device_token: str,
        title: str,
        body: str,
        data: Optional[Dict[str, str]] = None,
        notification_type: str = "alert"
    ) -> bool:
        """
        Send a notification to a specific device.
        
        Args:
            device_token: FCM device token
            title: Notification title
            body: Notification body/message
            data: Optional custom data payload
            notification_type: Type of notification (alert, weather, etc.)
        
        Returns:
            True if sent successfully, False otherwise
        """
        try:
            message = messaging.Message(
                notification=messaging.Notification(
                    title=title,
                    body=body,
                ),
                data=data or {},
                token=device_token,
            )
            
            response = messaging.send(message)
            logger.info(f"Notification sent successfully: {response}")
            return True
        except Exception as e:
            logger.error(f"Failed to send notification: {e}")
            return False
    
    @staticmethod
    def send_multicast_notification(
        device_tokens: List[str],
        title: str,
        body: str,
        data: Optional[Dict[str, str]] = None
    ) -> Dict[str, any]:
        """
        Send a notification to multiple devices.
        
        Args:
            device_tokens: List of FCM device tokens
            title: Notification title
            body: Notification body/message
            data: Optional custom data payload
        
        Returns:
            Dictionary with success and failure counts
        """
        try:
            message = messaging.MulticastMessage(
                notification=messaging.Notification(
                    title=title,
                    body=body,
                ),
                data=data or {},
                tokens=device_tokens,
            )
            
            response = messaging.send_multicast(message)
            logger.info(f"Multicast notification sent. Success: {response.success_count}, Failures: {response.failure_count}")
            return {
                "success": response.success_count,
                "failure": response.failure_count,
                "total": len(device_tokens)
            }
        except Exception as e:
            logger.error(f"Failed to send multicast notification: {e}")
            return {
                "success": 0,
                "failure": len(device_tokens),
                "total": len(device_tokens),
                "error": str(e)
            }
    
    @staticmethod
    def send_weather_alert(
        device_token: str,
        location: str,
        alert_type: str,
        description: str
    ) -> bool:
        """
        Send a weather alert notification.
        
        Args:
            device_token: FCM device token
            location: Location of weather alert
            alert_type: Type of alert (tornado, flood, etc.)
            description: Alert description
        
        Returns:
            True if sent successfully
        """
        title = f"⚠️ {alert_type.title()} Alert"
        body = f"{location}: {description}"
        data = {
            "type": "weather_alert",
            "alert_type": alert_type,
            "location": location
        }
        
        return FirebaseNotificationService.send_notification(
            device_token, title, body, data, "weather"
        )


# Singleton instance
firebase_service = FirebaseNotificationService()
