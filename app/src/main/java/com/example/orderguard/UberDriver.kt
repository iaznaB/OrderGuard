package com.example.orderguard

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

class UberDriver : AppDriver {

    private var nodesAfterMiles = 0

    private var uberDeclineRect: Rect? = null

    private var foundTimeMilesRow = false
    private var captureBusinessNext = false
    private var captureAddressNext = false

    override fun findValues(
        root: AccessibilityNodeInfo,
        data: MutableMap<String, Double>,
        textData: MutableMap<String, String>
    ) {

        foundTimeMilesRow = false
        captureBusinessNext = false
        captureAddressNext = false
        nodesAfterMiles = 0
        captureBusinessNext = false
        captureAddressNext = false
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

        val timeMatcher =
            Pattern.compile("(\\d+)\\s*min").matcher(combined)
        if (timeMatcher.find()) {

            textData["estTime"] = timeMatcher.group(1) ?: ""
        }

        if (combined.contains("$")) {

            val m = Pattern.compile("\\$(\\d+\\.?\\d*)").matcher(combined)

            if (m.find()) {

                val p = m.group(1)?.toDoubleOrNull() ?: 0.0

                if (p > (data["price"] ?: 0.0)) {
                    data["price"] = p
                }
            }
        }

        val milesMatcher =
            Pattern.compile("\\((\\d+\\.?\\d*)\\s*mi\\)").matcher(combined)

        if (milesMatcher.find()) {

            data["miles"] =
                milesMatcher.group(1)?.toDoubleOrNull() ?: 0.0

            foundTimeMilesRow = true
            nodesAfterMiles = 0

            return
        }

        // capture business and address AFTER time+miles row
        if (node.className == "android.widget.TextView" && text.isNotEmpty()) {

            if (foundTimeMilesRow && node.className == "android.widget.TextView" && text.isNotEmpty()) {

                nodesAfterMiles++

                if (nodesAfterMiles == 1) {

                    textData["businessName"] = text
                    Log.d("OrderGuard", "Uber business: $text")

                }

                else if (nodesAfterMiles == 2) {

                    textData["dropoffAddress"] = text
                    Log.d("OrderGuard", "Uber dropoff: $text")

                    foundTimeMilesRow = false
                }
            }
        }

        if (node.className == "android.widget.Button" && node.text == null) {

            val rect = Rect()
            node.getBoundsInScreen(rect)

            val width = rect.width()
            val height = rect.height()

            if (width in 100..150 && height in 100..150) {

                Log.d("OrderGuard", "Uber X detected: $rect")

                uberDeclineRect = rect
            }
        }



        for (i in 0 until node.childCount) {
            scan(node.getChild(i), data, textData)
        }

    }

    override fun executeDecline(
        service: OrderMonitorService,
        root: AccessibilityNodeInfo
    ) {

        val target = uberDeclineRect ?: return

        val x = target.centerX().toFloat()
        val y = target.centerY().toFloat()

        Log.d("OrderGuard", "Clicking Uber X at $x , $y")

        service.clickAt(x, y)

        uberDeclineRect = null
    }
}