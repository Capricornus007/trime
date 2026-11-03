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

class HandwritingCanvasView(context: Context) : View(context) {
    var state: StrokeState = StrokeState()
        set(value) {
            field = value
            invalidate()
        }
    var onStrokeFinished: (() -> Unit)? = null

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
        contentDescription = context.getString(com.osfans.trime.R.string.handwriting_canvas_description)
        isFocusable = true
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
        if (stroke.size == 1) {
            canvas.drawPoint(stroke[0].x, stroke[0].y, inkPaint)
            return
        }
        val path = Path().apply {
            moveTo(stroke[0].x, stroke[0].y)
            for (index in 1 until stroke.size) lineTo(stroke[index].x, stroke[index].y)
        }
        canvas.drawPath(path, inkPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val point = StrokePoint(event.x, event.y, event.eventTime)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                state.begin(point)
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.historySize) {
                    state.append(
                        StrokePoint(
                            event.getHistoricalX(index),
                            event.getHistoricalY(index),
                            event.getHistoricalEventTime(index),
                        ),
                    )
                }
                state.append(point)
            }
            MotionEvent.ACTION_UP -> {
                state.end(point)
                parent?.requestDisallowInterceptTouchEvent(false)
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
}
