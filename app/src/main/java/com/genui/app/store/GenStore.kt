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

    // ---------- 能力模型注册表（视觉/图生成/视频生成/TTS/STT/声音克隆/视频通话/语音通话） ----------
    // 每个能力槽可独立配置 OpenAI 兼容端点；工具在运行时读取对应槽位驱动调用。
    // 未配置的槽位=该能力不可用，工具如实报错并引导去「能力模型」页配置。
    val CAP_SLOTS = listOf(
        "vision", "imageGen", "videoGen", "tts",
        "stt", "voiceClone", "videoCall", "voiceCall"
    )
    val CAP_LABELS = mapOf(
        "vision" to "视觉理解", "imageGen" to "图片生成", "videoGen" to "视频生成",
        "tts" to "语音合成 TTS", "stt" to "语音识别 STT", "voiceClone" to "声音克隆",
        "videoCall" to "视频通话", "voiceCall" to "语音通话"
    )

    fun capFile() = File(dir, "capability_models.json")

    fun loadCapability(slot: String): Map<String, String> = runCatching {
        val arr = org.json.JSONArray(capFile().readText())
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .firstOrNull { it.optString("slot") == slot }
            ?.let { mapOf("baseUrl" to it.optString("baseUrl"), "apiKey" to it.optString("apiKey"),
                "model" to it.optString("model"), "protocol" to it.optString("protocol", "openai")) }
            ?: emptyMap()
    }.getOrDefault(emptyMap())

    @Synchronized
    fun saveCapability(slot: String, baseUrl: String, apiKey: String, model: String, protocol: String = "openai", enabled: Boolean = true) {
        val arr = runCatching { org.json.JSONArray(capFile().readText()) }.getOrDefault(org.json.JSONArray())
        val kept = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("slot") != slot) kept.put(o)
        }
        kept.put(org.json.JSONObject()
            .put("slot", slot).put("baseUrl", baseUrl).put("apiKey", apiKey)
            .put("model", model).put("protocol", protocol).put("enabled", enabled))
        capFile().writeText(kept.toString())
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


    private val sessionsDir: File get() = File(dir, "sessions").apply { mkdirs() }

    // —— 小程序画布栈（与 HTML 画面并列，出现在历史浏览里） ——
    private val miniAppPagesFile: File get() = File(dir, "pages_miniapp.json")

    fun loadMiniAppPages(): List<String> = runCatching {
        val arr = org.json.JSONArray(miniAppPagesFile.readText())
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    fun appendMiniAppPage(appId: String) {
        if (appId.isBlank()) return
        runCatching {
            val arr = if (miniAppPagesFile.exists()) org.json.JSONArray(miniAppPagesFile.readText()) else org.json.JSONArray()
            arr.put(appId)
            miniAppPagesFile.writeText(arr.toString())
        }
    }

    fun clearMiniAppPages() { runCatching { miniAppPagesFile.delete() } }

    /** 把当前对话（chatlog.json）归档到 sessions/<ts>.json（新建对话时保留历史） */
    fun archiveChatLog(): Boolean {
        val f = File(dir, "chatlog.json")
        if (!f.exists()) return false
        val entries = loadChatLog()
        if (entries.isEmpty()) { f.delete(); return false }
        val title = entries.firstOrNull { it.role == "user" }?.text?.lineSequence()?.firstOrNull()?.take(18) ?: "对话"
        return runCatching {
            sessionsDir.resolve("${System.currentTimeMillis()}.json").writeText(
                org.json.JSONObject()
                    .put("title", title)
                    .put("ts", entries.lastOrNull()?.ts ?: System.currentTimeMillis())
                    .put("entries", org.json.JSONArray(f.readText()))
                    .toString())
            f.delete()
            true
        }.getOrDefault(false)
    }

    data class ChatArchive(val file: java.io.File, val title: String, val ts: Long, val count: Int)

    fun listChatArchives(): List<ChatArchive> = runCatching {
        sessionsDir.listFiles { f -> f.name.endsWith(".json") }?.map { f ->
            runCatching {
                val o = org.json.JSONObject(f.readText())
                ChatArchive(f, o.optString("title", "对话"), o.optLong("ts", 0), o.optJSONArray("entries")?.length() ?: 0)
            }.getOrElse { ChatArchive(f, f.name, 0, 0) }
        }?.sortedByDescending { it.ts } ?: emptyList()
    }.getOrDefault(emptyList())

    /** 恢复归档为当前对话：当前对话先归档，选中归档移回 chatlog.json（会话在两者间移动，不重复） */
    fun restoreChatArchive(src: java.io.File): List<ChatLogEntry> {
        if (loadChatLog().isNotEmpty()) archiveChatLog()
        return runCatching {
            val arr = org.json.JSONObject(src.readText()).optJSONArray("entries") ?: org.json.JSONArray()
            File(dir, "chatlog.json").writeText(arr.toString())
            src.delete()
            loadChatLog()
        }.getOrDefault(emptyList())
    }

    fun deleteChatArchive(f: java.io.File) { runCatching { f.delete() } }

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
