package com.example.skripsi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var boxes: List<RectF> = emptyList()
    private var labels: List<String> = emptyList()

    // Paint for bounding box
    private val boxPaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = CornerPathEffect(8f)
        isAntiAlias = true
    }

    // Paint for label background
    private val labelBgPaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Paint for label text
    private val labelTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        isAntiAlias = true
    }

    fun setResults(boxes: List<RectF>, labels: List<String>) {
        this.boxes = boxes
        this.labels = labels
        invalidate()
    }

    fun clearResults() {
        this.boxes = emptyList()
        this.labels = emptyList()
        invalidate()
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        boxes.forEachIndexed { i, box ->
            // Draw bounding box
            canvas.drawRect(box, boxPaint)

            // Draw label background
            val label = labels.getOrNull(i) ?: return@forEachIndexed
            val textWidth = labelTextPaint.measureText(label)
            val textHeight = labelTextPaint.fontMetrics.bottom - labelTextPaint.fontMetrics.top
            val padding = 8f

            val left = box.left
            val top = box.top - textHeight - padding * 2

            if (top > 0) {
                val rect = RectF(left, top, left + textWidth + padding * 2, top + textHeight + padding * 2)
                canvas.drawRoundRect(rect, 12f, 12f, labelBgPaint)
                canvas.drawText(label, left + padding, top + textHeight + padding / 2, labelTextPaint)
            }
        }
    }
}
