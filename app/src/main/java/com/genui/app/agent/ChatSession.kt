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
data class ChatMsg(
    val id: String,
    val role: String,   // "user" | "assistant" | "tool"
    val text: String = "",
    val done: Boolean = false,
    val ts: Long = System.currentTimeMillis()
)

class ChatSession(
    private val store: GenStore,
    private val onUserMsg: (String) -> Unit,
    private val onAssistantStart: (id: String) -> Unit,
    private val onAssistantDelta: (id: String, delta: String) -> Unit,
    private val onAssistantDone: (id: String) -> Unit,
    private val onToolStart: (id: String, name: String, brief: String) -> Unit,
    private val onToolResult: (id: String, result: String) -> Unit,
    private val onThinking: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onAskPermission: suspend (tool: String, brief: String, level: Int) -> Boolean
) {
    @Volatile var cancelled = false
        private set
    fun cancel() { cancelled = true; llm.cancel() }

    private val tools = BuiltinTools(store.context())
    private val llm = LLMClient()
    /** 对话上下文（OpenAI messages 数组），跨多轮保留 */
    private val messages = JSONArray()

    private fun newId(): String = UUID.randomUUID().toString().take(8)

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
            while (!cancelled) {
                onThinking("思考中…")
                val decls = tools.declarations()
                val assistant = llm.chatOnce(provider, messages, decls)
                val calls = assistant.optJSONArray("tool_calls")
                val content = assistant.optString("content").orEmpty()

                if (calls == null || calls.length() == 0) {
                    // 无工具调用：用流式接口把最终回答呈现成气泡
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
                        onToolResult(tid, "已拒绝：$verdict")
                        messages.put(JSONObject().put("role", "tool")
                            .put("tool_call_id", callId)
                            .put("content", JSONObject().put("denied", verdict).toString()))
                        continue
                    }
                    val res = runCatching { tools.execute(name, args) }
                        .getOrDefault(JSONObject().put("error", "工具执行失败"))
                    val cost = System.currentTimeMillis() - t0
                    val ok = !res.has("error")
                    onToolResult(tid,
                        (if (ok) "✅ " else "❌ ") + "$name · ${cost}ms\n" + res.toString().take(400))
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
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            if (m.optString("role") == "system") continue
            msgs.put(m)
        }
        val thinkBuf = StringBuilder()
        llm.chatStream(
            provider = provider,
            system = "",
            messages = msgs,
            onChunk = { d -> onAssistantDelta(aid, d) },
            onDone = { _ -> onAssistantDone(aid) },
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
