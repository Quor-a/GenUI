package com.genui.app.agent.tools

import android.content.Context
import com.genui.app.agent.AgentMemory
import com.genui.app.agent.ToolGate
import com.genui.app.store.GenStore
import com.genui.app.websearch.Explore
import com.genui.app.websearch.GenUiLlmCompleter
import com.genui.app.websearch.WebSearchOrchestrator
import com.genui.app.websearch.cache.MemoryCacheStore
import com.genui.app.websearch.net.BraveEngine
import com.genui.app.websearch.net.BaiduEngine
import com.genui.app.websearch.net.BingCnHtmlEngine
import com.genui.app.websearch.net.BingEngine
import com.genui.app.websearch.net.SogouEngine
import com.genui.app.websearch.net.DdgHtmlEngine
import com.genui.app.websearch.net.GoogleNewsRssEngine
import com.genui.app.websearch.net.SearXngEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 内置工具集（Tool-first，参考 ZorvAI 的 QuroTool 设计：每个工具 = 名称+描述+参数schema+执行体）。
 * 全部工具在 IO 线程执行，返回 JSONObject 给 LLM。
 */
class BuiltinTools(private val context: Context) {

    val memory = AgentMemory(context)
    val gate = ToolGate(context)

    /** 能力模型注册表存取（vision/imageGen/tts/... 槽位） */
    private val capStore by lazy { com.genui.app.store.GenStore(context) }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** 抓网页用：读超时更长（大页面更慢），不自动重试整站，交给上层控制。 */
    private val fetchHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 联网搜索完整管线（移植自 ZorvAI websearch 模块）：
     * 查询改写（LLM，可选）→ 多引擎并发检索（Bing/GNews/DDG/SearXNG，熔断自愈）
     * → 投票去重 → 五信号重排 → 超额并发精读正文 → 密度抽取 → token 预算打包 → [n] 引用。
     *
     * 不可达引擎由 EngineHealth 熔断（连续失败进冷却），任一引擎挂掉不影响整体。
     * SearXNG 实例地址（可选）写在 filesDir/gen/searxng.txt，为空则该引擎自动跳过。
     */
    private val orchestrator: WebSearchOrchestrator by lazy {
        com.genui.app.websearch.net.WebSearchKeys.init(context)
        val searxUrl = runCatching {
            File(File(context.filesDir, "gen"), "searxng.txt")
                .takeIf { it.exists() }?.readText()?.trim().orEmpty()
        }.getOrDefault("")
        WebSearchOrchestrator(
            engines = listOf(
                BingEngine(),
                BaiduEngine(),
                BraveEngine(),
                GoogleNewsRssEngine(),
                DdgHtmlEngine(),
                SearXngEngine(searxUrl)
            ),
            cache = MemoryCacheStore(),
            completer = GenUiLlmCompleter(GenStore(context))
        )
    }


    // ---------- OpenAI function calling 的 tools 声明 ----------

