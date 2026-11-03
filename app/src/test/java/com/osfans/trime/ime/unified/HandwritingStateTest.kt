/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.unified

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class HandwritingStateTest :
    StringSpec({
        "normalized strokes preserve timing through resize and undo" {
            val state = StrokeState()
            state.begin(StrokePoint(0.1f, 0.2f, 10L))
            state.end(StrokePoint(0.5f, 0.6f, 12L))
            state.begin(StrokePoint(0.7f, 0.8f, 20L))
            state.end(StrokePoint(0.9f, 1f, 21L))

            val restored = StrokeState.decode(state.encode())
            restored.strokes.size shouldBe 2
            restored.strokes.first().last().timeMillis shouldBe 12L
            // scaled() 是純 float 乘法（0.6f * 100f 就是 60.000004f），畫布只要
            // 1/1000 px，寫死位元相等會把 float 表示誤差當幾何 bug 報。座標仍要
            // 真的按比例放大，所以留容差而不是刪掉斷言。
            val scaled = restored.scaled(200, 100).first().last()
            scaled.x shouldBe (100f plusOrMinus 0.001f)
            scaled.y shouldBe (60f plusOrMinus 0.001f)
            // 這支測試的名字承諾的是時間戳：resize 之後時間仍要跟著點走。
            scaled.timeMillis shouldBe 12L
            restored.undo() shouldBe true
            restored.strokes.size shouldBe 1
            restored.undo() shouldBe true
            restored.undo() shouldBe false
        }

        "out-of-range legacy pixel coordinates are not restored" {
            StrokeState.decode("125.0,42.0,10").isEmpty shouldBe true
        }

        "cancelled stroke is not recognized or persisted" {
            val state = StrokeState()
            state.begin(StrokePoint(0.1f, 0.2f, 3L))
            state.append(StrokePoint(0.2f, 0.3f, 4L))
            state.cancelActive()
            state.isEmpty shouldBe true
            state.encode() shouldBe ""
        }

        "model and recognition failures remain distinct" {
            val downloading = HandwritingStateReducer.downloading(HandwritingUiState())
            downloading.model shouldBe ModelDownloadState.DOWNLOADING
            val modelFailed = HandwritingStateReducer.modelFailed(downloading, "offline")
            modelFailed.model shouldBe ModelDownloadState.FAILED
            modelFailed.modelError shouldBe "offline"
            val ready = HandwritingStateReducer.ready(modelFailed)
            ready.model shouldBe ModelDownloadState.READY
            ready.modelError.shouldBeNull()
            val recognitionFailed = HandwritingStateReducer.recognitionFailed(ready, "bad ink")
            recognitionFailed.model shouldBe ModelDownloadState.READY
            recognitionFailed.recognitionError shouldBe "bad ink"
            val recognized = HandwritingStateReducer.recognized(recognitionFailed, listOf("字", "字", "", "語"))
            recognized.candidates shouldBe listOf("字", "語")
            recognized.recognitionError.shouldBeNull()
        }

        "candidate commit returns text and clears ink" {
            val state = StrokeState()
            state.begin(StrokePoint(0f, 0f, 1L))
            state.end(StrokePoint(1f, 1f, 2L))
            HandwritingCandidateCommitPolicy.select("日本", state) shouldBe "日本"
            state.isEmpty shouldBe true
            HandwritingCandidateCommitPolicy.select("", state).shouldBeNull()
        }
    })
