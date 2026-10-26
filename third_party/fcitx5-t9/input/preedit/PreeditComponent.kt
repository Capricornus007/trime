/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.horizontalPadding

class PreeditComponent :
    UniqueComponent<PreeditComponent>(),
    Dependent,
    InputBroadcastReceiver,
    ManagedHandler by managedHandler() {

    private val context by manager.context()
    private val service by manager.inputMethodService()
    private val theme by manager.theme()
    private var t9PreviewJob: Job? = null

    val ui by lazy {
        val keyBorder = ThemeManager.prefs.keyBorder.getValue()
        val bkgColor =
            if (!keyBorder && theme is Theme.Builtin) theme.barColor else theme.backgroundColor
        PreeditUi(context, theme, setupTextView = {
            backgroundColor = bkgColor
            horizontalPadding = dp(8)
        }).apply {
            // TODO make it customizable
            root.alpha = 0.8f
            root.visibility = View.INVISIBLE
            root.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        startT9PreviewCollection()
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        t9PreviewJob?.cancel()
                        t9PreviewJob = null
                    }
                },
            )
        }
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        if (service.isT9ModeActive) return
        render(data)
    }

    private fun startT9PreviewCollection() {
        if (t9PreviewJob != null) return
        t9PreviewJob = service.lifecycleScope.launch {
            service.t9PreeditState.collect { state ->
                if (service.isT9ModeActive) {
                    render(state ?: FcitxEvent.InputPanelEvent.Data())
                }
            }
        }
    }

    private fun render(data: FcitxEvent.InputPanelEvent.Data) {
        ui.update(data)
        ui.root.visibility = if (ui.visible) View.VISIBLE else View.INVISIBLE
    }
}
