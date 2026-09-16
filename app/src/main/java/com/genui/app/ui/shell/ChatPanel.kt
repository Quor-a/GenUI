package com.genui.app.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genui.app.agent.ChatMsg
import com.genui.app.agent.ToolTrace
import com.genui.app.ui.theme.GenTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 标准 Agent 对话面板：把 [ChatMsg] 流渲染成气泡流。
 *
 * 视觉与交互约定（v0.16.1 打磨）：
 * - 每条消息入场：淡入 + 12dp 上滑（240ms，FastOutSlowIn）——流式滚动不再生硬；
 * - 角色头像列：助手 ✦ 琥珀圈 / 工具族专属图标圈 / 错误 ⚠；用户消息右对齐无头像；
 * - 工具调用是可展开卡片：状态图标（运行中=旋转 loader / ✓ / ✗）+ 族图标 + 工具名
 *   + 耗时徽标 + 展开箭头；展开看参数与结果摘要（等宽字体，限行防爆屏）；
 * - 助手流式光标 ▌ 呼吸闪烁；首 token 前的空泡渲染三点跳动（思考中）；
 * - 旧持久化记录（无 ToolTrace 元数据）按文本格式兼容渲染。
 */
fun hhmm(ts: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ts))

/** 工具族 → 专属图标 */
internal fun toolIcon(name: String): String = when {
    name.startsWith("web_search") || name.startsWith("news_search") -> "\uD83D\uDD0D"
    name.startsWith("web_fetch") -> "\uD83C\uDF10"
    name.startsWith("community_search") -> "\uD83D\uDCAC"
    name.startsWith("github") -> "\uD83D\uDCDB"
    name.startsWith("memory") -> "\uD83D\uDCBE"
    name.startsWith("clipboard") -> "\uD83D\uDCCB"
    name.startsWith("notify") -> "\uD83D\uDD14"
    name.startsWith("tts") -> "\uD83D\uDD0A"
    name.startsWith("haptics") -> "\uD83D\uDCF3"
    name.startsWith("device") || name.startsWith("system_status") -> "\uD83D\uDCF1"
    name.startsWith("time") -> "\uD83D\uDD51"
    name.startsWith("file") -> "\uD83D\uDCC4"
    name.startsWith("calendar") -> "\uD83D\uDCC5"
    name.startsWith("location") -> "\uD83D\uDCCD"
    name.startsWith("mcp_") -> "\uD83E\uDE9B"
    name.startsWith("share") -> "\u2197"
    name.startsWith("flashlight") -> "\uD83D\uDD26"
    name.startsWith("contacts") -> "\uD83D\uDC65"
    name.startsWith("sms") -> "\uD83D\uDCE9"
    name.startsWith("call") -> "\uD83D\uDCDE"
    name.startsWith("alarm") || name.startsWith("open_url") -> "\uD83D\uDD17"
    else -> "\u2699"
}

/** 一次性入场动画包装：淡入 + 上滑 */
@Composable
private fun Appear(content: @Composable () -> Unit) {
    val v = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        v.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
    }
    Box(Modifier.graphicsLayer {
        alpha = v.value
        translationY = (1f - v.value) * 36f   // ~12dp
    }) { content() }
}

/** 旋转 loader（工具运行中） */
@Composable
private fun Loader(size: Int = 12, color: Color) {
    val t = rememberInfiniteTransition(label = "loader")
    val r by t.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "rot")
    Text("◠", color = color, fontSize = size.sp, modifier = Modifier.graphicsLayer { rotationZ = r })
}

/** 呼吸闪烁的光标（流式输出中） */
@Composable
private fun BlinkCursor() {
    val t = rememberInfiniteTransition(label = "cursor")
    val a by t.animateFloat(1f, 0.15f, infiniteRepeatable(tween(480, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Text("▌", color = GenTheme.Amber, fontSize = 13.sp, modifier = Modifier.graphicsLayer { alpha = a })
}

/** 思考中三点跳动（首个 token 到达前） */
@Composable
private fun ThinkingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "p")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val phase = ((p * 3f - i) % 3f + 3f) % 3f
            val lift = if (phase < 1f) (1f - phase) else 0f   // 依次上浮
            Text("●", color = GenTheme.Amber.copy(alpha = 0.35f + 0.65f * lift), fontSize = 9.sp,
                modifier = Modifier.graphicsLayer { translationY = -lift * 7f })
        }
    }
}

