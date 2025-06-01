package com.example.skripsi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var boundingBox: RectF? = null
    private var label: String? = null

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val textBackgroundPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        alpha = 160
    }

    fun setBoxAndLabel(box: RectF, text: String) {
        boundingBox = box
        label = text
        postInvalidate()
    }

    fun clearBox() {
        boundingBox = null
        label = null
        postInvalidate()
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        boundingBox?.let { box ->
            canvas.drawRect(box, boxPaint)

            label?.let { text ->
                val textBounds = Rect()
                textPaint.getTextBounds(text, 0, text.length, textBounds)

                val textX = box.left + 10
                val textY = box.top - textBounds.height() - 10
                val adjustedTextY = if (textY < textBounds.height() + 10) box.top + textBounds.height() + 10 else textY
                val adjustedTextX = if (textX < 0) 10f else textX

                val padding = 8
                canvas.drawRect(
                    adjustedTextX - padding,
                    adjustedTextY - textBounds.height() - padding,
                    adjustedTextX + textBounds.width() + padding,
                    adjustedTextY + padding,
                    textBackgroundPaint
                )

                canvas.drawText(text, adjustedTextX, adjustedTextY, textPaint)
            }
        }
    }
}
