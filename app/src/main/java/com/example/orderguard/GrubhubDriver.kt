package com.example.orderguard

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

class GrubhubDriver : AppDriver {

    private val TAG = "OrderGuard"
    private var isProcessing = false // Add this at the top
    /*
    Extract price + miles
    */
    override fun findValues(
        root: AccessibilityNodeInfo,
        data: MutableMap<String, Double>,
        textData: MutableMap<String, String>
    ) {
        val textList = mutableListOf<String>()
        collectText(root, textList)
        if (textList.isEmpty()) return

        // 1. Find the index of the Price node (the one containing '$')
        val priceIndex = textList.indexOfFirst { it.contains("$") }

        // 2. The Business Name is the text right ABOVE (before) the price
        if (priceIndex > 0) {
            val potentialName = textList[priceIndex - 1]

            // Basic check to ensure we aren't grabbing miles by mistake
            if (!potentialName.contains("mi", ignoreCase = true)) {
                textData["businessName"] = potentialName
                Log.d(TAG, "Grubhub: Business Name found above price: $potentialName")
            }
        }

        // 3. Address Detection (Pickup and Dropoffs)
        val dropoffs = mutableListOf<String>()
        for (i in textList.indices) {
            val text = textList[i]

            // Pickup address is usually the node before "Pickup by"
            if (text.contains("Pickup by", ignoreCase = true) && i > 0) {
                textData["pickupAddress"] = textList[i - 1]
            }

            // Dropoff address is usually the node before "Dropoff"
            if (text.contains("Dropoff", ignoreCase = true) && i > 0) {
                dropoffs.add(textList[i - 1])
            }
        }

        // Handle Stacked Orders (A and B)
        if (dropoffs.isNotEmpty()) {
            textData["dropoffA"] = dropoffs[0]
            if (dropoffs.size > 1) {
                textData["dropoffB"] = dropoffs[1]
                textData["dropoffAddress"] = "A: ${dropoffs[0]} | B: ${dropoffs[1]}"
            } else {
                textData["dropoffAddress"] = dropoffs[0]
            }
        }

        // 4. Price and Miles Extraction
        val joined = textList.joinToString(" ")
        Regex("""\$(\d+(?:\.\d+)?)""").find(joined)?.let {
            data["price"] = it.groupValues[1].toDoubleOrNull() ?: 0.0
        }
        Regex("""(\d+(?:\.\d+)?)\s*mi""").find(joined)?.let {
            data["miles"] = it.groupValues[1].toDoubleOrNull() ?: 0.0
        }
    }

    override fun executeDecline(
        service: OrderMonitorService,
        root: AccessibilityNodeInfo
    ) {
        if (isProcessing) return
        isProcessing = true

        Log.d("OrderGuard", "Grubhub: Scroll 1")

        service.swipeScreenDown {
            // Reduced delay to 150ms for a more fluid "double flick" feel
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d("OrderGuard", "Grubhub: Scroll 2")

                service.swipeScreenDown {
                    val finalRoot = service.rootInActiveWindow ?: run {
                        isProcessing = false
                        return@swipeScreenDown
                    }

                    val detailedText = mutableMapOf<String, String>()
                    val dataIgnore = mutableMapOf<String, Double>()
                    findValues(finalRoot, dataIgnore, detailedText)

                    service.logGrubhubDeclineWithDetails(detailedText)

                    val rejectBtn = findReject(finalRoot)
                    if (rejectBtn != null) {
                        if (service.tryClick(rejectBtn)) {
                            service.scheduleReturnToPreviousApp(600)
                        }
                    }

                    Handler(Looper.getMainLooper()).postDelayed({ isProcessing = false }, 3000)
                }
            }, 150)
        }
    }

    private fun finishGrubhubDecline(
        service: OrderMonitorService,
        root: AccessibilityNodeInfo,
        initialText: MutableMap<String, String>
    ) {
        val scrollData = mutableMapOf<String, Double>()
        val scrollText = mutableMapOf<String, String>()

        // Scan the scrolled screen for addresses
        findValues(root, scrollData, scrollText)

        // MERGE: Take addresses from scrollText and add them to initialText (which has the Business Name)
        if (scrollText.containsKey("pickupAddress")) initialText["pickupAddress"] = scrollText["pickupAddress"]!!
        if (scrollText.containsKey("dropoffAddress")) initialText["dropoffAddress"] = scrollText["dropoffAddress"]!!

        // ONE SINGLE LOG CALL - This is the only place Grubhub should ever hit the sheet
        service.logGrubhubDeclineWithDetails(initialText)

        // Click Reject
        val rejectButton = findReject(root)
        if (rejectButton != null) {
            service.tryClick(rejectButton)
            service.scheduleReturnToPreviousApp(500)
        }

        // Reset processing flag after a delay to allow the screen to clear
        Handler(Looper.getMainLooper()).postDelayed({ isProcessing = false }, 3000)
    }

    // Helper function to finish the job
    private fun processGrubhubFinal(
        service: OrderMonitorService,
        root: AccessibilityNodeInfo,
        existingData: MutableMap<String, String>
    ) {
        // Find addresses revealed by scroll
        val scrollData = mutableMapOf<String, Double>()
        val scrollText = mutableMapOf<String, String>()
        findValues(root, scrollData, scrollText)

        // Merge discovered data (Keep original name, get new addresses)
        existingData.putAll(scrollText)

        // Log to sheets
        service.logGrubhubDeclineWithDetails(existingData)

        val rejectButton = findReject(root)
        if (rejectButton != null) {
            val success = if (rejectButton.isClickable) {
                rejectButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                rejectButton.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            }
            if (success) service.scheduleReturnToPreviousApp(500)
        }

        Handler(Looper.getMainLooper()).postDelayed({
            isProcessing = false
        }, 3000)
    }

    /*
    Recursive text collector
    */
    private fun collectText(node: AccessibilityNodeInfo?, list: MutableList<String>) {

        if (node == null) return

        node.text?.toString()?.let { list.add(it) }
        node.contentDescription?.toString()?.let { list.add(it) }

        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), list)
        }
    }

    private fun scrollOffer(node: AccessibilityNodeInfo?): Boolean {

        if (node == null) return false

        if (node.isScrollable) {

            Log.d(TAG, "Scrolling Grubhub offer card")

            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

            return true
        }

        for (i in 0 until node.childCount) {
            if (scrollOffer(node.getChild(i))) return true
        }

        return false
    }

    /*
    Find Reject button
    */
    private fun findReject(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {

        if (node == null) return null

        val text = node.text?.toString()?.lowercase()
        val desc = node.contentDescription?.toString()?.lowercase()

        if (text == "reject" || desc == "reject") {
            return node
        }

        for (i in 0 until node.childCount) {
            val result = findReject(node.getChild(i))
            if (result != null) return result
        }

        return null
    }
}