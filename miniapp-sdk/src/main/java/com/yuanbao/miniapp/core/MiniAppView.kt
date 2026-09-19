package com.yuanbao.miniapp.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import com.yuanbao.miniapp.nativeapi.WxApi
import com.yuanbao.miniapp.pack.AppConfig
import com.yuanbao.miniapp.pack.MiniPackage
import com.yuanbao.miniapp.render.CanvasPainter
import com.yuanbao.miniapp.render.FlexLayout
import com.yuanbao.miniapp.render.NodeType
import com.yuanbao.miniapp.render.Overflow
import com.yuanbao.miniapp.render.RenderNode
import com.yuanbao.miniapp.render.TextMeasurer
import com.yuanbao.miniapp.util.Json
import com.yuanbao.miniapp.util.parseJson
import com.yuanbao.miniapp.view.TemplateNode
import com.yuanbao.miniapp.view.VDomBuilder
import com.yuanbao.miniapp.view.WxssParser
import com.yuanbao.miniapp.view.WxmlParser

/**
 * A running mini program instance.
 *
 * The view owns:
 *  - a SurfaceView drawn by our own renderer (never android.view.View children)
 *  - the logic runtime (JS engine on its own thread)
 *  - the page stack and the navigation host implementation
 */
class MiniAppView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle),
    SurfaceHolder.Callback,
    LogicRuntime.RenderHost,
    WxApi.NavigationHost {

    private val surfaceView = SurfaceView(context)
    private val overlay = FrameLayout(context)
    private val painter = CanvasPainter()
    private lateinit var logic: LogicRuntime
    private lateinit var wxApi: WxApi
    private var appConfig: AppConfig = AppConfig.empty()
    private var pkg: MiniPackage? = null

    private val lock = Object()
    @Volatile private var dirty = true
    @Volatile private var running = false
    private var renderThread: Thread? = null
    private var surfaceReady = false

    private val pageStack = ArrayList<PageEntry>()
    private var vdom = VDomBuilder()
    private var layoutEngine = FlexLayout(1f, 1f)

    private var titleBar: ((String) -> Unit)? = null
    private var logListener: ((String, String) -> Unit)? = null
    private var errorListener: ((String) -> Unit)? = null
    private var readyListener: ((String) -> Unit)? = null

    private var inputEditor: EditText? = null
    private var activeInputNode: RenderNode? = null

    private var viewportW = 0f
    private var viewportH = 0f

    private data class PageEntry(
        val path: String,
        val params: Map<String, String>,
        var template: TemplateNode,
        var rules: List<WxssParser.Rule>,
        var data: Json = Json.Obj(),
        var root: RenderNode? = null,
        var pageStyle: com.yuanbao.miniapp.render.Style? = null,
        var hasShown: Boolean = false,
        val scrollState: HashMap<Int, Float> = HashMap()
    )

    init {
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surfaceView.holder.addCallback(this)
        setBackgroundColor(Color.WHITE)
    }

    // ------------------------------------------------------------ lifecycle
    fun start(packageLoader: MiniPackage, config: AppConfig) {
        pkg = packageLoader
        appConfig = config
        wxApi = WxApi(context, this, overlay)
        logic = LogicRuntime(packageLoader, wxApi, this, object : LogicRuntime.EngineListener {
            override fun onLog(level: String, message: String) { logListener?.invoke(level, message) }
            override fun onError(message: String) { errorListener?.invoke(message) }
            override fun onLifecycle(event: String, page: String) {
                if (event == "onReady") readyListener?.invoke(page)
            }
        })
        val entry = config.entry
        if (entry.isNotEmpty()) {
            pushPage(entry, emptyMap())
        }
    }

    fun stop() {
        running = false
        renderThread?.interrupt()
        renderThread = null
        if (::logic.isInitialized) logic.destroy()
    }

    fun setTitleListener(listener: (String) -> Unit) { titleBar = listener }
    fun setLogListener(listener: (String, String) -> Unit) { logListener = listener }
    fun setErrorListener(listener: (String) -> Unit) { errorListener = listener }
    fun setReadyListener(listener: (String) -> Unit) { readyListener = listener }

    fun currentPagePath(): String = pageStack.lastOrNull()?.path ?: ""

    // ------------------------------------------------------------ surface
    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        startRenderLoop()
        markDirty()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        viewportW = width.toFloat()
        viewportH = height.toFloat()
        layoutEngine = FlexLayout(viewportW, viewportH)
        relayout()
        markDirty()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        running = false
    }

    private fun startRenderLoop() {
        if (renderThread != null) return
        running = true
        val task = Runnable {
            while (running) {
                synchronized(lock) {
                    if (!dirty) {
                        runCatching { lock.wait(200) }
                    }
                    dirty = false
                }
                if (surfaceReady) {
                    renderFrame()
                }
                notifyFirstFrame()
            }
        }
        renderThread = Thread(task, "miniapp-render").apply { start() }
    }

    private fun renderFrame() {
        val root = pageStack.lastOrNull()?.root ?: return
        val holder = surfaceView.holder ?: return
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas()
            if (canvas != null) {
                painter.rpxRatio = layoutEngine.rpxRatio
                painter.draw(canvas, root, viewportW, viewportH)
            }
        } catch (t: Throwable) {
            // surface lost, next frame retries
        } finally {
            if (canvas != null) {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }
    }

    /** Fires the page's onReady once the first frame has actually been drawn. */
    private fun notifyFirstFrame() {
        val entry = pageStack.lastOrNull() ?: return
        if (entry.hasShown || entry.root == null) return
        entry.hasShown = true
        post { logic.notifyReady() }
    }

    private fun markDirty() {
        synchronized(lock) {
            dirty = true
            lock.notifyAll()
        }
    }

    // ------------------------------------------------------------ render host
    override fun onPageLoaded(pagePath: String, wxml: String, wxss: String, pageJson: String?) {
        val tpl = WxmlParser().parse(wxml)
        val rules = WxssParser().parse(wxss)
        val entry = pageStack.lastOrNull()
        if (entry != null) {
            entry.template = tpl
            entry.rules = rules
        } else {
            pageStack.add(PageEntry(pagePath, emptyMap(), tpl, rules))
        }
        applyWindowStyle(pageJson)
        post { relayout(); markDirty() }
    }

    override fun onDataChanged(dataJson: String) {
        val parsed = runCatching { parseJson(dataJson) }.getOrElse { Json.Obj() }
        val entry = pageStack.lastOrNull() ?: return
        entry.data = parsed
        post { relayout(); markDirty() }
    }

    override fun onPageClosed() {
        pageStack.clear()
        post { markDirty() }
    }

    private fun applyWindowStyle(pageJson: String?) {
        val entry = pageStack.lastOrNull() ?: return
        val windowDecls = LinkedHashMap<String, String>()
        appConfig.window.forEach { (k, v) -> windowDecls[k] = v?.toString() ?: "" }
        if (!pageJson.isNullOrBlank()) {
            runCatching {
                val j = parseJson(pageJson) as? Json.Obj ?: return@runCatching
                j.fields.forEach { (k, v) -> windowDecls[k] = v.asString() }
            }
        }
        val pageStyle = WxssParser().styleFor(entry.template, entry.rules)
        pageStyle.merge(com.yuanbao.miniapp.render.Style.fromDeclarations(windowDecls))
        entry.pageStyle = pageStyle
        val title = windowDecls["navigationBarTitleText"]
        if (!title.isNullOrEmpty()) titleBar?.invoke(title)
    }

    private fun relayout() {
        val entry = pageStack.lastOrNull() ?: return
        val root = vdom.build(entry.template, entry.data, entry.rules)
        root.style = entry.pageStyle ?: root.style
        layoutEngine.textMeasurer = { text, fontSize, bold, maxWidth ->
            val lh = if (entry.pageStyle?.lineHeight?.isNaN() == false) entry.pageStyle!!.lineHeight else fontSize * 1.25f
            TextMeasurer.layout(text, fontSize, bold, maxWidth, lh)
        }
        layoutEngine.layout(root, viewportW, viewportH)
        restoreScroll(entry, root)
        entry.root = root
        markDirty()
    }

    private fun restoreScroll(entry: PageEntry, root: RenderNode) {
        fun walk(node: RenderNode) {
            val saved = entry.scrollState[node.id]
            if (saved != null) node.scrollTop = saved
            node.children.forEach { walk(it) }
        }
        walk(root)
    }

    private fun saveScroll(entry: PageEntry) {
        val root = entry.root ?: return
        fun walk(node: RenderNode) {
            if (node.style.overflow == Overflow.SCROLL) entry.scrollState[node.id] = node.scrollTop
            node.children.forEach { walk(it) }
        }
        walk(root)
    }

    // ------------------------------------------------------------ navigation
    override fun navigateTo(page: String, params: Map<String, String>) {
        post { pushPage(page, params) }
    }

    override fun redirectTo(page: String, params: Map<String, String>) {
        post {
            if (pageStack.isNotEmpty()) {
                logic.unloadCurrentPage()
                pageStack.removeAt(pageStack.lastIndex)
            }
            pushPage(page, params)
        }
    }

    override fun navigateBack(delta: Int) {
        post {
            repeat(delta.coerceAtMost(pageStack.size - 1)) {
                if (pageStack.size <= 1) return@repeat
                logic.unloadCurrentPage()
                pageStack.removeAt(pageStack.lastIndex)
            }
            val entry = pageStack.lastOrNull() ?: return@post
            logic.loadPage(entry.path, toJsonObject(entry.params))
            logic.notifyShow()
            relayout()
            markDirty()
        }
    }

    override fun setNavigationBarTitle(title: String) { post { titleBar?.invoke(title) } }
    override fun currentPage(): String = currentPagePath()

    private fun pushPage(page: String, params: Map<String, String>) {
        val wxml = pkg?.pageWxml(page) ?: ""
        val wxss = listOfNotNull(pkg?.appWxss, pkg?.pageWxss(page)).joinToString("\n")
        val tpl = WxmlParser().parse(wxml)
        val rules = WxssParser().parse(wxss)
        pageStack.add(PageEntry(page, params, tpl, rules))
        applyWindowStyle(pkg?.pageJson(page))
        logic.loadPage(page, toJsonObject(params))
        logic.notifyShow()
    }

    private fun toJsonObject(params: Map<String, String>): String {
        val fields = LinkedHashMap<String, Json>()
        params.forEach { (k, v) -> fields[k] = Json.Str(v) }
        return com.yuanbao.miniapp.util.writeJson(Json.Obj(fields))
    }

    // ------------------------------------------------------------ input
    private var downX = 0f
    private var downY = 0f
    private var scrollNode: RenderNode? = null
    private var scrollStartY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val entry = pageStack.lastOrNull() ?: return false
        val root = entry.root ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                scrollNode = findScrollable(root, event.x, event.y)
                scrollStartY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val node = scrollNode
                if (node != null) {
                    node.scrollTop = (node.scrollTop - (event.y - scrollStartY))
                        .coerceIn(0f, maxOf(0f, node.contentHeight - node.height))
                    scrollStartY = event.y
                    markDirty()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                scrollNode?.let { saveScroll(entry) }
                scrollNode = null
                if (Math.abs(dx) < 12 && Math.abs(dy) < 12) {
                    handleTap(root, event.x, event.y)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap(root: RenderNode, x: Float, y: Float) {
        val target = hitTest(root, x, y) ?: run {
            hideInput()
            return
        }
        if (target.type == NodeType.INPUT) {
            showInput(target)
            return
        }
        // 微信标准事件冒泡：deepest 命中节点没有 tap 绑定时沿 parent 链上溯
        var bind: RenderNode? = target
        while (bind != null && bind.events["tap"] == null && bind.events["click"] == null) {
            bind = bind.parent
        }
        val bindNode = bind ?: return
        val handler = bindNode.events["tap"] ?: bindNode.events["click"] ?: return
        fun datasetOf(n: RenderNode): Json.Obj = Json.Obj(LinkedHashMap<String, Json>().apply {
            n.attributes.filterKeys { it.startsWith("data-") }.forEach { (k, v) ->
                put(k.removePrefix("data-"), Json.Str(v))
            }
        })
        val args = LinkedHashMap<String, Json>()
        args["type"] = Json.Str("tap")
        args["timeStamp"] = Json.Num(System.currentTimeMillis().toDouble())
        args["target"] = Json.obj(
            "id" to Json.Num(target.id.toDouble()),
            "dataset" to datasetOf(target))
        args["currentTarget"] = Json.obj(
            "id" to Json.Num(bindNode.id.toDouble()),
            "dataset" to datasetOf(bindNode))
        logic.dispatchEvent(handler, com.yuanbao.miniapp.util.writeJson(Json.Arr(mutableListOf<Json>(Json.Obj(args)))))
    }

    private fun hitTest(node: RenderNode, x: Float, y: Float): RenderNode? {
        // deepest match; absolutely positioned children win
        var found: RenderNode? = null
        for (child in node.children) {
            if (child.style.position == com.yuanbao.miniapp.render.PositionType.ABSOLUTE) {
                val hit = hitTest(child, x, y)
                if (hit != null) return hit
            }
        }
        for (child in node.children) {
            if (child.style.position == com.yuanbao.miniapp.render.PositionType.ABSOLUTE) continue
            val hit = hitTest(child, x, y)
            if (hit != null) found = hit
        }
        if (found == null && node.containsPoint(x, y) && node.hasEvents()) found = node
        return found
    }

    private fun findScrollable(node: RenderNode, x: Float, y: Float): RenderNode? {
        var found: RenderNode? = null
        for (child in node.children) {
            val hit = findScrollable(child, x, y)
            if (hit != null) found = hit
        }
        if (found == null && node.style.overflow == Overflow.SCROLL && node.containsPoint(x, y)) found = node
        return found
    }

    private fun showInput(node: RenderNode) {
        hideInput()
        val et = EditText(context).apply {
            setText(node.attributes["value"] ?: "")
            hint = node.attributes["placeholder"] ?: ""
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            textSize = node.style.fontSize / resources.displayMetrics.density
        }
        val lp = FrameLayout.LayoutParams(node.width.toInt(), (node.height + 8).toInt()).apply {
            leftMargin = node.absX.toInt()
            topMargin = node.absY.toInt()
        }
        overlay.addView(et, lp)
        et.requestFocus()
        et.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }
        et.setOnEditorActionListener { _, _, _ ->
            fireInput(node, et.text.toString())
            hideInput()
            true
        }
        inputEditor = et
        activeInputNode = node
    }

    private fun fireInput(node: RenderNode, value: String) {
        node.attributes["value"] = value
        val handler = node.events["input"] ?: node.events["blur"] ?: return
        val payload = Json.Arr(mutableListOf<Json>(Json.obj(
            "type" to Json.Str("input"),
            "detail" to Json.obj("value" to Json.Str(value))
        )))
        logic.dispatchEvent(handler, com.yuanbao.miniapp.util.writeJson(payload))
    }

    private fun hideInput() {
        inputEditor?.let { overlay.removeView(it) }
        inputEditor = null
        val node = activeInputNode
        activeInputNode = null
        if (node != null) {
            val handler = node.events["blur"]
            if (handler != null) {
                val payload = Json.Arr(mutableListOf<Json>(Json.obj(
                    "type" to Json.Str("blur"),
                    "detail" to Json.obj("value" to Json.Str(node.attributes["value"] ?: ""))
                )))
                logic.dispatchEvent(handler, com.yuanbao.miniapp.util.writeJson(payload))
            }
        }
    }
}
