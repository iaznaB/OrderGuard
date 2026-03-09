package com.example.orderguard

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

object GrubhubDriver {

    private const val TAG = "OrderGuard"

    /*
    Extract price + miles
    */
    fun findValues(root: AccessibilityNodeInfo, data: MutableMap<String, Double>) {

        val textList = mutableListOf<String>()
        collectText(root, textList)

        val joined = textList.joinToString(" ")

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
    fun executeDecline(service: AccessibilityService, root: AccessibilityNodeInfo) {

        Handler(Looper.getMainLooper()).postDelayed({

            val rejectButton = findReject(root)

            if (rejectButton != null) {

                Log.d(TAG, "Grubhub Reject button found")

                if (rejectButton.isClickable) {
                    rejectButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    rejectButton.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }

                (service as OrderMonitorService).scheduleReturnToPreviousApp(500)

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