package com.example.orderguard

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

class UberDriver : AppDriver {

    private var uberDeclineRect: Rect? = null

    override fun findValues(
        root: AccessibilityNodeInfo,
        data: MutableMap<String, Double>
    ) {
        scan(root, data)
    }

    private fun scan(
        node: AccessibilityNodeInfo?,
        data: MutableMap<String, Double>
    ) {

        if (node == null) return

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val combined = "$text $desc"

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
            scan(node.getChild(i), data)
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