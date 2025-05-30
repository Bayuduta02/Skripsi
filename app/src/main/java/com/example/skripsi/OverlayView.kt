package com.example.skripsi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boundingBoxes = mutableListOf<RectF>()
    private val labels = mutableListOf<String>()

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4.0f
        isAntiAlias = true
    }

    private val labelBackgroundPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val labelTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36.0f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    fun setResults(boxes: List<RectF>, labelList: List<String>) {
        boundingBoxes.clear()
        labels.clear()
        boundingBoxes.addAll(boxes)
        labels.addAll(labelList)
        Log.d(TAG, "Setting results: ${boxes.size} boxes, ${labelList.size} labels")
        invalidate()
    }

    fun clearResults() {
        boundingBoxes.clear()
        labels.clear()
        Log.d(TAG, "Clearing results")
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d(TAG, "Size changed to: ${w}x${h}")
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        Log.d(TAG, "onDraw called, boxes count: ${boundingBoxes.size}")

        boundingBoxes.forEachIndexed { index, box ->
            if (box.left >= 0 && box.top >= 0 &&
                box.right <= width && box.bottom <= height &&
                box.width() > 0 && box.height() > 0) {

                Log.d(TAG, "Drawing box $index: $box, view size: ${width}x${height}")

                canvas.drawRect(box, boxPaint)

                if (index < labels.size) {
                    val label = labels[index]

                    val textBounds = Rect()
                    labelTextPaint.getTextBounds(label, 0, label.length, textBounds)

                    val textWidth = textBounds.width()
                    val textHeight = textBounds.height()

                    val labelLeft = box.left
                    val labelTop = maxOf(box.top - textHeight - 16, textHeight + 8f)
                    val labelRight = minOf(labelLeft + textWidth + 16, width.toFloat())
                    val labelBottom = labelTop + textHeight + 16

                    val backgroundRect = RectF(labelLeft, labelTop - 8, labelRight, labelBottom)
                    canvas.drawRect(backgroundRect, labelBackgroundPaint)

                    canvas.drawText(
                        label,
                        labelLeft + 8,
                        labelTop + textHeight,
                        labelTextPaint
                    )
                }

                Log.d(TAG, "Successfully drew box $index")
            } else {
                Log.w(TAG, "Invalid box $index: $box, view size: ${width}x${height}")
            }
        }
    }

    companion object {
        private const val TAG = "OverlayView"
    }
}
