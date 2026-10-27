package com.osfans.trime.t9.ui

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 九宮格：三列三行數字鍵＋一條功能鍵列。
 * 主字（數字）與字母提示用 Column 上下排，絕不疊在同一個位置——
 * 之前兩顆 Text 都居中，視覺上就是「2」壓著「abc」糊成一團。
 *
 * 手勢（2026-10 補齊，做法參考 lida0407 的傳統鍵盤實作）：
 *  - 數字鍵按住後往左／右滑＝該鍵第一個／最後一個字母；四字母鍵往上滑＝第二個字母；
 *    按住不放再鬆手＝中間那個字母（ABC→b、PQRS→r）。
 *  - 每挑一個字母就送進 rime 的拼音組字（先暫切 luna_pinyin），可連續挑字母組詞。
 *  - 滑動／按住時，鍵盤上方會出現氣泡即時顯示「放開後會送出的字母」。
 *  - 標點鍵：點＝，、長按＝。、上滑＝？、下滑＝！、左滑＝@、右滑＝、。
 */
data class KeySpec(
    val main: String,
    val legend: String = "",
    val code: String = main,
    /** 這個數字鍵上的字母，依序對應左滑／上滑（四字母鍵）／按住／右滑。 */
    val letters: String = "",
)

private val T9_ROWS = listOf(
    listOf(KeySpec("1"), KeySpec("2", "ABC", letters = "abc"), KeySpec("3", "DEF", letters = "def")),
    listOf(KeySpec("4", "GHI", letters = "ghi"), KeySpec("5", "JKL", letters = "jkl"), KeySpec("6", "MNO", letters = "mno")),
    listOf(KeySpec("7", "PQRS", letters = "pqrs"), KeySpec("8", "TUV", letters = "tuv"), KeySpec("9", "WXYZ", letters = "wxyz")),
)

private const val KEY_SPACE = "␣"
private const val KEY_BACK = "⌫"
private const val KEY_ENTER = "⏎"
private const val KEY_TOGGLE = "中英"

/** 標點鍵的六個出口，跟鍵面「，。？！」一致。 */
private const val PUNCT_CLICK = ","
private const val PUNCT_HOLD = "."
private const val PUNCT_UP = "?"
private const val PUNCT_DOWN = "!"
private const val PUNCT_LEFT = "@"
private const val PUNCT_RIGHT = "、"

/** 按住多久才算「長按選中間字母」。比系統長按短一點，九鍵打字才不會卡。 */
private const val HOLD_MS = 320L

/** 手指移動超過這個距離才算滑動（24dp，和傳統鍵盤的手勢門檻一致）。 */
private val SWIPE_TRAVEL = 24.dp

private enum class Dir { UP, DOWN, LEFT, RIGHT }

/** 手勢預覽氣泡要顯示什麼、放在哪個鍵的正上方。 */
private data class PreviewState(val text: String, val anchor: Rect)

@Composable
fun NineKeyKeyboard(
    code: String,
    candidates: List<String>,
    asciiMode: Boolean,
    onKey: (String) -> Unit,
    onCandidate: (Int) -> Unit,
    onToggleMode: () -> Unit,
    onPickLetter: (Char) -> Unit,
    onPunct: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var preview by remember { mutableStateOf<PreviewState?>(null) }
    var rootWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val swipePx = with(density) { SWIPE_TRAVEL.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { rootWidth = it.size.width }
            // 底板必須是實色：IME 視窗本身透明，這裡不鋪就會把桌布透進來。
            .background(MaterialTheme.colorScheme.surface)
            // 背景先吃滿再讓出導覽列高度，否則手勢條那 24dp 會露出一條透明帶（就是「底部那兩條透明」）。
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
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
                            DigitKey(
                                spec = spec,
                                swipePx = swipePx,
                                onClick = { onKey(spec.code) },
                                onPick = onPickLetter,
                                onPreview = { text, anchor -> preview = text?.let { PreviewState(it, anchor) } },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FuncKey(label = KEY_TOGGLE, weight = 1f, onClick = onToggleMode)
                    PunctKey(
                        weight = 1.15f,
                        swipePx = swipePx,
                        onPunct = onPunct,
                        onPreview = { text, anchor -> preview = text?.let { PreviewState(it, anchor) } },
                    )
                    FuncKey(label = KEY_SPACE, weight = 1.7f, hint = "空格", onClick = { onKey(KEY_SPACE) })
                    FuncKey(label = KEY_BACK, weight = 1f, onClick = { onKey(KEY_BACK) })
                    FuncKey(label = KEY_ENTER, weight = 1f, accent = true, onClick = { onKey(KEY_ENTER) })
                }
            }
        }

        // 氣泡放在所有按鍵之後：同一個 Box 裡後畫的蓋在上面。
        preview?.let { state ->
            if (rootWidth > 0) {
                val bubble = with(density) { 52.dp.toPx() }
                val gap = with(density) { 8.dp.toPx() }
                val edge = with(density) { 6.dp.toPx() }
                val x = (state.anchor.center.x - bubble / 2f).coerceIn(edge, (rootWidth - bubble - edge).coerceAtLeast(edge))
                val above = state.anchor.top - bubble - gap
                val y = if (above >= 0f) above else state.anchor.bottom + gap
                Box(
                    modifier = Modifier
                        .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                        .size(52.dp)
                        .zIndex(10f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.inverseSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }
        }
    }
}

