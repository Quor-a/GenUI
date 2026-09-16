package com.genui.app.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * GenUI 的本地存储：零第三方依赖，JSON 文件持久化。
 * - providers : 模型服务列表
 * - routing   : 专项模型分派
 * - pages     : 界面栈（AI 生成过的全部界面，可回溯）
 *
 * 存储位置：context.filesDir/gen/ —— 应用私有目录，卸载即清除。
 */
class GenStore(context: Context) {

    private val dir = File(context.filesDir, "gen").apply { mkdirs() }
    private val cfgFile = File(dir, "config.json")
    private val pagesFile = File(dir, "pages.jsonl")

    /** 供需要同一私有目录的子系统（记忆库/灵魂/权限）取 Context */
    fun context(): android.content.Context = contextPrivate

    private val contextPrivate: android.content.Context = context

    // ---------- 配置 ----------

    @Synchronized
    fun loadProviders(): MutableList<ModelProvider> {
        if (!cfgFile.exists()) return mutableListOf()
        return runCatching {
            val o = JSONObject(cfgFile.readText())
            val arr = o.optJSONArray("providers") ?: return mutableListOf()
            (0 until arr.length()).map { ModelProvider.fromJson(arr.getJSONObject(it)) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    @Synchronized
    fun saveProviders(list: List<ModelProvider>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        cfgFile.writeText(JSONObject().put("providers", arr).toString())
    }

    @Synchronized
    fun loadRouting(): FeatureRouting {
        if (!cfgFile.exists()) return FeatureRouting()
        return runCatching {
            val o = JSONObject(cfgFile.readText())
            val r = o.optJSONObject("routing") ?: JSONObject()
            FeatureRouting(
                mainProviderId = r.optString("main"),
                fastProviderId = r.optString("fast")
            )
        }.getOrDefault(FeatureRouting())
    }

    @Synchronized
    fun saveRouting(r: FeatureRouting) {
        val base = if (cfgFile.exists()) runCatching { JSONObject(cfgFile.readText()) }.getOrDefault(JSONObject()) else JSONObject()
        base.put("routing", JSONObject().put("main", r.mainProviderId).put("fast", r.fastProviderId))
        cfgFile.writeText(base.toString())
    }

    // ---------- 当前对话模式（GenUI 生成界面 / 标准 Agent 对话） ----------

    /** 对话模式：ui = GenUI 一句话生成界面；agent = 标准 Agent 多轮对话。默认 ui。 */
    @Synchronized
    /** 对话历史持久化：chatlog.json（重启不丢） */
    fun saveChatLog(items: List<Triple<String, String, String>>, done: List<Boolean>, ts: List<Long>) {
        val arr = org.json.JSONArray()
        for (i in items.indices) {
            arr.put(org.json.JSONObject()
                .put("id", items[i].first).put("role", items[i].second)
                .put("text", items[i].third).put("done", done[i]).put("ts", ts[i]))
        }
        runCatching { File(dir, "chatlog.json").writeText(arr.toString()) }
    }

    fun loadChatLog(): List<ChatLogEntry> = runCatching {
        val f = File(dir, "chatlog.json")
        if (!f.exists()) return emptyList()
        val arr = org.json.JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ChatLogEntry(o.optString("id"), o.optString("role"), o.optString("text"),
                o.optBoolean("done", true), o.optLong("ts"))
        }
    }.getOrDefault(emptyList())

    fun clearChatLog() { runCatching { File(dir, "chatlog.json").delete() } }

    fun loadMode(): String {
        if (!cfgFile.exists()) return "ui"
        return runCatching {
            JSONObject(cfgFile.readText()).optString("mode", "ui").takeIf { it.isNotBlank() } ?: "ui"
        }.getOrDefault("ui")
    }

    @Synchronized
    fun saveMode(mode: String) {
        val base = if (cfgFile.exists()) runCatching { JSONObject(cfgFile.readText()) }.getOrDefault(JSONObject()) else JSONObject()
        base.put("mode", mode)
        cfgFile.writeText(base.toString())
    }

    // ---------- 界面栈 ----------

    @Synchronized
    fun loadPages(): MutableList<GeneratedPage> {
        if (!pagesFile.exists()) return mutableListOf()
        return runCatching {
            pagesFile.readLines().filter { it.isNotBlank() }
                .map { GeneratedPage.fromJson(JSONObject(it)) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    @Synchronized
    fun appendPage(p: GeneratedPage) {
        pagesFile.appendText(p.toJson().toString() + "\n")
        // 上限 100 张纸，防止无限膨胀
        val lines = pagesFile.readLines().filter { it.isNotBlank() }
        if (lines.size > 100) pagesFile.writeText(lines.takeLast(100).joinToString("\n") + "\n")
    }

    @Synchronized
    fun clearPages() {
        pagesFile.delete()
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().take(8)
    }

    // ---------- MoBridge 键值存储（AI 界面的持久数据，独立于界面栈） ----------

    private val kvFile = File(dir, "bridge_kv.json")

    @Synchronized
    fun getPageValue(key: String): Any? = readKV().opt(key)

    @Synchronized
    fun putPageValue(key: String, value: Any?) {
        require(key.isNotBlank()) { "key 不能为空" }
        val o = readKV().put(key, value ?: JSONObject.NULL)
        kvFile.writeText(o.toString())
    }

    @Synchronized
    fun deletePageValue(key: String) {
        val o = readKV()
        o.remove(key)
        kvFile.writeText(o.toString())
    }

    @Synchronized
    fun pageKeys(): JSONArray {
        val arr = JSONArray()
        readKV().keys().forEach { arr.put(it) }
        return arr
    }

    private fun readKV(): JSONObject = runCatching { JSONObject(kvFile.readText()) }
        .getOrDefault(JSONObject())
}


/** 对话历史条目（持久化用） */
data class ChatLogEntry(val id: String, val role: String, val text: String, val done: Boolean, val ts: Long)
