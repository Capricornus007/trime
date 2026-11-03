/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.unified

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class HandwritingSessionStorageTest :
    StringSpec({
        "same editor restores normalized ink and another editor clears it" {
            val directory = Files.createTempDirectory("trime-ink").toFile()
            try {
                val storage = HandwritingSessionStorage(directory)
                val first = "package-one\u001f1"
                val second = "package-two\u001f2"
                storage.begin(first).isEmpty shouldBe true
                val state = StrokeState().apply {
                    begin(StrokePoint(0.2f, 0.3f, 1L))
                    end(StrokePoint(0.4f, 0.5f, 2L))
                }
                storage.save(state)
                storage.begin(first).strokes.size shouldBe 1
                storage.begin(second).isEmpty shouldBe true
                storage.begin(first).isEmpty shouldBe true
            } finally {
                directory.deleteRecursively()
            }
        }

        "editor token changes with the target field" {
            val first = HandwritingSessionStorage.editorToken("pkg", 1, "a", 1, 0, null)
            val second = HandwritingSessionStorage.editorToken("pkg", 2, "b", 1, 0, null)
            (first == second) shouldBe false
        }
    })
