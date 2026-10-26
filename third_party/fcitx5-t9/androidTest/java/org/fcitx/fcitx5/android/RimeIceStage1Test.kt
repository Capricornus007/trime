/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.core.data.DataManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class RimeIceStage1Test {

    companion object {
        private fun defaultSchemaStartsWithRimeIce(content: String) = content.contains("schema_list:\n  - schema: rime_ice")

        @BeforeClass
        @JvmStatic
        fun setup() {
            FcitxTestEnvironment.setup()
        }
    }

    @Test
    fun freshDataSyncShipsStage1RimeResources() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)

        val appAssets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val assetNames = appAssets.list("usr/share/rime-data")!!.toSet()
        val assetDefault = appAssets.open("usr/share/rime-data/default.yaml")
            .bufferedReader()
            .use { it.readText() }
        val runtimeDir = File(DataManager.dataDir, "usr/share/rime-data")
        val runtimeDefault = runtimeDir.resolve("default.yaml").readText()

        assertTrue(DataManager.getLoadedPlugins().none { it.name == "rime" })
        assertTrue(defaultSchemaStartsWithRimeIce(assetDefault))
        assertTrue(defaultSchemaStartsWithRimeIce(runtimeDefault))
        assertTrue(assetDefault.contains("schema: t9"))
        assertTrue(runtimeDefault.contains("schema: t9"))
        assertFalse(assetNames.contains("double_pinyin.schema.yaml"))
        assertTrue(assetNames.contains("t9.schema.yaml"))
        assertFalse(runtimeDir.resolve("double_pinyin.schema.yaml").exists())
        assertTrue(runtimeDir.resolve("t9.schema.yaml").isFile)
    }

    @Test
    fun readyBootstrapRestoresExclusiveRimeOnRestart() = runBlocking {
        val availableIme = FcitxTestEnvironment.fcitx.availableIme().map { it.uniqueName }
        assertTrue(
            "Expected available IMEs to include rime, got: $availableIme",
            "rime" in availableIme,
        )
        FcitxTestEnvironment.fcitx.setEnabledIme(emptyArray())
        assertEquals(emptyList<String>(), FcitxTestEnvironment.fcitx.enabledIme().map { it.uniqueName })

        FcitxTestEnvironment.restart()
        FcitxTestEnvironment.waitForEnabledIme("rime")

        assertEquals(listOf("rime"), FcitxTestEnvironment.fcitx.enabledIme().map { it.uniqueName })
    }

    @Test
    fun rimeIceTranslatesNiHao() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        delay(300)
        FcitxTestEnvironment.sendString("nihao", intervalMs = 100)
        FcitxTestEnvironment.selectCandidateText("你好")
        val commitString = FcitxTestEnvironment.receiveFirstCommitString()?.data?.text

        assertEquals("你好", commitString)
        FcitxTestEnvironment.fcitx.reset()
    }
}
