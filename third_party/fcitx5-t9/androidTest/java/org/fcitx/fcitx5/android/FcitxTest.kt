/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.core.whenReady
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import timber.log.Timber
import java.io.File

object FcitxTestEnvironment {
    @PublishedApi
    internal const val EVENT_TIMEOUT_MS = 10_000L

    lateinit var fcitx: Fcitx
        private set

    val fcitxEventChannel = Channel<FcitxEvent<*>>(capacity = Channel.UNLIMITED)
    private val scope = MainScope()
    private var initialized = false

    @Synchronized
    fun setup() {
        if (initialized) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fcitx = Fcitx(context)

        fcitx.eventFlow
            .onEach { fcitxEventChannel.send(it) }
            .launchIn(scope)

        runBlocking {
            restart()
            fcitx.setGlobalConfig(
                RawConfig(
                    arrayOf(
                        RawConfig(
                            "Behavior",
                            arrayOf(
                                RawConfig("ShowInputMethodInformation", false),
                            ),
                        ),
                    ),
                ),
            )
            useIme("rime")
        }
        initialized = true
    }

    @Synchronized
    fun cleanup() {
        if (!initialized) return
        // Keep the shared daemon alive for sibling instrumentation tests.
    }

    fun currentImeId(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return "${context.packageName}/${FcitxInputMethodService::class.java.name}"
    }

    suspend fun restart(resetData: Boolean = false) {
        if (fcitx.isReady) {
            fcitx.stop()
        }
        if (resetData) {
            DataManager.deleteAndSync()
        }
        fcitx.start()
        fcitx.lifecycle.whenReady { }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fcitx.activate(context.applicationInfo.uid, context.packageName)
        fcitx.setCapFlags(CapabilityFlags.DefaultFlags)
        fcitx.focus(true)
    }

    suspend fun sendString(str: String, intervalMs: Long = 50) {
        str.forEach { c ->
            fcitx.sendKey(c)
            delay(intervalMs)
        }
    }

    fun clearEvents() {
        while (fcitxEventChannel.tryReceive().isSuccess) {
            // Drain stale events so assertions only observe the current scenario.
        }
    }

    suspend fun useIme(name: String) {
        fcitx.setEnabledIme(arrayOf(name))
        fcitx.activateIme(name)
        waitForCurrentIme(name)
        fcitx.focusOutIn()
        fcitx.focus(true)
        if (name == "rime") {
            waitForRimeSubModeReady()
        }
        clearEvents()
    }

    private suspend fun waitForRimeSubModeReady() {
        repeat(100) {
            val currentIme = fcitx.currentIme()
            if (currentIme.uniqueName != "rime") {
                delay(100)
                return@repeat
            }
            val statusAction = fcitx.statusArea().firstOrNull { it.name == "fcitx-rime-im" }
            val ready = currentIme.subMode.name.isNotBlank() ||
                currentIme.subMode.label.isNotBlank() ||
                statusAction?.shortText?.isNotBlank() == true ||
                statusAction?.longText?.isNotBlank() == true
            if (ready) {
                return
            }
            delay(100)
        }
        val statusSummary = fcitx.statusArea().joinToString { action ->
            "${action.name}:${action.shortText}:${action.longText}"
        }
        Assert.fail(
            "Timed out waiting for rime sub mode to become ready; " +
                "ime=${fcitx.currentIme()} status=$statusSummary " +
                "snapshot=<${fcitx.readRimeDebugSnapshot()}>",
        )
    }

    suspend inline fun <reified T : FcitxEvent<*>> receiveFirst(): T? = withTimeoutOrNull(EVENT_TIMEOUT_MS) {
        fcitxEventChannel.receiveAsFlow().mapNotNull { it as? T }.firstOrNull()
    } ?: run {
        Assert.fail("Timed out waiting for ${T::class.simpleName}")
        null
    }

    suspend fun receiveFirstCandidateList() = receiveFirst<FcitxEvent.CandidateListEvent>()

    suspend fun receiveFirstPagedCandidate() = receiveFirst<FcitxEvent.PagedCandidateEvent>()

