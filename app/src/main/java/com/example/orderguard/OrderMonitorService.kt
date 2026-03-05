// ATTENTION AI: Before modifying this file, you MUST adhere to 
// the Mandatory Logic rules defined in /AI_PROMPT_RULES.md

package com.example.orderguard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.*
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

class OrderMonitorService : AccessibilityService() {

    private var lastActionTime: Long = 0
    private var pendingPrice: Double = 0.0
    private var pendingMiles: Double = 0.0
    private var uberDeclineRect: Rect? = null
    
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private lateinit var sheetsManager: SheetsManager

    private val deliveryApps = setOf(
        "com.doordash.driverapp",
        "com.ubercab.driver",
        "com.grubhub.driver"
    )

    private fun getAppName(packageName: String): String {
        return when (packageName) {
            "com.doordash.driverapp" -> "DoorDash"
            "com.ubercab.driver" -> "Uber"
            "com.grubhub.driver" -> "GrubHub"
            else -> "Unknown"
        }
    }

    private fun logOrder(packageName: String, price: Double, miles: Double, action: String) {
        if (price <= 0.0) return
        val historyPrefs = getSharedPreferences("OrderHistory", MODE_PRIVATE)
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "$time | $${String.format(Locale.US, "%.2f", price)} | ${miles}mi | ${action.uppercase()}"
        val key = "${dateKey}_$packageName"

        val existingLogs = historyPrefs.getStringSet(key, LinkedHashSet()) ?: LinkedHashSet()
        val newLogs = LinkedHashSet<String>(existingLogs)

        if (action.uppercase() == "DECLINED") {
            val priceStr = "$${String.format(Locale.US, "%.2f", price)}"
            val milesStr = "${miles}mi"
            newLogs.removeIf { it.contains(priceStr) && it.contains(milesStr) && it.contains("DETECTED") }
        }

        newLogs.add(entry)
        val trimmedLogs = if (newLogs.size > 50) newLogs.toList().takeLast(50).toSet() else newLogs
        historyPrefs.edit().putStringSet(key, trimmedLogs).apply()

        // Sync to Google Sheets
        serviceScope.launch {
            sheetsManager.logOrderToSheet(
                date = dateKey,
                time = time,
                appName = getAppName(packageName),
                price = price,
                miles = miles,
                status = action.uppercase()
            )
        }
    }

