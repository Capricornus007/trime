/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.data.DataManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test

class RimeIceStage2Test {

    companion object {
        private fun matchesLuaProcessorInvariant(preedit: String): Boolean {
            val hasReadableOrSegmentedTail = preedit.any(Char::isLetter) || ("'" in preedit)
            return preedit != "94126" && !preedit.contains("1") && hasReadableOrSegmentedTail
        }

        @BeforeClass
        @JvmStatic
        fun setup() {
            FcitxTestEnvironment.setup()
        }
    }

    @Test
    fun freshDataSyncShipsT9LuaProcessor() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)

        val appAssets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val assetNames = appAssets.list("usr/share/rime-data")!!.toSet()
        val assetLuaNames = appAssets.list("usr/share/rime-data/lua")!!.toSet()
        val assetDefault = appAssets.open("usr/share/rime-data/default.yaml")
            .bufferedReader()
            .use { it.readText() }
        val runtimeDir = FcitxTestEnvironment.runtimeRimeDataDir()

        assertTrue(DataManager.getLoadedPlugins().none { it.name == "rime" })
        assertTrue(assetNames.contains("t9.schema.yaml"))
        assertTrue(assetLuaNames.contains("fcitx5_t9_processor.lua"))
        assertTrue(assetDefault.contains("schema: t9"))
        assertTrue(runtimeDir.resolve("t9.schema.yaml").isFile)
        assertTrue(runtimeDir.resolve("lua/fcitx5_t9_processor.lua").isFile)
    }

    @Test
    fun t9SchemaRunsLuaProcessorFromEngineProcessors() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")

        delay(300)
        FcitxTestEnvironment.sendString("94126", intervalMs = 100)
        val preedit = FcitxTestEnvironment.waitForInputPanelPreedit { it.isNotEmpty() }
        assertTrue(
            "Expected lua processor invariant for 94126, got preedit=$preedit",
            matchesLuaProcessorInvariant(preedit),
        )
        FcitxTestEnvironment.fcitx.reset()

        FcitxTestEnvironment.sendString("941126", intervalMs = 100)
        val segmentedPreedit = FcitxTestEnvironment.waitForInputPanelPreedit { "'" in it }
        assertTrue(segmentedPreedit.contains("'"))
        assertFalse(segmentedPreedit.contains("1"))
        FcitxTestEnvironment.fcitx.reset()
    }

    @Test
    fun t9InlineCandidatesReusePagedStructuredEvent() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString("94126", intervalMs = 100)
        val paged = FcitxTestEnvironment.waitForPagedCandidate { it.candidates.isNotEmpty() }

        assertTrue(paged.candidates.isNotEmpty())
        assertTrue(paged.candidates[0].text.isNotBlank())
        assertNotNull(paged.candidates[0].comment)
        assertTrue(FcitxTestEnvironment.fcitx.select(0))
        val commit = FcitxTestEnvironment.receiveFirstCommitString()?.data?.text
        assertTrue(!commit.isNullOrBlank())
        FcitxTestEnvironment.fcitx.reset()
    }

    @Test
    fun representativeBucketInputStillConvergesToXiAnPath() = runBlocking {
        assertCandidatesContain(input = "94126", expected = "西安")
    }

    @Test
    fun delimiterInputStillConvergesAndCommitsXiAn() = runBlocking {
        assertCandidatesContain(input = "941126", expected = "西安")
        selectCandidateContaining("西安")

        val commit = FcitxTestEnvironment.receiveFirstCommitString()?.data?.text
        assertTrue("Expected commit to contain 西安, got: $commit", !commit.isNullOrBlank() && "西安" in commit)
    }

    @Test
    fun representativeBucketInputStillConvergesToTiAnPath() = runBlocking {
        assertCandidatesContain(input = "84126", expected = "提案")
    }

    @Test
    fun continuousSelectionKeepsTailConvergingForBeijingDiTieZhan() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")

        FcitxTestEnvironment.sendString("234546413484319426", intervalMs = 100)
        FcitxTestEnvironment.clearEvents()
        selectCandidateExactly("北京")
        val commit = pollCommitString()

        val candidateTexts = waitForCandidatesMatching(limit = 30) { texts ->
            texts.any { it.replace(" ", "").contains("地铁") || it.replace(" ", "").contains("站") }
        }

        if (candidateTexts.none { it.replace(" ", "").contains("地铁") || it.replace(" ", "").contains("站") }) {
            val preedit = FcitxTestEnvironment.fcitx.inputPanelCached.preedit.toString()
            val rawInput = FcitxTestEnvironment.readRawInput()
            fail(
                "Selecting 北京 should keep tail converging toward 地铁/站; " +
                    "commit=$commit rawInput=$rawInput preedit=$preedit candidates=$candidateTexts",
            )
        }
    }

    @Test
    fun continuousSelectionCommitsBeijingDiTieZhanStepByStep() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")

        FcitxTestEnvironment.sendString("234546413484319426", intervalMs = 100)
        selectCandidateExactly("北京")
        selectCandidateExactly("地铁")
        FcitxTestEnvironment.clearEvents()
        selectCandidateExactly("站")

        val commit = pollCommitString()
        assertTrue(
            "Expected step-by-step selection to commit 北京地铁站, got: $commit",
            !commit.isNullOrBlank() && "北京地铁站" in commit,
        )
    }

    @Test
    fun t9KeyboardLifecycleRestoresPreviousSchema() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")

        val previousSchema = FcitxTestEnvironment.fcitx.currentSchema()
        assertTrue(previousSchema.isNotBlank())

        FcitxTestEnvironment.setRimeSchema("t9")
        assertTrue(FcitxTestEnvironment.fcitx.currentSchema() == "t9")

        FcitxTestEnvironment.setRimeSchema(previousSchema)
        assertTrue(FcitxTestEnvironment.fcitx.currentSchema() == previousSchema)
    }

    private suspend fun assertCandidatesContain(input: String, expected: String) {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")

        FcitxTestEnvironment.sendString(input, intervalMs = 100)
        val candidateTexts = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { texts ->
                texts.any { it.replace(" ", "").contains(expected) }
            },
            limit = 30,
        )

        if (candidateTexts.none { it.replace(" ", "").contains(expected) }) {
            val preedit = FcitxTestEnvironment.fcitx.inputPanelCached.preedit.toString()
            fail("$input should converge to $expected; preedit=$preedit candidates=$candidateTexts")
        }
    }

    private suspend fun selectCandidateContaining(expected: String, limit: Int = 30) {
        repeat(50) {
            val candidates = FcitxTestEnvironment.fcitx.getCandidates(0, limit).filter { it.isNotBlank() }
            val index = candidates.indexOfFirst { it.replace(" ", "").contains(expected) }
            if (index >= 0) {
                assertTrue("Failed to select candidate containing '$expected'", FcitxTestEnvironment.fcitx.select(index))
                return
            }
            delay(100)
        }
        val preedit = FcitxTestEnvironment.fcitx.inputPanelCached.preedit.toString()
        fail("Timed out waiting to select candidate containing '$expected'; preedit=$preedit")
    }

    private suspend fun selectCandidateExactly(expected: String, limit: Int = 30) {
        repeat(50) {
            val candidates = FcitxTestEnvironment.fcitx.getCandidates(0, limit).filter { it.isNotBlank() }
            val index = candidates.indexOfFirst { candidateTextOf(it) == expected }
            if (index >= 0) {
                assertTrue("Failed to select exact candidate '$expected'", FcitxTestEnvironment.fcitx.select(index))
                return
            }
            delay(100)
        }
        val preedit = FcitxTestEnvironment.fcitx.inputPanelCached.preedit.toString()
        val candidates = FcitxTestEnvironment.fcitx.getCandidates(0, limit).joinToString()
        fail("Timed out waiting to select exact candidate '$expected'; preedit=$preedit candidates=[$candidates]")
    }

    private suspend fun waitForCandidatesMatching(
        limit: Int = 20,
        predicate: (List<String>) -> Boolean,
    ): List<String> {
        repeat(50) {
            val candidates = FcitxTestEnvironment.fcitx.getCandidates(0, limit).filter { it.isNotBlank() }
            if (predicate(candidates)) {
                return candidates
            }
            delay(100)
        }
        return FcitxTestEnvironment.fcitx.getCandidates(0, limit).filter { it.isNotBlank() }
    }

    private suspend fun pollCommitString(timeoutMs: Long = 1_500L): String? = withTimeoutOrNull(timeoutMs) {
        FcitxTestEnvironment.fcitxEventChannel
            .receiveAsFlow()
            .mapNotNull { (it as? FcitxEvent.CommitStringEvent)?.data?.text }
            .firstOrNull()
    }

    private fun candidateTextOf(candidate: String): String = candidate.substringBefore(' ')
}
