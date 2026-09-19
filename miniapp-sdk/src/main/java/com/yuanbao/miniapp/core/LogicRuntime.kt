package com.yuanbao.miniapp.core

import android.os.Handler
import android.os.HandlerThread
import com.yuanbao.miniapp.js.JsBridge
import com.yuanbao.miniapp.js.JsEngine
import com.yuanbao.miniapp.nativeapi.WxApi
import com.yuanbao.miniapp.pack.MiniPackage
import com.yuanbao.miniapp.util.Json
import com.yuanbao.miniapp.util.parseJson
import com.yuanbao.miniapp.util.writeJson

/**
 * The logic layer: owns the JS engine and the mini program's App/Page objects.
 * Runs on its own thread; talks to the render layer through [RenderHost].
 */
class LogicRuntime(
    private val pkg: MiniPackage,
    private val wxApi: WxApi,
    private val renderHost: RenderHost,
    private val listener: EngineListener?
) {

    interface RenderHost {
        /** New page data arrived (setData / initial render). */
        fun onDataChanged(dataJson: String)
        /** Page template + styles to render. */
        fun onPageLoaded(pagePath: String, wxml: String, wxss: String, pageJson: String?)
        fun onPageClosed()
    }

    interface EngineListener {
        fun onLog(level: String, message: String)
        fun onError(message: String)
        fun onLifecycle(event: String, page: String)
    }

    private val thread = HandlerThread("miniapp-logic").apply { start() }
    val handler = Handler(thread.looper)
    val engine = JsEngine()
    private val timers = HashMap<Int, Runnable>()
    private var nextTimerId = 1

    @Volatile
    private var currentPage: String = ""

    @Volatile
    private var destroyed = false

    init {
        wxApi.attach(engine, handler)
        JsBridge.hostInvoker = { name, argsJson -> handleNative(name, argsJson) }
        JsBridge.logger = { level, msg -> listener?.onLog(level, msg) }
        JsBridge.timerScheduler = { delay, repeat, fnId -> scheduleTimer(delay, repeat, fnId) }
        JsBridge.timerCanceller = { cancelTimer(it) }

        handler.post {
            setupRuntime()
            runApp()
        }
    }

    // ------------------------------------------------------------ runtime glue
    private fun setupRuntime() {
        // host bridge: __native(name, args...)
        engine.registerHostFunction("__native")
        WxApi.API_NAMES.forEach { engine.registerHostFunction("__wx_$it") }

        engine.evaluate(
            """
            var __handlers = {};
            var __page = null;
            var __app = null;
            var __pageData = {};
            var __pageOptions = {};
            var __launchOptions = {};
            var __currentPagePath = '';

            function App(options) {
              __app = options || {};
              globalThis.__appRef = __app;
              if (typeof __app.onLaunch === 'function') { __app.onLaunch(__launchOptions); }
            }

            function Page(options) {
              __page = options || {};
              __pageData = {};
              var src = (__page.data || {});
              for (var k in src) { __pageData[k] = src[k]; }
              __page.data = __pageData;
              __page.setData = function(patch) {
                for (var k in patch) { __pageData[k] = patch[k]; }
                __native('setData', JSON.stringify(__pageData));
              };
              if (typeof __page.onLoad === 'function') { __page.onLoad(__pageOptions); }
            }

            function getApp() { return __app; }
            function getCurrentPages() { return [__currentPagePath]; }

            function __setDataJson(jsonText) {
              var patch = JSON.parse(jsonText);
              for (var k in patch) { __pageData[k] = patch[k]; }
              __native('setData', JSON.stringify(__pageData));
              return true;
            }

            function __dispatch(name, argsJson) {
              if (!__page) { return null; }
              var fn = __page[name];
              if (typeof fn !== 'function') { return null; }
              var args = [];
              try { args = JSON.parse(argsJson || '[]'); } catch (e) { args = []; }
              return fn.apply(__page, args);
            }

            function __lifecycle(name, argsJson) {
              if (!__page) { return null; }
              var fn = __page[name];
              if (typeof fn !== 'function') { return null; }
              var args = [];
              try { args = JSON.parse(argsJson || '[]'); } catch (e) { args = []; }
              return fn.apply(__page, args);
            }

            function __appLifecycle(name, argsJson) {
              if (!__app) { return null; }
              var fn = __app[name];
              if (typeof fn !== 'function') { return null; }
              var args = [];
              try { args = JSON.parse(argsJson || '[]'); } catch (e) { args = []; }
              return fn.apply(__app, args);
            }
            """.trimIndent()
        )

        // wx namespace: each method forwards to a host function
        val wxGlue = buildString {
            append("var wx = {};\n")
            for (name in WxApi.API_NAMES) {
                append("wx.$name = function(){ return __wx_$name.apply(null, arguments); };\n")
            }
            append("globalThis.wx = wx;\n")
        }
        engine.evaluate(wxGlue)
    }

    private fun runApp() {
        val appJs = pkg.appJs
        if (!appJs.isNullOrBlank()) {
            val res = engine.evaluate(appJs)
            if (res.isError()) listener?.onError("app.js: ${res.errorMessage()}")
        }
        listener?.onLifecycle("onLaunch", "")
    }

    /** Called by the host when a page should be created. */
    fun loadPage(pagePath: String, paramsJson: String) {
        handler.post {
            currentPage = pagePath
            engine.evaluate("__pageOptions = $paramsJson;")
            engine.evaluate("__currentPagePath = ${q(pagePath)};")
            val js = pkg.pageJs(pagePath)
            if (!js.isNullOrBlank()) {
                val res = engine.evaluate(js)
                if (res.isError()) listener?.onError("$pagePath.js: ${res.errorMessage()}")
            }
            val data = engine.evaluate("JSON.stringify(__pageData)")
            val dataJson = if (data.isError()) "{}" else data.asString()
            renderHost.onPageLoaded(
                pagePath,
                pkg.pageWxml(pagePath) ?: "",
                listOfNotNull(pkg.appWxss, pkg.pageWxss(pagePath)).joinToString("\n"),
                pkg.pageJson(pagePath)
            )
            renderHost.onDataChanged(dataJson)
            engine.evaluate("__lifecycle('onShow', '[]')")
            listener?.onLifecycle("onLoad", pagePath)
        }
    }

    fun notifyReady() {
        handler.post {
            engine.evaluate("__lifecycle('onReady', '[]')")
            listener?.onLifecycle("onReady", currentPage)
        }
    }

    fun notifyShow() {
        handler.post {
            engine.evaluate("__lifecycle('onShow', '[]')")
            engine.evaluate("__appLifecycle('onShow', '[]')")
        }
    }

    fun notifyHide() {
        handler.post {
            engine.evaluate("__lifecycle('onHide', '[]')")
            engine.evaluate("__appLifecycle('onHide', '[]')")
        }
    }

    fun unloadCurrentPage() {
        handler.post {
            engine.evaluate("__lifecycle('onUnload', '[]')")
            renderHost.onPageClosed()
        }
    }

    /** Event from the render layer: call the page method [handlerName]. */
    fun dispatchEvent(handlerName: String, argsJson: String) {
        handler.post {
            val res = engine.evaluate("__dispatch(${q(handlerName)}, ${q(argsJson)})")
            if (res.isError()) listener?.onError("event $handlerName: ${res.errorMessage()}")
        }
    }

    /** Sends a setData patch (produced by JS) to the render layer. */
    private fun handleNative(name: String, argsJson: String): String {
        val args = runCatching { parseJson(argsJson) }.getOrNull()
        val list = if (args is Json.Arr) args.items else listOf(args ?: Json.Null)
        when (name) {
            "__native" -> {
                val cmd = list.getOrNull(0)?.asString() ?: return "null"
                val payload = list.getOrNull(1)?.asString() ?: ""
                when (cmd) {
                    "setData" -> renderHost.onDataChanged(payload)
                    "log" -> listener?.onLog("info", payload)
                    else -> Unit
                }
                return "null"
            }
            else -> {
                if (name.startsWith("__wx_")) {
                    val api = name.removePrefix("__wx_")
                    return wxApi.dispatch(api, argsJson)
                }
                return "null"
            }
        }
    }

    /** Schedules a JS timer; [fnId] is the engine-side handle of the callback. */
    private fun scheduleTimer(delayMs: Int, repeat: Boolean, fnId: Int): Int {
        val id = nextTimerId++
        val task = object : Runnable {
            override fun run() {
                if (destroyed) return
                engine.invokeFunction(fnId, "[]")
                if (repeat) handler.postDelayed(this, delayMs.toLong())
            }
        }
        timers[id] = task
        handler.postDelayed(task, delayMs.toLong())
        return id
    }

    private fun cancelTimer(id: Int) {
        timers.remove(id)?.let { handler.removeCallbacks(it) }
    }

    fun evaluate(source: String): String {
        val res = engine.evaluate(source)
        return if (res.isError()) res.errorMessage() else res.asString()
    }

    fun destroy() {
        destroyed = true
        handler.post {
            runCatching { engine.close() }
            thread.quitSafely()
        }
    }

    private fun q(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
