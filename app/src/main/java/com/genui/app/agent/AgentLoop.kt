package com.genui.app.agent

import android.content.Context
import kotlinx.coroutines.launch
import com.genui.app.agent.tools.BuiltinTools
import com.genui.app.llm.LLMClient
import com.genui.app.llm.Prompts
import com.genui.app.store.GenStore
import com.genui.app.store.ModelProvider
import com.genui.app.store.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Agent 主循环 —— ZorvAI 式"会想、会查、会记、会动手"的智能体，输出仍是 A2UI 界面。
 *
 * 两阶段设计（体验与可靠性平衡）：
 *  1) 决策轮（非流式，带 tools，仅 OpenAI 兼容协议）：模型决定查资料/读记忆/用设备能力，
 *     工具结果回填上下文，模型自行决定查几轮（不再被固定数字腰斩，靠死循环检测兜底）；若模型直接交出 HTML 则跳过渲染轮；
 *  2) 渲染轮（流式，不带 tools）：全部上下文（含工具结果）交给模型逐字写出 HTML → 画布。
 *
 * 上下文：灵魂卡（AI 自动孵化的人格）+ 记忆库索引 + 最近界面历史 + 本条指令。
 */
class AgentLoop(
    context: Context,
    private val store: GenStore,
    private val onStatus: (String) -> Unit,        // 状态行（一行摘要，保留兼容）
    private val onHtmlDelta: (String) -> Unit,     // 渲染轮流式增量
    private val onAskPermission: suspend (tool: String, brief: String, level: Int) -> Boolean = { _, _, _ -> true },
    /** 结构化事件流：思考/决策/工具全过程。UI 用它画时间线。 */
    private val onEvent: (AgentEvent) -> Unit = {},
    /** 问答直答：用户在生成模式里提问/闲聊时，Agent 以文字回答（不渲染界面） */
    private val onTextAnswer: (String) -> Unit = {},
    /** 小程序画布直通：create_miniapp 成功后把 appId 交给画布层内嵌实时渲染（不跳独立程序） */
    val onMiniAppCanvas: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext
    val tools = BuiltinTools(appContext)
    private val soulStore = SoulStore(appContext)
    private val llm = LLMClient()
    /** 快速模型通道（专项模型分派里 fastProviderId 的真实落点） */
    private val fast = com.genui.app.llm.FastClient(store)

    @Volatile var cancelled = false
    /** 服务端按 max_tokens 截断（finish_reason=length）——GenScaffold 检测后自动续写 */
    @Volatile var lengthCutoff = false
    /** 本轮已成功创建的小程序 id（create_miniapp 成功后记录；决策轮见 MINIAPP_DELIVERED 即交付，不再画 HTML） */
    private var miniAppDelivered: String? = null
        private set
    fun cancel() { cancelled = true; llm.cancel() }

    /** 渲染轮思考缓冲：onReasoning 每 token 一发，直接进时间线会刷出几百条单字碎片 */
    private val renderThinkBuf = StringBuilder()
    private var lastThinkEmit = 0L

    /** 统计本次生成的工具调用次数（供 Finished 事件） */
    private var toolCallCount = 0
    private val startedAt = System.currentTimeMillis()

    suspend fun run(
        provider: ModelProvider,
        userPrompt: String,
        seedHtml: String? = null,
        onDone: (html: String, title: String) -> Unit,
        onDirectHtml: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        cancelled = false
        toolCallCount = 0
        val failedTools = mutableListOf<String>()   // 本轮失败/被拒工具（渲染前点名用）
        onEvent(AgentEvent.Started(userPrompt, provider.name, provider.model))
        val logCtx = store.context()
        AgentLog.append(logCtx, "画布 · " + userPrompt.take(60).replace("\n", " "),
            listOf("模型 ${provider.name} / ${provider.model}" +
                (if (seedHtml != null) "（续写 ${seedHtml.length / 1024}KB）" else "")))

        val soul = soulStore.load() ?: soulStore.fallback
        val system = Prompts.systemWith(soul, tools.memory.indexForPrompt(), recentHistory())
        // 决策轮专用提示：此阶段禁止写 HTML，只决定工具调用；否则模型会在决策轮
        // 就开始输出整页 HTML（非流式、耗时且被丢弃，导致画布空白）
        val decisionSystem = system +
            "\n\n# 你在哪个模式、该干什么（先读这个）\n" +
            "你是 GenUI 的智能体——GenUI 是一个把 AI 回复变成真实可用界面的 Android 应用，" +
            "它有两个界面：【Agent 对话框】（聊天/问答/出数据卡）和【GenUI 画布】（生成完整可交互页面）。\n" +
            "你当前在【GenUI 画布模式】：用户的这条输入就是**生成指令**，你的产出是一整页" +
            "真实可交互的 HTML 界面——不是聊天回复。此处没有寒暄：规划→取真实数据→渲染。\n" +
            "（Agent 对话框由另一个会话负责，与本模式无关。）\n" +
            "\n# 当前阶段：规划 + 工具决策（重要）\n" +
            "输出顺序必须是：先 [PLAN] 规划块，再做决策。禁止输出 HTML 文档。\n" +
            "[PLAN] 块格式（每行一条，不写废话）：\n" +
            "  需求：一句话说清用户要什么\n" +
            "  画布：miniapp|html + 一句理由（见下方画布选择）\n" +
            "  功能：功能名 —— 真实实现(数据源：哪个工具/API) | 演示数据(界面需标注) | 不做(理由)\n" +
            "  数据：列出取数途径\n" +
            "# 画布选择（GenUI 不止 HTML 画布——先选对画布再动手）\n" +
            "GenUI 有两类画布：\n" +
            "· 【小程序画布】（自研引擎，类原生体验）：**有状态、频繁交互的轻应用**。" +
            "硬性枚举（出现即必须选 miniapp）：记账/记账本/账单、待办/清单/TODO、计算器、计时器/秒表/番茄钟、" +
            "日记/记事/笔记、单位换算、抽签/骰子/随机、小游戏、表单收集。\n" +
            "· 【HTML 画布】（图文排版强）：适合**信息展示为主**——新闻页、报告、仪表盘、" +
            "图表可视化、落地页、长图文、自我介绍页。判定：看/读为主，少量点击 → 选 html。\n" +
            "决策（跟在 [PLAN] 块之后）：\n" +
            "0) 用户原话点名了实现方式（\"用 html/网页\" 或 \"用小程序\"）→ 无条件照办，这是最高优先级。\n" +
            "a) 需要实时信息/记忆/设备能力 → 调用相应工具（可连续多个），之后按默认决策；\n" +
            "b) 交互型应用/工具（未点名）→ 默认 miniapp：调用 create_miniapp" +
            "（files 完整可运行，真实逻辑+真实状态，尺寸全用 rpx）→ open_miniapp → 只回复 MINIAPP_DELIVERED；\n" +
            "c) 纯展示内容（新闻/图文/报告/图表/官网，未点名）→ 回复 NO_TOOLS 走 html。\n" +
            "两条路径都完整可用：小程序渲染在对话框卡片里（可试玩），HTML 渲染在画布上（可交互）。\n" +
            "★ 特例：用户让你『介绍你自己 / 展示你能做什么 / 自我介绍』→ 这是 GenUI 的" +
            "招牌演示场景，必须选 b) 渲染一个自我介绍页（把身份/能力/工作方式/原则做成" +
            "可视化界面呈现），严禁文字直答——文字介绍自己等于让厨师用嘴报菜名。\n" +
            "⚠ 用户要界面时永远禁止选 c)；规划里的功能取舍必须诚实——没有真实数据源的就写" +
            "「演示数据」，不要硬装成真的。\n" +
            "不要写界面代码、不要解释你的选择。"

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", decisionSystem))
            .put(JSONObject().put("role", "user").put("content", userPrompt))

        // ★ 小程序画布直通：交互类应用关键词命中 → 注入确定指令，不走犹豫的画布选择。
        // 小程序渲染在对话框卡片里（用户主交互面），是 GenUI 的第一等交付形态——HTML 只是图文页的备选。
        val wantsMini = wantsMiniApp(userPrompt)
        val wantsHtml = wantsHtmlPage(userPrompt)
        if (wantsMini && !wantsHtml) {
            messages.put(JSONObject().put("role", "system").put("content",
                "【推荐画布：小程序】这类需求适合小程序（渲染在对话框卡片里，用户可直接试玩）。\n" +
                "调用 create_miniapp（files 给出完整可运行的 app.json/app.js/app.wxss/pages/index/index.{wxml,wxss,js}，" +
                "真实逻辑+真实状态，尺寸全用 rpx），然后调用 open_miniapp 全屏打开，最后只回复：MINIAPP_DELIVERED\n" +
                "（若用户原话明确要求用 HTML/网页实现，则忽略本条，回复 NO_TOOLS 走 HTML 画布。）\n"))
            onEvent(AgentEvent.Thinking("推荐小程序画布（关键词直通）"))
        }

        // 快速模型预检（专项模型分派的真实用途之一）：这条指令要不要先联网？
        // 失败/未配置时静默跳过，绝不阻塞主流程。
        if (!isPrefetchSkipped(userPrompt)) {
            onEvent(AgentEvent.Thinking("读取意图…"))
            runCatching { fast.preflight(userPrompt) }.getOrNull()?.let { hint ->
                onStatus("意图预检 · 建议联网")
                onEvent(AgentEvent.Thinking("判断出这条指令可能要查实时信息"))
                messages.put(JSONObject().put("role", "system").put("content", "# 预检提示\n$hint"))
            }
        }

        try {
            // ---------- 决策轮（带工具，OpenAI 兼容协议才支持 function calling） ----------
            if (provider.protocol == Protocol.OPENAI) {
                val decls = tools.declarations()
                // MCP 聚合：所有已启用服务器的外部工具并入 function calling。
                // 单台失败不影响其他，也不阻塞本次生成（runCatching 兜底）。
                runCatching {
                    val mcpDecls = McpManager.aggregateDeclarations(appContext)
                    for (i in 0 until mcpDecls.length()) decls.put(mcpDecls.getJSONObject(i))
                    if (mcpDecls.length() > 0) {
                        onStatus("MCP：已并入 ${mcpDecls.length()} 个外部工具")
                        messages.put(JSONObject().put("role", "system").put("content",
                            "已连接外部 MCP 服务器，工具列表中 mcp_ 前缀的函数来自外部服务器，可直接调用。" +
                            "调用结果中 is_error=true 表示工具侧报错。"))
                    }
                }
                runCatching {
                    // 运行时工具：run_js（代码运行时）+ 插件管理 + 已装插件工具
                    val rtDecls = tools.runtimeDeclarations()
                    for (i in 0 until rtDecls.length()) decls.put(rtDecls.getJSONObject(i))
                    val plDecls = PluginRuntime.declarations(appContext)
                    for (i in 0 until plDecls.length()) decls.put(plDecls.getJSONObject(i))
                    if (plDecls.length() > 0) {
                        onStatus("运行时：JS引擎 + ${plDecls.length()} 个插件工具就绪")
                        messages.put(JSONObject().put("role", "system").put("content",
                            "代码运行时已就绪（run_js 可真实执行 JS）。已加载插件，plugin_ 前缀函数是插件工具，" +
                            "插件返回 ok=false 表示插件侧出错。"))
                    }
                }
                // 关键立场：工具调用【不再被固定轮数腰斩】。模型想查多少轮就查多少轮，
                // 自己会靠 NO_TOOLS / 直接成稿收尾。maxToolRounds 现在只是【软提醒阈值】——
                // 超过后轻推一次、绝不强制中断；0 = 完全不限制。
                // 唯一的硬停止只有两道"意外兜底"安全闸，二者都只拦失控、不拦正常调研：
                //   ① 死循环检测：累计重复调用"完全相同的工具+参数"（拿不到新信息、在空转）；
                //   ② 很宽松的总时长兜底：防模型无限调不同工具把一次生成挂死。
                val roundNudge = provider.maxToolRounds   // 0 = 不限制
                var rounds = 0
                val seenSigs = LinkedHashSet<String>()
                var redundant = 0
                val loopStart = System.currentTimeMillis()
                while (!cancelled) {
                    rounds++
                    onEvent(AgentEvent.Thinking(
                        if (rounds == 1) "分析指令，判断需不需要查资料或调用设备能力…"
                        else "根据上一步结果，继续判断还缺什么…"
                    ))
                    // 软提醒（非强制，只触发一次）：超阈值后请模型自行评估是否该收尾
                    if (roundNudge > 0 && rounds == roundNudge + 1) {
                        messages.put(JSONObject().put("role", "user").put("content",
                            "你已连续调用约 $roundNudge 轮工具。若信息已足够，请停止调用工具、直接输出完整 HTML 文档；若确实还缺关键数据，可继续。"))
                    }
                    val assistant = llm.chatOnce(provider, messages, decls)
                    // 思考过程进时间线：reasoning_content 字段 + <think> 标签两种形态都收
                    assistant.optString("reasoning_content").takeIf { it.isNotBlank() }?.let {
                        onEvent(AgentEvent.Thinking(it.take(400)))
                    }
                    val calls = assistant.optJSONArray("tool_calls")
                    val rawContent = assistant.optString("content").orEmpty()
                    Regex("(?s)<think>(.*?)</think>").find(rawContent)?.let {
                        onEvent(AgentEvent.Thinking(it.groupValues[1].take(400)))
                    }
                    val content = rawContent.replace(Regex("(?s)<think>.*?</think>"), "").trim()
                    // [PLAN] 规划块：解析进时间线展示；决策判断用剥离后的 decisionContent，
                    // 写回上下文的 content 保留规划原文（渲染轮按规划执行）
                    val planMatch = Regex("(?s)\\[PLAN\\]\\s*([\\s\\S]*?)(?=\\[(?:TOOLS|QA)\\]|NO_TOOLS|$)").find(content)
                    planMatch?.let { m ->
                        val plan = m.groupValues[1].trim()
                        if (plan.isNotBlank()) onEvent(AgentEvent.Plan(plan))
                    }
                    val decisionContent = planMatch?.let { content.replace(it.value, "").trim() } ?: content

                    if (calls == null || calls.length() == 0) {
                        if (decisionContent.isNotBlank() && decisionContent.contains("<!DOCTYPE", ignoreCase = true)) {
                            // 决策轮直接交出 HTML（罕见）：流式写入画布后完成，不丢内容
                            onStatus("直接出稿 · ${kb(content.length)}")
                            onEvent(AgentEvent.Decided(0, "不需要工具，直接成稿"))
                            onHtmlDelta(content)
                            onDone(content, extractTitle(content))
                            return
                        }
                        // 决策轮里模型可能说一些"数据已确认，开始落稿"之类的说明——它不是 HTML，
                        // 且渲染轮自带全部上下文，无需把它塞回 messages。否则它会进入渲染轮
                        // 的 renderMsgs，模型在 <!DOCTYPE 前复述它，最终作为说明文字出现在画布顶部。
                        // 因此这里直接丢弃，只 break 进渲染轮。
                        // ★ 小程序画布交付：create_miniapp 已成功 → 不再走 HTML 渲染轮，
                        // 画布给一张交付卡（对话流里已有可交互小程序卡片、画布栈已入栈）
                        if (miniAppDelivered != null) {
                            val id = miniAppDelivered!!
                            val card = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>" +
                                "<body style=\"margin:0;background:#141210;color:#F2EAD9;font-family:system-ui;" +
                                "display:flex;align-items:center;justify-content:center;min-height:100vh\">" +
                                "<div style=\"text-align:center;padding:32px\">" +
                                "<div style=\"font-size:52px;margin-bottom:12px\">▦</div>" +
                                "<h2 style=\"margin:0 0 8px;color:#D9A05B\">$id</h2>" +
                                "<p style=\"color:#8a8378;font-size:14px;margin:0 0 6px\">小程序已在画布内打开，可直接交互</p>" +
                                "<p style=\"color:#8a8378;font-size:12px;margin:0\">对话卡片同款可试玩；≡ → 栈 可随时回看</p>" +
                                "</div></body></html>"
                            onEvent(AgentEvent.Decided(0, "小程序画布交付 · $id"))
                            onHtmlDelta(card)
                            onMiniAppCanvas(id)   // 画布内嵌实时渲染（v0.26.8：不再只给说明卡）
                            onDone(card, "小程序 · $id")
                            return
                        }
                        onEvent(AgentEvent.Decided(0, "信息已足够，开始写界面"))
                        break   // 进入渲染轮
                    }

                    // 死循环检测：把本轮所有工具调用的"名称|参数"拼成一个签名。
                    // 若与之前某轮完全相同 → 没拿到新信息、在空转；累计到阈值就温柔收尾。
                    val sig = buildString {
                        for (i in 0 until calls.length()) {
                            val fn = calls.getJSONObject(i).optJSONObject("function") ?: continue
                            append(fn.optString("name")).append('|')
                                .append(fn.optString("arguments").trim()).append(';')
                        }
                    }
                    if (seenSigs.contains(sig)) {
                        redundant++
                        if (redundant >= REDUNDANT_LIMIT) {
                            onEvent(AgentEvent.Thinking("检测到工具调用陷入重复，改用已有信息成稿"))
                            messages.put(JSONObject().put("role", "user").put("content",
                                "你已重复调用完全相同的工具且未获得新信息，请停止调用工具，直接基于已有信息输出完整 HTML 文档。必须以 <!DOCTYPE html> 开头、</html> 结尾，不要只写一句话。"))
                            break
                        }
                    } else {
                        seenSigs.add(sig)
                    }

                    onEvent(AgentEvent.Decided(calls.length(), "需要 ${calls.length()} 项能力"))

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
                        val callId = call.optString("id", "call_${rounds}_$i")
                        val args = runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }
                            .getOrDefault(JSONObject())
                        val gate = tools.gateFor(name)
                        val level = ToolGate.level(gate)
                        val briefText = brief(args)

                        onStatus("调用工具：$name $briefText")
                        // 若该工具策略是"每次询问"，先广播等待授权事件
                        if (tools.gate.modeOf(gate) == ToolGate.AuthMode.ALWAYS_ASK) {
                            onEvent(AgentEvent.ToolAwaitingAuth(callId, name, briefText, level))
                        }
                        // 广播"开始执行"：UI 靠它把时间线条目置为 running，并在完成时回填耗时。
                        // （此前只有 ToolFinished，条目永远停在"运行中"，也看不到参数与结果）
                        onEvent(AgentEvent.ToolStarted(callId, name, briefText, level, rounds))
                        toolCallCount++

                        val t0 = System.currentTimeMillis()
                        val result = executeTool(name, args)
                        val cost = System.currentTimeMillis() - t0
                        if (name == "create_miniapp" && !result.has("denied") && !result.has("error")) {
                            miniAppDelivered = args.optString("app_id", "").ifBlank { null }
                        }
                        AgentLog.append(logCtx, "工具 · $name", listOf(
                            (if (result.has("error")) "❌ " else "✅ ") + ToolSummarize.summarize(name, result).take(200)
                                + " · ${com.genui.app.agent.ToolSummarize.fmtMs(cost)}"))

                        val denied = result.has("denied")
                        // 判定精细化（对齐 ChatSession）：有有效数据就不算失败
                        val hasData = (result.optJSONArray("results")?.length() ?: 0) > 0 ||
                            (result.optJSONArray("citations")?.length() ?: 0) > 0 ||
                            (result.optJSONArray("items")?.length() ?: 0) > 0 ||
                            result.optString("text").isNotBlank() ||
                            result.optString("output").isNotBlank() ||
                            result.optString("content").isNotBlank() ||
                            result.optString("context").isNotBlank() ||
                            result.optBoolean("ok", false)
                        val hasError = result.has("error") && !hasData
                        if (denied) failedTools.add("$name（用户未授权）")
                        if (hasError) failedTools.add("$name：${result.optString("error").take(100)}")
                        val summary = when {
                            denied -> result.optString("denied")
                            hasError -> result.optString("error")
                            else -> summarizeToolResult(name, result)
                        }
                        onEvent(
                            if (denied) AgentEvent.ToolDenied(callId, name, summary)
                            else AgentEvent.ToolFinished(callId, name, cost, !hasError, summary)
                        )
                        onStatus("工具完成：$name · ${kb(result.toString().length)}")
                        messages.put(JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", callId)
                            .put("content", result.toString()))
                    }

                    // 总时长兜底（很宽松，正常任务远到不了）：防模型无限调不同工具把生成挂死
                    if (System.currentTimeMillis() - loopStart > HARD_TIME_MS) {
                        onEvent(AgentEvent.Thinking("已达安全时长上限，用现有信息成稿"))
                        messages.put(JSONObject().put("role", "user").put("content",
                            "本次生成已达安全时长上限。请停止调用工具，直接基于已有信息输出完整 HTML 文档。"))
                        break
                    }
                }
            }

            // ---------- 渲染轮（流式出 HTML，最多两遍：模板语法残留自动打回重画） ----------
            if (cancelled) return
            var renderAttempt = 0
            var retryNote: String? = null
            val delivered = java.util.concurrent.atomic.AtomicReference<String?>(null)
            while (true) {
                renderAttempt++
            onStatus("写界面 · 流式渲染中")
            renderThinkBuf.setLength(0)
            lastThinkEmit = 0L
            onEvent(AgentEvent.Rendering(if (!seedHtml.isNullOrBlank()) "接着已中断的部分继续写…" else "开始绘制界面…"))


            // 渲染轮：把 tool 消息折成 user 备注（避免部分端点要求 tool/tool_calls 严格配对）
            val renderMsgs = JSONArray()
            for (i in 0 until messages.length()) {
                val m = messages.getJSONObject(i)
                val role = m.optString("role")
                if (role == "tool") {
                    renderMsgs.put(JSONObject().put("role", "user")
                        .put("content", "[工具结果] " + m.optString("content").take(8000)))
                } else {
                    val c = if (m.isNull("content")) "" else m.optString("content")
                    if (c.isBlank() && role != "system") continue
                    renderMsgs.put(JSONObject().put("role", role).put("content", c))
                }
            }
            // —— 续写模式：画布上已有上次留下的部分 HTML ——
            // 只给模型【尾部片段】而不是全文，避免把几万字符塞进上下文（既贵又慢）；
            // 尾部足够让它判断"写到哪儿了、接下来该写什么"。
            val seed: String? = seedHtml?.takeIf { it.isNotBlank() }
            val isContinuation = seed != null
            val tailLimit = provider.contextChars.coerceIn(500, 8000)
            val tail = seed?.takeLast(tailLimit).orEmpty()
            val closingTagHint = if (isContinuation)
                "你要做的是【续写】：上面是已写入画布的部分文档（只展示尾部）。" +
                "直接从它中断的地方接着往下写，绝对不要重复任何已有内容，" +
                "不要重新输出 <!DOCTYPE html> / <html> / <head> / 已有的 <style> 与 <script>。" +
                "补完剩余的 DOM 与脚本后，用 </body></html> 结束。"
            else
                "基于以上全部信息，现在输出最终界面。只输出以 <!DOCTYPE html> 开头的完整 HTML 文档，不要任何解释。"

            // —— 诚实性硬兜底：本轮失败/被拒工具逐条点名，渲染前注入 ——
            // 提示词约束模型行为终有漏网（实测：全失败仍编新闻+谎报"已保存已通知"），
            // 在渲染消息里点名失败清单，模型无法假装没看见。
            if (failedTools.isNotEmpty()) {
                renderMsgs.put(JSONObject().put("role", "user").put("content",
                    "【系统校验 · 必读】本轮以下工具调用失败，共 " + failedTools.size + " 项：\n" +
                    failedTools.mapIndexed { i, e -> "${i + 1}. $e" }.joinToString("\n") +
                    "\n最终回复硬性要求：①严禁虚构以上失败工具本应产生的任何数据" +
                    "（新闻/天气/文件内容/通知等）；②必须明确告知用户哪些功能失败及原因；" +
                    "③严禁使用「已保存/已发送/已获取到」等成功话术——失败就是失败。"))
            }

            if (renderAttempt == 2 && retryNote != null) {
                renderMsgs.put(JSONObject().put("role", "user").put("content",
                    "【重画 · 必读】" + retryNote))
            }
            if (isContinuation) {
                renderMsgs.put(JSONObject().put("role", "user")
                    .put("content", "【已在画布上的文档尾部片段】\n```\n" + tail + "\n```\n\n" + closingTagHint))
            } else {
                renderMsgs.put(JSONObject().put("role", "user").put("content", closingTagHint))
            }

            val sb = StringBuilder()
            // 续写时交付"种子 + 增量"，否则就是增量本身；统一在此剥掉 Markdown 围栏 + 截断非 HTML 前缀
            fun deliver(): String {
                val body = sb.toString()
                // 续写模式种子已是合法 HTML，只对"新增部分"做前缀清洗；全新生成则整体清洗
                val cleanBody = if (isContinuation) stripFences(body) else extractHtmlStart(stripFences(body))
                return if (seed != null) seed + cleanBody else cleanBody
            }
            // 绘制过程探针：从流式增量里实时解析"正在画什么"（真实观测，非假进度）
            val probe = PaintProbe()
            // 部分模型即使在提示词里被禁止，仍会顺手把 HTML 包在 ```html ... ``` 里。
            // 这个过滤器只在【开头】剥一次围栏：一旦确认不是围栏就原样放行后续所有内容，
            // 绝不把正常 HTML 当成围栏缓冲而丢弃（v0.11.9 黑屏回归的根因正是旧版有状态
            // stripper 把正文误判成围栏首部、持续返回空、最终吞掉整段输出）。结尾围栏在 deliver() 收口。
            val fenceFilter = LeadingFenceFilter()
            // 「HTML 起始检测」：模型常在 <!DOCTYPE 前先输出一段说明 / 状态汇报（如"数据已备齐…接着成稿"），
            // 若原样写入画布会作为文字出现在顶部。此处缓冲增量，直到真正 HTML 起始标签出现才放行；
            // 此前内容一律丢弃。续写模式（seed!=null）跳过——模型只补增量、可能不以 HTML 标签起头。
            val htmlStartRe = Regex("(?i)<!(doctype|DOCTYPE)|<html|<head|<body|<style|<script|<svg")
            var htmlStarted = isContinuation
            val preBuf = StringBuilder()
            fun pump(raw: String): String {
                if (htmlStarted) return raw
                preBuf.append(raw)
                val hit = htmlStartRe.find(preBuf.toString())
                return if (hit != null) {
                    htmlStarted = true
                    val tail = preBuf.toString().substring(hit.range.first)
                    preBuf.clear()
                    tail
                } else ""
            }
            // 渲染轮 system = 基座 + 灵魂（说话层+视觉签名层）：视觉签名只在此轮注入，影响 UI 美学方向
            val renderSystem = system + Soul.injectStyle(soul) +
                "\n# 输出格式（务必遵守）\n" +
                "直接输出写入 WebView 画布的原始 HTML 文档：以 <!DOCTYPE html> 开头、以 </html> 结尾。" +
                "不要使用 Markdown 代码块包裹（不要写 ```html 或 ```）；也不要写任何解释性文字、状态汇报、" +
                "思考过程或元叙述——尤其不要复述用户输入里的『接着成稿』『数据已备齐』等备注，直接以 <!DOCTYPE html> 起头。\n" +
                "⚠ 汇报/旁白类文字（如『数据拿到了…落笔。』）绝对禁止出现在页面里——包括 <body> 开头。" +
                "它们会被端上剥离并转到对话流，页面上只允许存在界面内容本身。\n" +
                "⚠ 每个界面至少包含一个真实可交互的功能：事件已绑定的按钮/输入框/切换标签，" +
                "点了必须发生真实的事（见交互绑定纪律与真实功能铁律）。" +
                "纯静态展示页仅在用户明确要求静态时输出。\n" +
                "★ 零失败交互协议（优先用）：按钮/元素加 data-ga 属性，端上原生执行真实行为，" +
                "不依赖任何页面 JS，永不失效：\n" +
                "  <button data-ga=\"notify:天气提醒|今天有雨\">提醒我</button>   系统通知\n" +
                "  <button data-ga=\"toast:已保存\">保存</button>                原生提示\n" +
                "  <button data-ga=\"copy:复制的文本\">复制</button>              写剪贴板\n" +
                "  <button data-ga=\"speak:要朗读的文本\">朗读</button>           TTS 出声\n" +
                "  <button data-ga=\"open:https://...\">打开</button>             浏览器打开\n" +
                "  <button data-ga=\"vibrate\">震动</button>                      震动反馈\n" +
                "\n# 移动端优先（画布是手机竖屏，逻辑宽约 390px——不是桌面显示器）\n" +
                "1) 根容器 padding 16-24px（顶部额外留 40px 状态栏安全区）、单列纵向布局；" +
                "2) 【禁止】固定像素宽度（width:900px 必溢出）——用 width:100%/flex:1/max-width；" +
                "3) 字号 ≥14px、按钮/标签/输入框高度固定 44-56px【禁止用 flex:1 拉伸它们——flex:1 只给主内容列表区，" +
                "分类标签/搜索框/按钮被 flex 拉伸会变成几屏高的竖条】；" +
                "4) 横向元素用 flex 并允许换行（flex-wrap:wrap）。\n" +
                "\n# 硬性语法红线（HTML 画布 = 纯静态 HTML，浏览器直接渲染）\n" +
                "【严禁】任何模板引擎语法：{{ 插值 }}、v-if/v-for、wx:if、ng-*、{% %} 等——" +
                "浏览器不认识它们，只会把 {{ todayText }} 裸露在页面上，页面等于废的。\n" +
                "【数据必须内联为真实值】新闻条目、商品、列表项直接写具体内容（标题/数字/日期），" +
                "或用 <script> 在页面里用 JS 数组+DOM 生成。\n" +
                "\n# 原生渲染通道（HTML 是宿主，AI 可自由混搭原生块）\n" +
                "在 HTML 里写以下结构，端上会把对应区域替换为真实原生渲染（WebView 之外的真控件）：\n" +
                "· XML 原生布局：<!--stack:xml--> + <div id=\"gen-xml\"></div> 占位 + " +
                "<script type=\"text/xml-layout\"> 里写 Android 原生控件 XML" +
                "（LinearLayout/TextView/Button/ImageView/EditText/ScrollView 等，属性反射自由设置）。\n" +
                "· Compose 组件：<!--stack:compose--> + <div id=\"gen-compose\"></div> + " +
                "<script type=\"text/x-compose\"> 里写 JSON 组件树（{type:\"Column\",children:[{type:\"Text\",text:\"标题\"}]}，" +
                "组件见 Compose 组件表：Column/Row/Text/Button/Card/TextField/Switch/Slider/LazyColumn…）。\n" +
                "· GenCanvas：<!--stack:canvas--> + <div id=\"gen-canvas\"></div> + " +
                "<script type=\"text/x-canvas\"> 里写绘制指令 JSON（op：rect/rrect/circle/svg/clip/group…）。\n" +
                "占位容器上方建议留出高度（如 style=\"min-height:300px\"）。整页只有原生块时，宿主 HTML 也要给基本骨架。\n" +
                "\n# A2UI 官方引擎（谷歌 A2UI v0.10——compose 通道的高级组件走官方渲染器）\n" +
                com.genui.app.render.ComposeDescRenderer.A2UI_GUIDANCE + "\n" +
                "\n# 质量自检（输出前心里过一遍）\n" +
                "界面至少包含：清晰的层级标题、真实密度的内容、一处数据可视化（图表/进度/徽标任选）、" +
                "至少一个可交互反馈（按钮按压态/状态切换/过渡动画）、内联 SVG 图标至少两枚。\n" +
                (if (isContinuation) "# 当前是续写任务\n你正在补完一个已被中断的文档，只输出新增部分，不要重复已有内容。\n" else "")
            llm.chatStream(
                provider = provider,
                system = renderSystem,
                messages = renderMsgs,
                onChunk = { delta ->
                    // 先过「HTML 起始检测」丢弃前置说明，再过围栏剥离；两者都只在开头生效，绝不吞正文
                    val out = pump(fenceFilter.feed(delta))
                    sb.append(out)
                    onHtmlDelta(out)
                    // 绘制过程：解析出的每个里程碑都是一条真实事件，UI 直接呈现"正在画什么"
                    probe.feed(out, deliver()).forEach(onEvent)
                    // 体量里程碑：作为兜底进度（结构无明显变化的长文档里仍有心跳）
                    val n = sb.length
                    if (n / 4096 > lastMilestone) {
                        lastMilestone = n / 4096
                        onEvent(AgentEvent.RenderProgress(n.toLong()))
                    }
                },
                onDone = { _ ->
                    val html = deliver()
                    if (html.isBlank()) {
                        // 模型跑完若干轮思考却没吐出任何 HTML：绝不静默黑屏，明确报错让用户重试
                        onError("模型未输出任何界面内容（接口返回为空，或提示词过严导致模型困惑）。请重试，或换一种说法 / 换个模型。")
                    } else {
                        // ★ 结构完整性兜底（截断根修第二道）：部分中转/模型把 max_tokens 截断
                        // 标成 finish_reason=stop，靠 finish_reason 检测会漏——HTML 没有 </html>
                        // 收尾就是物理截断，直接置位触发上游自动续写
                        if (!html.contains("</html>", ignoreCase = true) && html.length > 2048) {
                            lengthCutoff = true
                        }
                        // ★ 模板语法残留校验：{{ }} 裸露 ≥3 处 = 页面必然残废（浏览器不渲染插值）。
                        // 不交付废页——带错误反馈重画一次（纯 HTML + 内联数据）。
                        val templateLeftovers = Regex("\\{\\{[^}]{1,60}\\}\\}").findAll(html).count()
                        if (templateLeftovers >= 3 && renderAttempt == 1) {
                            onStatus("检出模板语法残留（${templateLeftovers} 处）· 打回重画")
                            retryNote = "上一次输出把 {{ 插值 }} 模板语法写进了 HTML——浏览器不认识，页面已废。" +
                                "重画要求：1) 纯静态 HTML+CSS+JS，禁止任何 {{ }} / v-if / wx: 语法；" +
                                "2) 所有数据（新闻条目/列表项/数字）内联写成真实具体内容，或用页面内 <script> JS 生成 DOM。"
                            return@chatStream   // delivered 仍为 null → 外层 while 重画
                        }
                        delivered.set(html)
                    }
                },
                onLengthCutoff = { lengthCutoff = true },
                onReasoning = { chunk ->
                    renderThinkBuf.append(chunk)
                    val now = System.currentTimeMillis()
                    if (now - lastThinkEmit > 300) {
                        lastThinkEmit = now
                        onEvent(AgentEvent.Thinking(
                            renderThinkBuf.takeLast(200).toString().replace("\n", " ")))
                    }
                },
                onError = { msg ->
                    if (deliver().isBlank()) throw RuntimeException(msg)
                    val html = deliver()
                    if (!html.contains("</html>", ignoreCase = true) && html.length > 2048) {
                        lengthCutoff = true
                    }
                    delivered.set(html)   // 有部分内容仍交付（走统一的循环尾交付逻辑）
                }
            )
                // —— 循环尾：chatStream 结束，检查是否需要重画 / 统一交付 ——
                var done = delivered.get()
                if (done == null) {
                    if (renderAttempt >= 2 || cancelled) {
                        done = deliver()   // 两轮都残留模板语法：尽力交付最后一版
                        if (done.isBlank()) break
                    } else {
                        continue           // 带重画提示再来一遍
                    }
                }
                // ★ 交付前自检闭环：写完 ≠ 完成。看效果/查错误/测交互/修补，PAGE_VERIFIED 才交付。
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val finalHtml = runCatching { selfCheck(provider, done, userPrompt) }
                        .getOrDefault(done)
                    AgentLog.append(logCtx, "交付 · " + extractTitle(finalHtml), listOf(
                        "${finalHtml.length / 1024}KB · 工具 ${toolCallCount} 次 · " +
                            "耗时 ${String.format(java.util.Locale.US, "%.1f", (System.currentTimeMillis() - startedAt) / 1000.0)}s"))
                    onEvent(AgentEvent.Finished(
                        title = extractTitle(finalHtml),
                        bytes = finalHtml.length.toLong(),
                        toolCalls = toolCallCount,
                        elapsedMs = System.currentTimeMillis() - startedAt
                    ))
                    onDone(finalHtml, extractTitle(finalHtml))
                }
                break
            }   // while (true) 渲染重画循环
        } catch (e: Exception) {
            if (!cancelled) {
                val msg = e.message ?: "Agent 执行失败"
                onEvent(AgentEvent.Failed(msg, 0))
                onError(msg)
            }
        }
    }

    // ---------- internals ----------

    /** 已播报的 4KB 里程碑数（渲染进度去抖） */
    private var lastMilestone = 0

    /** UI 意图关键词兜底：用户在要界面/应用/工具时，禁止模型以文字回答搪塞进对话框 */
    private fun isUiIntent(prompt: String): Boolean {
        val nouns = listOf(
            "页面", "网页", "界面", "应用", "小程序", "工具", "仪表盘", "看板", "面板",
            "卡片", "图表", "表单", "计算器", "游戏", "Dashboard", "dashboard",
        )
        // 自我展示类：GenUI 里让 AI 介绍自己 = 招牌演示场景，必须出页面
        val selfShow = listOf(
            "你自己", "自我介绍", "介绍自己", "你是谁", "介绍下你", "介绍一下你",
            "你的能力", "你能做什么", "展示一下你",
        )
        return nouns.any { prompt.contains(it, ignoreCase = true) } ||
            selfShow.any { prompt.contains(it) }
    }

    /** 交互类应用关键词 → 小程序画布直通 */
    private fun wantsMiniApp(prompt: String): Boolean {
        val kws = listOf(
            "记账", "账本", "待办", "清单", "todo", "TODO", "计算器", "番茄钟", "倒计时", "秒表",
            "计时", "打卡", "签到", "记事", "日记", "笔记", "备忘", "换算", "抽签", "骰子",
            "随机数", "小游戏", "密码生成", "bmi", "BMI", "小工具", "小程序", "习惯", "存钱", "预算")
        return kws.any { prompt.contains(it, ignoreCase = true) }
    }

    /** 信息展示类关键词（或用户点名 html/web）→ HTML 画布。用户点名永远最高优先。 */
    private fun wantsHtmlPage(prompt: String): Boolean {
        val kws = listOf(
            "html", "HTML", "Html", "web 页", "web页", "web app", "WebApp",
            "新闻", "资讯", "文章", "报告", "仪表盘", "看板", "图表", "数据可视化",
            "落地页", "官网", "介绍页", "网页", "网页版", "图文", "海报", "简历", "专题")
        return kws.any { prompt.contains(it, ignoreCase = true) }
    }

    /** 续写/追问这类明确不需要联网的短指令，跳过预检省一次请求 */
    private fun isPrefetchSkipped(prompt: String): Boolean {
        if (prompt.length < 4) return true
        return prompt.contains("继续") || prompt.contains("接着写") || prompt.contains("补完")
    }

    /** 工具结果摘要（给时间线看，不塞全文） */
    /** 把工具返回压缩成一行人类可读摘要，用于思考时间线。 */
    private fun summarizeToolResult(name: String, r: JSONObject): String = runCatching {
        when {
            r.has("error") -> "失败：${r.optString("error").take(60)}"
            name == "web_search" -> buildString {
                append(r.optJSONArray("results")?.length() ?: 0).append(" 条结果")
                val eng = r.optString("engines")
                if (eng.isNotBlank()) append(" · ").append(eng)
                val pf = r.optJSONArray("partial_failures")
                if (pf != null && pf.length() > 0) append("（部分引擎失败）")
            }
            name == "web_fetch" -> buildString {
                append("读到 ").append(r.optInt("chars")).append(" 字")
                if (r.optBoolean("truncated")) append("（已截断）")
                val ct = r.optString("content_type")
                if (ct.isNotBlank()) append(" · ").append(ct)
            }
            name == "file_list" -> "共 ${r.optInt("count")} 个文件"
            name == "contacts_search" -> "找到 ${r.optInt("count")} 位联系人"
            r.has("results") -> "${r.optJSONArray("results")?.length() ?: 0} 条结果"
            r.has("count") -> "共 ${r.optInt("count")} 条"
            r.has("content") -> "读到 ${r.optString("content").length} 字"
            r.has("text") -> r.optString("text").take(48)
            r.has("value") -> "已取回数据"
            r.has("ok") -> "完成"
            else -> r.toString().take(56)
        }
    }.getOrDefault("完成")

    /**
     * 交付前自检轮（≤2 轮）：画布已渲染完成，AI 通过 page_preview（看效果）/ page_errors（查错）/
     * page_eval（测交互+修补）验证自己写的界面，全部正常后回复 PAGE_VERIFIED。
     * 只允许 page_* 与 vision_analyze；任何异常都静默放行（自检是增强，不是闸门）。
     */
    private suspend fun selfCheck(provider: ModelProvider, html: String, userPrompt: String): String {
        // 等待画布稳定（流式刚结束，图表/动画/JS 初始化可能未跑完）
        kotlinx.coroutines.delay(1200)
        val decls = JSONArray()
        run {
            val allow = setOf("page_preview", "page_errors", "page_eval", "vision_analyze")
            val all = tools.declarations()
            for (i in 0 until all.length()) {
                val d = all.optJSONObject(i) ?: continue
                if (d.optJSONObject("function")?.optString("name") in allow) decls.put(d)
            }
        }
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content",
                "你是 GenUI 的交付自检员。画布上已渲染出为用户生成的界面（需求：" + userPrompt.take(200) + "）。\n" +
                "写完不等于完成——现在验证它真的可用：\n" +
                "1. 调 page_preview 看渲染效果（视觉模型可用会返回页面简评）；\n" +
                "2. 调 page_errors 查 JS 运行时错误；\n" +
                "3. 用 page_eval 测交互（查按钮数量、模拟 .click()、读关键 DOM 状态），发现小问题直接用 page_eval 修补；\n" +
                "4. 全部正常（或已修补）→ 只回复四个词：PAGE_VERIFIED\n" +
                "只许用上述工具，不要重新输出 HTML，不要长篇说明。"))
            .put(JSONObject().put("role", "user").put("content", "开始自检。"))
        var round = 0
        while (round < 2 && !cancelled) {
            val assistant = llm.chatOnce(provider, msgs, decls)
            val content = assistant.optString("content")
            val calls = assistant.optJSONArray("tool_calls")
            if (content.contains("PAGE_VERIFIED")) return html
            if (calls == null || calls.length() == 0) return html   // 模型放弃自检 → 照常交付
            msgs.put(JSONObject().put("role", "assistant").put("content", content))
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val fn = call.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }.getOrDefault(JSONObject())
                val t0 = System.currentTimeMillis()
                val result = runCatching { executeTool(name, args) }
                    .getOrDefault(JSONObject().put("error", "工具执行失败"))
                onEvent(AgentEvent.ToolStarted(call.optString("id") ?: "sc$i", name,
                    "自检 · ${args.toString().take(80)}", 0, 0))
                onEvent(AgentEvent.ToolFinished(call.optString("id") ?: "sc$i", name,
                    System.currentTimeMillis() - t0, !result.has("error"),
                    com.genui.app.agent.ToolSummarize.summarize(name, result)))
                msgs.put(JSONObject().put("role", "tool")
                    .put("tool_call_id", call.optString("id"))
                    .put("content", result.toString()))
            }
            round++
        }
        return html
    }

    private suspend fun executeTool(name: String, args: JSONObject): JSONObject {
        // 授权按"工具族"判定：memory_write 走 memory 的策略（权限屏设置的就是族名）。
        // 注意必须传族名给 gate，否则用户对 memory 设置的策略对 memory_write 不生效。
        val family = tools.gateFor(name)
        val verdict = try {
            tools.gate.authorize(family, brief(args)) { tool, briefArg, level ->
                onAskPermission(tool, briefArg, level)
            }
        } catch (e: Exception) {
            "授权流程异常：${e.message}"
        }
        if (verdict != null) {
            return JSONObject().put("denied", verdict)
        }
        // 插件路由：plugin_ 前缀工具在代码运行时引擎内执行
        if (name.startsWith("plugin_")) {
            return try {
                kotlinx.coroutines.withTimeout(90_000) { PluginRuntime.callTool(appContext, name, args) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                JSONObject().put("error", "插件工具执行超时（90秒）。")
            } catch (e: Exception) {
                JSONObject().put("error", "插件调用失败：${e.message ?: "未知错误"}")
            }
        }
        // MCP 路由：mcp_ 前缀工具交由外部服务器执行（授权已按 "mcp" 族完成）
        if (name.startsWith("mcp_")) {
            return try {
                kotlinx.coroutines.withTimeout(90_000) { McpManager.routeCall(appContext, name, args) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                JSONObject().put("error", "MCP 工具执行超时（90秒）。")
            } catch (e: Exception) {
                JSONObject().put("error", "MCP 调用失败：${e.message ?: "未知错误"}")
            }
        }
        // 超时保护：单个工具最长 40 秒，避免一个卡住的工具拖死整个生成
        val result = try {
            kotlinx.coroutines.withTimeout(TOOL_TIMEOUT_MS) { tools.execute(name, args) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            JSONObject().put("error", "工具执行超时（${TOOL_TIMEOUT_MS / 1000}秒），已跳过。可换一种方式或稍后重试。")
        } catch (e: Exception) {
            JSONObject().put("error", humanizeToolError(name, e.message ?: "工具执行失败"))
        }
        return result
    }

    /**
     * 把工具抛出的原始异常转成模型能据以调整、用户能看懂的说明。
     * 注意：权限类文案已由 PermRegistry 生成（含"去哪个屏开哪个开关"），此处只补充
     * "模型该怎么应对"的指令，不再重复解释——重复会让同一句话出现两遍。
     */
    private fun humanizeToolError(name: String, raw: String): String = when {
        raw.contains("权限") || raw.contains("permission", true) || raw.contains("需要「") ->
            "$raw\n（模型注意：不要反复重试同一工具，改用不需要该权限的方案，或请用户先开启权限。）"
        raw.contains("网络不可达") || raw.contains("Unable to resolve host", true) ||
            raw.contains("连接超时") || raw.contains("SocketTimeout", true) ->
            "网络不可用或超时。\n（模型注意：可改用已有知识作答，并如实说明未能联网核实。）"
        name == "web_search" && raw.contains("所有搜索引擎") ->
            "$raw\n（模型注意：换更通用或更短的关键词再试一次；仍失败就用已知信息作答并说明。）"
        raw.contains("不存在") || raw.contains("未安装") -> raw
        else -> "$name 执行失败：$raw"
    }

    private fun recentHistory(): String {
        val pages = store.loadPages().takeLast(5)
        if (pages.isEmpty()) return "（这是本次会话第一次生成）"
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        return "最近生成过的界面（新→旧）：\n" + pages.reversed().joinToString("\n") { p ->
            "· ${fmt.format(Date(p.ts))} 「${p.title}」"
        }
    }

    private fun extractTitle(html: String): String =
        Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: ("界面 · " + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date()))

    private fun brief(a: JSONObject): String = runCatching {
        a.keys().asSequence().take(2).joinToString(" ") { k ->
            "$k=" + (a.opt(k)?.toString() ?: "").take(24)
        }
    }.getOrDefault("")

    private fun kb(n: Int): String = String.format(Locale.US, "%.1fKB", n / 1024.0)

    /**
     * 去掉模型偶尔顺手包在 HTML 外的 Markdown 代码围栏（开头 ```html / 结尾 ```）。
     * 只动"最外层"的围栏：开头一个、结尾一个，文档内部的 ```（如 <script> 里的示例）不受影响。
     * 这是最终收口，配合 [LeadingFenceFilter] 的流式开头剥离，双保险。
     */
    private fun stripFences(raw: String): String {
        var s = raw.trimStart('\uFEFF')
        val lead = Regex("^\\s*```[a-zA-Z0-9_+#-]*\\s*\\n?", RegexOption.DOT_MATCHES_ALL)
        s = lead.replaceFirst(s, "")
        val trail = Regex("\\n?```\\s*$", RegexOption.DOT_MATCHES_ALL)
        s = trail.replaceFirst(s, "")
        return s
    }

    /**
     * 兜底截断：若整段文本不是以 HTML 起始标签开头（前面还夹着说明/状态汇报/围栏残留），
     * 找到第一个真正的 HTML 起点并丢弃其前的所有内容。配合渲染轮的流式 [pump] 起始检测双保险。
     */
    private fun extractHtmlStart(raw: String): String {
        val re = Regex("(?i)<!(doctype|DOCTYPE)|<html|<head|<body|<style|<script|<svg")
        val m = re.find(raw)
        return if (m != null) raw.substring(m.range.first) else raw
    }

    /**
     * 流式写入画布前剥离【开头的】Markdown 围栏。
     *
     * 只判定一次：遇到开头的 ```html / ``` 就剥掉并放行后续；一旦确认不是围栏
     *（开头不是反引号）或缓冲超过上限，立即原样放行——绝不会把正常 HTML 当成围栏缓冲丢弃
     *（这正是 v0.11.9 黑屏回归的根因）。结尾围栏由 [stripFences] 在 deliver() 统一收口。
     */
    private class LeadingFenceFilter {
        private var resolved = false
        private val buf = StringBuilder()

        fun feed(chunk: String): String {
            if (resolved) return chunk
            buf.append(chunk)
            val s = buf.toString()
            val fence = Regex("^\\s*```[a-zA-Z0-9_+#-]*\\s*\\n?", RegexOption.DOT_MATCHES_ALL)
            val m = fence.find(s)
            if (m != null) {
                resolved = true
                buf.clear()
                return s.substring(m.range.last + 1)
            }
            // 已能判定不是围栏：去掉前导空白后既非空、也不以反引号开头 → 直接放行缓冲内容
            val trimmed = s.trimStart()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("`")) {
                resolved = true
                val out = buf.toString()
                buf.clear()
                return out
            }
            // 防无限缓冲：超过 256 字符还没形成围栏，直接放行（正常 HTML 远到不了这长度）
            if (s.length > 256) {
                resolved = true
                val out = buf.toString()
                buf.clear()
                return out
            }
            // 仍在判定中（可能是围栏的一部分，或纯空白前导）：暂不放行
            return ""
        }
    }

    companion object {
        /** 单个工具执行的超时上限：一个卡住的工具不该拖死整个生成。
         *  web_search 是多引擎串行探测（最多 3 引擎 × 2 次重试），给足余量避免误判超时。 */
        private const val TOOL_TIMEOUT_MS = 75_000L
        /** 死循环检测：累计出现多少次"完全相同的工具调用签名"才温柔收尾（不拦正常调研）。 */
        private const val REDUNDANT_LIMIT = 3
        /** 总时长兜底：单轮生成决策阶段最多跑这么久（很宽松，只防挂死，正常任务远到不了）。 */
        private const val HARD_TIME_MS = 20 * 60_000L
    }
}

/**
 * 一次待裁决的实时授权请求（L3/L4 工具调用时由 UI 弹卡）。
 * [continuation] 由授权卡的三颗按钮恢复：本次允许=true / 拒绝=false / 永久允许=gate 记忆后 true。
 */
class PermRequest(
    val tool: String,
    val brief: String,
    val level: Int,
    val continuation: kotlinx.coroutines.CancellableContinuation<Boolean>
) {
    fun complete(allow: Boolean) {
        try {
            continuation.resumeWith(kotlin.Result.success(allow))
        } catch (_: Exception) {
            // 重复 resume 会抛异常：弹窗可能被"允许"与"关闭"两条路径各调用一次，
            // 这里静默吞掉即可，属于预期内的幂等保护
        }
    }
}
