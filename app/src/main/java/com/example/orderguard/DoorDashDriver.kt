package com.example.orderguard

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

class DoorDashDriver : AppDriver {

    private var captureBusinessNext = false
    override fun findValues(
        root: AccessibilityNodeInfo,
        data: MutableMap<String, Double>,
        textData: MutableMap<String, String>
    ){

        captureBusinessNext = false

        scan(root, data, textData)

    }

    private fun scan(
        node: AccessibilityNodeInfo?,
        data: MutableMap<String, Double>,
        textData: MutableMap<String, String>
    ) {

        if (node == null) return

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        val combined = "$text $desc"

        if (combined.isNotEmpty()) {

            val priceMatcher =
                Pattern.compile("\\$(\\d+(?:\\.\\d+)?)").matcher(combined)

            if (priceMatcher.find()) {

                data["price"] =
                    priceMatcher.group(1)?.toDoubleOrNull() ?: 0.0
            }

            val milesMatcher =
                Pattern.compile("(\\d+\\.?\\d*)\\s*(mi|miles)").matcher(combined)

            if (milesMatcher.find()) {

                data["miles"] =
                    milesMatcher.group(1)?.toDoubleOrNull() ?: 0.0
            }
        }

        if (text.equals("Pickup", ignoreCase = true)) {
            captureBusinessNext = true
        }

        if (captureBusinessNext &&
            node.className == "android.widget.TextView" &&
            text.isNotBlank() &&
            !text.equals("Pickup", true)
        ) {

            textData["businessName"] = text

            Log.d("OrderGuard", "DoorDash business detected: $text")

            captureBusinessNext = false
        }

        for (i in 0 until node.childCount) {
            scan(node.getChild(i), data, textData)
        }
    }

    override fun executeDecline(
        service: OrderMonitorService,
        root: AccessibilityNodeInfo
    ) {

        Log.d("OrderGuard", "DoorDash decline step 1")

        val declineNodes =
            root.findAccessibilityNodeInfosByText("Decline")

        for (node in declineNodes) {

            var clickable: AccessibilityNodeInfo? = node

            while (clickable != null && !clickable.isClickable) {
                clickable = clickable.parent
            }

            if (clickable != null) {

                if (service.tryClick(clickable)) {

                    Handler(Looper.getMainLooper()).postDelayed({

                        val newRoot =
                            service.rootInActiveWindow ?: return@postDelayed

                        val confirmNodes =
                            newRoot.findAccessibilityNodeInfosByText("Decline offer")

                        for (confirm in confirmNodes) {

                            var c: AccessibilityNodeInfo? = confirm

                            while (c != null && !c.isClickable) {
                                c = c.parent
                            }

                            if (c != null) {

                                if (service.tryClick(c)) {

                                    Log.d("OrderGuard", "DoorDash confirmed decline")

                                    service.scheduleReturnToPreviousApp(500)

                                    return@postDelayed
                                }
                            }
                        }

                    }, 250)

                    return
                }
            }
        }

        Log.d("OrderGuard", "DoorDash decline button not found")
    }
}