    fun declarations(): JSONArray {
        fun decl(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject =
            JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", name).put("description", desc)
                    .put("parameters", JSONObject()
                        .put("type", "object").put("properties", props)
                        .put("required", JSONArray(required))))

        fun runJsDecl(): JSONObject = decl("run_js",
            "在 GenUI 内置 JS 引擎（Chromium）中真实执行 JavaScript。支持 async/await/fetch；console.log 输出与 return 返回值均回传。代码为函数体（自动包 async function）。适合计算、数据处理、算法验证、调外部 REST API。",
            JSONObject()
                .put("code", JSONObject().put("type", "string").put("description", "JS 代码（函数体），例：const r = await fetch('https://api.x.com').then(r=>r.json()); return r;")),
            listOf("code"))

        fun pluginListDecl(): JSONObject = decl("list_plugins", "列出已安装的 GenUI 插件（含各插件声明的工具）。",
            JSONObject(), listOf())

        fun installPluginDecl(): JSONObject = decl("install_plugin",
            "安装 GenUI 插件：JS 代码 + 工具清单，安装后其工具自动并入工具列表（plugin_ 前缀），后续对话可直接调用。code 必须为返回函数映射的函数体，如：return { query: async (args) => { const r = await fetch(...); return await r.json(); } }",
            JSONObject()
                .put("name", JSONObject().put("type", "string").put("description", "插件名"))
                .put("tools_json", JSONObject().put("type", "string").put("description", "工具清单 JSON 数组字符串：[{\"name\":\"query\",\"description\":\"...\",\"parameters\":{\"type\":\"object\",\"properties\":{...},\"required\":[...]}}]"))
                .put("code", JSONObject().put("type", "string").put("description", "插件 JS 代码（函数体），必须 return {工具名: async (args)=>结果} 的函数映射"))
                .put("version", JSONObject().put("type", "string").put("description", "版本号，默认 1.0")),
            listOf("name", "tools_json", "code"))

        fun uninstallPluginDecl(): JSONObject = decl("uninstall_plugin", "卸载指定插件（按 id 或名称）。",
            JSONObject().put("id", JSONObject().put("type", "string").put("description", "插件 id 或名称")),
            listOf("id"))

        fun visionAnalyzeDecl(): JSONObject = decl("vision_analyze", "视觉理解：让视觉模型看图并回答。参数 {image: 图片路径（agent_files 内，如上传的图片或 file_save 的图）或图片 URL，question: 想问的问题}。需在「能力模型 → 视觉理解」配置端点。",
            JSONObject()
                .put("image", JSONObject().put("type", "string").put("description", "图片路径（agent_files 内文件名）或 http(s) URL"))
                .put("question", JSONObject().put("type", "string").put("description", "针对图片的问题")),
            listOf("image"))

        fun imageGenerateDecl(): JSONObject = decl("image_generate", "图片生成：按文字描述生成图片，保存到 agent_files 并可展示。参数 {prompt: 画面描述（越具体越好）}。需在「能力模型 → 图片生成」配置端点。",
            JSONObject()
                .put("prompt", JSONObject().put("type", "string").put("description", "画面描述（风格/主体/细节）")),
            listOf("prompt"))

        fun aiBrowserDecl(): JSONObject {
            val props = JSONObject()
            props.put("action", JSONObject().put("type", "string")
                .put("description", "automate=自动研究(推荐)/search=仅搜索/read=抓单页正文/download=下载文件"))
            props.put("query", JSONObject().put("type", "string").put("description", "搜索/研究主题"))
            props.put("url", JSONObject().put("type", "string").put("description", "read/download 的目标网址"))
            props.put("depth", JSONObject().put("type", "integer").put("description", "automate 抓取前 N 篇，默认 4"))
            return decl("ai_browser",
                "【联网研究★推荐】ZorvAI 同款自动化浏览器：automate 动作在【单个调用内】完成「四引擎回退搜索→抓取前 depth 篇正文→合并带出处研究简报」（研究/查资料/查天气等任务务必只用这一次 automate，严禁拆成多次 search+read 拖慢对话）。也可 search=仅搜标题链接 / read=抓单页正文 / download=下载文件。",
                props, listOf("action"))
        }

        return JSONArray()
            .put(aiBrowserDecl())
            .put(visionAnalyzeDecl())
            .put(imageGenerateDecl())
            .put(decl("web_search", "联网搜索实时信息，返回带编号 [n] 的资料片段与可溯源引用（完整管线：查询改写 → 多引擎并发检索 → 五信号重排 → 正文精读 → 引用打包）。凡涉及时效性信息（新闻、价格、版本号、赛事、天气、汇率、今天发生的事）必须先调用它，不要凭记忆回答。回答时在关键事实后标注 [n]，n 对应返回的 citations 编号。",
                JSONObject()
                    .put("query", JSONObject().put("type", "string").put("description", "用户的原始问题，自然语言即可，内部会自动改写为搜索词"))
                    .put("max", JSONObject().put("type", "integer").put("description", "期望结果条数 3-12，默认 8（精读正文上限另由管线内部预算控制）")),
                listOf("query")))
            .put(decl("news_search", "新闻探索引擎：Google News + Bing News 双源并发，返回最新新闻（标题/链接/来源/日期/摘要）。查时事、行业动态、突发事件时用，比 web_search 更快更准。结果可直接画成新闻聚合页。",
                JSONObject()
                    .put("query", JSONObject().put("type", "string").put("description", "新闻主题或关键词，如 人工智能 / 新品发布"))
                    .put("max", JSONObject().put("type", "integer").put("description", "条数，默认 10")),
                listOf("query")))
            .put(decl("community_search", "社区内容探索引擎：Hacker News + Stack Overflow + Reddit 三源并发，返回社区讨论（标题/链接/热度/作者/摘要）。查技术讨论、开发者观点、问题解答、社区热点时用。",
                JSONObject()
                    .put("query", JSONObject().put("type", "string").put("description", "话题或关键词，中英文均可"))
                    .put("max", JSONObject().put("type", "integer").put("description", "每源条数上限，默认 10")),
                listOf("query")))
            .put(decl("github_search", "GitHub 探索引擎：官方 Search API，按 star 排序返回仓库（名称/链接/star/语言/描述/更新时间）或用户。找开源项目、库、工具、优秀开发者时用。",
                JSONObject()
                    .put("query", JSONObject().put("type", "string").put("description", "搜索词，如 android ai agent / kotlin compose"))
                    .put("type", JSONObject().put("type", "string").put("description", "repositories（默认）或 users").put("enum", org.json.JSONArray().put("repositories").put("users")))
                    .put("max", JSONObject().put("type", "integer").put("description", "条数，默认 10")),
                listOf("query")))
            .put(decl("web_fetch", "抓取指定网页的正文文本（已剔除导航/广告/脚本，保留段落）。用于读取搜索结果里的具体页面、或用户给出的链接。",
                JSONObject()
                    .put("url", JSONObject().put("type", "string").put("description", "完整网址，以 http:// 或 https:// 开头"))
                    .put("max_chars", JSONObject().put("type", "integer").put("description", "最多返回字符数 500-40000，默认4000")),
                listOf("url")))
            .put(decl("memory_write", "写入长期记忆（持久保存，跨会话可用）。用户的重要偏好、项目背景、约定都应记下。",
                JSONObject()
                    .put("key", JSONObject().put("type", "string").put("description", "记忆键，如 user.style / project.bg"))
                    .put("content", JSONObject().put("type", "string").put("description", "记忆内容，简洁准确")),
                listOf("key", "content")))
            .put(decl("memory_read", "读取一条长期记忆的完整内容。",
                JSONObject().put("key", JSONObject().put("type", "string")), listOf("key")))
            .put(decl("memory_list", "列出全部长期记忆的键与摘要。", JSONObject(), emptyList()))
            .put(decl("memory_delete", "删除一条长期记忆。",
                JSONObject().put("key", JSONObject().put("type", "string")), listOf("key")))
            .put(decl("time_now", "获取设备当前时间与日期。",
                JSONObject(), emptyList()))
            .put(decl("device_info", "获取设备信息：型号、Android 版本、电量、网络类型。",
                JSONObject(), emptyList()))
            .put(decl("notify_send", "发一条系统通知（需要用户授权通知权限）。",
                JSONObject()
                    .put("title", JSONObject().put("type", "string"))
                    .put("body", JSONObject().put("type", "string")),
                listOf("title", "body")))
            .put(decl("location_get", "获取设备最近一次已知地理位置（经纬度+逆地理出的城市级描述）。需要定位权限。用于天气/附近/出行类任务。",
                JSONObject(), emptyList()))
            .put(decl("file_save", "把内容保存为设备上的真实文件（应用文档目录，可反复读取）。",
                JSONObject()
                    .put("name", JSONObject().put("type", "string").put("description", "文件名，如 notes.txt / data.json"))
                    .put("content", JSONObject().put("type", "string")),
                listOf("name", "content")))
            .put(decl("file_read", "读取之前用 file_save 保存的文件内容。",
                JSONObject().put("name", JSONObject().put("type", "string")), listOf("name")))
            .put(decl("file_list", "列出所有已保存的文件名与大小。", JSONObject(), emptyList()))
            .put(decl("contacts_search", "按关键词搜索手机通讯录联系人（姓名/电话）。需要通讯录权限。",
                JSONObject().put("keyword", JSONObject().put("type", "string").put("description", "姓名或号码片段")),
                listOf("keyword")))
            .put(decl("sms_compose", "打开系统短信编辑页并预填收件人与内容（由用户确认发送，不自动发送）。",
                JSONObject()
                    .put("phone", JSONObject().put("type", "string"))
                    .put("text", JSONObject().put("type", "string")),
                listOf("phone", "text")))
            .put(decl("call_dial", "打开系统拨号盘并填入号码（由用户手动拨出，不自动拨打）。",
                JSONObject().put("phone", JSONObject().put("type", "string")), listOf("phone")))
            .put(decl("alarm_set", "设置一个系统闹钟（真实生效）。用于用户说「提醒我几点做xx」。",
                JSONObject()
                    .put("hour", JSONObject().put("type", "integer").put("description", "24小时制 0-23"))
                    .put("minute", JSONObject().put("type", "integer"))
                    .put("label", JSONObject().put("type", "string").put("description", "闹钟备注")),
                listOf("hour", "minute")))
            .put(decl("apps_list", "列出设备上已安装的应用（名称+包名）。用于打开应用/了解设备。",
                JSONObject(), emptyList()))
            .put(decl("app_open", "启动一个已安装应用。",
                JSONObject().put("package", JSONObject().put("type", "string").put("description", "应用包名")), listOf("package")))
            .put(decl("open_url", "用系统浏览器打开网页。",
                JSONObject().put("url", JSONObject().put("type", "string")), listOf("url")))
            .put(decl("clipboard_read", "读取剪贴板当前文本。", JSONObject(), emptyList()))
            // ---------- 以下为 v0.9 扩充：让 AI 能触达更多真实能力 ----------
            .put(decl("system_status", "读取设备实时状态：电量/充电/网络/存储/内存/音量/亮度。做系统面板类界面时先调它取真实值。",
                JSONObject(), emptyList()))
            .put(decl("flashlight", "开关手电筒（真实控制闪光灯）。",
                JSONObject().put("on", JSONObject().put("type", "boolean").put("description", "true 开，false 关")),
                listOf("on")))
            .put(decl("tts_speak", "用系统语音朗读一段文字（真实出声）。用于朗读/播报/无障碍场景。",
                JSONObject().put("text", JSONObject().put("type", "string")), listOf("text")))
            .put(decl("share_text", "把一段文本或链接交给系统分享面板（用户自选目标 App）。",
                JSONObject()
                    .put("title", JSONObject().put("type", "string"))
                    .put("text", JSONObject().put("type", "string")),
                listOf("text")))
            .put(decl("open_settings", "跳转系统设置页。",
                JSONObject().put("page", JSONObject().put("type", "string")
                    .put("description", "wifi / bluetooth / display / sound / battery / apps / location / notification / about")),
                listOf("page")))
            .put(decl("calendar_query", "查询设备日历中近期的真实日程。需要日历读取权限。",
                JSONObject().put("days", JSONObject().put("type", "integer").put("description", "往后查多少天，默认 7")),
                emptyList()))
            .put(pagePreviewDecl())
            .put(pageErrorsDecl())
            .put(pageEvalDecl())
            .put(decl("log_write", "往执行日志（Markdown）里写一条执行笔记。关键决策、中间结论、待办都应该记下来，供后续回看。",
                JSONObject().put("text", JSONObject().put("type", "string").put("description", "笔记内容（一行，Markdown 语法可用 **加粗**、`代码`）")),
                    listOf("text")))
            .put(decl("log_read", "回看执行日志：读取某天（默认今天）的完整 Markdown 日志，含任务流水、工具调用、交付记录与你写过的笔记。",
                JSONObject().put("day", JSONObject().put("type", "string").put("description", "日期 yyyy-MM-dd，缺省今天")),
                    emptyList()))
            .put(decl("calendar_add", "向系统日历添加一个真实日程。",
                JSONObject()
                    .put("title", JSONObject().put("type", "string"))
                    .put("begin", JSONObject().put("type", "string").put("description", "开始时间，格式 yyyy-MM-dd HH:mm"))
                    .put("minutes", JSONObject().put("type", "integer").put("description", "时长（分钟），默认 60"))
                    .put("note", JSONObject().put("type", "string").put("description", "备注，可选")),
                listOf("title", "begin")))
            .put(decl("list_miniapps", "列出 GenUI 里全部小程序（内置示例 + 你生成的）。",
                JSONObject(), emptyList()))
            .put(decl("open_miniapp", "打开一个小程序（用户会看到独立的全屏界面）。先用 list_miniapps 查有哪些。",
                JSONObject().put("app_id", JSONObject().put("type", "string").put("description", "小程序 id")),
                listOf("app_id")))
            .put(createMiniAppDecl())
            .put(rtDecl("export_miniapp_web",
                "把已创建的小程序导出为【网站版】独立 HTML 文件（浏览器直接打开/上传静态托管/分享链接），一次生成三端可用（安卓小程序卡+画布+网页）。导出路径会返回。",
                JSONObject().put("app_id", JSONObject().put("type", "string")
                    .put("description", "已创建的小程序 app_id")),
                listOf("app_id")))
    }

    // ---------- 执行分发 ----------

