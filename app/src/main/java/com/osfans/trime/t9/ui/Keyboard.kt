package com.osfans.trime.t9.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 九宮格：三列三行數字鍵＋一條功能鍵列。
 * 主字（數字）與字母提示用 Column 上下排，絕不疊在同一個位置——
 * 之前兩顆 Text 都居中，視覺上就是「2」壓著「abc」糊成一團。
 */
data class KeySpec(val main: String, val legend: String = "", val code: String = main)

private val T9_ROWS = listOf(
    listOf(KeySpec("1"), KeySpec("2", "ABC"), KeySpec("3", "DEF")),
    listOf(KeySpec("4", "GHI"), KeySpec("5", "JKL"), KeySpec("6", "MNO")),
    listOf(KeySpec("7", "PQRS"), KeySpec("8", "TUV"), KeySpec("9", "WXYZ")),
)

private const val KEY_SPACE = "␣"
private const val KEY_BACK = "⌫"
private const val KEY_ENTER = "⏎"
private const val KEY_TOGGLE = "中英"

@Composable
fun NineKeyKeyboard(
    code: String,
    candidates: List<String>,
    asciiMode: Boolean,
    onKey: (String) -> Unit,
    onCandidate: (Int) -> Unit,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 底板必須是實色：IME 視窗本身透明，這裡不鋪就會把桌布透進來。
            .background(MaterialTheme.colorScheme.surface)
            // 背景先吃滿再讓出導覽列高度，否則手勢條那 24dp 會露出一條透明帶（就是「底部那兩條透明」）。
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        CandidateStrip(
            code = code,
            candidates = candidates,
            asciiMode = asciiMode,
            onCandidate = onCandidate,
            onToggleMode = onToggleMode,
        )
        // 數字區：左右留白比鍵距大一點，看起來才不像貼到螢幕邊。
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            T9_ROWS.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { spec ->
                        DigitKey(spec = spec, onClick = { onKey(spec.code) })
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FuncKey(label = KEY_TOGGLE, weight = 1f, onClick = onToggleMode)
                FuncKey(label = KEY_SPACE, weight = 2.2f, hint = "空格", onClick = { onKey(KEY_SPACE) })
                FuncKey(label = KEY_BACK, weight = 1f, onClick = { onKey(KEY_BACK) })
                FuncKey(label = KEY_ENTER, weight = 1f, accent = true, onClick = { onKey(KEY_ENTER) })
            }
        }
    }
}

@Composable
private fun CandidateStrip(
    code: String,
    candidates: List<String>,
    asciiMode: Boolean,
    onCandidate: (Int) -> Unit,
    onToggleMode: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            // 候選列用比底板亮一階的顏色，兩層才分得出來，也不會像懸空。
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (candidates.isEmpty()) {
            Text(
                text = if (code.isNotEmpty()) {
                    code
                } else if (asciiMode) {
                    "英數"
                } else {
                    "九鍵"
                },
                fontSize = 17.sp,
                fontWeight = if (code.isNotEmpty()) FontWeight.Medium else FontWeight.Normal,
                color = if (code.isNotEmpty()) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(candidates) { index, word ->
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { onCandidate(index) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = word,
                            fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(6.dp))
    }
}

@Composable
private fun RowScope.DigitKey(spec: KeySpec, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 按壓用彈簧而不是固定時長：M3E 的「手感」就是這個回彈，規格照 gboard 的按鍵縮放。
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.35f, stiffness = 900f),
        label = "keyScale",
    )
    Box(
        modifier = Modifier
            .weight(1f)
            .height(76.dp)
            .scale(scale)
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (pressed) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = spec.main,
                fontSize = 26.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 24.sp,
            )
            if (spec.legend.isNotEmpty()) {
                Text(
                    text = spec.legend,
                    fontSize = 12.sp,
                    letterSpacing = 1.4.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun RowScope.FuncKey(
    label: String,
    weight: Float,
    hint: String = "",
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.35f, stiffness = 900f),
        label = "funcScale",
    )
    Box(
        modifier = Modifier
            .weight(weight)
            .height(70.dp)
            .scale(scale)
            .clip(MaterialTheme.shapes.medium)
            .background(
                when {
                    accent && !pressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    pressed -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = hint.ifEmpty { label },
            fontSize = if (hint.isNotEmpty()) 16.sp else 24.sp,
            fontWeight = if (hint.isNotEmpty()) FontWeight.Normal else FontWeight.Medium,
            color = when {
                accent && !pressed -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
