package com.genui.app.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 权限网关 —— 参考 ZorvAI 的 L1–L5 能力分级与"每一级都显式授权"。
 *
 * 五级能力分级：
 *  L1 常驻    time / device / haptics          —— 无敏感面，默认放行
 *  L2 本机写  memory.* / clipboard             —— 只改本机数据，默认放行
 *  L3 外联    web_search / web_fetch           —— 触网，默认每次询问
 *  L4 系统触达 notify                           —— 触达系统层，默认每次询问
 *  L5 预留    （文件/通讯录等未来能力挂此级）
 *
 * 每个工具有三种授权策略：ALWAYS_ALLOW / ALWAYS_ASK / DENY，用户可在权限屏改。
 * ALWAYS_ASK 时由 UI 层弹出实时授权卡（工具名+等级+参数），用户"本次允许 / 永久允许 / 拒绝"。
 * 全部请求与结果写入审计。
 */
class ToolGate(context: Context) {

    enum class AuthMode { ALWAYS_ALLOW, ALWAYS_ASK, DENY }

    private val dir = File(context.filesDir, "gen").apply { mkdirs() }
    private val cfgFile = File(dir, "toolgate.json")
    private val auditFile = File(dir, "audit.jsonl")

    companion object {
        // 工具 → 等级（L5 预留，当前无挂载能力）
        val LEVEL: Map<String, Int> = mapOf(
            "time" to 1, "device" to 1, "haptics" to 1,
            "memory" to 2, "clipboard" to 2, "apps" to 2, "tts" to 2, "flashlight" to 2,
            "web_search" to 3, "web_fetch" to 3, "file" to 3, "alarm" to 3, "open_url" to 3,
            "news_search" to 3, "community_search" to 3, "github_search" to 3,
            "calendar" to 3, "system" to 3,
            "notify" to 4, "location" to 4,
            "contacts" to 5, "sms" to 5, "call" to 5
        )
        val ALL = LEVEL.keys.toList()

        fun level(tool: String): Int = LEVEL[tool] ?: 5
        fun levelName(tool: String): String = "L${level(tool)}"

        fun brief(tool: String): String = when (tool) {
            "time" -> "设备时间与日期"
            "device" -> "设备型号/电量/网络/存储/音量状态"
            "haptics" -> "震动反馈"
            "memory" -> "长期记忆库读写（跨界面持久）"
            "clipboard" -> "系统剪贴板"
            "tts" -> "系统语音朗读出声"
            "flashlight" -> "控制手电筒/闪光灯"
            "web_search" -> "联网搜索（多引擎）"
            "news_search" -> "新闻探索（Google News/Bing News）"
            "community_search" -> "社区内容探索（HN/StackOverflow/Reddit）"
            "github_search" -> "GitHub 探索（仓库/用户）"
            "web_fetch" -> "抓取指定网页正文"
            "file" -> "应用文档目录读写"
            "alarm" -> "设置系统闹钟"
            "open_url" -> "用浏览器打开链接"
            "calendar" -> "读写系统日历"
            "system" -> "系统分享面板 / 跳转系统设置"
            "notify" -> "发系统通知栏通知"
            "location" -> "读取设备地理位置"
            "apps" -> "枚举与启动已安装应用"
            "contacts" -> "检索通讯录联系人"
            "sms" -> "短信读取/编辑"
            "call" -> "打开拨号盘"
            else -> tool
        }

        /** 等级默认策略 */
        fun defaultMode(tool: String): AuthMode = when (level(tool)) {
            in 1..2 -> AuthMode.ALWAYS_ALLOW
            else -> AuthMode.ALWAYS_ASK
        }
    }

    private fun load(): JSONObject = runCatching { JSONObject(cfgFile.readText()) }.getOrDefault(JSONObject())

    /** 当前策略：用户改过的优先，否则按等级默认 */
    @Synchronized
    fun modeOf(tool: String): AuthMode {
        val s = load().optString(tool, "")
        return when (s) {
            "allow" -> AuthMode.ALWAYS_ALLOW
            "ask" -> AuthMode.ALWAYS_ASK
            "deny" -> AuthMode.DENY
            else -> defaultMode(tool)
        }
    }

    @Synchronized
    fun setMode(tool: String, mode: AuthMode) {
        val v = when (mode) {
            AuthMode.ALWAYS_ALLOW -> "allow"
            AuthMode.ALWAYS_ASK -> "ask"
            AuthMode.DENY -> "deny"
        }
        cfgFile.writeText(load().put(tool, v).toString())
    }

    fun loadAllModes(): Map<String, AuthMode> = ALL.associateWith { modeOf(it) }

    /** "永久允许"：把策略固化为 ALWAYS_ALLOW */
    @Synchronized
    fun grantAlways(tool: String) = setMode(tool, AuthMode.ALWAYS_ALLOW)

    /**
     * 授权判定。返回 null = 放行；返回 String = 拒绝理由（会回传给模型）。
     * @param ask UI 层注入的实时询问（suspend，等待用户选择）
     */
    suspend fun authorize(tool: String, argsBrief: String, ask: suspend (tool: String, brief: String, level: Int) -> Boolean): String? {
        val mode = modeOf(tool)
        return when {
            mode == AuthMode.DENY -> {
                audit(tool, "denied(policy)", argsBrief)
                "用户已在权限设置中禁用此工具（${levelName(tool)}），请改用其他方式完成任务"
            }
            mode == AuthMode.ALWAYS_ALLOW -> {
                audit(tool, "ok(auto)", argsBrief)
                null
            }
            else -> {
                val allowed = ask(tool, argsBrief, level(tool))
                if (allowed) {
                    audit(tool, "ok(user)", argsBrief)
                    null
                } else {
                    audit(tool, "denied(user)", argsBrief)
                    "用户本次拒绝了该工具调用（${levelName(tool)}），请继续任务但不要使用此工具"
                }
            }
        }
    }

    fun audit(tool: String, action: String, detail: String) {
        val line = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("tool", tool).put("action", action).put("detail", detail.take(160))
        auditFile.appendText(line.toString() + "\n")
        val lines = auditFile.readLines().filter { it.isNotBlank() }
        if (lines.size > 200) auditFile.writeText(lines.takeLast(200).joinToString("\n") + "\n")
    }

    fun recentAudit(limit: Int = 30): List<JSONObject> {
        if (!auditFile.exists()) return emptyList()
        return auditFile.readLines().filter { it.isNotBlank() }
            .takeLast(limit).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .reversed()
    }

    fun counts(): Map<String, Int> {
        if (!auditFile.exists()) return emptyMap()
        val m = mutableMapOf<String, Int>()
        auditFile.readLines().filter { it.isNotBlank() }.forEach {
            runCatching {
                val t = JSONObject(it).optString("tool")
                if (t.isNotBlank()) m[t] = (m[t] ?: 0) + 1
            }
        }
        return m
    }
}