@Composable
fun ChatPanel(
    messages: List<ChatMsg>,
    modifier: Modifier = Modifier,
    onCardAction: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()
    // 自动跟随：消息数量变化时滚到最后一条；用户向上翻页（不在底部）时不打扰
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= messages.size - 2) {
                listState.animateScrollToItem(messages.lastIndex)
            }
        }
    }

    if (messages.isEmpty()) {
        Column(
            modifier.fillMaxSize().padding(horizontal = 30.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("◎", color = GenTheme.Amber, fontSize = 26.sp, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(14.dp))
            Text("跟 AI 说什么都行", color = GenTheme.Text, fontSize = 15.sp, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(8.dp))
            Text(
                "它会调工具查资料、跑搜索、读时间、记备忘 ——\n比如「今天有什么值得关注的新闻？」",
                color = GenTheme.Dim, fontSize = 12.sp, lineHeight = 19.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(messages, key = { it.id }) { m ->
            Appear {
                Column {
                    when (m.role) {
                        "user" -> Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(hhmm(m.ts), color = GenTheme.Dim.copy(alpha = .6f), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.width(5.dp))
                            UserBubble(m.text)
                        }
                        "tool" -> Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.Top
                        ) {
                            Avatar(toolIcon(m.tool?.name ?: legacyToolName(m.text)),
                                bg = GenTheme.Amber.copy(alpha = 0.14f))
                            Spacer(Modifier.width(6.dp))
                            ToolCard(m, Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(5.dp))
                            Text(hhmm(m.ts), color = GenTheme.Dim.copy(alpha = .6f), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.Bottom))
                        }
                        "error" -> Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.Top
                        ) {
                            Avatar("⚠", bg = GenTheme.Red.copy(alpha = 0.15f))
                            Spacer(Modifier.width(6.dp))
                            ErrorBubble(m.text)
                            Spacer(Modifier.width(5.dp))
                            Text(hhmm(m.ts), color = GenTheme.Dim.copy(alpha = .6f), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.Bottom))
                        }
                        else -> Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.Top
                        ) {
                            Avatar("✦", bg = GenTheme.Amber.copy(alpha = 0.18f), fg = GenTheme.Amber)
                            Spacer(Modifier.width(6.dp))
                            AssistantBubble(m.text, m.done, onCardAction, Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(5.dp))
                            Text(hhmm(m.ts), color = GenTheme.Dim.copy(alpha = .6f), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.Bottom))
                        }
                    }
                }
            }
        }
    }
}

private fun legacyToolName(text: String): String =
    text.removePrefix("✅ ").removePrefix("❌ ").removePrefix("⚙ ").substringBefore('·').substringBefore('(').trim()

/** 20dp 角色头像圈 */
@Composable
private fun Avatar(glyph: String, bg: Color, fg: Color = GenTheme.Text) {
    Box(
        Modifier.size(22.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center
    ) { Text(glyph, fontSize = 11.sp, color = fg) }
}

@Composable
private fun UserBubble(text: String) {
    Text(
        text = text,
        color = Color.White, fontSize = 14.sp, lineHeight = 21.sp,
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(12.dp, 12.dp, 3.dp, 12.dp))
            .background(GenTheme.Amber)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    )
}

@Composable
private fun AssistantBubble(text: String, done: Boolean, onCardAction: (String) -> Unit = {},
                            modifier: Modifier = Modifier) {
    Column(
        modifier
            .widthIn(max = 310.dp)
            .clip(RoundedCornerShape(12.dp, 12.dp, 12.dp, 3.dp))
            .background(GenTheme.Panel)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        if (text.isBlank() && !done) {
            ThinkingDots()
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChatCards.split(text).forEach { (isCard, seg) ->
                    if (isCard) CardInline(seg)
                    else RichText(
                        text = seg,
                        baseStyle = TextStyle(fontSize = 14.sp, color = GenTheme.Text, lineHeight = 21.sp),
                    )
                }
            }
            if (!done) {
                Spacer(Modifier.height(3.dp))
                BlinkCursor()
            }
        }
    }
}

