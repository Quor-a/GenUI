package com.genui.app.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Agent 记忆库 —— 模型的长期记忆（跨界面、跨会话持久）。
 * LLM 通过 memory.* 工具自主读写；每次生成时把【记忆索引】注入系统提示，
 * 让模型知道自己记得什么。
 *
 * 存储：filesDir/gen/memory.json —— [{key, content, ts}]
 */
class AgentMemory(context: Context) {

    private val file = File(File(context.filesDir, "gen").apply { mkdirs() }, "memory.json")

    @Synchronized
    private fun readAll(): JSONArray = runCatching { JSONArray(file.readText()) }
        .getOrDefault(JSONArray())

    @Synchronized
    private fun writeAll(arr: JSONArray) {
        // 上限 200 条，超出时淘汰最旧的
        val trimmed = JSONArray()
        val start = maxOf(0, arr.length() - 200)
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        file.writeText(trimmed.toString())
    }

    fun write(key: String, content: String): JSONObject {
        require(key.isNotBlank()) { "记忆键不能为空" }
        require(content.isNotBlank()) { "记忆内容不能为空" }
        val trimmedContent = if (content.length > 2000) content.take(2000) + "…" else content
        val arr = readAll()
        val entry = JSONObject().put("key", key.trim()).put("content", trimmedContent)
            .put("ts", System.currentTimeMillis())
        // upsert：同 key 覆盖
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("key") != key.trim()) out.put(o)
        }
        out.put(entry)
        writeAll(out)
        return JSONObject().put("ok", true).put("key", key.trim())
    }

    fun read(key: String): JSONObject {
        val arr = readAll()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("key") == key.trim())
                return JSONObject().put("key", key.trim()).put("content", o.optString("content"))
        }
        return JSONObject().put("key", key.trim()).put("content", JSONObject.NULL)
            .put("hint", "没有这条记忆，可先用 memory.list 查看全部键")
    }

    fun list(): JSONObject {
        val arr = readAll()
        val items = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val c = o.optString("content")
            items.put(JSONObject()
                .put("key", o.optString("key"))
                .put("brief", if (c.length > 60) c.take(60) + "…" else c)
                .put(
                    "time",
                    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(o.optLong("ts")))
                ))
        }
        return JSONObject().put("count", items.length()).put("items", items)
    }

    fun delete(key: String): JSONObject {
        val arr = readAll()
        val out = JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("key") == key.trim()) removed = true else out.put(o)
        }
        writeAll(out)
        return JSONObject().put("ok", removed)
    }

    /** 记忆屏"清空"入口 */
    @Synchronized
    fun clear() {
        file.delete()
    }

    /** 注入系统提示的索引文本（只有键+摘要，省 token；条数设上限防上下文膨胀） */
    fun indexForPrompt(): String {
        val r = list()
        val items = r.optJSONArray("items") ?: return "（记忆库为空）"
        if (items.length() == 0) return "（记忆库为空，可主动用 memory_write 记住用户的重要偏好与事实）"
        val sb = StringBuilder()
        var shown = 0
        for (i in 0 until items.length()) {
            if (shown >= MAX_INDEX_ENTRIES) {
                // 记忆会越攒越多，全量注入会逐渐吃掉上下文预算、推高成本，
                // 还会稀释真正重要的信息。超出部分只报数量，模型需要时可 memory_list 查。
                sb.append("… 另有 ").append(items.length() - shown)
                    .append(" 条未列出（用 memory_list 查看全部）\n")
                break
            }
            val o = items.getJSONObject(i)
            sb.append("· ").append(o.optString("key")).append("：").append(o.optString("brief")).append('\n')
            shown++
        }
        return sb.toString()
    }

    private companion object {
        /** 提示词里最多列出的记忆条数 */
        const val MAX_INDEX_ENTRIES = 40
    }
}
