/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.unified

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.osfans.trime.R

class HandwritingCanvasView(context: Context) : View(context) {
    var state: StrokeState = StrokeState()
        set(value) {
            field = value
            invalidate()
        }
    var onInkChanged: (() -> Unit)? = null
    var onStrokeFinished: (() -> Unit)? = null

    private var completingTouch = false
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = resources.displayMetrics.density * 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55777777
        strokeWidth = resources.displayMetrics.density
    }

    init {
        setBackgroundColor(0xFF202124.toInt())
        contentDescription = context.getString(R.string.handwriting_canvas_description)
        isFocusable = true
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), guidePaint)
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, guidePaint)
        state.strokes.forEach { drawStroke(canvas, it) }
        drawStroke(canvas, state.activeStroke)
    }

    private fun drawStroke(canvas: Canvas, stroke: HandwritingStroke) {
        if (stroke.isEmpty()) return
        fun x(point: StrokePoint) = point.x * width
        fun y(point: StrokePoint) = point.y * height
        if (stroke.size == 1) {
            canvas.drawPoint(x(stroke[0]), y(stroke[0]), inkPaint)
            return
        }
        val path = Path().apply {
            moveTo(x(stroke[0]), y(stroke[0]))
            for (index in 1 until stroke.size) lineTo(x(stroke[index]), y(stroke[index]))
        }
        canvas.drawPath(path, inkPaint)
    }

    private fun point(x: Float, y: Float, time: Long) = StrokePoint(
        x = if (width > 0) x / width else 0f,
        y = if (height > 0) y / height else 0f,
        timeMillis = time,
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val currentPoint = point(event.x, event.y, event.eventTime)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onInkChanged?.invoke()
                state.begin(currentPoint)
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.historySize) {
                    state.append(point(event.getHistoricalX(index), event.getHistoricalY(index), event.getHistoricalEventTime(index)))
                }
                state.append(currentPoint)
            }
            MotionEvent.ACTION_UP -> {
                state.end(currentPoint)
                parent?.requestDisallowInterceptTouchEvent(false)
                completingTouch = true
                performClick()
                completingTouch = false
                onStrokeFinished?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> {
                state.cancelActive()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            else -> return false
        }
        invalidate()
        return true
    }

    /** Accessibility activation creates a centered tap stroke, an equivalent non-drag gesture. */
    override fun performClick(): Boolean {
        super.performClick()
        if (!completingTouch && width > 0 && height > 0) {
            onInkChanged?.invoke()
            val now = android.os.SystemClock.uptimeMillis()
            val center = StrokePoint(0.5f, 0.5f, now)
            state.begin(center)
            state.end(center)
            invalidate()
            onStrokeFinished?.invoke()
            announceForAccessibility(context.getString(R.string.handwriting_stroke_added))
        }
        return true
    }
}
