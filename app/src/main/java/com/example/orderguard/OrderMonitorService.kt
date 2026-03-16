// ATTENTION AI: Before modifying this file, you MUST adhere to 
// the Mandatory Logic rules defined in /AI_PROMPT_RULES.md

package com.example.orderguard


import android.app.ActivityOptions
import android.app.PendingIntent
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

    private val resetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            resetOrderDetectionState()
        }
    }
    private fun refreshAllowedApps() {
        val prefs = getSharedPreferences("OrderGuardPrefs", MODE_PRIVATE)
        allowedReturnApps =
            prefs.getStringSet("ALLOWED_RETURN_APPS", emptySet()) ?: emptySet()
    }
    private val deliveryApps = setOf(
        "com.doordash.driverapp",
        "com.ubercab.driver",
        "com.grubhub.driver"
    )
    private var lastActionTime: Long = 0
    private var pendingPrice: Double = 0.0
    private var pendingMiles: Double = 0.0

    private var lastNonDeliveryApp: String? = null
    private var lastForegroundApp: String? = null

    private var allowedReturnApps: Set<String> = emptySet()

    private val uberDriver = UberDriver()
    private val doorDashDriver = DoorDashDriver()

    private val grubhubDriver = GrubhubDriver()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private lateinit var sheetsManager: SheetsManager

       private fun getAppName(packageName: String): String {
        return when (packageName) {
            "com.doordash.driverapp" -> "DoorDash"
            "com.ubercab.driver" -> "Uber"
            "com.grubhub.driver" -> "GrubHub"
            else -> "Unknown"
        }
    }

    private fun logOrder(
        packageName: String,
        price: Double,
        miles: Double,
        action: String,
        textData: Map<String, String> = emptyMap()
    ) {
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
        if (::sheetsManager.isInitialized) {
            serviceScope.launch {
                sheetsManager.logOrderToSheet(
                    date = dateKey,
                    time = time,
                    appName = getAppName(packageName),
                    price = price,
                    miles = miles,
                    status = action.uppercase(),
                    businessName = textData["businessName"] ?: "",
                    pickupAddress = textData["pickupAddress"] ?: "",
                    dropoffAddress = textData["dropoffAddress"] ?: "",
                    estTime = textData["estTime"] ?: "",
                    actualTime = textData["actualTime"] ?: "",
                    actualMiles = textData["actualMiles"] ?: ""
                )
            }
        }

    }

    // Used by Grubhub after it has scrolled and re-read the screen so that
    // the Declined entry in Sheets can include business/address details.
    fun logGrubhubDeclineWithDetails(textData: Map<String, String>) {
        if (pendingPrice <= 0.0 || pendingMiles <= 0.0) return
        logOrder(
            packageName = "com.grubhub.driver",
            price = pendingPrice,
            miles = pendingMiles,
            action = "Declined",
            textData = textData
        )
    }
    private fun triggerFakeFlash() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val flashView = View(this).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0.8f // Slightly transparent for a cleaner look
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )

        try {
            wm.addView(flashView, params)
            // Remove it after 50ms (the speed of a camera shutter)
            Handler(Looper.getMainLooper()).postDelayed({
                try { wm.removeView(flashView) } catch (_: Exception) {}
            }, 50)
        } catch (e: Exception) { Log.e("OrderGuard", "Flash error: ${e.message}") }
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
        info.eventTypes =
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 50
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

        this.serviceInfo = info
        val prefs = getSharedPreferences("OrderGuardPrefs", MODE_PRIVATE)

        allowedReturnApps =
            prefs.getStringSet("ALLOWED_RETURN_APPS", emptySet()) ?: emptySet()
        Log.d("OrderGuardDebug", "Accessibility Service Connected")

        registerReceiver(resetReceiver, IntentFilter("ORDERGUARD_RESET_STATE"))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {

        val rootNode = rootInActiveWindow ?: return

        val prefs = getSharedPreferences("OrderGuardPrefs", MODE_PRIVATE)

        if (!prefs.getBoolean("IS_MONITORING", false)) {
            return
        }
        val packageName = event.packageName?.toString() ?: ""

        // Track last foreground app
        // Track last foreground app
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {

            // 1. Ignore system UI and delivery apps
            if (packageName == "com.android.systemui" ||
                deliveryApps.contains(packageName) ||
                packageName == "com.example.orderguard") return

            // 2. ONLY save the app if it is in your "Allowed" list
            // This ensures LAST_FOREGROUND_APP always points to a valid return target
            if (allowedReturnApps.contains(packageName)) {
                lastForegroundApp = packageName
                val prefs = getSharedPreferences("OrderGuardPrefs", MODE_PRIVATE)
                prefs.edit().putString("LAST_FOREGROUND_APP", packageName).apply()
                Log.d("OrderGuard", "Valid return app saved: $packageName")
            }
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return
        }

        // MANDATORY: Check monitoring state
        val filterPrefs = getSharedPreferences("OrderGuardPrefs", MODE_PRIVATE)
        if (!filterPrefs.getBoolean("IS_MONITORING", false)) return

        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            return // This logic is now handled by OrderNotificationListener.
        }

        if (allowedReturnApps.contains(packageName) &&
            packageName != lastNonDeliveryApp) {

            lastNonDeliveryApp = packageName
            Log.d("OrderGuard", "Return app set to: $lastNonDeliveryApp")
        }

                // DoorDash reacts immediately when the offer view appears
        if (packageName == "com.doordash.driverapp" &&
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {

            val rootNodeForDD = rootInActiveWindow ?: return

            val data = mutableMapOf<String, Double>()
            val textData = mutableMapOf<String, String>()
            doorDashDriver.findValues(rootNodeForDD, data, textData)

            val price = data["price"] ?: 0.0
            val miles = data["miles"] ?: 0.0

            if (price > 0.0 && miles > 0.0) {
                if (price != pendingPrice || miles != pendingMiles) {
                    pendingPrice = price
                    pendingMiles = miles

                    val filterPrefs = getSharedPreferences("OrderGuardPrefs", MODE_PRIVATE)
                    val minRatio = filterPrefs.getFloat("MIN_RATIO", 2.0f).toDouble()
                    val minPay = filterPrefs.getFloat("MIN_PAY", 5.0f).toDouble()

                    if ((price / miles) < minRatio || price < minPay) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastActionTime > 3000) {
                            lastActionTime = currentTime
                            updateStats(packageName, "declined")

                            // --- FIX IS HERE ---
                            // Only log immediately for Uber and DoorDash.
                            // Grubhub is skipped here because it logs itself after the scroll.
                            if (packageName != "com.grubhub.driver") {
                                logOrder(packageName, price, miles, "Declined", textData)
                            }

                            executeDeclineSequence(rootNode, packageName)
                        }
                    }
                }
            }
            return
        }



        val data = mutableMapOf<String, Double>()
        val textData = mutableMapOf<String, String>()
        when (packageName) {

            "com.doordash.driverapp" ->
                doorDashDriver.findValues(rootNode, data, textData)

            "com.ubercab.driver" ->
                uberDriver.findValues(rootNode, data, textData)

            "com.grubhub.driver" ->
                grubhubDriver.findValues(rootNode, data, textData)

        }

        val price = data["price"] ?: 0.0
        val miles = data["miles"] ?: 0.0

        if (price > 0.0 && miles > 0.0) {
            if (price != pendingPrice || miles != pendingMiles) {
                pendingPrice = price
                pendingMiles = miles

                updateFinancials(packageName, price, miles, false)

                // FIX 1: Don't log "Detected" for Grubhub (Driver will log everything later)
                if (packageName != "com.grubhub.driver") {
                    logOrder(packageName, price, miles, "Detected", textData)
                }

                val minRatio = filterPrefs.getFloat("MIN_RATIO", 2.0f).toDouble()
                val minPay = filterPrefs.getFloat("MIN_PAY", 5.0f).toDouble()

                if ((price / miles) < minRatio || price < minPay) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastActionTime > 3000) {
                        lastActionTime = currentTime
                        updateStats(packageName, "declined")

                        // FIX 2: Don't log "Declined" here for Grubhub
                        if (packageName != "com.grubhub.driver") {
                            logOrder(packageName, price, miles, "Declined", textData)
                        }

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

    private fun executeDeclineSequence(
        rootNode: AccessibilityNodeInfo,
        packageName: String
    ) {
        // We no longer handle timers or scheduleReturn here.
        // Each driver handles its own workflow and return trigger.
        when (packageName) {
            "com.ubercab.driver" -> uberDriver.executeDecline(this, rootNode)
            "com.doordash.driverapp" -> doorDashDriver.executeDecline(this, rootNode)
            "com.grubhub.driver" -> grubhubDriver.executeDecline(this, rootNode)
        }
    }



    fun clickAt(x: Float, y: Float) {

        val path = Path()
        path.moveTo(x, y)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        dispatchGesture(gesture, null, null)

        showVisualClick(x, y)
    }

    // REPLACE the existing swipeScreenDown with this:
    fun swipeScreenDown(onComplete: () -> Unit) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        // Swipe from 80% height to 20% height (flicking UP to scroll DOWN)
        val startX = width / 2f
        val startY = height * 0.8f
        val endY = height * 0.2f

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                // Wait 300ms for the UI animation to stop moving
                Handler(Looper.getMainLooper()).postDelayed({
                    onComplete()
                }, 300)
            }
        }, null)
    }



   fun tryClick(node: AccessibilityNodeInfo?): Boolean {
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

    fun findAndClickByText(rootNode: AccessibilityNodeInfo, text: String): Boolean {
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
        try {
            unregisterReceiver(resetReceiver)
        } catch (_: Exception) {}

    }

    override fun onInterrupt() {}

    fun resetOrderDetectionState() {
        pendingPrice = 0.0
        pendingMiles = 0.0
        lastActionTime = 0
        Log.d("OrderGuard", "Order detection state reset after resume")
    }

    private fun switchBackToPreviousApp() {

        val root = rootInActiveWindow ?: return
        val currentPackage = root.packageName?.toString() ?: return

        if (!deliveryApps.contains(currentPackage)) return

        // Open recents
        performGlobalAction(GLOBAL_ACTION_RECENTS)

        Handler(Looper.getMainLooper()).postDelayed({

            val recentsRoot = rootInActiveWindow ?: return@postDelayed

            fun findApp(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {

                if (node == null) return null

                val pkg = node.packageName?.toString()

                if (pkg != null && !deliveryApps.contains(pkg)) {
                    if (node.isClickable) return node
                }

                for (i in 0 until node.childCount) {
                    val result = findApp(node.getChild(i))
                    if (result != null) return result
                }

                return null
            }

            val target = findApp(recentsRoot)

            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        }, 400)
    }

    fun scheduleReturnToPreviousApp(delay: Long) {
        Handler(Looper.getMainLooper()).postDelayed({
            returnToPreviousAppSmart()
        }, delay)
    }

    private fun punchHoleInThrottle() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = View(this)
        val params = WindowManager.LayoutParams(
            1, 1, // 1x1 pixel (invisible)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            wm.addView(view, params)
            // Removing it immediately causes a focus change event in the OS
            // which often clears the background activity block.
            Handler(Looper.getMainLooper()).postDelayed({
                try { wm.removeView(view) } catch (e: Exception) {}
            }, 5)
        } catch (e: Exception) {}
    }

    private fun returnToPreviousAppSmart() {
        val prefs = getSharedPreferences("OrderGuardPrefs", MODE_PRIVATE)
        val targetPackage = prefs.getString("LAST_FOREGROUND_APP", null) ?: return

        try {
            // Use the focus punch instead of a screenshot to bypass the 3s delay
            punchHoleInThrottle()

            val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )

                val options = ActivityOptions.makeBasic()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    options.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }

                // Fire immediately after the punch
                startActivity(launchIntent, options.toBundle())
                Log.d("OrderGuard", "High-speed return to $targetPackage executed.")
            }
        } catch (e: Exception) {
            Log.e("OrderGuard", "Instant switch failed: ${e.message}")
        }
    }
}