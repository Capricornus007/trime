/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.unified

import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink

/** Lazily owns one on-device recognizer and rejects stale language and ink callbacks. */
class DigitalInkModelManager(
    private val isUserUnlocked: () -> Boolean,
) {
    private val remoteModels by lazy(LazyThreadSafetyMode.NONE) { RemoteModelManager.getInstance() }
    private var modelGeneration = 0L
    private var inkGeneration = 0L
    private var recognizer: DigitalInkRecognizer? = null
    private var activeTag: String? = null

    fun prepare(tag: String, onState: (HandwritingUiState) -> Unit) {
        if (!isUserUnlocked()) {
            onState(HandwritingStateReducer.modelFailed(HandwritingUiState(), "Unlock the device to load handwriting models"))
            return
        }
        val request = ++modelGeneration
        invalidateInk()
        closeRecognizer()
        activeTag = tag
        val identifier = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)
        } catch (error: MlKitException) {
            onState(HandwritingStateReducer.modelFailed(HandwritingUiState(), error.localizedMessage))
            return
        }
        if (identifier == null) {
            onState(HandwritingStateReducer.modelFailed(HandwritingUiState(), "Unsupported language: $tag"))
            return
        }
        val model = DigitalInkRecognitionModel.builder(identifier).build()
        remoteModels.isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (request != modelGeneration) return@addOnSuccessListener
                if (downloaded) {
                    createRecognizer(model)
                    onState(HandwritingStateReducer.ready(HandwritingUiState()))
                } else {
                    download(model, request, onState)
                }
            }
            .addOnFailureListener { error ->
                if (request == modelGeneration) {
                    onState(HandwritingStateReducer.modelFailed(HandwritingUiState(), error.localizedMessage))
                }
            }
    }

    fun retryModel(onState: (HandwritingUiState) -> Unit) {
        activeTag?.let { prepare(it, onState) }
    }

    private fun download(
        model: DigitalInkRecognitionModel,
        request: Long,
        onState: (HandwritingUiState) -> Unit,
    ) {
        onState(HandwritingStateReducer.downloading(HandwritingUiState()))
        remoteModels.download(model, DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (request != modelGeneration) return@addOnSuccessListener
                createRecognizer(model)
                onState(HandwritingStateReducer.ready(HandwritingUiState()))
            }
            .addOnFailureListener { error ->
                if (request == modelGeneration) {
                    onState(HandwritingStateReducer.modelFailed(HandwritingUiState(), error.localizedMessage))
                }
            }
    }

    private fun createRecognizer(model: DigitalInkRecognitionModel) {
        closeRecognizer()
        recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
    }

    fun invalidateInk() {
        inkGeneration++
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
        val modelRequest = modelGeneration
        val inkRequest = ++inkGeneration
        val ink = Ink.builder().apply {
            strokes.forEach { stroke ->
                if (stroke.isEmpty()) return@forEach
                addStroke(
                    Ink.Stroke.builder().apply {
                        stroke.forEach { point -> addPoint(Ink.Point.create(point.x, point.y, point.timeMillis)) }
                    }.build(),
                )
            }
        }.build()
        client.recognize(ink)
            .addOnSuccessListener { result ->
                if (modelRequest == modelGeneration && inkRequest == inkGeneration) {
                    onSuccess(result.candidates.map { it.text })
                }
            }
            .addOnFailureListener { error ->
                if (modelRequest == modelGeneration && inkRequest == inkGeneration) {
                    onFailure(error.localizedMessage)
                }
            }
    }

    fun close() {
        modelGeneration++
        invalidateInk()
        activeTag = null
        closeRecognizer()
    }

    private fun closeRecognizer() {
        recognizer?.close()
        recognizer = null
    }
}