    suspend fun waitForPagedCandidate(
        predicate: (FcitxEvent.PagedCandidateEvent.Data) -> Boolean,
    ): FcitxEvent.PagedCandidateEvent.Data {
        repeat(50) {
            val event = receiveFirstPagedCandidate()?.data
            if (event != null && predicate(event)) return event
        }
        Assert.fail("Timed out waiting for paged candidate to match predicate")
        return FcitxEvent.PagedCandidateEvent.Data.Empty
    }

    suspend fun waitForPagedCandidateSnapshot(
        predicate: (PagedCandidateSnapshot) -> Boolean,
    ): PagedCandidateSnapshot {
        repeat(50) {
            val snapshot = receiveFirstPagedCandidate()?.data?.toSnapshot()
            if (snapshot != null && predicate(snapshot)) return snapshot
        }
        Assert.fail("Timed out waiting for paged candidate snapshot to match predicate")
        return PagedCandidateSnapshot.Empty
    }

    suspend fun receiveFirstCommitString() = receiveFirst<FcitxEvent.CommitStringEvent>()

    suspend fun receiveFirstPreedit() = receiveFirst<FcitxEvent.ClientPreeditEvent>()

    suspend fun receiveFirstInputPanelAux() = receiveFirst<FcitxEvent.InputPanelEvent>()

    suspend fun waitForInputPanelPreedit(predicate: (String) -> Boolean): String {
        repeat(50) {
            val preedit = fcitx.inputPanelCached.preedit.toString()
            if (predicate(preedit)) return preedit
            delay(100)
        }
        Assert.fail("Timed out waiting for input panel preedit to match predicate")
        return ""
    }

    suspend fun waitForRawInput(predicate: (String) -> Boolean): String {
        repeat(50) {
            val rawInput = readRawInput()
            if (predicate(rawInput)) return rawInput
            delay(100)
        }
        Assert.fail("Timed out waiting for raw input to match predicate")
        return ""
    }

    suspend fun waitForDaemonRawInput(predicate: (String) -> Boolean): String {
        repeat(50) {
            val rawInput = fcitx.readInput()
            if (predicate(rawInput)) return rawInput
            delay(100)
        }
        Assert.fail(
            "Timed out waiting for daemon raw input to match predicate; " +
                "daemonRawInput=${fcitx.readInput()} inputPanelPreedit=${fcitx.inputPanelCached.preedit} " +
                "snapshot=<${fcitx.readRimeDebugSnapshot()}>",
        )
        return ""
    }

    suspend fun waitForCompositionToClear() {
        repeat(50) {
            val daemonRawInput = fcitx.readInput()
            val inputPanelPreedit = fcitx.inputPanelCached.preedit.toString()
            if (daemonRawInput.isBlank() && inputPanelPreedit.isBlank()) {
                return
            }
            delay(100)
        }
        Assert.fail(
            "Timed out waiting for composition to clear; " +
                "daemonRawInput=${fcitx.readInput()} inputPanelPreedit=${fcitx.inputPanelCached.preedit} " +
                "snapshot=<${fcitx.readRimeDebugSnapshot()}>",
        )
    }

    suspend fun readRawInput(): String {
        val daemonRawInput = fcitx.readInput()
        if (daemonRawInput.isNotBlank()) return daemonRawInput

        val panelRawInput = fcitx.inputPanelCached.preedit
            .toString()
            .replace(" ", "")
        if (panelRawInput.isNotBlank()) return panelRawInput

        return ""
    }

    suspend fun replaceRawInputRange(start: Int, endExclusive: Int, replacement: String) {
        val rawInput = readRawInput()
        require(start >= 0) { "start must be non-negative" }
        require(endExclusive >= start) { "endExclusive must be >= start" }
        require(endExclusive <= rawInput.length) {
            "endExclusive must be within current raw input length"
        }

        val updated = rawInput.replaceRange(start, endExclusive, replacement)
        fcitx.setInput(updated)
        fcitx.setCaret(start + replacement.length)
    }

    suspend fun setRimeSchema(schemaId: String) {
        waitForRuntimeRimeSchemaFiles("rime_ice", schemaId)
        fcitx.setSchema(schemaId)
        waitForRimeSchema(schemaId)
        fcitx.focusOutIn()
        fcitx.focus(true)
        clearEvents()
    }