    suspend fun execute(name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        when (name) {
            "web_search" -> webSearch(args.optString("query"), args.optInt("max", 8))
            "vision_analyze" -> visionAnalyze(args.optString("image"), args.optString("question", "描述这张图片"))
            "image_generate" -> imageGenerate(args.optString("prompt"))
            "ai_browser" -> {
                val action = args.optString("action", "automate").trim().lowercase()
                when (action) {
                    // 全分支统一 JSONObject（String 与 JSONObject 混类型会让 when 推断成 Any! 炸返回）
                    "search" -> JSONObject().put("text", AiBrowser.search(
                        args.optString("query"), args.optInt("limit", 5).coerceIn(1, 20)))
                    "read" -> JSONObject().put("text", AiBrowser.readPage(args.optString("url")))
                    "automate" -> JSONObject().put("text", AiBrowser.automate(
                        args.optString("query"), args.optInt("depth", 4).coerceIn(1, 8)))
                    "download" -> JSONObject().put("error",
                        "download 暂未接入（可先用 read 读正文或等下个版本）。")
                    else -> JSONObject().put("error", "未知 action: $action（支持 automate/search/read/download）")
                }
            }
            "news_search" -> {
                // 五层纵深（引擎可达性实时波动：同代码 10s 成功/58s 全灭交替）：
                // 管线×2（抗抖动重试）→ AiBrowser 四引擎 → 直搜 → 垂直源；原始 query 不拼接（管线自带改写）
                val q0 = args.optString("query")
                val want = args.optInt("max", 10).coerceAtMost(10)
                suspend fun pipelineOnce(tag: String): JSONObject? {
                    val pipe = runCatching { orchestrator.search(q0) }.getOrNull() ?: return null
                    if (pipe.context.isBlank() || pipe.citations.isEmpty()) return null
                    val cites = JSONArray()
                    for (c in pipe.citations) cites.put(
                        JSONObject().put("index", c.index).put("title", c.title)
                            .put("url", c.url).put("domain", c.domain))
                    return JSONObject()
                        .put("mode", "full_pipeline").put("query", q0)
                        .put("context", pipe.context).put("citations", cites)
                        .put("results", JSONArray(pipe.citations.map { c ->
                            JSONObject().put("title", c.title).put("url", c.url)
                                .put("snippet", c.domain) }))
                        .put("engines", "管线($tag)")
                }
                var pipe = pipelineOnce("首跑")
                if (pipe == null) {
                    kotlinx.coroutines.delay(400)
                    pipe = pipelineOnce("重试")
                }
                if (pipe != null) return@withContext pipe
                val ab = AiBrowser.search(q0 + " 最新 新闻", want)
                if (!ab.startsWith("联网搜索失败") && !ab.startsWith("未从搜索引擎解析到结果")) {
                    return@withContext JSONObject().put("results", JSONArray())
                        .put("text", ab).put("engines", "AiBrowser 回退链")
                }
                val direct: JSONObject = WebSearch.search(http, q0 + " 新闻", want)
                if (!direct.has("error")) return@withContext direct
                val vert = Explore.news(q0, want)
                if (!vert.has("error")) return@withContext vert
                JSONObject().put("error", "新闻搜索五层全部失败（管线×2/AiBrowser/直搜/垂直源）。建议改用 web_search 或稍后重试。")
            }
            "community_search" -> Explore.community(args.optString("query"), args.optInt("max", 10))
            "github_search" -> Explore.github(
                args.optString("query"),
                args.optString("type", "repositories"),
                args.optInt("max", 10)
            )
            "web_fetch" -> webFetch(args.optString("url"), args.optInt("max_chars", 4000))
            "run_js" -> com.genui.app.agent.CodeRuntime.runJs(
                context, args.optString("code", ""))
            "run_python" -> runPython(args.optString("code", ""))
            "list_plugins" -> JSONObject().put("plugins", JSONArray().apply {
                com.genui.app.agent.PluginRuntime.list(context).forEach { p ->
                    put(JSONObject().put("id", p.id).put("name", p.name).put("version", p.version)
                        .put("tools", JSONArray(p.tools.map { it.name })))
                }
            })
            "install_plugin" -> com.genui.app.agent.PluginRuntime.install(
                context, args.optString("name"), args.optString("version", "1.0"),
                args.optString("tools_json"), args.optString("code"))
            "uninstall_plugin" -> com.genui.app.agent.PluginRuntime.uninstall(
                context, args.optString("id"))
            "memory_write" -> memory.write(args.optString("key"), args.optString("content"))
            "memory_read" -> memory.read(args.optString("key"))
            "memory_list" -> memory.list()
            "memory_delete" -> memory.delete(args.optString("key"))
            "time_now" -> timeNow()
            "device_info" -> deviceInfo()
            "notify_send" -> notifySend(args.optString("title", "GenUI"), args.optString("body"))
            "location_get" -> locationGet()
            "file_save" -> fileSave(args.optString("name"), args.optString("content"))
            "file_read" -> fileRead(args.optString("name"))
            "file_list" -> fileList()
            "contacts_search" -> contactsSearch(args.optString("keyword"))
            "sms_compose" -> smsCompose(args.optString("phone"), args.optString("text"))
            "call_dial" -> callDial(args.optString("phone"))
            "alarm_set" -> alarmSet(args.optInt("hour"), args.optInt("minute"), args.optString("label", "GenUI 提醒"))
            "apps_list" -> appsList()
            "app_open" -> appOpen(args.optString("package"))
            "open_url" -> openUrl(args.optString("url"))
            "clipboard_read" -> clipboardRead()
            // ---------- v0.9 扩充 ----------
            "system_status" -> systemStatus()
            "flashlight" -> flashlight(args.optBoolean("on"))
            "tts_speak" -> ttsSpeak(args.optString("text"))
            "share_text" -> shareText(args.optString("title", ""), args.optString("text"))
            "open_settings" -> openSettings(args.optString("page"))
            "log_write" -> logWrite(args.optString("text", ""))
            "log_read" -> logRead(args.optString("day", ""))
            "page_preview" -> pagePreview()
            "page_errors" -> pageErrors()
            "page_eval" -> pageEval(args.optString("js", ""))
            "list_miniapps" -> listMiniApps()
            "open_miniapp" -> openMiniApp(args.optString("app_id", ""))
            "create_miniapp" -> createMiniApp(args.optString("app_id", ""), args)
            "export_miniapp_web" -> exportMiniAppWeb(args.optString("app_id", ""))
            "calendar_query" -> calendarQuery(args.optInt("days", 7))
            "calendar_add" -> calendarAdd(
                args.optString("title"), args.optString("begin"),
                args.optInt("minutes", 60), args.optString("note")
            )
            else -> JSONObject().put("error", "未知工具: $name")
        }
    }

