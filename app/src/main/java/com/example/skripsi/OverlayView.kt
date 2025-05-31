package com.example.skripsi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var boundingBox: RectF? = null
    private var labelText: String? = null

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.GREEN
        textSize = 48f
        style = Paint.Style.FILL
        isAntiAlias = true
        isFakeBoldText = true
    }

    private val textBackgroundPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0) // Semi-transparent black
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setBoxAndLabel(box: RectF, label: String) {
        boundingBox = box
        labelText = label
        invalidate() // Trigger onDraw
    }

    fun clearBox() {
        boundingBox = null
        labelText = null
        invalidate()
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        boundingBox?.let { box ->
            // Draw bounding box
            canvas.drawRect(box, boxPaint)

            // Draw label with background
            labelText?.let { label ->
                val textBounds = android.graphics.Rect()
                textPaint.getTextBounds(label, 0, label.length, textBounds)

                val textWidth = textBounds.width()
                val textHeight = textBounds.height()

                // Calculate text position (above the box)
                val textX = box.left
                val textY = box.top - 20f

                // Draw text background
                val bgRect = RectF(
                    textX - 10f,
                    textY - textHeight - 10f,
                    textX + textWidth + 10f,
                    textY + 10f
                )
                canvas.drawRoundRect(bgRect, 8f, 8f, textBackgroundPaint)

                // Draw text
                canvas.drawText(label, textX, textY, textPaint)
            }
        }
    }
}