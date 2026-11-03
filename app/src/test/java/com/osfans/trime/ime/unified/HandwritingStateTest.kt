/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.unified

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingStateTest {
    @Test
    fun strokesPreserveTimeRoundTripAndUndoOneStroke() {
        val state = StrokeState()
        state.begin(StrokePoint(1.25f, 2.5f, 10L))
        state.append(StrokePoint(3f, 4f, 11L))
        state.end(StrokePoint(5f, 6f, 12L))
        state.begin(StrokePoint(7f, 8f, 20L))
        state.end(StrokePoint(9f, 10f, 21L))

        val restored = StrokeState.decode(state.encode())
        assertEquals(2, restored.strokes.size)
        assertEquals(12L, restored.strokes.first().last().timeMillis)
        assertTrue(restored.undo())
        assertEquals(1, restored.strokes.size)
        assertTrue(restored.undo())
        assertFalse(restored.undo())
    }

    @Test
    fun cancelledStrokeIsNotRecognizedOrPersisted() {
        val state = StrokeState()
        state.begin(StrokePoint(1f, 2f, 3L))
        state.append(StrokePoint(2f, 3f, 4L))
        state.cancelActive()
        assertTrue(state.isEmpty)
        assertEquals("", state.encode())
    }

    @Test
    fun reducerExposesDownloadFailureRetryReadyAndCandidates() {
        val initial = HandwritingUiState()
        val downloading = HandwritingStateReducer.downloading(initial)
        assertEquals(ModelDownloadState.DOWNLOADING, downloading.model)
        val failed = HandwritingStateReducer.failed(downloading, "offline")
        assertEquals(ModelDownloadState.FAILED, failed.model)
        assertEquals("offline", failed.error)
        val ready = HandwritingStateReducer.ready(failed)
        assertEquals(ModelDownloadState.READY, ready.model)
        assertNull(ready.error)
        val recognized = HandwritingStateReducer.recognized(ready, listOf("字", "字", "", "語"))
        assertEquals(listOf("字", "語"), recognized.candidates)
    }

    @Test
    fun selectingCandidateReturnsTextAndClearsInk() {
        val state = StrokeState()
        state.begin(StrokePoint(0f, 0f, 1L))
        state.end(StrokePoint(1f, 1f, 2L))
        assertEquals("日本", HandwritingCandidateCommitPolicy.select("日本", state))
        assertTrue(state.isEmpty)
        assertNull(HandwritingCandidateCommitPolicy.select("", state))
    }
}
