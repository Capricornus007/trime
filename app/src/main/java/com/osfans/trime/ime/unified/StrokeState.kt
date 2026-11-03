/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.unified

import java.util.Locale

data class StrokePoint(val x: Float, val y: Float, val timeMillis: Long)
typealias HandwritingStroke = List<StrokePoint>

/** Points are normalized to the canvas (0..1), so rotation and resizing preserve geometry. */
class StrokeState(initial: List<HandwritingStroke> = emptyList()) {
    private val completed = initial.mapTo(mutableListOf()) { it.toList() }
    private var active = mutableListOf<StrokePoint>()

    val strokes: List<HandwritingStroke> get() = completed
    val activeStroke: HandwritingStroke get() = active
    val isEmpty: Boolean get() = completed.isEmpty() && active.isEmpty()

    fun begin(point: StrokePoint) {
        active = mutableListOf(point.normalized())
    }

    fun append(point: StrokePoint) {
        if (active.isNotEmpty()) active.add(point.normalized())
    }

    fun end(point: StrokePoint) {
        if (active.isEmpty()) return
        val normalized = point.normalized()
        if (active.last() != normalized) active.add(normalized)
        completed.add(active.toList())
        active = mutableListOf()
    }

    fun cancelActive() {
        active = mutableListOf()
    }

    fun undo(): Boolean {
        if (active.isNotEmpty()) {
            cancelActive()
            return true
        }
        if (completed.isEmpty()) return false
        completed.removeAt(completed.lastIndex)
        return true
    }

    fun clear() {
        completed.clear()
        active.clear()
    }

    fun scaled(width: Int, height: Int): List<HandwritingStroke> = completed.map { stroke ->
        stroke.map { point ->
            StrokePoint(point.x * width, point.y * height, point.timeMillis)
        }
    }

    fun encode(): String = completed.joinToString("|") { stroke ->
        stroke.joinToString(";") { point ->
            String.format(Locale.ROOT, "%.6f,%.6f,%d", point.x, point.y, point.timeMillis)
        }
    }

    private fun StrokePoint.normalized() = copy(x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f))

    companion object {
        fun decode(raw: String?): StrokeState {
            if (raw.isNullOrBlank()) return StrokeState()
            val strokes = raw.split('|').mapNotNull stroke@{ encodedStroke ->
                val points = encodedStroke.split(';').mapNotNull point@{ encodedPoint ->
                    val fields = encodedPoint.split(',')
                    if (fields.size != 3) return@point null
                    val x = fields[0].toFloatOrNull()?.takeIf { it in 0f..1f } ?: return@point null
                    val y = fields[1].toFloatOrNull()?.takeIf { it in 0f..1f } ?: return@point null
                    val time = fields[2].toLongOrNull() ?: return@point null
                    StrokePoint(x, y, time)
                }
                points.takeIf { it.isNotEmpty() }
            }
            return StrokeState(strokes)
        }
    }
}

enum class ModelDownloadState { NOT_DOWNLOADED, DOWNLOADING, READY, FAILED }

data class HandwritingUiState(
    val model: ModelDownloadState = ModelDownloadState.NOT_DOWNLOADED,
    val modelError: String? = null,
    val recognitionError: String? = null,
    val candidates: List<String> = emptyList(),
)

object HandwritingStateReducer {
    fun downloading(old: HandwritingUiState) = old.copy(
        model = ModelDownloadState.DOWNLOADING,
        modelError = null,
        recognitionError = null,
        candidates = emptyList(),
    )

    fun ready(old: HandwritingUiState) = old.copy(
        model = ModelDownloadState.READY,
        modelError = null,
    )

    fun modelFailed(old: HandwritingUiState, message: String?) = old.copy(
        model = ModelDownloadState.FAILED,
        modelError = message,
        recognitionError = null,
        candidates = emptyList(),
    )

    fun recognitionFailed(old: HandwritingUiState, message: String?) = old.copy(
        recognitionError = message,
        candidates = emptyList(),
    )

    fun recognized(old: HandwritingUiState, candidates: List<String>) = old.copy(
        candidates = candidates.distinct().filter(String::isNotBlank).take(8),
        recognitionError = null,
    )
}

object HandwritingCandidateCommitPolicy {
    fun select(candidate: String, strokes: StrokeState): String? {
        val committed = candidate.takeIf(String::isNotBlank) ?: return null
        strokes.clear()
        return committed
    }
}
