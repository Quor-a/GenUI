package com.yuanbao.miniapp.core

import android.content.Context
import android.graphics.Color
import android.widget.FrameLayout
import com.yuanbao.miniapp.pack.MiniPackage
import com.yuanbao.miniapp.webview.MiniWebViewRenderer

/**
 * 小程序通用宿主：微信双轨架构的容器层。
 *
 * 两套渲染引擎并存，整包默认引擎由 app.json 的 "renderer" 决定
 * （"webview"=传统 WebView 通道，默认启用；"skyline"=自研原生引擎）：
 *
 *  - WebView 通道：系统内核渲染，CSS 全量兼容；整包单文档页共享渲染实例
 *  - Skyline 通道（[MiniAppView]）：自研 GUI 引擎直连 Skia——逻辑层(C++JS引擎)
 *    与视图层(FlexLayout+Skia)分离、共享实例、无 WebView，内存与启动开销更低；
 *    CSS 特性集为精简子集
 *
 * 跨引擎混跳（页面粒度迁移）：page.json 显式声明 "renderer" 的页面被打开时，
 * 宿主切换到对应引擎容器从该页启动；返回时回到主引擎。
 */
class MiniAppHostView private constructor(
    context: Context,
    private val pkg: MiniPackage,
    private val appId: String,
    private val defaultRenderer: String
) : FrameLayout(context) {

    private var webRenderer: MiniWebViewRenderer? = null
    private var nativeRenderer: MiniAppView? = null
    private var currentEngine: String = defaultRenderer

    var onTitle: (String) -> Unit = {}
    var onError: (String) -> Unit = {}

    /** 与 [MiniAppView] 同名兼容 API */
    fun setTitleListener(listener: (String) -> Unit) { onTitle = listener }
    fun setErrorListener(listener: (String) -> Unit) { onError = listener }

    companion object {
        /**
         * 引擎分派入口：读 app.json "renderer"。
         * 默认 skyline（原生引擎）——存量小程序均按原生引擎调教生成；
         * WebView 通道（CSS 全量兼容）为 opt-in：app.json 显式 "renderer":"webview"。
         */
        fun create(context: Context, pkg: MiniPackage, appId: String): MiniAppHostView {
            val renderer = runCatching {
                Regex("\"renderer\"\\s*:\\s*\"(webview|skyline)\"")
                    .find(pkg.read("app.json") ?: "")?.groupValues?.get(1)
            }.getOrNull() ?: "skyline"
            return MiniAppHostView(context, pkg, appId, renderer)
        }
    }

    init {
        setBackgroundColor(Color.parseColor("#141210"))
        openEngine(defaultRenderer, entryPage = null)
    }

    /** 按引擎名挂载对应容器并启动（entryPage 非空 = 跨引擎混跳落点） */
    private fun openEngine(engine: String, entryPage: String?) {
        removeAllViews()
        currentEngine = engine
        when (engine) {
            "skyline" -> {
                val v = MiniAppEngine.create(context, pkg)
                v.setBackgroundColor(Color.parseColor("#141210"))
                v.setTitleListener { t -> onTitle(t) }
                v.setErrorListener { e -> onError(e) }
                addView(v, LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                nativeRenderer = v
                webRenderer = null
            }
            else -> {
                val w = MiniWebViewRenderer(context, pkg, appId)
                w.onTitle = { t -> onTitle(t) }
                w.onError = { e -> onError(e) }
                w.onSwitchEngine = { page ->                 // WebView → 原生混跳
                    openEngine("skyline", entryPage = page)
                }
                addView(w, LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                webRenderer = w
                nativeRenderer = null
                if (entryPage != null) {
                    w.post { w.eval("location.hash='#/'+" + jsonEscape(entryPage) +
                        ";window.dispatchEvent(new Event('hashchange'))") }
                }
            }
        }
    }

    private fun jsonEscape(s: String): String = "\"" + s.replace("\"", "\\\"") + "\""

    /** 原生 → WebView 混跳入口（由原生引擎导航扩展调用；v1 骨架） */
    fun switchToWebView(page: String?) {
        openEngine("webview", entryPage = page)
    }

    /** 系统返回：WebView 有历史则内退；否则交由宿主处理 */
    fun handleBack(): Boolean = webRenderer?.goBack() ?: false

    /** 释放两引擎资源（WebView 内存 / 原生引擎线程与 Skia） */
    fun release() {
        removeAllViews()
        nativeRenderer?.let { MiniAppEngine.destroy(it) }
        nativeRenderer = null
        webRenderer?.release()
        webRenderer = null
    }
}
