/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.input.t9.T9Coordinator
import org.fcitx.fcitx5.android.input.t9.selectT9Candidate
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.Test

class RimePredictionTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            FcitxTestEnvironment.setup()
        }

        @AfterClass
        @JvmStatic
        fun cleanup() {
            FcitxTestEnvironment.cleanup()
        }
    }

    private var enabledIme: List<String> = listOf()

    @Before
    fun saveEnabledIme() = runBlocking {
        enabledIme = FcitxTestEnvironment.fcitx.enabledIme().map { it.uniqueName }
    }

    @After
    fun restoreEnabledIme() = runBlocking {
        FcitxTestEnvironment.fcitx.setEnabledIme(enabledIme.toTypedArray())
    }

    @Test
    fun predictDatabaseIsBundledIntoRuntimeRimeData() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        assertTrue(
            "predict.db should be present in runtime Rime shared data",
            FcitxTestEnvironment.runtimeRimeDataFile("predict.db").isFile,
        )
    }

    @Test
    fun rimeIceCommitCanEnterPredictionAndContinueOnce() = runBlocking {
        assertPostCommitPredictionChain(
            schemaId = "rime_ice",
            input = "wo",
            committedCandidate = "我",
        )
    }

    @Test
    fun t9CommitCanEnterPredictionAndContinueOnce() = runBlocking {
        assertT9PostCommitPredictionChain(
            input = "96",
            committedCandidate = "我",
        )
    }

    @Ignore("Flaky native crash (scudo invalid chunk state) on emulator during repeated runtime restarts; tracked for dedicated native debugging")
    @Test
    fun simplifiedPredictionCandidatesStaySimplifiedAfterCommit() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("rime_ice")

        FcitxTestEnvironment.sendString("jintian", intervalMs = 100)
        FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { candidates -> candidates.any { it == "今天" } },
            limit = 20,
        )

        FcitxTestEnvironment.clearEvents()
        FcitxTestEnvironment.selectCandidateText("今天")
        val commit = FcitxTestEnvironment.receiveFirstCommitString()?.data?.text
        assertTrue(
            "Expected commit to contain 今天, got: $commit",
            !commit.isNullOrBlank() && "今天" in commit,
        )

        FcitxTestEnvironment.waitForCompositionToClear()
        val predictionCandidates = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { candidates ->
                candidates.isNotEmpty() && (candidates.contains("我们") || candidates.contains("我們"))
            },
            limit = 20,
        )

        assertTrue(
            "Expected simplified prediction candidate '我们', got: $predictionCandidates",
            predictionCandidates.contains("我们"),
        )
        assertFalse(
            "Prediction candidates should not contain traditional text once simplified db is installed: $predictionCandidates",
            predictionCandidates.contains("我們"),
        )
    }

    private suspend fun assertPostCommitPredictionChain(
        schemaId: String,
        input: String,
        committedCandidate: String,
        enablePagedCandidates: Boolean = false,
    ) {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema(schemaId)
        if (enablePagedCandidates) {
            FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)
        }

        FcitxTestEnvironment.sendString(input, intervalMs = 100)
        val preCommitCandidates = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { candidates -> candidates.any { it == committedCandidate } },
            limit = 20,
        )

        FcitxTestEnvironment.clearEvents()
        FcitxTestEnvironment.selectCandidateText(committedCandidate)
        val firstCommit = FcitxTestEnvironment.receiveFirstCommitString()?.data?.text
        assertTrue(
            "Expected commit to contain $committedCandidate, got: $firstCommit",
            !firstCommit.isNullOrBlank() && committedCandidate in firstCommit,
        )

        FcitxTestEnvironment.waitForCompositionToClear()
        val firstPredictionCandidates = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { candidates -> candidates.isNotEmpty() && candidates != preCommitCandidates },
            limit = 20,
        )
        assertTrue(
            "Expected post-commit prediction candidates for schema=$schemaId, got: $firstPredictionCandidates",
            firstPredictionCandidates.isNotEmpty(),
        )

        FcitxTestEnvironment.clearEvents()
        assertTrue(
            "Selecting first prediction candidate should succeed for schema=$schemaId",
            FcitxTestEnvironment.fcitx.select(0),
        )
        val secondCommit = FcitxTestEnvironment.receiveFirstCommitString()?.data?.text
        assertTrue(
            "Expected prediction candidate selection to commit text, got: $secondCommit",
            !secondCommit.isNullOrBlank(),
        )

        FcitxTestEnvironment.waitForCompositionToClear()
        val secondPredictionCandidates = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { it.isNotEmpty() },
            limit = 20,
        )
        assertTrue(
            "Expected another prediction round after selecting first prediction candidate; first=$firstPredictionCandidates second=$secondPredictionCandidates",
            secondPredictionCandidates.isNotEmpty(),
        )
    }

    private suspend fun assertT9PostCommitPredictionChain(
        input: String,
        committedCandidate: String,
    ) {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString(input, intervalMs = 100)
        val preCommitCandidates = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { candidates ->
                candidates.any { candidate -> candidateTextOf(candidate) == committedCandidate }
            },
            limit = 20,
        ).map(::candidateTextOf)

        FcitxTestEnvironment.clearEvents()
        assertTrue(
            "Selecting committed T9 candidate should succeed",
            selectT9CandidateByText(committedCandidate),
        )
        val firstCommit = FcitxTestEnvironment.receiveFirstCommitString()?.data?.text
        assertTrue(
            "Expected T9 commit to contain $committedCandidate, got: $firstCommit",
            !firstCommit.isNullOrBlank() && committedCandidate in firstCommit,
        )

        FcitxTestEnvironment.waitForCompositionToClear()
        val firstPredictionCandidates = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { candidates ->
                val texts = candidates.map(::candidateTextOf)
                texts.isNotEmpty() && texts != preCommitCandidates
            },
            limit = 20,
        ).map(::candidateTextOf)
        assertTrue(
            "Expected post-commit prediction candidates for t9, got: $firstPredictionCandidates",
            firstPredictionCandidates.isNotEmpty(),
        )

        FcitxTestEnvironment.clearEvents()
        assertTrue(
            "Selecting first T9 prediction candidate should succeed",
            selectT9CandidateAtIndex(0),
        )
        val secondCommit = FcitxTestEnvironment.receiveFirstCommitString()?.data?.text
        assertTrue(
            "Expected T9 prediction candidate selection to commit text, got: $secondCommit",
            !secondCommit.isNullOrBlank(),
        )

        FcitxTestEnvironment.waitForCompositionToClear()
        val secondPredictionCandidates = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { candidates -> candidates.map(::candidateTextOf).isNotEmpty() },
            limit = 20,
        ).map(::candidateTextOf)
        assertTrue(
            "Expected another T9 prediction round after selecting first prediction candidate; first=$firstPredictionCandidates second=$secondPredictionCandidates",
            secondPredictionCandidates.isNotEmpty(),
        )
    }

    private suspend fun selectT9CandidateByText(expected: String): Boolean {
        repeat(50) {
            val candidates = FcitxTestEnvironment.fcitx.getCandidates(0, 20).filter { it.isNotBlank() }
            val index = candidates.indexOfFirst { candidate -> candidateTextOf(candidate) == expected }
            if (index >= 0) {
                return selectT9CandidateAtIndex(index)
            }
            delay(100)
        }
        return false
    }

    private fun candidateTextOf(candidate: String): String = candidate.substringBefore(' ')

    private suspend fun selectT9CandidateAtIndex(index: Int): Boolean {
        if (index < 0) return false
        val coordinator = T9Coordinator()
        val selectionState = coordinator.sync(
            rawInput = FcitxTestEnvironment.fcitx.readInput(),
            caret = FcitxTestEnvironment.fcitx.readCaret(),
            preedit = FcitxTestEnvironment.fcitx.inputPanelCached.preedit.toString(),
        )
        return FcitxTestEnvironment.fcitx.selectT9Candidate(index, selectionState)
    }
}