@Composable
private fun ErrorBubble(text: String) {
    Text(
        text = text,
        color = GenTheme.Red, fontSize = 13.sp, lineHeight = 19.sp,
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(GenTheme.Red.copy(alpha = 0.08f))
            .border(0.5.dp, GenTheme.Red.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

// ---------- 工具调用卡片（可展开，状态动画） ----------

@Composable
private fun ToolCard(m: ChatMsg, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val t: ToolTrace? = m.tool
    // 兼容旧持久化记录：从文本解析状态与工具名
    val legacy = t == null
    val name = t?.name ?: legacyToolName(m.text)
    val running = (t != null && t.ms < 0L)
    val isErr = (t?.isError == true) || m.text.startsWith("❌")
    val denied = t?.denied == true
    val ms = t?.ms ?: -1L
    val summary = if (legacy) m.text.removePrefix("✅ ").removePrefix("❌ ")
        else m.text.substringAfter('\n', "").ifBlank { m.text }

    val accent = when {
        denied || isErr -> GenTheme.Red
        running -> GenTheme.Amber
        else -> GenTheme.Green
    }
    val statusLabel = when {
        running -> "运行中"
        denied -> "已拒绝"
        isErr -> "失败"
        else -> "完成"
    }

    Column(
        modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(GenTheme.Screen)
            .border(0.5.dp, accent.copy(alpha = if (running) 0.8f else 0.4f), RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        // —— 卡头：状态 + 图标 + 名字 + 耗时 + 箭头 ——
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) Loader(color = GenTheme.Amber)
            else Text(if (isErr || denied) "✗" else "✓",
                color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(toolIcon(name), fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = GenTheme.Text, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t?.brief?.isNotBlank() == true) {
                    Text(t.brief, color = GenTheme.Dim, fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(statusLabel, color = accent, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace)
            if (ms > 0) {
                Spacer(Modifier.width(4.dp))
                Text(fmtMsBadge(ms), color = GenTheme.Dim, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.width(4.dp))
            Text(if (expanded) "▾" else "▸", color = GenTheme.Dim, fontSize = 10.sp)
        }
        // —— 展开区：结果摘要（等宽，限行） ——
        AnimatedVisibility(expanded) {
            Column {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(GenTheme.Panel)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        summary.ifBlank { "（无输出）" },
                        color = GenTheme.Text.copy(alpha = 0.85f),
                        fontSize = 10.5.sp, lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 12, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (!legacy && !running && t?.brief?.isNotBlank() == true) {
                    Spacer(Modifier.height(4.dp))
                    Text("参数：${t.brief}", color = GenTheme.Dim, fontSize = 9.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun fmtMsBadge(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> String.format(java.util.Locale.CHINA, "%.1fs", ms / 1000.0)
    else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}

// ---------- 轻量 Markdown ----------

/** 按代码围栏切块：(是否代码块, 内容) 交替 */
private fun splitMd(text: String): List<Pair<Boolean, String>> {
    val out = mutableListOf<Pair<Boolean, String>>()
    val re = Regex("(?s)```[a-zA-Z]*\\n?(.*?)```")
    var last = 0
    for (m in re.findAll(text)) {
        if (m.range.first > last) out.add(false to text.substring(last, m.range.first))
        out.add(true to m.groupValues[1].removeSuffix("\n"))
        last = m.range.last + 1
    }
    if (last < text.length) out.add(false to text.substring(last))
    return out
}

/** 行内 Markdown：**粗体** / `行内代码` → AnnotatedString */
private fun inlineMd(s: String): AnnotatedString = buildAnnotatedString {
    val re = Regex("\\*\\*(.+?)\\*\\*|`([^`\n]+?)`")
    var last = 0
    for (m in re.findAll(s)) {
        if (m.range.first > last) append(s.substring(last, m.range.first))
        val b = m.groupValues[1]
        val c = m.groupValues[2]
        if (b != null) {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append(b)
            pop()
        } else if (c != null) {
            pushStyle(SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                background = Color(0x14000000)
            ))
            append(c)
            pop()
        }
        last = m.range.last + 1
    }
    if (last < s.length) append(s.substring(last))
}
