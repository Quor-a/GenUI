package com.genui.app.agent

import com.genui.app.agent.tools.BuiltinTools
import com.genui.app.llm.LLMClient
import com.genui.app.store.GenStore
import com.genui.app.store.ModelProvider
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 标准 Agent 对话会话：多轮聊天、流式气泡、可调用工具（搜索/文件/记忆/设备能力…），
 * 但不生成界面——答案以对话消息呈现。
 *
 * 设计：与 [AgentLoop] 共用同一套 [BuiltinTools] + [LLMClient]，工具执行逻辑在此复刻，
 * 不重复实现。区别仅在"产出形态"——[AgentLoop] 把结果喂给 WebView 画界面，
 * [ChatSession] 把结果以消息气泡流式呈现。
 */
data class ToolTrace(
    val name: String,
    val brief: String,
    val ms: Long = -1,              // -1 = 进行中
    val isError: Boolean = false,
    val denied: Boolean = false,
)

data class ChatMsg(
    val id: String,
    val role: String,   // "user" | "assistant" | "tool" | "error"
    val text: String = "",
    val done: Boolean = false,
    val ts: Long = System.currentTimeMillis(),
    val tool: ToolTrace? = null     // 工具调用结构化元数据（不持久化，运行期渲染用）
)

class ChatSession(
    private val store: GenStore,
    private val onUserMsg: (String) -> Unit,
    private val onAssistantStart: (id: String) -> Unit,
    private val onAssistantDelta: (id: String, delta: String) -> Unit,
    private val onAssistantDone: (id: String) -> Unit,
    private val onToolStart: (id: String, name: String, brief: String) -> Unit,
    private val onToolResult: (id: String, result: String, ms: Long, ok: Boolean) -> Unit,
    private val onThinking: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onAskPermission: suspend (tool: String, brief: String, level: Int) -> Boolean,
    /** 对话里生成了完整 HTML 界面 → 交给画布渲染（GenUI 渲染支持） */
    private val onUiDetected: (html: String) -> Unit = {}
) {
    @Volatile var cancelled = false
        private set
    fun cancel() { cancelled = true; llm.cancel() }

    private val tools = BuiltinTools(store.context())
    private val llm = LLMClient()
    /** 对话上下文（OpenAI messages 数组），跨多轮保留 */
    private val messages = JSONArray()

    private fun newId(): String = UUID.randomUUID().toString().take(8)

    /** 最近一条用户消息（UI 意图判断用） */
    private fun lastUserText(): String {
        for (i in messages.length() - 1 downTo 0) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") == "user") return m.optString("content")
        }
        return ""
    }

    /** UI 意图关键词：与 AgentLoop.isUiIntent 同一份清单 */
    private fun isUiIntent(prompt: String): Boolean {
        val nouns = listOf(
            "页面", "网页", "界面", "应用", "小程序", "工具", "仪表盘", "看板", "面板",
            "卡片", "图表", "表单", "计算器", "游戏", "Dashboard", "dashboard",
        )
        val selfShow = listOf(
            "你自己", "自我介绍", "介绍自己", "你是谁", "介绍下你", "介绍一下你",
            "你的能力", "你能做什么", "展示一下你",
        )
        return nouns.any { prompt.contains(it, ignoreCase = true) } ||
            selfShow.any { prompt.contains(it) }
    }

    /** 从回答里提取完整 HTML 文档（```html 围栏或裸 <!DOCTYPE>…</html>） */
    private fun extractHtml(text: String): String? {
        val fence = Regex("(?is)```html\\s*\\n(.*?)```").find(text)
        if (fence != null) return fence.groupValues[1].trim()
        val doc = Regex("(?is)(<!DOCTYPE html>.*</html>)").find(text)
        return doc?.groupValues?.get(1)
    }

    /** 回答含完整界面时：气泡只留提示，HTML 走画布渲染 */
    private fun deliverMaybeUi(aid: String, text: String) {
        val html = extractHtml(text)
        if (html == null) {
            onAssistantDelta(aid, text)
        } else {
            val prose = text.replace(Regex("(?is)```html\\s*\\n.*?```"), "")
                .replace(Regex("(?is)<!DOCTYPE html>.*</html>"), "").trim()
            if (prose.isNotBlank()) onAssistantDelta(aid, prose + "\n\n")
            onAssistantDelta(aid, "🎛 已生成界面（${html.length / 1024}KB），正在画布显示")
            onUiDetected(html)
        }
    }

    /** 长对话防爆上下文：决策轮只带最近 30 条（system 恒在首条） */
    private fun capHistory(): JSONArray {
        if (messages.length() <= 30) return messages
        val out = JSONArray()
        out.put(messages.getJSONObject(0)) // system
        for (i in messages.length() - 29 until messages.length()) out.put(messages.getJSONObject(i))
        return out
    }

    /** 对话模式系统提示：灵魂名 + 记忆索引 + 真实时间 + 对话守则 */
    private fun chatSystem(): String {
        val ctx = store.context()
        val soulName = runCatching { SoulStore(ctx).load()?.name }.getOrNull() ?: "助手"
        val mem = runCatching { AgentMemory(ctx).indexForPrompt() }.getOrDefault("")
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", java.util.Locale.CHINA)
            .format(java.util.Date())
        return buildString {
            append("你是 ").append(soulName).append("，GenUI 的常驻对话助手。\n")
            append("# 你在哪个模式、该干什么\n")
            append("GenUI 是一个把 AI 回复变成真实可用界面的 Android 应用，有两个界面：\n")
            append("- 【Agent 对话框】（当前）：用户在这里和你聊天、提问、要数据。你直接回答、" +
                "出数据卡（```card 围栏）、用户要界面时在回复末尾给 ```html 完整文档（端上渲染到画布）。\n")
            append("- 【GenUI 画布】（另一个模式）：用户在那里给生成指令，产出整页界面——不归本会话管。\n")
            append("当前你的职责只有对话：回答、取数、出卡。\n")
            append("# 现在时间\n").append(now).append("\n")
            if (mem.isNotBlank()) append("\n# 记忆库索引（用户相关的长期记忆，细节用 memory_read 查）\n").append(mem).append("\n")
            append("\n# 对话守则\n")
            append("- 直接回答问题；不要输出 HTML 文档，用户明确要代码片段时才给代码。\n")
            append("- 需要实时信息、设备能力或读写记忆时调用工具；调用前用一句话说明目的。\n")
            append("- 回答用 Markdown：要点用列表，命令/术语用行内代码。\n")
            append("- 工具失败就如实说明，绝不编造数据。\n")
            append("- 用户要界面/页面/可视化/小工具时：禁止只给文字描述——先按需调用工具取真实数据，" +
                "然后回复末尾用 ```html 围栏给出完整 HTML 文档（脚本引用 /assets/runtimes/ 本地运行时，" +
                "数据用工具返回的真值填充），端上会把它渲染到画布并通知用户。\n")
            append("- 结构化数据用卡片围栏输出（气泡下方渲染真实数据卡）：类型 stat(指标" +
                "title/value/delta)、progress(title/percent)、list(title/items[{text}])、" +
                "bar(title/data[{label,value}])、gauge(title/percent)、line(title/data[{value}])、" +
                "kv(title/items[{k,v}])。\n")
            append("- ★ 自写可视化卡：先 MoBridge.ui.component('名字', 模板组件树) 注册" +
                "（模板 {{prop}} 占位），然后回复里 ```card {\"use\":\"名字\",\"属性\":\"值\"}``` " +
                "即渲染你自己的设计；改版式重新注册同名即重渲染，弹窗/卡片全端生效。")
        }
    }

    /**
     * 从持久化对话记录重建模型上下文（单一事实源 = GenStore 的 chatLog）。
     * 修复：ChatSession.messages 是内存态——重启/Activity 重建后 UI 有历史、
     * 模型零记忆（"每次对话都是新的对话"）。每轮 chat() 前重灌，自愈。
     * 保持 messages[0]=system；截最近 20 条、单条 1200 字符；跳过空文本。
     */
    fun restoreHistory(items: List<Pair<String, String>>) {
        val real = items.filter { it.second.isNotBlank() }
            .map { (r, t) -> r to if (t.length > 1200) t.take(1200) + "…(截断)" else t }
            .takeLast(20)
        if (real.isEmpty()) return
        val arr = JSONArray()
        arr.put(JSONObject().put("role", "system").put("content", chatSystem()))
        real.forEach { (role, text) ->
            arr.put(JSONObject().put(
                "role", if (role == "assistant") "assistant" else "user"
            ).put("content", text))
        }
        while (messages.length() > 0) messages.remove(0)
        for (i in 0 until arr.length()) messages.put(arr.getJSONObject(i))
    }

    /** 把用户本轮输入加入上下文并通知 UI */
    fun addUser(text: String) {
        messages.put(JSONObject().put("role", "user").put("content", text))
        onUserMsg(text)
    }

    /**
     * 跑一轮对话：循环决策（带 function calling）→ 调用工具 → 直到模型不再调工具，
     * 再用流式接口把最终回答呈现成气泡。工具循环本身用非流式 chatOnce（带工具必须 OpenAI 协议），
     * 最终回答用 chatStream 流式输出。
     */
    suspend fun run(provider: ModelProvider) {
        try {
            // cancel() 之后 session 会被复用（ensureSession 是单例），不复位就永远起不来
            cancelled = false
            // 注入系统提示（只在首轮插一次）：灵魂 + 记忆 + 时间 + 对话守则
            if (messages.length() == 0 || messages.optJSONObject(0)?.optString("role") != "system") {
                val sys = JSONArray().put(JSONObject().put("role", "system").put("content", chatSystem()))
                for (i in 0 until sys.length()) messages.put(sys.getJSONObject(i))
                // 把 system 挪到最前：新 put 的在尾部
                val arr = JSONArray()
                arr.put(messages.getJSONObject(messages.length() - 1))
                for (i in 0 until messages.length() - 1) arr.put(messages.getJSONObject(i))
                while (messages.length() > 0) messages.remove(0)
                for (i in 0 until arr.length()) messages.put(arr.getJSONObject(i))
            }
            while (!cancelled) {
                onThinking("思考中…")
                val decls = tools.declarations()
                val assistant = llm.chatOnce(provider, capHistory(), decls)
                // 决策轮的推理过程也给用户看见（推理模型 reasoning_content / 思考标签）
                runCatching {
                    val th = assistant.optString("reasoning_content").ifBlank {
                        assistant.optString("reasoning")
                    }
                    if (th.isNotBlank()) onThinking("💭 " + th.takeLast(140).replace("\n", " "))
                }
                val calls = assistant.optJSONArray("tool_calls")
                val content = assistant.optString("content").orEmpty()

                if (calls == null || calls.length() == 0) {
                    // 快车道：决策轮若已给出有内容的完整回答（而非"我再想想"式的短句），
                    // 直接呈现，省掉一次重流式请求，且不丢模型的原话
                    if (content.length >= 40 && !content.startsWith("NO_TOOLS")) {
                        val aid = newId()
                        onAssistantStart(aid)
                        deliverMaybeUi(aid, content)
                        onAssistantDone(aid)
                        messages.put(JSONObject().put("role", "assistant").put("content", content))
                        // 用户要界面但模型只给了文字描述 → 自动跟进一次，逼出 ```html 真文档
                        if (isUiIntent(lastUserText()) && extractHtml(content) == null) {
                            messages.put(JSONObject().put("role", "user").put("content",
                                "（自动跟进）你刚才只给了文字描述，没有给界面。现在直接输出完整 HTML 文档：" +
                                    "需要数据就先调用工具取真实数据，然后以 ```html 围栏给出整页代码，" +
                                    "数据必须来自工具结果，禁止编造假数据。"))
                            val aid2 = newId()
                            onAssistantStart(aid2)
                            streamAnswer(provider, aid2)
                            return
                        }
                        return
                    }
                    // 否则走流式重答
                    val aid = newId()
                    onAssistantStart(aid)
                    streamAnswer(provider, aid)
                    return
                }

                // 有工具调用：写回 assistant 消息（含 tool_calls），逐个执行
                messages.put(JSONObject()
                    .put("role", "assistant")
                    .put("content", if (content.isBlank()) JSONObject.NULL else content)
                    .put("tool_calls", calls))
                for (i in 0 until calls.length()) {
                    if (cancelled) return
                    val call = calls.getJSONObject(i)
                    val fn = call.optJSONObject("function") ?: continue
                    val name = fn.optString("name")
                    val callId = call.optString("id", "call_${i}")
                    val args = runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }
                        .getOrDefault(JSONObject())
                    val brief = args.keys().asSequence().take(2)
                        .joinToString(" ") { k -> "$k=${args.opt(k)?.toString()?.take(24)}" }

                    val tid = newId()
                    val t0 = System.currentTimeMillis()
                    onToolStart(tid, name, brief)
                    onThinking("调用工具：$name $brief")

                    val verdict = tools.gate.authorize(tools.gateFor(name), brief) { t, b, l ->
                        onAskPermission(t, b, l)
                    }
                    if (verdict != null) {
                        onToolResult(tid, "已拒绝：$verdict", -1L, true)
                        messages.put(JSONObject().put("role", "tool")
                            .put("tool_call_id", callId)
                            .put("content", JSONObject().put("denied", verdict).toString()))
                        continue
                    }
                    val res = runCatching { tools.execute(name, args) }
                        .getOrDefault(JSONObject().put("error", "工具执行失败"))
                    val cost = System.currentTimeMillis() - t0
                    val ok = !res.has("error") && !res.has("denied")
                    // 给人看摘要（ToolSummarize），给模型看全量 JSON——此前气泡里怼 400 字
                    // 原始 JSON，用户根本读不了
                    onToolResult(tid,
                        (if (ok) "✅ " else "❌ ") + "$name · " + ToolSummarize.fmtMs(cost) +
                            "\n" + ToolSummarize.summarize(name, res), cost, !ok)
                    messages.put(JSONObject().put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", res.toString()))
                }
            }
        } catch (e: Exception) {
            if (!cancelled) onError(e.message ?: "对话失败")
        }
    }

    /** 用流式接口生成最终回答（复用已经累积的完整上下文） */
    private suspend fun streamAnswer(provider: ModelProvider, aid: String) {
        val msgs = JSONArray()
        val all = capHistory()
        for (i in 0 until all.length()) {
            val m = all.getJSONObject(i)
            if (m.optString("role") == "system") continue
            msgs.put(m)
        }
        val thinkBuf = StringBuilder()
        val answerBuf = StringBuilder()
        llm.chatStream(
            provider = provider,
            system = chatSystem(),
            messages = msgs,
            onChunk = { d ->
                answerBuf.append(d)
                onAssistantDelta(aid, d)
            },
            onDone = { _ ->
                onAssistantDone(aid)
                // 关键：把本轮回答写回上下文——此前模型对上一轮自己说过的话毫无记忆
                if (answerBuf.isNotBlank()) {
                    messages.put(JSONObject().put("role", "assistant")
                        .put("content", answerBuf.toString()))
                    // 对话里生成的界面同样送画布（GenUI 渲染支持）
                    runCatching { deliverMaybeUi(aid, answerBuf.toString()) }
                }
            },
            onError = { msg -> onError(msg) },
            onReasoning = { chunk ->
                // 思考过程实时展示：滚动摘录最近 ~140 字，完整思路留在推理模型侧
                thinkBuf.append(chunk)
                val tail = thinkBuf.toString().takeLast(140).replace("\n", " ")
                onThinking("💭 $tail")
            }
        )
    }
}