    /** 调用是否应该走权限门禁（按"工具族"检查） */
    private fun rtDecl(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject =
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object")
                .put("properties", props).put("required", JSONArray(required))))

    fun createMiniAppDecl(): JSONObject = rtDecl("create_miniapp", "创建一个完整的小程序（微信小程序语法：app.json/app.js/app.wxss + pages/index/index.{wxml,wxss,js}），保存成功后自动内嵌对话框卡片打开。若调用被打回（返回 error），错误里含具体文件与原因——必须按提示修正后重新调 create_miniapp（通常一次即成），禁止因打回放弃小程序改用网页交付。适合：待办、计算器、查数工具等小应用。【尺寸单位：全部用 rpx（750rpx=整屏宽），禁止 px——否则手机上溢出】【布局：手机竖屏单列；display:flex 必须同时写 flex-direction:column（引擎对未写 direction 的容器一律纵向排布，想横排必须显式 flex-direction:row 且记得 flex-wrap）】【多页】：app.json 的 pages 数组列出全部页面路径（每页 pages/xxx/xxx.{wxml,wxss,js} 四件套齐全），页内 wx.navigateTo({url:'/pages/xxx/xxx'}) 跳转；首屏页放 pages[0]。【JS 语法边界（自研引擎，必须严格遵守否则被打回）】：支持 var/let/const、function 声明/表达式、箭头函数、闭包、对象/数组字面量（普通 key:value 写法）、字符串 + 拼接、if/else/for/while、JSON、Page({data:{...}, onTap: function(){ this.setData({...}) }})、App({})、wx.* API；【禁用】模板字符串（反引号）、解构、展开(...)、默认参数、对象方法简写、class、async/await、可选链?.、空值合并??。",
        JSONObject()
            .put("app_id", JSONObject().put("type", "string").put("description", "英文短 id，如 weather-tool"))
            .put("title", JSONObject().put("type", "string").put("description", "显示标题（写入 app.json 的 navigationBarTitleText）"))
            .put("files", JSONObject().put("type", "object").put("description", "相对路径到文件内容的映射，路径不以 / 开头：{\"app.json\":\"...\",\"app.js\":\"...\",\"app.wxss\":\"...\",\"pages/index/index.wxml\":\"...\",\"pages/index/index.wxss\":\"...\",\"pages/index/index.js\":\"...\"}")),
        listOf("app_id", "files"))

    fun runPyDecl(): JSONObject = rtDecl("run_python",
        "在 GenUI 内置真实 CPython 3.12 解释器中执行 Python 代码（完整 stdlib：json/re/math/datetime/urllib/hashlib/itertools/collections…）。print 输出与异常 traceback 均回传。适合：文本处理、数学计算、数据转换、协议模拟、算法实现。注意：无第三方库（无 requests/numpy），网络用 urllib；纯计算代码即可 return 无需——用 print 输出结果。",
        JSONObject()
            .put("code", JSONObject().put("type", "string").put("description", "Python 源码（完整脚本，用 print 输出结果）。例：import json; print(json.dumps({'sum': sum(range(100))}))")),
        listOf("code"))

    fun pagePreviewDecl(): JSONObject = rtDecl("page_preview", "【画布自检】截取当前画布渲染画面；若已配置视觉模型，会自动描述页面效果（布局/内容/明显问题）。写完界面后先调它看一眼。",
        JSONObject(), emptyList())

    fun pageErrorsDecl(): JSONObject = rtDecl("page_errors", "【画布自检】读取画布页面积累的 JS 运行时错误（console.error 与未捕获异常），读走即清。",
        JSONObject(), emptyList())

    fun pageEvalDecl(): JSONObject = rtDecl("page_eval", "【画布自检/修补】在画布页面内执行任意 JS 并返回结果。可用来测试交互（querySelector 找元素、.click() 模拟点击）、读取 DOM 状态、修补问题（改样式/补内容）。例：{\"js\":\"document.querySelectorAll('button').length\"}",
        JSONObject().put("js", JSONObject().put("type", "string").put("description", "要执行的 JS 表达式（返回值需可直接序列化）")),
        listOf("js"))

    fun runJsDecl(): JSONObject = rtDecl("run_js",
        "在 GenUI 内置 JS 引擎（Chromium）中真实执行 JavaScript。支持 async/await/fetch；console.log 输出与 return 返回值均回传。代码为函数体（自动包 async function）。适合计算、数据处理、算法验证、调外部 REST API。",
        JSONObject()
            .put("code", JSONObject().put("type", "string").put("description", "JS 代码（函数体），例：const r = await fetch('https://api.x.com').then(r=>r.json()); return r;")),
        listOf("code"))

    fun pluginListDecl(): JSONObject = rtDecl("list_plugins", "列出已安装的 GenUI 插件（含各插件声明的工具）。",
        JSONObject(), listOf())

    fun installPluginDecl(): JSONObject = rtDecl("install_plugin",
        "安装 GenUI 插件：JS 代码 + 工具清单，安装后其工具自动并入工具列表（plugin_ 前缀），后续对话可直接调用。code 必须为返回函数映射的函数体，如：return { query: async (args) => { const r = await fetch(...); return await r.json(); } }",
        JSONObject()
            .put("name", JSONObject().put("type", "string").put("description", "插件名"))
            .put("tools_json", JSONObject().put("type", "string").put("description", "工具清单 JSON 数组字符串：[{\"name\":\"query\",\"description\":\"...\",\"parameters\":{\"type\":\"object\",\"properties\":{...},\"required\":[...]}}]"))
            .put("code", JSONObject().put("type", "string").put("description", "插件 JS 代码（函数体），必须 return {工具名: async (args)=>结果} 的函数映射"))
            .put("version", JSONObject().put("type", "string").put("description", "版本号，默认 1.0")),
        listOf("name", "tools_json", "code"))

    fun uninstallPluginDecl(): JSONObject = rtDecl("uninstall_plugin", "卸载指定插件（按 id 或名称）。",
        JSONObject().put("id", JSONObject().put("type", "string").put("description", "插件 id 或名称")),
        listOf("id"))

    /** 运行时工具声明（代码运行时 + 插件运行时），由 AgentLoop 并入 function calling */
    fun runtimeDeclarations(): JSONArray = JSONArray()
        .put(runPyDecl())
        .put(runJsDecl())
        .put(pluginListDecl())
        .put(installPluginDecl())
        .put(uninstallPluginDecl())

    fun gateFor(name: String): String = when {
        name == "run_js" -> "code"
        name == "run_python" -> "code"
        name.startsWith("plugin_") || name.startsWith("install_plugin") ||
            name.startsWith("uninstall_plugin") || name == "list_plugins" -> "plugin"
        name.startsWith("mcp_") -> "mcp"
        name.startsWith("memory") -> "memory"
        name.startsWith("time") -> "time"
        name.startsWith("device") || name == "system_status" -> "device"
        name.startsWith("location") -> "location"
        name.startsWith("file") -> "file"
        name.startsWith("calendar") -> "calendar"
        name == "list_miniapps" || name == "open_miniapp" || name == "create_miniapp" -> "none"
        name.startsWith("page_") || name.startsWith("log_") -> "none"
        name in listOf("web_search", "web_fetch") -> name
        name == "notify_send" -> "notify"
        name == "haptics" || name.startsWith("clipboard") -> name
        name == "flashlight" -> "flashlight"
        name == "tts_speak" -> "tts"
        name.startsWith("share") || name == "open_settings" -> "system"
        else -> name
    }

    // ---------- 各工具实现 ----------

    private fun timeNow(): JSONObject {
        val now = java.util.Date()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd E HH:mm:ss", java.util.Locale.CHINA)
        return JSONObject().put("text", fmt.format(now))
            .put("epoch", now.time)
    }

    // ---------- v0.9 扩充工具实现 ----------

    /** 设备实时状态：电量/充电/网络/存储/内存/音量/亮度 */
    private fun systemStatus(): JSONObject {
        val battery = com.genui.app.bridge.MoBridgeHost.deviceInfoStatic(context)
        // Python 运行时真实状态（AI 自检"有没有 Python"用，不猜）
        val pyProbe = runCatching { com.genui.app.agent.python.PyEngine.probeAvailable(context) }.getOrDefault(false)
        val py = JSONObject()
            .put("native_cpython314", if (pyProbe) "已内置（assets 标准库就绪，run_python 直接可用）" else "未打包")
            .put("fallback", "Chaquopy CPython 3.12（run_python 自动降级可用）")
        val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val totalGB = st.blockCountLong * st.blockSizeLong / 1024.0 / 1024 / 1024
        val freeGB = st.availableBlocksLong * st.blockSizeLong / 1024.0 / 1024 / 1024
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val volPercent = runCatching {
            val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            if (max > 0) audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / max else 0
        }.getOrDefault(0)
        val brightness = runCatching {
            android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        return JSONObject()
            .put("battery", battery.optJSONObject("battery"))
            .put("model", battery.optString("model"))
            .put("os", battery.optString("os"))
            .put("network", battery.optString("network"))
            .put("storage", JSONObject()
                .put("total_gb", String.format(java.util.Locale.US, "%.1f", totalGB).toDouble())
                .put("free_gb", String.format(java.util.Locale.US, "%.1f", freeGB).toDouble())
                .put("used_pct", if (totalGB > 0) ((totalGB - freeGB) / totalGB * 100).toInt() else 0))
            .put("memory", JSONObject()
                .put("total_mb", mi.totalMem / 1024 / 1024)
                .put("avail_mb", mi.availMem / 1024 / 1024)
                .put("used_pct", if (mi.totalMem > 0) ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt() else 0))
            .put("volume_pct", volPercent)
            .put("brightness", if (brightness >= 0) brightness * 100 / 255 else -1)
            .put("python", py)
    }

    /** 手电筒：Camera2 无闪光灯权限时降级为提示 */
    private fun flashlight(on: Boolean): JSONObject = runCatching {
        val cam = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        // 相机权限未授予时 setTorchMode 会抛 SecurityException；提前判断给出可操作提示
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) throw SecurityException(com.genui.app.perms.PermRegistry.guideFor("flashlight"))
        val id = cam.cameraIdList.firstOrNull { cid ->
            cam.getCameraCharacteristics(cid)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw IllegalStateException("此设备没有可用闪光灯")
        cam.setTorchMode(id, on)
        JSONObject().put("ok", true).put("on", on)
    }.getOrElse {
        JSONObject().put("error", "手电筒控制失败：${it.message ?: "未知原因"}（部分设备无闪光灯，或已被相机应用占用）")
    }

    /** 系统 TTS 朗读 */
    private fun ttsSpeak(text: String): JSONObject {
        require(text.isNotBlank()) { "text 不能为空" }
        val clipped = text.take(500)
        // 优先：能力模型 TTS 槽（HTTP TTS，音色/效果远超系统 TTS）；未配置 → 系统 TTS
        val cfg = capStore.loadCapability("tts")
        if (!cfg["baseUrl"].isNullOrBlank() && !cfg["model"].isNullOrBlank()) {
            return runCatching {
                val body = JSONObject().put("model", cfg["model"]).put("input", clipped)
                    .put("voice", "alloy").put("response_format", "mp3")
                val req = Request.Builder()
                    .url(cfg["baseUrl"]!!.trimEnd('/') + "/audio/speech")
                    .header("Authorization", "Bearer " + cfg["apiKey"])
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val sysOk = systemTtsSpeak(clipped)
                        return JSONObject().put("ok", sysOk)
                            .put("engine", "系统 TTS（HTTP TTS HTTP ${resp.code} 降级）")
                            .put("chars", clipped.length)
                    }
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (bytes.isEmpty()) {
                        val sysOk = systemTtsSpeak(clipped)
                        return JSONObject().put("ok", sysOk).put("engine", "系统 TTS（空响应降级）")
                    }
                    val f = java.io.File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
                    f.writeBytes(bytes)
                    val mp = android.media.MediaPlayer()
                    mp.setDataSource(f.absolutePath)
                    mp.setOnCompletionListener { mp.release() }
                    mp.prepare(); mp.start()
                    JSONObject().put("ok", true).put("engine", "能力模型 TTS（${cfg["model"]}）")
                        .put("chars", clipped.length)
                }
            }.getOrElse {
                val sysOk = systemTtsSpeak(clipped)
                JSONObject().put("ok", sysOk).put("engine", "系统 TTS（HTTP 异常降级：${it.message}）")
            }
        }
        val sysOk = systemTtsSpeak(clipped)
        return JSONObject().put("ok", sysOk).put("engine", "系统 TTS（可在能力模型配置 HTTP TTS）")
            .put("chars", clipped.length)
    }

    /** 系统 TTS 播报（主线程 post），返回是否成功入队。 */
    private fun systemTtsSpeak(text: String): Boolean {
        var queued = false
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                val tts = android.speech.tts.TextToSpeech(context) { }
                tts.language = java.util.Locale.CHINA
                tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "gen")
                queued = true
            }
        }
        Thread.sleep(80)   // post 异步，稍候让主线程入队（简化同步）
        return true
    }

    /** 系统分享面板 */
    private fun shareText(title: String, text: String): JSONObject {
        require(text.isNotBlank()) { "text 不能为空" }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            if (title.isNotBlank()) putExtra(android.content.Intent.EXTRA_SUBJECT, title)
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(android.content.Intent.createChooser(send, title.ifBlank { "分享" })
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return JSONObject().put("ok", true)
    }

    /** 跳系统设置页 */
    private fun openSettings(page: String): JSONObject {
        val action = when (page.lowercase()) {
            "wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> android.provider.Settings.ACTION_SOUND_SETTINGS
            "battery" -> android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "notification" -> "android.settings.NOTIFICATION_SETTINGS"
            "apps" -> android.provider.Settings.ACTION_APPLICATION_SETTINGS
            "about" -> android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS
            else -> android.provider.Settings.ACTION_SETTINGS
        }
        runCatching {
            context.startActivity(android.content.Intent(action)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.getOrElse {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return JSONObject().put("ok", true).put("page", page)
    }

    /** 查日历事件 */
    /** 小程序根目录：filesDir/miniapps —— 与 MiniAppEngine.userAppsRoot 一致 */
    private fun miniAppsRoot(): java.io.File =
        java.io.File(context.filesDir, "miniapps").apply { mkdirs() }

    private fun listMiniApps(): JSONObject {
        val arr = org.json.JSONArray()
        // 内置（assets/miniprograms）
        runCatching {
            context.assets.list("miniprograms")?.forEach { id ->
                val cfg = try {
                    context.assets.open("miniprograms/$id/app.json").bufferedReader().use { r -> r.readText() }
                } catch (_: Exception) { "" }
                val title = miniAppTitle(cfg, id)
                arr.put(JSONObject().put("app_id", id).put("title", title).put("source", "内置"))
            }
        }
        // 用户生成（filesDir/miniapps）
        miniAppsRoot().listFiles { f -> f.isDirectory && java.io.File(f, "app.json").exists() }?.sortedBy { it.name }?.forEach { d ->
            val cfg = runCatching { java.io.File(d, "app.json").readText() }.getOrDefault("")
            val title = miniAppTitle(cfg, d.name)
            arr.put(JSONObject().put("app_id", d.name).put("title", title).put("source", "你创建的"))
        }
        return JSONObject().put("apps", arr)
            .put("hint", "用 open_miniapp 打开；用 create_miniapp 创建新的。")
    }

    // ---------- 画布自检三件套（page_preview / page_errors / page_eval） ----------

    /** 截取画布当前渲染画面 → agent_files/preview_<ts>.png；视觉模型可用时自动自评 */
    private fun pagePreview(): JSONObject {
        val w = com.genui.app.agent.CanvasHub.web
            ?: throw IllegalStateException("画布不存在（当前不在画布模式）")
        val shotFile = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val bmp = android.graphics.Bitmap.createBitmap(
                    w.width.coerceAtLeast(1), w.height.coerceAtLeast(1),
                    android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                w.draw(canvas)
                val dir = java.io.File(context.filesDir, "agent_files").apply { mkdirs() }
                val f = java.io.File(dir, "preview_${System.currentTimeMillis()}.png")
                f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                bmp.recycle()
                f
            }
        }
        val out = JSONObject()
            .put("saved", "preview_${shotFile.name.removePrefix("preview_")}")
            .put("file", shotFile.name)
            .put("size_kb", shotFile.length() / 1024)
        // 视觉模型可用 → 自动自评一步到位
        val vision = runCatching { visionAnalyze(shotFile.name,
            "这是 GenUI 画布渲染出的界面截图。简评：1) 布局是否完整正常 2) 内容是否充实（还是空壳/占位）3) 有无明显渲染问题（错位/空白/乱码）4) 一句话总评（可用/需修）。150 字内。")
        }.getOrDefault(JSONObject().put("error", "视觉自评不可用"))
        if (!vision.has("error") && vision.optString("analysis").isNotBlank()) {
            out.put("visual_review", vision.optString("analysis"))
        } else {
            out.put("hint", "视觉模型未配置，无法自动看图。可调 page_eval 检查 DOM 内容完整性。")
        }
        return out
    }

    /** 读走画布积累的 JS 错误 */
    private fun pageErrors(): JSONObject {
        val errs = com.genui.app.agent.CanvasHub.drainErrors()
        return JSONObject()
            .put("count", errs.size)
            .put("errors", org.json.JSONArray(errs))
            .put("hint", if (errs.isEmpty()) "画布无 JS 错误记录" else "逐条修复后可用 page_eval 验证")
    }

    /** 画布内执行 JS：测试交互 / 读 DOM / 修补页面 */
    private fun pageEval(js: String): JSONObject {
        if (js.isBlank()) throw IllegalArgumentException("js 不能为空")
        val result = kotlinx.coroutines.runBlocking {
            com.genui.app.agent.CanvasHub.evaluate(js)
        }
        return JSONObject()
            .put("result", if (result.length > 4000) result.take(4000) + "…(截断)" else result)
            .put("note", "result 是 JSON 序列化值；「undefined」表示表达式无返回值")
    }

    // ---------- 执行日志（Markdown） ----------

    private fun logWrite(text: String): JSONObject {
        if (text.isBlank()) throw IllegalArgumentException("text 不能为空")
        com.genui.app.agent.AgentLog.note(context, text.take(500))
        return JSONObject().put("written", true)
            .put("file", "agent_logs/" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(java.util.Date()) + ".md")
    }

    private fun logRead(day: String): JSONObject {
        val content = com.genui.app.agent.AgentLog.readDay(context, day)
        val days = com.genui.app.agent.AgentLog.listDays(context).joinToString(", ") { it.first }
        return JSONObject()
            .put("day", day.ifBlank { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date()) })
            .put("markdown", content.take(12000))
            .put("available_days", days)
    }

    /** app.json → navigationBarTitleText（app.json 是标准 JSON，直接解析，不做正则） */
    private fun miniAppTitle(cfg: String, fallback: String): String = runCatching {
        JSONObject(cfg).optJSONObject("window")?.optString("navigationBarTitleText", fallback) ?: fallback
    }.getOrDefault(fallback)

    private fun openMiniApp(appId: String): JSONObject {
        if (appId.isBlank()) throw IllegalArgumentException("app_id 不能为空")
        val dirName = safeDirName(appId)
        val has = runCatching {
            context.assets.list("miniprograms/$dirName")?.isNotEmpty() == true
        }.getOrDefault(false) || java.io.File(miniAppsRoot(), dirName).let { it.isDirectory && java.io.File(it, "app.json").exists() }
        if (!has) throw IllegalArgumentException("小程序不存在：$appId（可先 list_miniapps）")
        val it = android.content.Intent(context, com.genui.app.miniapp.GenUiMiniAppActivity::class.java)
            .putExtra("appId", appId)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(it)
        return JSONObject().put("opened", appId)
    }

    /** app_id → 文件系统安全目录名（确定性双向映射：open/list 用同函数自动兼容） */
    private fun safeDirName(id: String): String {
        val sb = StringBuilder()
        for (c in id.trim()) when {
            c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' -> sb.append(c)
            c in 'A'..'Z' -> sb.append(c.lowercaseChar())
            c.code > 127 -> sb.append("_u").append(Integer.toHexString(c.code))
            else -> sb.append('_')
        }
        val r = sb.toString().ifEmpty { "app" }
        return if (r.length > 64) r.substring(0, 64) + "_" + Integer.toHexString(id.hashCode()) else r
    }

    private fun createMiniApp(appId: String, args: JSONObject): JSONObject {
        // v0.28.5：app_id 支持中文/Unicode（TA 点名补齐）——内部目录名做确定性安全映射，
        // 同名 id 永远映射到同一目录（open/list 自动兼容）
        if (appId.isBlank() || appId.length > 64)
            throw IllegalArgumentException("app_id 需 1-64 字符（支持中文/英文/数字），且不能为空")
        if (appId in setOf("hello", "todo", "hello 小程序"))
            throw IllegalArgumentException("app_id '$appId' 与内置示例冲突，请换一个名字")
        val files = args.optJSONObject("files")
            ?: throw IllegalArgumentException("files 缺失：需为 {路径: 内容} 映射")
        val root = java.io.File(miniAppsRoot(), safeDirName(appId))
        if (root.exists()) root.deleteRecursively()
        val title = args.optString("title", appId)
        // 无 app.json 时兜底生成（保证包结构可运行）
        var hasJson = false
        val keys = (0 until files.length()).map { files.names().getString(it) }
        for (k in keys) {
            val content = files.optString(k)
            if (k.contains("..") || k.startsWith("/")) throw IllegalArgumentException("非法路径：$k")
            val f = java.io.File(root, k)
            f.parentFile?.mkdirs()
            f.writeText(content)
            if (k == "app.json") hasJson = true
        }
        if (!hasJson) {
            java.io.File(root, "app.json").writeText(
                """{"pages":["pages/index/index"],"window":{"navigationBarTitleText":"$title"}}""")
            if (keys.none { it.startsWith("pages/index/index.") }) {
                val p = java.io.File(root, "pages/index/index")
                p.parentFile?.mkdirs()
                p.resolve(".wxml").writeText("<view class=\"box\"><text class=\"tip\">$title</text></view>")
                p.resolve(".wxss").writeText(".box{padding:40rpx}.tip{color:#d9a05b;font-size:32rpx}")
                p.resolve(".js").writeText("Page({data:{}})")
            }
        } else {
            // 标题统一写进 app.json
            val cfgF = java.io.File(root, "app.json")
            val cfg = cfgF.readText()
            if (!cfg.contains("navigationBarTitleText")) {
                cfgF.writeText(cfg.replaceFirst("{", "{\"window\":{\"navigationBarTitleText\":\"$title\"},"))
            }
        }
        // ★ 创建时校验（v0.26.6 重写：校验器自身故障绝不能阻塞创建——v0.26.5 的
        //   miniapp-sdk parseJson 返回 sealed class Json，被 as? Map 转换恒失败，导致合法
        //   app.json 全部误报"缺 pages 数组"、创建 7 连败。原则：确定失败才打回，存疑放行。）
        // ① app.json：软校验（v0.27.4）——元数据不规范不阻断交付：自动生成兜底 app.json
        //   （入口 = files 里第一个含 .wxml 的页面），警告随结果返回供 AI 下次自纠。
        //   交付优先：白屏根因（指令/布局）已在渲染层修复，元数据问题不再 fail-closed。
        val cfgPath = java.io.File(root, "app.json")
        val jsonWarnings = mutableListOf<String>()
        val pages: List<String> = run {
            val parsed = runCatching {
                val arr = org.json.JSONObject(cfgPath.takeIf { it.isFile }?.readText() ?: "{}")
                    .optJSONArray("pages")
                if (arr == null || arr.length() == 0) null
                else (0 until arr.length()).map { arr.getString(it) }
            }.getOrNull()
            if (parsed != null) parsed
            else {
                val entry = keys.filter { it.endsWith(".wxml") }.minOrNull()?.removeSuffix(".wxml")
                if (entry == null) {
                    root.deleteRecursively()
                    throw IllegalArgumentException("files 里没有任何 .wxml 页面文件——小程序至少需要一个页面（如 pages/index/index.wxml）。补齐后重新调 create_miniapp。")
                }
                val fixed = org.json.JSONObject()
                    .put("pages", org.json.JSONArray().put(entry))
                    .put("window", org.json.JSONObject().put("navigationBarTitleText", title))
                cfgPath.writeText(fixed.toString())
                jsonWarnings.add("app.json 缺失/无效，已自动生成兜底（入口页 $entry）")
                listOf(entry)
            }
        }
        // ② 每个页面的 wxml/js 必须存在（wxss 可选；文件系统检查，零误杀）
        for (p in pages) {
            val missing = listOf("$p.wxml", "$p.js").filter { !java.io.File(root, it).isFile }
            if (missing.isNotEmpty()) {
                val names = keys.joinToString()
                root.deleteRecursively()
                throw IllegalArgumentException("页面文件缺失：${missing.joinToString()}（app.json pages 里声明了 $p）\n" +
                    "已提供文件：$names\n补齐后重新调 create_miniapp。")
            }
        }
        // ③ WXML 试解析：软校验（fail-open）——解析器误报不删包不打回，
        //    警告随创建结果返回供 AI 自纠；坏 WXML 打开时引擎会自行报错
        val wxmlWarnings = jsonWarnings
        for (wf in keys.filter { it.endsWith(".wxml") }.sorted()) {
            try {
                com.yuanbao.miniapp.view.WxmlParser().parse(java.io.File(root, wf).readText())
            } catch (e: Exception) {
                wxmlWarnings.add("WXML 解析警告 @$wf：${e.message?.take(200)}")
            }
        }
        // ④ JS 语法预检：引擎级解析，只解析不执行（历史版本已验证无误杀，保留 fail-closed；
        //    引擎自身崩溃等意外异常不视为代码错误，放行）
        val engine = com.yuanbao.miniapp.core.MiniAppEngine.createEngine()
        try {
            for (jsf in keys.filter { it.endsWith(".js") }.sorted()) {
                val code = java.io.File(root, jsf).readText()
                val r = try {
                    engine.evaluate("(function(){\n" + code + "\n})")
                } catch (e: Exception) {
                    null  // 引擎意外崩溃 ≠ 代码错误，放行
                }
                if (r != null && r.isError()) {
                    val err = engine.lastError().take(300)
                    root.deleteRecursively()
                    throw IllegalArgumentException(
                        "JS 语法错误 @$jsf：$err\n" +
                        "自研引擎语法边界（与工具说明一致）：禁用模板字符串(反引号)/解构/展开(...)/默认参数/对象方法简写/class/async/await/可选链?./空值合并??；字符串拼接用 + ；对象写 {key: function(){}} 不写方法简写。修正后重新调 create_miniapp。")
                }
            }
        } finally {
            runCatching { engine.close() }
        }
        // 空壳检测：总内容过薄大概率是骨架/示例残留，提示 AI 补全真实功能
        val totalBytes = keys.sumOf { (java.io.File(root, it).length() / 1L) }
        if (totalBytes < 600) jsonWarnings.add(
            "小程序内容过于单薄（共 ${totalBytes}B）——疑似骨架未填功能，请补全完整业务逻辑与界面后重做")
        val ret = JSONObject().put("created", appId)
            .put("root", root.absolutePath)
            .put("files", org.json.JSONArray(keys))
            .put("syntax", "checked")
            .put("hint", "创建完成（app.json/页面完整性/JS 语法已校验通过），可直接 open_miniapp 打开给用户看。")
        if (wxmlWarnings.isNotEmpty())
            ret.put("warnings", org.json.JSONArray(wxmlWarnings))
                .put("hint", ret.optString("hint") + " 注意存在 WXML 解析警告（不阻塞），建议检查标签闭合与 wx:for 语法。")
        return ret
    }

    /** 网站版小程序：整包编译成独立 HTML（浏览器直接打开/托管/分享——一次生成三端可用） */
    private fun exportMiniAppWeb(appId: String): JSONObject {
        if (appId.isBlank()) throw IllegalArgumentException("app_id 不能为空")
        val dirName = safeDirName(appId)
        val root = java.io.File(miniAppsRoot(), dirName)
        if (!root.isDirectory) {
            // 回退内置 assets 包
            if (runCatching { context.assets.list("miniprograms/$dirName")?.isNotEmpty() == true }.getOrDefault(false))
                throw IllegalArgumentException("内置示例请用 open_miniapp 打开；导出仅支持用户创建的小程序")
            throw IllegalArgumentException("小程序不存在：$appId（可先 list_miniapps）")
        }
        val pkg = com.yuanbao.miniapp.pack.MiniPackage.fromDirectory(root, dirName)
        val html = com.yuanbao.miniapp.webview.MiniWebViewRenderer
            .buildPackageHtml(context, pkg, dirName)
        val out = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "$dirName.web.html")
        out.writeText(html)
        return JSONObject().put("exported", out.absolutePath)
            .put("url_hint", "文件可直接用浏览器打开，或上传到任意静态托管（GitHub Pages/对象存储）变成可分享的网站版小程序")
            .put("size_kb", (out.length() / 1024.0).toInt())
    }

    private fun calendarQuery(days: Int): JSONObject {
        requireCalendarRead()
        val d = days.coerceIn(1, 90)
        val from = System.currentTimeMillis()
        val to = from + d * 24L * 3600 * 1000
        val out = JSONArray()
        val uri = android.provider.CalendarContract.Events.CONTENT_URI
        context.contentResolver.query(
            uri,
            arrayOf(
                android.provider.CalendarContract.Events.TITLE,
                android.provider.CalendarContract.Events.DTSTART,
                android.provider.CalendarContract.Events.DTEND,
                android.provider.CalendarContract.Events.ALL_DAY,
                android.provider.CalendarContract.Events.EVENT_LOCATION
            ),
            "${android.provider.CalendarContract.Events.DTSTART} >= ? AND ${android.provider.CalendarContract.Events.DTSTART} <= ?",
            arrayOf(from.toString(), to.toString()),
            "${android.provider.CalendarContract.Events.DTSTART} ASC"
        )?.use { c ->
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            var n = 0
            while (c.moveToNext() && n < 40) {
                out.put(JSONObject()
                    .put("title", c.getString(0) ?: "(无标题)")
                    .put("begin", fmt.format(java.util.Date(c.getLong(1))))
                    .put("end", if (c.isNull(2)) "" else fmt.format(java.util.Date(c.getLong(2))))
                    .put("allDay", c.getInt(3) == 1)
                    .put("where", c.getString(4) ?: ""))
                n++
            }
        }
        return JSONObject().put("days", d).put("count", out.length()).put("events", out)
    }

    /** 写日历 */
    private fun calendarAdd(title: String, begin: String, minutes: Int, note: String): JSONObject {
        require(title.isNotBlank()) { "title 不能为空" }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
        val start = runCatching { fmt.parse(begin)?.time }
            .getOrNull() ?: throw IllegalStateException("时间格式应为 yyyy-MM-dd HH:mm，收到：$begin")
        val end = start + minutes.coerceIn(5, 24 * 60).toLong() * 60 * 1000
        val intent = android.content.Intent(android.content.Intent.ACTION_INSERT).apply {
            data = android.provider.CalendarContract.Events.CONTENT_URI
            putExtra(android.provider.CalendarContract.Events.TITLE, title)
            putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, end)
            if (note.isNotBlank()) putExtra(android.provider.CalendarContract.Events.DESCRIPTION, note)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            throw IllegalStateException("设备上没有可用的日历应用。请先安装/启用系统日历后再试。")
        }
        return JSONObject().put("ok", true)
            .put("title", title)
            .put("note", "已打开系统日历新建页并预填，由用户确认后保存（不静默写入）")
    }

    /** 日历读取权限守卫：报错文案由 [com.genui.app.perms.PermRegistry] 统一生成 */
    private fun requireCalendarRead() {
        val ok = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CALENDAR
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!ok) throw IllegalStateException(com.genui.app.perms.PermRegistry.guideFor("calendar_query"))
    }

    private fun deviceInfo(): JSONObject = com.genui.app.bridge.MoBridgeHost.deviceInfoStatic(context)

    /**
     * run_python：对齐 ZorvAI 的降级链 ——
     * ① 原生 CPython 3.14（PEP 738 嵌入，完整 stdlib 含 C 扩展，首启解压 ~几秒）
     * ② Chaquopy CPython 3.12（插件管理，stdlib 完整）
     * 原生引擎级错误（不可用/初始化失败）才降级；用户代码报错照实返回不降级。
     */
    private fun runPython(code: String): JSONObject {
        val py = com.genui.app.agent.python.PyEngine
        if (py.probeAvailable(context)) {
            val r = py.run(context, code)
            // 引擎级错误且无任何输出 → 降级；用户代码 traceback 属正常结果
            if (!(r.error != null && r.stdout.isBlank() && r.stderr.isBlank())) {
                return JSONObject()
                    .put("ok", r.error == null)
                    .put("output", (r.stdout + if (r.stderr.isNotBlank()) "\n⚠️ " + r.stderr else "").trim().ifBlank { "（无输出）" })
                    .put("engine", "CPython 3.14 原生嵌入")
                    .let { if (r.error != null) it.put("error", r.error) else it }
            }
        }
        val fallback = com.genui.app.agent.PyRuntime.run(context, code)
        return fallback.put("engine", "CPython 3.12 · Chaquopy")
    }

    private fun notifySend(title: String, body: String): JSONObject =
        com.genui.app.bridge.MoBridgeHost.notifyStatic(context, title, body)

    /** 真实定位：系统权限 ACCESS_FINE_LOCATION 已授权时才可用（权限屏/系统弹窗授权） */
    private fun locationGet(): JSONObject {
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse)
            throw IllegalStateException(com.genui.app.perms.PermRegistry.guideFor("location_get"))
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val providers = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
        var best: android.location.Location? = null
        for (prov in providers) {
            runCatching {
                val loc = lm.getLastKnownLocation(prov)
                val cur = best
                if (loc != null && (cur == null || loc.time > cur.time)) best = loc
            }
        }
        val loc = best ?: throw IllegalStateException("暂无位置缓存，请打开地图类应用后重试或稍后再试")
        val geo = android.location.Geocoder(context, java.util.Locale.CHINA)
        val city = runCatching {
            @Suppress("DEPRECATION")   // 逆地理编码：新 API 需要额外依赖，此处沿用平台能力
            val addrs = geo.getFromLocation(loc.latitude, loc.longitude, 1)
            addrs?.firstOrNull()?.let { a -> a.locality ?: a.subAdminArea ?: a.adminArea ?: "" }
        }.getOrNull() ?: ""
        return JSONObject()
            .put("lat", loc.latitude).put("lng", loc.longitude)
            .put("accuracy_m", loc.accuracy.toInt())
            .put("city", city)
            .put("ts", loc.time)
    }

    private fun fileDir(): File = File(context.filesDir, "agent_files").apply { mkdirs() }

    private fun safeName(n: String): String {
        val cleaned = n.replace("/", "_").replace("..", "_").ifBlank { "untitled.txt" }
        return cleaned
    }

    private fun fileSave(name: String, content: String): JSONObject {
        val f = File(fileDir(), safeName(name))
        f.writeText(content)
        return JSONObject().put("ok", true).put("path", f.absolutePath).put("bytes", content.length)
    }

    private fun fileRead(name: String): JSONObject {
        val f = File(fileDir(), safeName(name))
        if (!f.exists()) throw IllegalStateException("文件不存在：$name")
        val text = f.readText()
        return JSONObject().put("name", name).put("content", text.take(8000))
    }

    /**
     * web_search 主实现：先走完整管线（改写→检索→重排→精读→引用打包），
     * 管线拿到有效上下文时返回带 [n] 引用的结构化资料；
     * 管线空手而归（全部引擎不可达/查询过偏）时，退回本地多引擎直搜兜底（实现见 [WebSearch]，
     * 返回每个引擎的具体失败原因，便于模型换关键词或改用 web_fetch），
     * 两层都失败才返回错误与人话提示 —— 联网能力两层纵深，任何一层挂掉不断崖。
     */
    private suspend fun webSearch(query: String, max: Int): JSONObject {
        val q = query.trim()
        if (q.isBlank()) return JSONObject().put("error", "query 不能为空")

        // 第一层：完整管线（ZorvAI 移植）；死因记录进 pipelineError，兜底时可见
        val pipelineResult = runCatching { orchestrator.search(q) }
        val pipelineError = pipelineResult.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + (it.message ?: "") }
        pipelineResult.getOrNull()?.let { b ->
            if (b.context.isNotBlank()) {
                val cites = JSONArray()
                for (c in b.citations) cites.put(
                    JSONObject()
                        .put("index", c.index).put("title", c.title).put("url", c.url)
                        .put("domain", c.domain).put("published_at", c.publishedAt)
                        .put("truncated", c.truncated)
                )
                val src = StringBuilder()
                for (c in b.citations) src.append('[').append(c.index).append("] ")
                    .append(c.title).append(" — ").append(c.url).append('\n')
                val timings = JSONObject()
                for ((k, v) in b.timings) timings.put(k, v)
                return JSONObject()
                    .put("mode", "full_pipeline")
                    .put("query", q)
                    .put("rewritten_queries", JSONArray(b.queries))
                    .put("context", b.context + "\n\n引用来源：\n" + src)
                    .put("citations", cites)
                    .put("timings_ms", timings)
                    .put("usage_note", "context 中已有 [n] 编号的资料片段；回答时在关键事实后标注对应 [n]")
            }
        }

        // 第二层：本地多引擎直搜（标题/链接/摘要，无正文精读）
        val legacy = WebSearch.search(http, q, max)
        // 失败可见化：把管线层的具体死因与引擎熔断状态带给模型/用户，可诊断而不是黑盒
        pipelineError?.let { legacy.put("pipeline_error", it) }
        legacy.put("engine_health", com.genui.app.websearch.net.EngineHealth.summary())
        return legacy
    }

    /**
     * 抓取网页正文。
     *
     * 相比旧版增强：
     * - 正文抽取优先取 article/main/content 区域，剔除导航/页脚/脚本噪声；
     * - 保留段落换行（旧版把所有换行压成一行，长文难读）；
     * - 按 Content-Type 的 charset 正确解码（旧版只靠 string() 猜，中文常乱码）；
     * - 给出截断提示与重定向后的最终地址。
     */
    /**
     * 视觉理解：OpenAI 兼容 chat completions + image_url（base64/URL）。
     * 「能力模型 → 视觉理解」未配置时如实报错引导，不静默降级。
     */
    private fun visionAnalyze(imageRef: String, question: String): JSONObject {
        val cfg = capStore.loadCapability("vision")
        if (cfg["baseUrl"].isNullOrBlank() || cfg["model"].isNullOrBlank())
            return JSONObject().put("error", "视觉模型未配置。请到 设置 → 能力模型 → 视觉理解 填写端点/Key/模型后重试。")
        return runCatching {
            // 图片来源：http(s) 直接用 URL；否则当 agent_files 内文件读出 base64
            val urlPart = if (imageRef.startsWith("http")) imageRef else {
                val f = resolveAgentFile(imageRef)
                    ?: return JSONObject().put("error", "找不到图片：$imageRef（agent_files 内无此文件，也不是 URL）")
                val mime = when (f.extension.lowercase()) {
                    "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/jpeg"
                }
                "data:$mime;base64," + android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
            }
            val content = org.json.JSONArray()
                .put(JSONObject().put("type", "text").put("text", question.ifBlank { "描述这张图片" }))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", urlPart)))
            val body = JSONObject()
                .put("model", cfg["model"])
                .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                .put("max_tokens", 800)
            val req = Request.Builder()
                .url(cfg["baseUrl"]!!.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer " + cfg["apiKey"])
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful)
                    return JSONObject().put("error", "视觉模型 HTTP ${resp.code}：${text.take(200)}")
                val msg = org.json.JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
                JSONObject().put("answer", msg.ifBlank { "（视觉模型返回空）" })
                    .put("model", cfg["model"])
            }
        }.getOrElse { JSONObject().put("error", "视觉调用失败：${it.message}") }
    }

    /** agent_files 内按文件名/模糊匹配解析文件（"news.png"、"1_news.png" 等形式都可命中） */
    private fun resolveAgentFile(ref: String): java.io.File? {
        val dir = java.io.File(context.filesDir, "agent_files")
        val exact = java.io.File(dir, ref)
        if (exact.exists()) return exact
        return dir.listFiles()?.firstOrNull { it.name.endsWith(ref) || it.name.contains(ref) }
    }

    /**
     * 图片生成：标准 OpenAI images/generations（b64_json 或 url 响应都兼容）。
     * 生成图保存到 agent_files，返回路径供展示/file_read。
     */
    private fun imageGenerate(prompt: String): JSONObject {
        val cfg = capStore.loadCapability("imageGen")
        if (cfg["baseUrl"].isNullOrBlank() || cfg["model"].isNullOrBlank())
            return JSONObject().put("error", "图片生成模型未配置。请到 设置 → 能力模型 → 图片生成 填写端点/Key/模型后重试。")
        if (prompt.isBlank()) return JSONObject().put("error", "prompt 不能为空")
        return runCatching {
            val body = JSONObject()
                .put("model", cfg["model"]).put("prompt", prompt)
                .put("n", 1).put("response_format", "b64_json")
            val req = Request.Builder()
                .url(cfg["baseUrl"]!!.trimEnd('/') + "/images/generations")
                .header("Authorization", "Bearer " + cfg["apiKey"])
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful)
                    return JSONObject().put("error", "图片生成 HTTP ${resp.code}：${text.take(200)}")
                val root = org.json.JSONObject(text)
                val item = root.optJSONArray("data")?.optJSONObject(0)
                val b64 = item?.optString("b64_json").orEmpty()
                val remoteUrl = item?.optString("url").orEmpty()
                val bytes: ByteArray = when {
                    b64.isNotBlank() -> runCatching {
                        android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                    }.getOrDefault(ByteArray(0))
                    remoteUrl.startsWith("http") ->
                        http.newCall(Request.Builder().url(remoteUrl).build()).execute().use { r2 ->
                            if (r2.isSuccessful) r2.body?.bytes() ?: ByteArray(0) else ByteArray(0)
                        }
                    else -> ByteArray(0)
                }
                if (bytes.isEmpty()) return JSONObject().put("error", "生成响应里没有图片数据：${text.take(200)}")
                val dir = java.io.File(context.filesDir, "agent_files").apply { mkdirs() }
                val f = java.io.File(dir, "gen_${System.currentTimeMillis()}.png")
                f.writeBytes(bytes)
                JSONObject().put("ok", true).put("path", f.absolutePath)
                    .put("bytes", bytes.size)
                    .put("hint", "图片已保存，可在对话中让用户打开查看（或用 web_fetch 展示 URL 时引用此文件）。")
            }
        }.getOrElse { JSONObject().put("error", "图片生成失败：${it.message}") }
    }

    private fun webFetch(url: String, maxChars: Int): JSONObject {
        val target = url.trim()
        if (!target.startsWith("http://") && !target.startsWith("https://"))
            return JSONObject().put("error", "网址必须以 http:// 或 https:// 开头").put("url", target)
        return runCatching {
            val req = Request.Builder()
                .url(target)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
                )
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept", "text/html,application/xhtml+xml,text/plain,*/*;q=0.8")
                .build()
            fetchHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return JSONObject().put("url", target)
                        .put("error", "HTTP ${resp.code} ${resp.message.ifBlank { "" }}".trim())
                        .put("hint", httpHint(resp.code))
                }
                val ct = resp.header("Content-Type").orEmpty()
                val rawBytes = resp.body?.bytes() ?: ByteArray(0)
                if (rawBytes.isEmpty())
                    return JSONObject().put("url", target).put("error", "响应为空").put("content_type", ct)

                val mime = ct.substringBefore(';').trim().lowercase()
                val isHtml = mime.contains("html") || mime.contains("xml")
                val isText = isHtml || mime.startsWith("text/") || mime.contains("json")
                if (!isText) {
                    return JSONObject().put("url", target).put("content_type", ct)
                        .put("bytes", rawBytes.size)
                        .put("hint", "非文本内容（${mime.ifBlank { "未知类型" }}），未解析。若需引用请让用户提供摘要。")
                }

                val charset = detectCharset(ct, rawBytes)
                val raw = runCatching { String(rawBytes, charset) }
                    .getOrElse { String(rawBytes, Charsets.UTF_8) }

                val text = if (isHtml) Text.article(raw) else raw.trim()
                if (text.isBlank())
                    return JSONObject().put("url", target).put("content_type", ct)
                        .put("hint", "页面没有可提取的正文（可能是纯 JS 渲染的页面）。")

                val limit = maxChars.coerceIn(500, 40_000)
                val clipped = text.length > limit
                val content = if (clipped) text.take(limit) else text

                val finalUrl = resp.request.url.toString()
                return JSONObject()
                    .put("url", target)
                    .also { if (finalUrl != target) it.put("final_url", finalUrl) }
                    .put("content_type", mime)
                    .put("charset", charset.name())
                    .put("chars", text.length)
                    .put("truncated", clipped)
                    .put("content", if (clipped) "$content\n…（已截断，原文共 ${text.length} 字）" else content)
            }
        }.getOrElse { e ->
            JSONObject().put("url", target)
                .put("error", "抓取失败：${humanizeNetError(e)}")
                .put("hint", "确认网址可公网访问、且不是需要登录的页面；也可改用 web_search 找别的来源。")
        }
    }

    /** HTTP 状态码 → 可操作的人话提示。 */
    private fun httpHint(code: Int): String = when (code) {
        401, 403 -> "该页面需要登录或拒绝了爬取，换其他来源试试。"
        404 -> "页面不存在，检查网址是否正确。"
        429 -> "被限流，稍等片刻或换其他来源。"
        in 500..599 -> "对方服务器异常，稍后重试。"
        else -> "可稍后重试或换其他来源。"
    }

    /** 从 Content-Type 的 charset 或 BOM / meta 猜编码，中文站常见 GBK 必须处理。 */
    private fun detectCharset(contentType: String, bytes: ByteArray): java.nio.charset.Charset {
        Regex("""charset=["']?([\w-]+)""", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)
            ?.let { name -> runCatching { java.nio.charset.Charset.forName(name) }.getOrNull() }
            ?.let { return it }
        // BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return Charsets.UTF_8
        // 从前 2048 字节里找 <meta charset>
        val head = runCatching { String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1) }.getOrDefault("")
        Regex("""charset=["']?([\w-]+)""", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1)
            ?.let { name -> runCatching { java.nio.charset.Charset.forName(name) }.getOrNull() }
            ?.let { return it }
        return Charsets.UTF_8
    }

    private fun humanizeNetError(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "域名解析失败（网络不通或网址有误）"
        is java.net.SocketTimeoutException -> "连接超时"
        is javax.net.ssl.SSLException -> "SSL 证书校验失败"
        is java.net.ConnectException -> "连接被拒绝"
        else -> e.message ?: e.javaClass.simpleName
    }


    // ---------- 系统能力：通信 / 定时 / 应用 / 剪贴板 ----------

    private fun contactsSearch(keyword: String): JSONObject {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw IllegalStateException(com.genui.app.perms.PermRegistry.guideFor("contacts_search"))
        val out = JSONArray()
        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
            arrayOf("%$keyword%", "%$keyword%"),
            null
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n < 10) {
                out.put(JSONObject().put("name", c.getString(0) ?: "").put("phone", c.getString(1) ?: ""))
                n++
            }
        }
        return JSONObject().put("count", out.length()).put("contacts", out)
    }

    /** 打开短信编辑页（用户确认后自己点发送；不自动发送） */
    private fun smsCompose(phone: String, text: String): JSONObject {
        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO,
            android.net.Uri.parse("smsto:$phone")).apply {
            putExtra("sms_body", text)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return JSONObject().put("ok", true).put("note", "已打开短信编辑页，由用户确认发送")
    }

    private fun callDial(phone: String): JSONObject {
        val intent = android.content.Intent(android.content.Intent.ACTION_DIAL,
            android.net.Uri.parse("tel:$phone")).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
        return JSONObject().put("ok", true).put("note", "已打开拨号盘，由用户手动拨出")
    }

    /** 真闹钟：AlarmManager 精确闹钟（Android 12+ 需用户在系统设置授权精确闹钟） */
    private fun alarmSet(hour: Int, minute: Int, label: String): JSONObject {
        require(hour in 0..23 && minute in 0..59) { "时间不合法：$hour:$minute" }
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            if (before(now)) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        val pi = android.app.PendingIntent.getBroadcast(
            context, (hour * 60 + minute) % 65536,
            android.content.Intent(context, com.genui.app.bridge.AlarmReceiver::class.java)
                .putExtra("label", label),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val canExact = if (android.os.Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
        if (canExact) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, target.timeInMillis, pi)
        else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, target.timeInMillis, pi)
        return JSONObject()
            .put("ok", true).put("ring_at", java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(target.time))
            .put("label", label)
            .put("exact", canExact)
    }

    private fun appsList(): JSONObject {
        val pm = context.packageManager
        val out = JSONArray()
        pm.getInstalledApplications(0).sortedBy { it.loadLabel(pm).toString() }.forEach { app ->
            if (pm.getLaunchIntentForPackage(app.packageName) != null)
                out.put(JSONObject().put("app", app.loadLabel(pm).toString()).put("pkg", app.packageName))
        }
        val clipped = JSONArray()
        for (i in 0 until minOf(40, out.length())) clipped.put(out.get(i))
        return JSONObject().put("count", out.length()).put("apps", clipped)
    }

    private fun appOpen(pkg: String): JSONObject {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw IllegalStateException("未安装或不可启动：$pkg")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject().put("ok", true).put("pkg", pkg)
    }

    private fun openUrl(url: String): JSONObject {
        require(url.startsWith("http")) { "仅支持 http(s) 链接" }
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return JSONObject().put("ok", true).put("url", url)
    }

    private fun clipboardRead(): JSONObject {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        return JSONObject().put("text", text)
    }

    private fun fileList(): JSONObject {
        val out = JSONArray()
        fileDir().listFiles()?.sortedByDescending { it.lastModified() }?.forEach {
            out.put(JSONObject().put("name", it.name).put("bytes", it.length()))
        }
        return JSONObject().put("count", out.length()).put("files", out)
    }

}
