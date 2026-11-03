/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.unified

import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink

/** Owns one on-device recognizer and ignores stale callbacks after a language change. */
class DigitalInkModelManager {
    private val remoteModels = RemoteModelManager.getInstance()
    private var generation = 0
    private var recognizer: DigitalInkRecognizer? = null
    private var activeTag: String? = null

    fun prepare(tag: String, onState: (HandwritingUiState) -> Unit) {
        val request = ++generation
        closeRecognizer()
        activeTag = tag
        val identifier = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)
        } catch (error: MlKitException) {
            onState(HandwritingStateReducer.failed(HandwritingUiState(), error.localizedMessage))
            return
        }
        if (identifier == null) {
            onState(HandwritingStateReducer.failed(HandwritingUiState(), "Unsupported language: $tag"))
            return
        }
        val model = DigitalInkRecognitionModel.builder(identifier).build()
        remoteModels.isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (request != generation) return@addOnSuccessListener
                if (downloaded) {
                    createRecognizer(model)
                    onState(HandwritingStateReducer.ready(HandwritingUiState()))
                } else {
                    download(model, request, onState)
                }
            }
            .addOnFailureListener { error ->
                if (request == generation) {
                    onState(HandwritingStateReducer.failed(HandwritingUiState(), error.localizedMessage))
                }
            }
    }

    fun retry(onState: (HandwritingUiState) -> Unit) {
        activeTag?.let { prepare(it, onState) }
    }

    private fun download(
        model: DigitalInkRecognitionModel,
        request: Int,
        onState: (HandwritingUiState) -> Unit,
    ) {
        onState(HandwritingStateReducer.downloading(HandwritingUiState()))
        remoteModels.download(model, DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (request != generation) return@addOnSuccessListener
                createRecognizer(model)
                onState(HandwritingStateReducer.ready(HandwritingUiState()))
            }
            .addOnFailureListener { error ->
                if (request == generation) {
                    onState(HandwritingStateReducer.failed(HandwritingUiState(), error.localizedMessage))
                }
            }
    }

    private fun createRecognizer(model: DigitalInkRecognitionModel) {
        closeRecognizer()
        recognizer = DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).build(),
        )
    }

    fun recognize(
        strokes: List<HandwritingStroke>,
        onSuccess: (List<String>) -> Unit,
        onFailure: (String?) -> Unit,
    ) {
        val client = recognizer
        if (client == null) {
            onFailure("Model is not ready")
            return
        }
        val request = generation
        val ink = Ink.builder().apply {
            strokes.forEach { stroke ->
                if (stroke.isEmpty()) return@forEach
                addStroke(
                    Ink.Stroke.builder().apply {
                        stroke.forEach { point ->
                            addPoint(Ink.Point.create(point.x, point.y, point.timeMillis))
                        }
                    }.build(),
                )
            }
        }.build()
        client.recognize(ink)
            .addOnSuccessListener { result ->
                if (request == generation) onSuccess(result.candidates.map { it.text })
            }
            .addOnFailureListener { error ->
                if (request == generation) onFailure(error.localizedMessage)
            }
    }

    fun close() {
        generation++
        activeTag = null
        closeRecognizer()
    }

    private fun closeRecognizer() {
        recognizer?.close()
        recognizer = null
    }
}
