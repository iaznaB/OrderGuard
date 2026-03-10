// ATTENTION AI: Before modifying this file, you MUST adhere to
// the Mandatory Logic rules defined in /AI_PROMPT_RULES.md

package com.example.orderguard

import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class OrderNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {

        val prefs = getSharedPreferences("OrderGuardPrefs", MODE_PRIVATE)
        if (!prefs.getBoolean("IS_MONITORING", false)) return

        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getString("android.title")?.lowercase() ?: ""
        val text = extras.getCharSequence("android.text")?.toString()?.lowercase() ?: ""
        val combined = "$title $text"

        // 1. Check for DoorDash specifically
        if (packageName == "com.doordash.driverapp") {
            // IGNORE if it's a persistent/ongoing notification (like "Looking for orders")
            if (sbn.isOngoing) return

            // IGNORE known background noise keywords
            if (combined.contains("looking for") || combined.contains("on a dash") || combined.contains("location")) {
                return
            }

            // TRIGGER if it contains any order-related keywords
            val isNewOrder = combined.contains("new order") ||
                    combined.contains("delivery") ||
                    combined.contains("accept") ||
                    combined.contains("offer") ||
                    combined.contains("opportunity") ||
                    combined.contains("dash")

            if (isNewOrder) {
                // Programmatically "click" the notification to open the app and dismiss the pop-up
                triggerClick(sbn)
            }
        }
        // 2. Uber and GrubHub logic
        else if (packageName == "com.ubercab.driver" ||
            packageName == "com.grubhub.driver" ||
            combined.contains("order") ||
            combined.contains("request")) {
            // This logic might need to be different for Uber/Grubhub, but for now we'll click
            triggerClick(sbn)
        }
    }

    private fun triggerClick(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        Log.d("OrderGuard", "New order notification detected from: $packageName")
        try {
            // First, send the intent to bring the app to the foreground.
            sbn.notification.contentIntent?.send()
            Log.d("OrderGuard", "Sent contentIntent for $packageName.")

            // Then, explicitly cancel the notification to dismiss the pop-up.
            cancelNotification(sbn.key)
            Log.d("OrderGuard", "Canceled notification for $packageName to dismiss pop-up.")

        } catch (e: PendingIntent.CanceledException) {
            Log.e("OrderGuard", "Failed to send content intent for $packageName, falling back to broadcast.", e)
            // As a fallback, use the old method to bring the app to the foreground.
            val intent = Intent("com.example.orderguard.SIMULATE_NOTIFICATION")
            intent.putExtra("package", packageName)
            sendBroadcast(intent)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}
}