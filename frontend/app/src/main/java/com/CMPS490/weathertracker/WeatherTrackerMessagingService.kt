package com.CMPS490.weathertracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.util.Log

class WeatherTrackerMessagingService : FirebaseMessagingService() {
    
    companion object {
        const val TAG = "FCM"
        const val NOTIFICATION_CHANNEL_ID = "weather_alerts"
        const val NOTIFICATION_CHANNEL_NAME = "Weather Alerts"
    }
    
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed token: $token")
        
        // Send this token to your backend to register the device
        sendTokenToBackend(token)
    }
    
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // Handle incoming messages
        Log.d(TAG, "From: ${remoteMessage.from}")
        
        // Create notification channel
        createNotificationChannel()
        
        // Check if message contains a notification payload
        remoteMessage.notification?.let {
            val title = it.title ?: "Weather Alert"
            val body = it.body ?: "New weather notification"
            val data = remoteMessage.data
            
            // Send notification
            sendNotification(title, body, data)
        }
        
        // Handle data payload
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(TAG, "Message data payload: ${remoteMessage.data}")
            handleDataMessage(remoteMessage.data)
        }
    }
    
    /**
     * Create notification channel for Android 8.0+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for weather alerts and updates"
                enableVibration(true)
                enableLights(true)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Send notification to device
     */
    private fun sendNotification(
        title: String,
        body: String,
        data: Map<String, String>
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // Add data to intent
            data.forEach { (key, value) ->
                putExtra(key, value)
            }
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        
        // Set vibration
        notificationBuilder.setVibrate(longArrayOf(0, 500, 250, 500))
        
        // Set color for notification
        notificationBuilder.setColor(0xFF1976D2.toInt())
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }
    
    /**
     * Handle data message payload
     */
    private fun handleDataMessage(data: Map<String, String>) {
        val messageType = data["type"] ?: "unknown"
        when (messageType) {
            "weather_alert" -> {
                Log.d(TAG, "Weather alert received: ${data["alert_type"]}")
            }
            "test" -> {
                Log.d(TAG, "Test notification received")
            }
            else -> {
                Log.d(TAG, "Unknown message type: $messageType")
            }
        }
    }
    
    /**
     * Send the FCM token to your backend
     */
    private fun sendTokenToBackend(token: String) {
        // This should call your backend endpoint to register the device token
        // Using BackendRepository.registerDeviceToken(token)
        Log.d(TAG, "Device token: $token")
        
        // TODO: Implement backend token registration
        // val repository = BackendRepository()
        // repository.registerDeviceToken(token) { success ->
        //     Log.d(TAG, "Token registration: $success")
        // }
    }
}
