package com.example.orderguard

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

class GrubhubDriver : AppDriver {

    private val TAG = "OrderGuard"

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

        val joined = textList.joinToString(" ")

        // --- Address detection for Grubhub ---

        var pickupAddress: String? = null
        val dropoffs = mutableListOf<String>()

        for (i in textList.indices) {

            val text = textList[i]

            if (text.contains("Pickup by", ignoreCase = true)) {

                if (i > 0) {
                    pickupAddress = textList[i - 1]
                }

            }

            if (text.contains("Dropoff by", ignoreCase = true)) {

                if (i > 0) {
                    dropoffs.add(textList[i - 1])
                }

            }
        }

        pickupAddress?.let {
            textData["pickupAddress"] = it
            Log.d(TAG, "Grubhub pickup detected: $it")
        }

        if (dropoffs.isNotEmpty()) {
            textData["dropoffA"] = dropoffs[0]
            Log.d(TAG, "Grubhub dropoff A: ${dropoffs[0]}")
        }

        if (dropoffs.size > 1) {
            textData["dropoffB"] = dropoffs[1]
            Log.d(TAG, "Grubhub dropoff B: ${dropoffs[1]}")
        }

        val priceRegex = Regex("""\$(\d+(?:\.\d+)?)""")
        val mileRegex = Regex("""(\d+(?:\.\d+)?)\s*mi""")

        val priceMatch = priceRegex.find(joined)
        val mileMatch = mileRegex.find(joined)

        if (priceMatch != null) {
            data["price"] = priceMatch.groupValues[1].toDoubleOrNull() ?: 0.0
        }

        if (mileMatch != null) {
            data["miles"] = mileMatch.groupValues[1].toDoubleOrNull() ?: 0.0
        }
    }

    /*
    Decline sequence
    */
    override fun executeDecline(
        service: OrderMonitorService,
        root: AccessibilityNodeInfo
    ) {

        scrollOffer(root)

        Handler(Looper.getMainLooper()).postDelayed({

            val rejectButton = findReject(root)

            if (rejectButton != null) {

                Log.d(TAG, "Grubhub Reject button found")

                if (rejectButton.isClickable) {
                    rejectButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    rejectButton.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }

                service.scheduleReturnToPreviousApp(500)

                Log.d(TAG, "Grubhub Reject clicked")

            } else {
                Log.d(TAG, "Reject button not found")
            }

        }, 120)
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