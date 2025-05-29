package com.example.skripsi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var boxes: List<RectF> = emptyList()
    private var labels: List<String> = emptyList()

    private val boxPaint = Paint().apply {
        color = ContextCompat.getColor(context, android.R.color.holo_green_light)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = CornerPathEffect(8f)
        isAntiAlias = true
    }

    private val textPaintStroke = Paint().apply {
        color = Color.BLACK
        textSize = 42f
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
    }

    private val textPaintFill = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.FILL
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
    }

    private val textBgPaint = Paint().apply {
        color = Color.argb(200, 0, 150, 0)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val shadowPaint = Paint().apply {
        color = Color.argb(60, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = CornerPathEffect(8f)
        isAntiAlias = true
    }

    fun setResults(boxes: List<RectF>, labels: List<String>) {
        Log.d("OverlayView", "Setting results: ${boxes.size} boxes, ${labels.size} labels")
        boxes.forEachIndexed { index, box ->
            Log.d("OverlayView", "Box $index: $box")
        }

        this.boxes = boxes
        this.labels = labels
        invalidate()
    }

    fun clearResults() {
        Log.d("OverlayView", "Clearing results")
        boxes = emptyList()
        labels = emptyList()
        invalidate()
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        Log.d("OverlayView", "onDraw called, boxes count: ${boxes.size}")

        if (boxes.isEmpty()) return

        boxes.forEachIndexed { index, box ->
            Log.d("OverlayView", "Drawing box $index: $box, view size: ${width}x${height}")

            // Validasi dan clamp box coordinates untuk memastikan dalam bounds
            val clampedBox = RectF(
                box.left.coerceAtLeast(0f),
                box.top.coerceAtLeast(0f),
                box.right.coerceAtMost(width.toFloat()),
                box.bottom.coerceAtMost(height.toFloat())
            )

            // Pastikan box memiliki ukuran yang valid
            if (clampedBox.width() > 0 && clampedBox.height() > 0) {
                // Draw shadow untuk efek depth
                val shadowBox = RectF(
                    clampedBox.left + 2f,
                    clampedBox.top + 2f,
                    clampedBox.right + 2f,
                    clampedBox.bottom + 2f
                )
                canvas.drawRect(shadowBox, shadowPaint)

                // Draw main bounding box
                canvas.drawRect(clampedBox, boxPaint)

                // Draw label jika ada
                val label = labels.getOrNull(index)
                if (!label.isNullOrEmpty()) {
                    drawLabel(canvas, clampedBox, label)
                }

                Log.d("OverlayView", "Successfully drew box $index")
            } else {
                Log.w("OverlayView", "Invalid box dimensions: ${clampedBox.width()}x${clampedBox.height()}")
            }
        }
    }

    private fun drawLabel(canvas: Canvas, box: RectF, label: String) {
        val textBounds = Rect()
        textPaintFill.getTextBounds(label, 0, label.length, textBounds)

        val textWidth = textBounds.width().toFloat()
        val textHeight = textBounds.height().toFloat()

        val padding = 12f
        val cornerRadius = 8f

        // Hitung posisi background label
        val bgLeft = box.left
        val bgTop = (box.top - textHeight - padding * 2).coerceAtLeast(0f)
        var bgRight = box.left + textWidth + padding * 2
        val bgBottom = box.top.coerceAtLeast(textHeight + padding * 2)

        // Pastikan label tidak keluar dari layar
        if (bgRight > width) {
            bgRight = width.toFloat()
        }

        val bgRect = RectF(bgLeft, bgTop, bgRight, bgBottom)

        // Draw background label dengan rounded corners
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, textBgPaint)

        // Hitung posisi text
        val textX = bgLeft + padding
        val textY = bgBottom - padding

        // Draw text dengan stroke untuk visibility yang lebih baik
        canvas.drawText(label, textX, textY, textPaintStroke)
        canvas.drawText(label, textX, textY, textPaintFill)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clearResults()
    }

    // Override untuk debugging
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d("OverlayView", "Size changed to: ${w}x${h}")
    }
}