/**
 * 按鍵手勢：按下後移動超過門檻就是滑動；按住超過 [HOLD_MS] 且沒移動就是長按；
 * 兩者都沒有才是普通點按。移動永遠比賽過長按——先按住再滑也算滑動（lida0407
 * 的 39e4610f 就是在修這個「按住後才移動」的情況）。
 */
private fun Modifier.t9Gesture(
    swipePx: Float,
    resolve: (dir: Dir?, held: Boolean) -> String?,
    onDown: () -> Unit,
    onPreview: (String?) -> Unit,
    onCommit: (dir: Dir?, held: Boolean) -> Unit,
): Modifier = pointerInput(swipePx) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        onDown()
        var dir: Dir? = null
        var held = false
        var lastPreview: String? = null

        fun refreshPreview() {
            val text = resolve(dir, held)
            if (text != lastPreview) {
                lastPreview = text
                onPreview(text)
            }
        }

        val holdDeadline = down.uptimeMillis + HOLD_MS
        while (true) {
            // 手指完全不動時不會有 MOVE 事件，長按只能用逾時來判定。
            val event = if (!held && dir == null) {
                val remaining = (holdDeadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)
                withTimeoutOrNull(remaining) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }
            if (event == null) {
                held = true
                refreshPreview()
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val up = change.changedToUpIgnoreConsumed()
            if (!up) {
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (max(abs(dx), abs(dy)) >= swipePx) {
                    dir = if (abs(dx) > abs(dy)) {
                        if (dx > 0) Dir.RIGHT else Dir.LEFT
                    } else {
                        if (dy > 0) Dir.DOWN else Dir.UP
                    }
                }
                refreshPreview()
                change.consume()
            } else {
                change.consume()
                break
            }
        }
        onPreview(null)
        onCommit(dir, held)
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
private fun RowScope.DigitKey(
    spec: KeySpec,
    swipePx: Float,
    onClick: () -> Unit,
    onPick: (Char) -> Unit,
    onPreview: (String?, Rect) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    // 按壓用彈簧而不是固定時長：M3E 的「手感」就是這個回彈，規格照 gboard 的按鍵縮放。
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.35f, stiffness = 900f),
        label = "keyScale",
    )

    fun letterFor(dir: Dir?, held: Boolean): String? {
        val letters = spec.letters
        if (letters.isEmpty()) return null
        val four = letters.length == 4
        return when (dir) {
            Dir.LEFT -> letters[0].toString()

            Dir.UP -> if (four) letters[1].toString() else null

            Dir.RIGHT -> letters.last().toString()

            Dir.DOWN -> null

            null -> when {
                held -> letters[if (four) 2 else 1].toString()
                else -> null
            }
        }
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .height(76.dp)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .scale(scale)
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (pressed) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            )
            .t9Gesture(
                swipePx = swipePx,
                resolve = { dir, held -> letterFor(dir, held) },
                onDown = { pressed = true },
                onPreview = { text -> onPreview(text, bounds) },
                onCommit = { dir, held ->
                    pressed = false
                    val letter = letterFor(dir, held)
                    if (letter != null) onPick(letter[0]) else onClick()
                },
            ),
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
        // 左右滑的字母直接印在鍵的兩側，不然沒人知道 GHI 鍵還能滑出 g／i。
        if (spec.letters.length >= 3) {
            Text(
                text = spec.letters[0].toString(),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp),
            )
            Text(
                text = spec.letters.last().toString(),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
            )
        }
    }
}

@Composable
private fun RowScope.PunctKey(
    weight: Float,
    swipePx: Float,
    onPunct: (String) -> Unit,
    onPreview: (String?, Rect) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.35f, stiffness = 900f),
        label = "punctScale",
    )

    fun punctFor(dir: Dir?, held: Boolean): String = when (dir) {
        Dir.UP -> PUNCT_UP
        Dir.DOWN -> PUNCT_DOWN
        Dir.LEFT -> PUNCT_LEFT
        Dir.RIGHT -> PUNCT_RIGHT
        null -> if (held) PUNCT_HOLD else PUNCT_CLICK
    }

    Box(
        modifier = Modifier
            .weight(weight)
            .height(70.dp)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .scale(scale)
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (pressed) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            )
            .t9Gesture(
                swipePx = swipePx,
                resolve = { dir, held -> punctFor(dir, held) },
                onDown = { pressed = true },
                onPreview = { text -> onPreview(text, bounds) },
                onCommit = { dir, held ->
                    pressed = false
                    onPunct(punctFor(dir, held))
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "，。？！",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
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