    suspend fun waitForRimeSchema(schemaId: String) {
        repeat(50) {
            val current = runCatching { fcitx.currentSchema() }.getOrNull()
            if (current == schemaId) return
            delay(100)
        }
        val current = runCatching { fcitx.currentSchema() }
            .getOrElse { "<error:${it::class.simpleName}:${it.message}>" }
        Assert.fail("Timed out waiting for current Rime schema '$schemaId', current='$current'")
    }

    private suspend fun waitForRuntimeRimeSchemaFiles(vararg schemaIds: String) {
        repeat(50) {
            if (schemaIds.all { runtimeRimeSchemaFile(it).isFile }) return
            delay(100)
        }
        val missing = schemaIds.filterNot { runtimeRimeSchemaFile(it).isFile }
        Assert.fail(
            "Timed out waiting for runtime Rime schema files: $missing; " +
                "runtimeDir=${runtimeRimeDataDir().absolutePath}",
        )
    }

    fun runtimeRimeDataDir(): File = File(DataManager.dataDir, "usr/share/rime-data")

    fun runtimeRimeDataFile(relativePath: String): File = File(runtimeRimeDataDir(), relativePath)

    fun runtimeRimeSchemaFile(schemaId: String): File = runtimeRimeDataFile("$schemaId.schema.yaml")

    suspend fun waitForEnabledIme(name: String) {
        repeat(50) {
            if (fcitx.enabledIme().any { it.uniqueName == name }) return
            delay(100)
        }
        Assert.fail("Timed out waiting for enabled IME '$name'")
    }

    suspend fun waitForCurrentIme(name: String) {
        repeat(50) {
            if (fcitx.currentIme().uniqueName == name) return
            delay(100)
        }
        Assert.fail("Timed out waiting for current IME '$name'")
    }

    suspend fun selectCandidateText(text: String, limit: Int = 20) {
        repeat(50) {
            val candidates = fcitx.getCandidates(0, limit)
            val index = candidates.indexOfFirst { candidate ->
                candidate == text ||
                    candidate.startsWith("$text ") ||
                    candidate.substringBefore(' ') == text
            }
            if (index >= 0) {
                Assert.assertTrue("Failed to select candidate '$text'", fcitx.select(index))
                return
            }
            delay(100)
        }
        val currentIme = fcitx.currentIme()
        val statusArea = fcitx.statusArea().joinToString { action ->
            buildString {
                append("${action.name}:${action.shortText}:${action.longText}:${action.isChecked}")
                action.menu?.takeIf { it.isNotEmpty() }?.let { menu ->
                    append(
                        menu.joinToString(prefix = "{", postfix = "}") {
                            "${it.name}:${it.shortText}:${it.longText}:${it.isChecked}"
                        },
                    )
                }
            }
        }
        val preedit = fcitx.clientPreeditCached
        val inputPanel = fcitx.inputPanelCached.preedit
        val available = fcitx.getCandidates(0, limit).joinToString()
        Assert.fail(
            "Candidate '$text' not found; ime=${currentIme.uniqueName}/${currentIme.subMode.name}" +
                " label=${currentIme.subMode.label}; status=[$statusArea]; preedit=$preedit;" +
                " inputPanelPreedit=$inputPanel; candidates=[$available]",
        )
    }

    suspend fun waitForCandidateTexts(
        predicate: (List<String>) -> Boolean,
        limit: Int = 20,
    ): List<String> {
        repeat(50) {
            val candidates = fcitx.getCandidates(0, limit).filter { it.isNotBlank() }
            if (predicate(candidates)) {
                return candidates
            }
            delay(100)
        }
        val currentIme = fcitx.currentIme()
        val preedit = fcitx.clientPreeditCached
        val inputPanel = fcitx.inputPanelCached.preedit
        val available = fcitx.getCandidates(0, limit).joinToString()
        Assert.fail(
            "Candidates did not match predicate; ime=${currentIme.uniqueName}/${currentIme.subMode.name}" +
                " label=${currentIme.subMode.label}; preedit=$preedit;" +
                " inputPanelPreedit=$inputPanel; candidates=[$available]",
        )
        return emptyList()
    }

