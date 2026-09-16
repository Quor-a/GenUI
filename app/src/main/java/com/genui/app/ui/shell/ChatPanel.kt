package com.genui.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genui.app.agent.ChatMsg
import com.genui.app.ui.theme.GenTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 标准 Agent 对话面板：把 [ChatMsg] 流渲染成气泡流。
 *
 * 细节约定：
 * - 新消息到达自动滚到底（用户上滑翻历史时不打扰——仅当已接近底部才跟随）；
 * - 每条气泡带 HH:mm 时间戳（monospace 小字，贴边不抢戏）；
 * - 工具调用是等宽"终端卡"：⚙运行中 / ✅完成（绿）/ ❌失败（红），内容人类可读摘要；
 * - 助手气泡渲染轻量 Markdown：代码围栏、行内代码、**粗体**、标题、列表；
 * - 错误消息有独立红色样式并落在会话里（失败轮不再死寂）；
 *  * - 空会话给一句引导，避免用户面对一屏空白不知道能干什么。
 */
fun hhmm(ts: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ts))

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
                        verticalAlignment = Alignment.Bottom
                    ) {
                        ToolBubble(m.text)
                        Spacer(Modifier.width(5.dp))
                        Text(hhmm(m.ts), color = GenTheme.Dim.copy(alpha = .6f), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    "error" -> Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        ErrorBubble(m.text)
                        Spacer(Modifier.width(5.dp))
                        Text(hhmm(m.ts), color = GenTheme.Dim.copy(alpha = .6f), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    else -> Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        AssistantBubble(m.text, m.done, onCardAction)
                        Spacer(Modifier.width(5.dp))
                        Text(hhmm(m.ts), color = GenTheme.Dim.copy(alpha = .6f), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(0.86f), horizontalArrangement = Arrangement.End) {
        Text(
            text = text,
            color = Color.White, fontSize = 14.sp, lineHeight = 21.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp, 12.dp, 3.dp, 12.dp))
                .background(GenTheme.Amber)
                .padding(horizontal = 12.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun AssistantBubble(text: String, done: Boolean, onCardAction: (String) -> Unit = {}) {
    Column(
        Modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(12.dp, 12.dp, 12.dp, 3.dp))
            .background(GenTheme.Panel)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        if (text.isBlank() && !done) {
            Text("…", color = GenTheme.Dim, fontSize = 14.sp)
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
                Text("▌", color = GenTheme.Amber, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ErrorBubble(text: String) {
    Text(
        text = "⚠ $text",
        color = GenTheme.Red, fontSize = 13.sp, lineHeight = 19.sp,
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(10.dp))
            .background(GenTheme.Red.copy(alpha = 0.08f))
            .border(0.5.dp, GenTheme.Red.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

@Composable
private fun ToolBubble(text: String) {
    val tint = when {
        text.startsWith("❌") -> GenTheme.Red
        text.startsWith("✅") -> GenTheme.Green
        else -> GenTheme.Dim
    }
        Text(
            text = text,
            color = tint, fontSize = 11.sp, lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(8.dp))
                .background(GenTheme.Screen)
                .border(0.5.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
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

/** 正文预处理：# 标题 → 粗体；- 列表 → 圆点 */
private fun mdPreprocess(s: String): String = s.lineSequence().joinToString("\n") { l ->
    when {
        l.matches(Regex("#{1,4} .*")) -> "**" + l.substringAfter(' ').trim() + "**"
        Regex("^\\s*[-*] ").containsMatchIn(l) -> l.replaceFirst(Regex("^\\s*[-*] "), "  • ")
        else -> l
    }
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
