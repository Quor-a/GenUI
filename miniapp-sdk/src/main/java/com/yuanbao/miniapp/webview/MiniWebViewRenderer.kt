package com.yuanbao.miniapp.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.yuanbao.miniapp.pack.MiniPackage

/**
 * WebView 渲染通道（微信双轨架构的「传统通道」角色，默认启用）。
 *
 * 经典双线程模型的工程化落位：
 *  - 界面：系统 WebView 内核渲染（完整 CSS：Grid/多背景/滤镜…自研引擎暂不支持的全量特性）
 *  - 逻辑：页面 JS 在 WebView 的 JS 上下文执行，由 wv-runtime.js 提供
 *          Page()/App()/setData 响应式与 wx: 指令展开（框架代码与业务同上下文，
 *          绕开序列化桥——与 Skyline 的"共享上下文"思路一致）
 *  - 桥：toast/loading/标题/跨引擎跳转经 [Bridge] 进原生；网络 fetch、存储 localStorage 用内核原生能力
 *  - 页面共享一个渲染实例（整包单文档 + hash 路由），无逐页 WebView 开销
 *
 * 跨引擎：page.json 的 renderer 与当前引擎不同时，运行时调 Bridge.switchEngine，
 * 由 MiniAppHostView 完成整容器切换（页面/分包粒度迁移的落点）。
 */
class MiniWebViewRenderer(
    context: Context,
    private val pkg: MiniPackage,
    private val appId: String
) : FrameLayout(context) {

    /** 宿主回调：标题 / 跨引擎切换（由 MiniAppHostView 注入） */
    var onTitle: (String) -> Unit = {}
    var onSwitchEngine: (page: String) -> Unit = {}
    var onError: (String) -> Unit = {}

    private val webView: WebView = WebView(context)

    init {
        setBackgroundColor(Color.parseColor("#141210"))
        addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setupWebView()
        loadPackage()
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true                       // localStorage（wx 存储语义）
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
        }
        webView.setBackgroundColor(Color.parseColor("#141210"))
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { /* 首屏由运行时驱动 */ }
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(Bridge(), "GSBridge")
    }

    private fun loadPackage() {
        val html = buildDocument()
        webView.loadDataWithBaseURL("https://genui-miniapp.local/", html, "text/html", "UTF-8", null)
    }

    /** 整包编译：所有页面的 HTML/JS/WXSS 预编译进单文档，运行时按 hash 路由展开 */
    private fun buildDocument(): String {
        val pages = runCatching { com.yuanbao.miniapp.pack.AppConfig.parse(pkg.read("app.json") ?: "{}").pages }
            .getOrDefault(emptyList())
        val pageDefs = StringBuilder()
        for (p in pages) {
            val wxml = pkg.pageWxml(p) ?: continue
            val js = pkg.pageJs(p) ?: ""
            val wxss = pkg.pageWxss(p) ?: ""
            val pj = pkg.pageJson(p) ?: "{}"
            pageDefs.append("GS.registerPage(")
                .append(jsonStr(p)).append(',')
                .append(jsonStr(WxmlToHtml.convertWxml(wxml))).append(',')
                .append(jsonStr(js)).append(',')
                .append(jsonStr(WxmlToHtml.convertWxss(wxss))).append(',')
                .append(jsonStr(pageRenderer(pj)))
                .append(");\n")
        }
        val appWxss = WxmlToHtml.convertWxss(pkg.read("app.wxss") ?: "")
        val rt = assetsText("miniapp/wv-runtime.js")
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover, user-scalable=no\">")
            append("<style>:root{--gs-base:#141210}html,body{margin:0;padding:0;background:#141210;color:#eee;")
            append("font-family:-apple-system,'Microsoft YaHei',sans-serif;-webkit-tap-highlight-color:transparent}")
            append("img{max-width:100%}input,textarea,button{font-family:inherit}</style>")
            append("<style>").append(appWxss).append("</style>")
            append("</head><body><div id=\"app\"></div><script>")
            append(rt)
            append("\n;GS.setPackageRoot(").append(jsonStr(appId)).append(");\n")
            append(pageDefs)
            append("</script></body></html>")
        }
    }

    /** 读页面 json 的 renderer 字段（"webview"|"skyline"，缺省随 app.json，这里只透传显式值） */
    private fun pageRenderer(pageJson: String): String = runCatching {
        Regex("\"renderer\"\\s*:\\s*\"(webview|skyline)\"").find(pageJson)?.groupValues?.get(1) ?: ""
    }.getOrDefault("")

    private fun assetsText(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n"); '\r' -> sb.append("")
            '<' -> sb.append("\\u003c"); '>' -> sb.append("\\u003e")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }

    /** 宿主驱动：执行页内 JS（跨引擎混跳入口用） */
    fun eval(js: String) = webView.evaluateJavascript(js, null)

    fun goBack(): Boolean =
        if (webView.canGoBack()) { webView.goBack(); true } else false

    /** 释放 WebView（ destroy 前必须先从视图树摘除） */
    fun release() {
        webView.stopLoading()
        webView.removeAllViews()
        webView.destroy()
        removeAllViews()
    }

    /** 原生桥：toast/loading/标题/跨引擎切换（网络=fetch、存储=localStorage，走内核能力） */
    inner class Bridge {
        @JavascriptInterface
        fun post(payload: String) {
            // payload: {"api":"toast|loading|hideLoading|setTitle|switchEngine|navigateBack","args":{...}}
            runCatching {
                val o = com.yuanbao.miniapp.util.parseJson(payload)
                val api = (o as? com.yuanbao.miniapp.util.Json.Obj)?.getOrNull("api")
                    ?.let { (it as? com.yuanbao.miniapp.util.Json.Str)?.value } ?: return
                val args = (o as? com.yuanbao.miniapp.util.Json.Obj)?.get("args")
                postOnMain(api, args?.let { com.yuanbao.miniapp.util.writeJson(it) } ?: "{}")
            }.onFailure { onError("bridge: ${it.message}") }
        }

        private fun postOnMain(api: String, argsJson: String) {
            post { when (api) {
                "setTitle" -> {
                    val t = Regex("\"v\"\\s*:\\s*\"([^\"]*)\"").find(argsJson)?.groupValues?.get(1) ?: ""
                    onTitle(t)
                }
                "switchEngine" -> {
                    val page = Regex("\"page\"\\s*:\\s*\"([^\"]*)\"").find(argsJson)?.groupValues?.get(1) ?: ""
                    onSwitchEngine(page)
                }
                "navigateBack" -> goBack()
                else -> {}                       // toast/loading：v1 由页面内 CSS 提示替代，v2 接原生浮层
            } }
        }

        private fun post(f: () -> Unit) = android.os.Handler(android.os.Looper.getMainLooper()).post(f)
    }
}
