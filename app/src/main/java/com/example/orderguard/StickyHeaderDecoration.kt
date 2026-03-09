package com.example.orderguard

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class StickyHeaderDecoration : RecyclerView.ItemDecoration() {

    private val paint = Paint().apply {
        textSize = 40f
        isFakeBoldText = true
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {

        val child = parent.getChildAt(0) ?: return
        val position = parent.getChildAdapterPosition(child)

        val adapter = parent.adapter as? AppPickerAdapter ?: return
        val letter = adapter.getSectionLetter(position)

        c.drawText(letter.toString(), 20f, 60f, paint)
    }
}