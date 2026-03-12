package com.example.orderguard

import android.view.accessibility.AccessibilityNodeInfo

interface AppDriver {

    fun findValues(
        root: AccessibilityNodeInfo,
        data: MutableMap<String, Double>,
        textData: MutableMap<String, String>
    )

    fun executeDecline(
        service: OrderMonitorService,
        root: AccessibilityNodeInfo
    )
}