/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.unified

import android.view.inputmethod.EditorInfo
import java.io.File

/** Stores transient ink under noBackupFilesDir; Android backup transports never receive it. */
class HandwritingSessionStorage(private val directory: File) {
    private val tokenFile = File(directory, "handwriting-editor.token")
    private val inkFile = File(directory, "handwriting-ink.normalized")

    fun begin(editor: EditorInfo): StrokeState = begin(editorToken(editor))

    internal fun begin(token: String): StrokeState {
        if (tokenFile.runCatching { readText() }.getOrNull() != token) {
            clear()
            tokenFile.parentFile?.mkdirs()
            tokenFile.writeText(token)
            return StrokeState()
        }
        return StrokeState.decode(inkFile.runCatching { readText() }.getOrNull())
    }

    fun save(state: StrokeState) {
        inkFile.parentFile?.mkdirs()
        inkFile.writeText(state.encode())
    }

    fun clearInk() {
        inkFile.delete()
    }

    fun clear() {
        inkFile.delete()
        tokenFile.delete()
    }

    companion object {
        fun editorToken(editor: EditorInfo): String = editorToken(
            editor.packageName?.toString(), editor.fieldId, editor.fieldName, editor.inputType,
            editor.imeOptions, editor.privateImeOptions,
        )

        internal fun editorToken(
            packageName: String?,
            fieldId: Int,
            fieldName: String?,
            inputType: Int,
            imeOptions: Int,
            privateImeOptions: String?,
        ): String = listOf(
            packageName, fieldId, fieldName, inputType, imeOptions, privateImeOptions,
        ).joinToString("\u001f") { it?.toString().orEmpty() }
    }
}
