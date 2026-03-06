package com.example.orderguard

import android.view.accessibility.AccessibilityNodeInfo

interface AppDriver {

    fun findValues(root: AccessibilityNodeInfo, data: MutableMap<String, Double>)

    fun executeDecline(
        service: OrderMonitorService,
        root: AccessibilityNodeInfo
    )
}