    private fun showVisualClick(x: Float, y: Float) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dot = View(this).apply {
            setBackgroundColor(Color.RED)
            elevation = 15f
        }
        val params = WindowManager.LayoutParams(
            45, 45,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.toInt() - 22
            this.y = y.toInt() - 22
        }
        try {
            wm.addView(dot, params)
            Handler(Looper.getMainLooper()).postDelayed({
                try { wm.removeView(dot) } catch (_: Exception) {}
            }, 1500)
        } catch (e: Exception) { Log.e("OrderGuard", "Visual dot error: ${e.message}") }
    }

    private fun showVisualClick(node: AccessibilityNodeInfo) {
        val r = Rect()
        node.getBoundsInScreen(r)
        showVisualClick(r.centerX().toFloat(), r.centerY().toFloat())
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        sheetsManager = SheetsManager(this)
        val info = AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 50
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

        this.serviceInfo = info
        Log.d("OrderGuardDebug", "Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: ""

        // MANDATORY: Check monitoring state
        val filterPrefs = getSharedPreferences("OrderGuardPrefs", MODE_PRIVATE)
        if (!filterPrefs.getBoolean("IS_MONITORING", false)) return

        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            return // This logic is now handled by OrderNotificationListener.
        }

        if (!deliveryApps.contains(packageName)) return
        val rootNode = rootInActiveWindow ?: return

        val data = mutableMapOf<String, Double>()
        if (packageName == "com.ubercab.driver") {
            uberDeclineRect = null
            findValuesUber(rootNode, data)
        } else {
            findValuesGeneral(rootNode, data)
        }

        val price = data["price"] ?: 0.0
        val miles = data["miles"] ?: 0.0

        if (price > 0.0 && miles > 0.0) {
            if (price != pendingPrice || miles != pendingMiles) {
                pendingPrice = price
                pendingMiles = miles

                updateFinancials(packageName, price, miles, false)
                logOrder(packageName, price, miles, "Detected")

                val minRatio = filterPrefs.getFloat("MIN_RATIO", 2.0f).toDouble()
                val minPay = filterPrefs.getFloat("MIN_PAY", 5.0f).toDouble()

                if ((price / miles) < minRatio || price < minPay) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastActionTime > 3000) {
                        lastActionTime = currentTime
                        updateStats(packageName, "declined")
                        logOrder(packageName, price, miles, "Declined")
                        executeDeclineSequence(rootNode, packageName)
                    }
                }
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val nodeText = event.text.joinToString(" ").lowercase()
            if (nodeText.contains("accept") || nodeText.contains("match") || nodeText.contains("confirm")) {
                updateStats(packageName, "accepted")
                logOrder(packageName, pendingPrice, pendingMiles, "Accepted")
                updateFinancials(packageName, pendingPrice, pendingMiles, true)
                pendingPrice = 0.0
                pendingMiles = 0.0
            }
        }
    }

    private fun findValuesUber(node: AccessibilityNodeInfo?, data: MutableMap<String, Double>) {
        if (node == null) return
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val combined = "$text $desc"

        if (combined.contains("$")) {
            val m = Pattern.compile("\\\$(\\d+\\\\.?\\d*)").matcher(combined)
            if (m.find()) {
                val p = m.group(1)?.toDoubleOrNull() ?: 0.0
                if (p > (data["price"] ?: 0.0)) data["price"] = p
            }
        }

        val milesMatcher = Pattern.compile("\\((\\d+\\\\.?\\d*)\\s*mi\\)").matcher(combined)
        if (milesMatcher.find()) {
            data["miles"] = milesMatcher.group(1)?.toDoubleOrNull() ?: 0.0
        }

        if (node.className == "android.widget.Button" && node.text == null) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() in 70..220 && rect.left > 500) {
                uberDeclineRect = rect
            }
        }

        for (i in 0 until node.childCount) findValuesUber(node.getChild(i), data)
    }

    private fun findValuesGeneral(node: AccessibilityNodeInfo?, data: MutableMap<String, Double>) {
        if (node == null) return
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val combined = "$text $desc"

        if (combined.isNotEmpty()) {
            val priceMatcher = Pattern.compile("\\\$(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{2})?)").matcher(combined)
            if (priceMatcher.find()) data["price"] = priceMatcher.group(1)?.replace(",", "")?.toDoubleOrNull() ?: 0.0

            val milesMatcher = Pattern.compile("(\\d+\\\\.?\\d*)\\s*(mi|miles)", Pattern.CASE_INSENSITIVE).matcher(combined)
            if (milesMatcher.find()) data["miles"] = milesMatcher.group(1)?.toDoubleOrNull() ?: 0.0
        }
        for (i in 0 until node.childCount) findValuesGeneral(node.getChild(i), data)
    }

    private fun executeDeclineSequence(rootNode: AccessibilityNodeInfo, packageName: String) {
        if (packageName == "com.ubercab.driver") {
            Handler(Looper.getMainLooper()).postDelayed({
                uberDeclineRect?.let {
                    showVisualClick(it.centerX().toFloat(), it.centerY().toFloat())
                    clickAt(it.centerX().toFloat(), it.centerY().toFloat())
                }
            }, 300)
        } else if (packageName == "com.doordash.driverapp") {
            Log.d("OrderGuard", "Attempting DoorDash decline in 1000ms...")
            Handler(Looper.getMainLooper()).postDelayed({
                val currentRoot = rootInActiveWindow ?: rootNode
                attemptDecline(currentRoot, 0)
            }, 1000)
        }
    }

    private fun attemptDecline(root: AccessibilityNodeInfo, retryCount: Int) {
        // Step 1: Target the initial Decline button from Screenshot 1
        val ddIds = listOf(
            "com.doordash.driverapp:id/decline_button", 
            "com.doordash.driverapp:id/view_decline"
        )
        
        var clicked = false
        for (id in ddIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                Log.d("OrderGuard", "Found Decline button by ID: $id")
                if (tryClick(nodes[0])) {
                    clicked = true
                    break
                }
            }
        }

        if (!clicked) {
            val declineTerms = listOf("Decline", "Reject", "No thanks")
            for (term in declineTerms) {
                if (findAndClickByText(root, term)) {
                    Log.d("OrderGuard", "Clicked Decline via Text: $term")
                    clicked = true
                    break
                }
            }
        }

        if (clicked) {
            handleDoordashMultiStepDecline()
        } else if (retryCount < 3) {
            Log.d("OrderGuard", "Decline button not found, retrying... ($retryCount)")
            Handler(Looper.getMainLooper()).postDelayed({
                val newRoot = rootInActiveWindow ?: root
                attemptDecline(newRoot, retryCount + 1)
            }, 500)
        } else {
            Log.d("OrderGuard", "Failed to find DoorDash decline button after retries.")
        }
    }

    private fun handleDoordashMultiStepDecline() {
        Handler(Looper.getMainLooper()).postDelayed({
            val postRoot = rootInActiveWindow ?: return@postDelayed

            // Step 2: Target the red "Decline offer" button from Screenshot 2
            val redButtonIds = listOf(
                "com.doordash.driverapp:id/view_decline_button"
            )
            
            var redButtonClicked = false
            for (id in redButtonIds) {
                val nodes = postRoot.findAccessibilityNodeInfosByViewId(id)
                if (nodes.isNotEmpty()) {
                    Log.d("OrderGuard", "Found red button by ID: $id. Clicking...")
                    if (tryClick(nodes[0])) {
                        redButtonClicked = true
                        break
                    }
                }
            }

            if (!redButtonClicked) {
                val possibleTexts = listOf("Decline offer", "Decline Offer")
                for (text in possibleTexts) {
                    val nodes = postRoot.findAccessibilityNodeInfosByText(text)
                    if (nodes.isNotEmpty()) {
                        Log.d("OrderGuard", "Found red button with text '$text'. Clicking...")
                        if (tryClick(nodes[0])) {
                            redButtonClicked = true
                            break
                        }
                    }
                }
            }

            if (redButtonClicked) {
                Log.d("OrderGuard", "Red button clicked successfully.")
            } else {
                Log.d("OrderGuard", "Red button not found via ID or text. Fallback to reasons...")
                clickReasonAndSubmit(postRoot)
            }
        }, 1200)
    }

    private fun clickReasonAndSubmit(root: AccessibilityNodeInfo) {
        val reasons = listOf("Order is too small", "Distance is too far", "Something else", "too small", "Distance")
        var reasonClicked = false
        for (reason in reasons) {
            val nodes = root.findAccessibilityNodeInfosByText(reason)
            if (nodes.isNotEmpty()) {
                if (tryClick(nodes[0])) {
                    Log.d("OrderGuard", "Clicked decline reason: $reason")
                    reasonClicked = true
                    break
                }
            }
        }

        if (reasonClicked) {
            Handler(Looper.getMainLooper()).postDelayed({
                val submitRoot = rootInActiveWindow ?: return@postDelayed
                if (findAndClickByText(submitRoot, "Submit")) {
                    Log.d("OrderGuard", "Clicked final Submit button.")
                }
            }, 600)
        }
    }

    private fun clickAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 1, 50)).build(), null, null)
    }

    private fun tryClick(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable) {
                Log.d("OrderGuard", "Clicking node: ${current.text ?: current.contentDescription ?: "NO_TEXT"}")
                showVisualClick(current)
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun findAndClickByText(rootNode: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (tryClick(node)) {
                return true
            }
        }
        return false
    }

    private fun updateStats(packageName: String, type: String) {
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val statsPrefs = getSharedPreferences("OrderStats", MODE_PRIVATE)
        val key = "${dateKey}_${packageName}_$type"
        statsPrefs.edit { putInt(key, statsPrefs.getInt(key, 0) + 1) }
    }

    private fun updateFinancials(packageName: String, price: Double, miles: Double, onlyAccepted: Boolean) {
        if (price <= 0.0) return
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val statsPrefs = getSharedPreferences("OrderStats", MODE_PRIVATE)
        val suffix = if (onlyAccepted) "acc" else "all"
        statsPrefs.edit {
            val tp = "${dateKey}_total_pay_$suffix"
            val tm = "${dateKey}_total_miles_$suffix"
            val appPay = "${dateKey}_${packageName}_pay_$suffix"
            val appMiles = "${dateKey}_${packageName}_miles_$suffix"
            putFloat(tp, statsPrefs.getFloat(tp, 0f) + price.toFloat())
            putFloat(tm, statsPrefs.getFloat(tm, 0f) + miles.toFloat())
            putFloat(appPay, statsPrefs.getFloat(appPay, 0f) + price.toFloat())
            putFloat(appMiles, statsPrefs.getFloat(appMiles, 0f) + miles.toFloat())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onInterrupt() {}
}