    suspend fun <T> withPatchedRuntimeRimeData(
        relativePath: String,
        transform: (String) -> String,
        block: suspend () -> T,
    ): T {
        val runtimeFile = runtimeRimeDataFile(relativePath)
        val original = runtimeFile.readText()
        try {
            runtimeFile.writeText(transform(original))
            restart()
            return block()
        } finally {
            runtimeFile.writeText(original)
            restart()
        }
    }

    suspend fun <T> withPatchedRimeSchema(
        schemaId: String = "t9",
        transform: (String) -> String,
        block: suspend () -> T,
    ): T = withPatchedRuntimeRimeData("$schemaId.schema.yaml", transform, block)

    suspend fun <T> withRimeSchema(schemaId: String, block: suspend () -> T): T {
        val previousSchemaId = fcitx.currentSchema()
        setRimeSchema(schemaId)
        return try {
            block()
        } finally {
            if (previousSchemaId.isNotBlank() && previousSchemaId != schemaId) {
                setRimeSchema(previousSchemaId)
            }
        }
    }
}

data class PagedCandidateSnapshot(
    val candidates: List<FcitxEvent.Candidate>,
    val cursorIndex: Int,
    val layoutHint: FcitxEvent.PagedCandidateEvent.LayoutHint,
    val hasPrev: Boolean,
    val hasNext: Boolean,
) {
    val texts: List<String> = candidates.map { it.text }
    val comments: List<String> = candidates.map { it.comment }

    fun summary(limit: Int = 5): String = candidates.joinToString(
        prefix = "cursor=$cursorIndex, layout=$layoutHint, hasPrev=$hasPrev, hasNext=$hasNext, candidates=[",
        postfix = "]",
        limit = limit,
    ) { "${it.label}:${it.text}:${it.comment}" }

    companion object {
        val Empty = PagedCandidateSnapshot(
            candidates = emptyList(),
            cursorIndex = -1,
            layoutHint = FcitxEvent.PagedCandidateEvent.LayoutHint.NotSet,
            hasPrev = false,
            hasNext = false,
        )
    }
}

fun FcitxEvent.PagedCandidateEvent.Data.toSnapshot(): PagedCandidateSnapshot = PagedCandidateSnapshot(
    candidates = candidates.toList(),
    cursorIndex = cursorIndex,
    layoutHint = layoutHint,
    hasPrev = hasPrev,
    hasNext = hasNext,
)

class FcitxTest {

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
    fun saveEnabledIME() = runBlocking {
        enabledIme = FcitxTestEnvironment.fcitx.enabledIme().map { it.uniqueName }
    }

    @After
    fun restoreEnabledIME() = runBlocking {
        FcitxTestEnvironment.fcitx.setEnabledIme(enabledIme.toTypedArray())
    }

    @Test
    fun testRime(): Unit = runBlocking {
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.sendString("nihao")
        val expected = "你好"
        FcitxTestEnvironment.selectCandidateText(expected)
        val commitString = FcitxTestEnvironment.receiveFirstCommitString()?.data?.text
        Timber.i("commitString is $commitString")
        Assert.assertEquals(expected, commitString)
        FcitxTestEnvironment.fcitx.reset()
    }

    @Test
    fun testInputPanelStatus(): Unit = runBlocking {
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.fcitx.reset()
        Timber.i("after first reset: ${FcitxTestEnvironment.fcitx.isEmpty()}")
        Assert.assertEquals(true, FcitxTestEnvironment.fcitx.isEmpty())
        FcitxTestEnvironment.fcitx.sendKey('n')
        do {
            val list = FcitxTestEnvironment.receiveFirstCandidateList()
        } while (list!!.data.candidates.isNotEmpty())
        Timber.i("after sending 'n': ${FcitxTestEnvironment.fcitx.isEmpty()}")
        Assert.assertEquals(false, FcitxTestEnvironment.fcitx.isEmpty())
        FcitxTestEnvironment.fcitx.reset()
        do {
            val list = FcitxTestEnvironment.receiveFirstCandidateList()
        } while (list!!.data.candidates.isNotEmpty())
        Timber.i("after second reset: ${FcitxTestEnvironment.fcitx.isEmpty()}")
        Assert.assertEquals(true, FcitxTestEnvironment.fcitx.isEmpty())
    }
}
