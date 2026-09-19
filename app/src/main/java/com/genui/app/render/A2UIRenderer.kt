package com.genui.app.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.genui.app.bridge.MoBridgeDispatcher
import com.genui.app.bridge.MoBridgeHost
/**
 * A2UI 渲染引擎 —— 端上唯一的"渲染责任"。
 *
 * 机制：
 * 1. WebView 开启 WebMessageListener（__moHost），把 MoBridge 暴露给 AI 的页面；
 * 2. 生成开始：loadUrl("about:blank") → onPageFinished → document.open()
 * 3. 每个流式 chunk：document.write(chunk) —— 浏览器原生流式 HTML 解析，
 *    截断在标签中间也安全（解析器自动缓冲等待后续数据）。
 * 4. 生成完成：document.close()，页面可交互。
 *
 * 安全：禁文件访问、禁 content 访问；网络仅走 bridge 的 net.proxy（AI 页面自身
 * 处于 about:blank origin，fetch 受 CORS 限制，形成天然第二道闸）。
 */

/**
 * 触摸兜底注入脚本（document-start，先于 AI 任何脚本）：
 * 1. cursor:pointer CSS —— WebView 已知怪癖：无该样式的元素 click 事件可能不派发
 *    （Google IssueTracker 36932783）
 * 2. 点击合成器 —— tap 落在可交互元素上且 150ms 内未见原生 click 时，
 *    主动 el.click() 合成；滚动（位移>14px）不合成。Compose 互操作层
 *    吞点击（kotlinlang 2024-11 AndroidView 行为变更）也能被这层兜住。
 * 幂等：__genuiTap 哨兵。计数进 __genuiTouch，供 🩺 触诊探针读取。
 */
val TEMPLATE_ENGINE_JS = """
function __genuiEval(expr, scope) {
  try {
    var keys = [], vals = [];
    if (scope) { for (var k in scope) { keys.push(k); vals.push(scope[k]); } }
    keys.push('window');
    var f = Function(keys.join(','), 'return (' + expr + ');');
    var r = f.apply(null, vals.concat([window]));
    return (r === undefined || r === null) ? '' : String(r);
  } catch (e) { return null; }
}
function __genuiTemplate(root) {
  if (!root) return;
  var host;
  while ((host = root.querySelector('[wx\\:for],[data-wxfor]')) !== null) {
    var expr = host.getAttribute('wx:for') || host.getAttribute('data-wxfor') || '';
    var itemName = host.getAttribute('wx:for-item') || 'item';
    var idxName = host.getAttribute('wx:for-index') || 'index';
    var parent = host.parentNode;
    if (!parent) break;
    var arr = null;
    try { var clean = expr.replace(/^\{\{|\}\}$/g, ''); arr = (new Function('return (' + clean + ');'))(); } catch (e) {}
    if (!arr || !arr.length) { host.style.display = 'none'; continue; }
    var frag = document.createDocumentFragment();
    var n = Math.min(arr.length, 200);
    for (var i = 0; i < n; i++) {
      var clone = host.cloneNode(true);
      clone.removeAttribute('wx:for'); clone.removeAttribute('wx:for-item'); clone.removeAttribute('wx:for-index');
      clone.removeAttribute('data-wxfor');
      var scope = {}; scope[itemName] = arr[i]; scope[idxName] = i;
      clone.__genuiScope = scope;
      __genuiTemplate(clone);
      frag.appendChild(clone);
    }
    parent.replaceChild(frag, host);
  }
  root.querySelectorAll('[wx\\:if],[data-wxif]').forEach(function(el) {
    var expr = el.getAttribute('wx:if') || el.getAttribute('data-wxif') || 'false';
    var v = __genuiEval(expr.replace(/^\{\{|\}\}$/g, ''), el.__genuiScope);
    if (v === null || v === 'false' || v === '') el.style.display = 'none';
  });
  var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
  var nodes = [];
  while (walker.nextNode()) nodes.push(walker.currentNode);
  nodes.forEach(function(tn) {
    var s = tn.nodeValue;
    if (s.indexOf('{{') === -1) return;
    var scope = null; var p = tn.parentElement;
    while (p) { if (p.__genuiScope) { scope = p.__genuiScope; break; } p = p.parentElement; }
    var out = s.replace(/\{\{([^}]{1,120})\}\}/g, function(_, expr) {
      var v = __genuiEval(expr.trim(), scope);
      return v === null ? '' : v;
    });
    if (out !== s) tn.nodeValue = out;
  });
  root.querySelectorAll('*').forEach(function(el) {
    for (var i = 0; i < el.attributes.length; i++) {
      var attr = el.attributes[i];
      if (attr.value.indexOf('{{') === -1) continue;
      var nv = attr.value.replace(/\{\{([^}]{1,120})\}\}/g, function(_, expr) {
        var v = __genuiEval(expr.trim(), el.__genuiScope);
        return v === null ? '' : v;
      });
      el.setAttribute(attr.name, nv);
    }
  });
  // 5) 竖胶囊修正：AI 滥用 flex:1 时，chips/搜索框被拉成两屏高的竖条——
  // 高度超过 2 倍视口但内容只有少量文本的元素，强制恢复 auto 高度
  try {
    var vh = window.innerHeight || 800;
    root.querySelectorAll('*').forEach(function(el) {
      var r = el.getBoundingClientRect();
      if (r.height > vh * 2 && el.innerText && el.innerText.trim().length < 200 && r.width < window.innerWidth) {
        el.style.height = 'auto';
        el.style.minHeight = '0';
        el.style.maxHeight = 'none';
      }
    });
  } catch (e) {}
}
"""

val TAP_NORMALIZER_JS = """
(function(){
if(window.__genuiTap)return;window.__genuiTap=1;
var st=window.__genuiTouch={t:0,c:0,e:[],last:0,x:0,y:0};
window.addEventListener('error',function(ev){if(st.e.length<6)st.e.push(String(ev.message||ev).slice(0,80));});
var SEL='[data-ga],button,a,[role=button],[onclick],input,select,textarea,label,summary,[data-action],.clickable';
try{var css=SEL.split(',').map(function(q){return q+'{cursor:pointer;-webkit-tap-highlight-color:rgba(79,142,247,.22)}'}).join('');
var s=document.createElement('style');s.textContent=css;(document.head||document).appendChild(s);}catch(e){}
document.addEventListener('touchstart',function(ev){st.t++;var x=ev.changedTouches&&ev.changedTouches[0];if(x){st.x=x.clientX;st.y=x.clientY;}},{passive:true});
document.addEventListener('click',function(){st.last=Date.now();},true);
document.addEventListener('touchend',function(ev){
var x=ev.changedTouches&&ev.changedTouches[0];if(!x)return;
if(Math.abs(x.clientX-st.x)>14||Math.abs(x.clientY-st.y)>14)return;
var el=ev.target&&ev.target.closest?ev.target.closest(SEL):null;if(!el)return;
setTimeout(function(){if(Date.now()-st.last<300)return;st.last=Date.now();st.c++;try{el.click();}catch(e){}},150);
},{passive:true});
})();
""".trimIndent()

class A2UIRenderer(
    private val context: Context,
    private val webView: WebView,
    private val onFirstPaint: () -> Unit = {},
    private val onPageTitle: (String) -> Unit = {},
    private val onBridgeCall: (String) -> Unit = {},
    private val onWidget: (kind: String, payload: String) -> Unit = { _, _ -> },
    private val onRenderIssue: (sample: List<String>, errs: List<String>) -> Unit = { _, _ -> },
    /** 正文前置旁白剥离：AI 写进 <body> 开头的汇报文字 → 转发到对话流，不留在页面上 */
    private val onNarration: (String) -> Unit = {},
    /** 页面体检：每次生成收尾必报（按钮数/真实点击数/脚本数/JS错误数） */
    private val onHealth: (String) -> Unit = {}
) {
    private val main = Handler(Looper.getMainLooper())
    private var bridgeDispatcher: MoBridgeDispatcher? = null
    private val assetLoader = androidx.webkit.WebViewAssetLoader.Builder()
        .setDomain("genui.local")
        .addPathHandler(
            "/assets/",
            androidx.webkit.WebViewAssetLoader.AssetsPathHandler(context)
        )
        .build()
    var writing = false
        private set

    /** 页面是否已就绪（onPageFinished 后才能 document.write） */
    private var pageReady = false

    /** 流式期间收集的页面 JS 错误（供收尾体检 / 自愈定向修复用） */
    private val jsErrors = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** 已知运行时/字体文件名集合（无论从哪个 CDN 引用都回本地字节） */
    private val knownRuntimeFiles: Set<String> by lazy {
        RuntimeRegistry.allAssetFileNames().toSet()
    }

    private fun assetResponse(pathInAssets: String, mime: String): android.webkit.WebResourceResponse? =
        runCatching {
            val bytes = context.assets.open(pathInAssets).use { it.readBytes() }
            val resp = android.webkit.WebResourceResponse(mime, "utf-8", java.io.ByteArrayInputStream(bytes))
            // about:blank origin 跨源加载字体需要 ACAO，否则 @font-face 静默失败
            resp.responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
            resp
        }.getOrNull()

    /**
     * 网络层兜底（shouldInterceptRequest 委托进来）：
     * 1) genui.local/assets/ → WebViewAssetLoader（本地运行时/字体）；
     * 2) 任何 CDN 上引用的已知运行时文件 → 直接回本地字节。国内访问 unpkg/jsdelivr
     *    动辄挂起数十秒，<script src> 会把流式解析器整个阻塞 —— 后文全部丢失，
     *    这就是「部分渲染不完整」与「按钮没反应」的共同根因；
     * 3) 流式期间的未知外部 .js → 立即回空 stub 解除阻塞（本地优先哲学下，
     *    AI 引用未知 CDN 本身就是违规，端上不陪着它等超时）。
     */
    fun intercept(request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
        val url = request?.url ?: return null
        if (url.host == "genui.local") {
            assetLoader.shouldInterceptRequest(url)?.let { return it }
            return null
        }
        val name = url.lastPathSegment ?: return null
        // 现代三件套（three.module.js / three.core.js…）没有离线 ESM 产物，
        // 统一回退到本地 UMD：classic 脚本拿到 window.THREE 照常用，
        // module 导入拿不到 named exports —— 这类页面由体检报错 + 定向修复纠正
        if (name != "three.min.js" && name.startsWith("three.") && name.endsWith(".js")) {
            return assetResponse("runtimes/three.min.js", "text/javascript")
        }
        if (name in knownRuntimeFiles) {
            val mime = when {
                name.endsWith(".css") -> "text/css"
                name.endsWith(".woff2") -> "font/woff2"
                name.endsWith(".html") -> "text/html"
                else -> "text/javascript"
            }
            return assetResponse("runtimes/$name", mime)
        }
        if (writing && (url.scheme == "http" || url.scheme == "https") &&
            name.endsWith(".js")
        ) {
            // stub 内容自报家门：console.error 会汇入 __genErrors，
            // 收尾体检把具体 URL 报给壳层，AI 修复时就知道该换哪个本地运行时
            val body = "console.error('GenUI: 流式期间拦截外部脚本（本地无此运行时）: ' + location.href);"
            return android.webkit.WebResourceResponse(
                "text/javascript", "utf-8",
                java.io.ByteArrayInputStream(body.toByteArray())
            )
        }
        return null
    }

    /** 本地运行时文件名 → /assets/runtimes/ 路径（CDN 失败回落映射） */
    private val genLocalJson: String by lazy {
        RuntimeRegistry.allAssetFileNames().joinToString(",", prefix = "{", postfix = "}") { f ->
            org.json.JSONObject.quote(f) + ":" + org.json.JSONObject.quote("/assets/runtimes/$f")
        }
    }

    /**
     * 流式渲染引导 shim —— 必须写在 MoBridge 之后、AI 任何脚本之前：
     * 1) JS 错误可见：console.error / window.onerror 汇入 __genErrors，收尾体检用；
     * 2) CDN 失败回落：捕获阶段监听 script 加载失败，按文件名换本地 /assets/runtimes/；
     * 3) Vue 延迟挂载：流式期间 createApp().mount('#app') 找不到挂载点时排队，
     *    DOMContentLoaded 与收尾 __genFlushMounts() 时统一放出 —— 治花括号残留的根。
     */
    private val genShim: String by lazy {
        """<script>(function(){
window.__genErrors = [];
var _ce = console.error;
console.error = function(){
  var a = [].slice.call(arguments).map(function(x){ try { return typeof x === 'string' ? x : JSON.stringify(x); } catch(e) { return String(x); } }).join(' ');
  __genErrors.push(a.slice(0, 300));
  try { if (_ce) _ce.apply(console, arguments); } catch(e) {}
};
window.addEventListener('error', function(e){ if (e && e.message) __genErrors.push(('uncaught: ' + e.message).slice(0, 300)); });
// data-ga 全局动作委托：data-ga="notify:标题|内容" 等由端上原生执行，
// 不依赖页面任何 JS —— 按钮真实行为的结构性保证
document.addEventListener('click', function(e){
  var el = e.target && e.target.closest ? e.target.closest('[data-ga]') : null;
  if (!el) return;
  var expr = el.getAttribute('data-ga') || '';
  if (!expr) return;
  e.preventDefault();
  try { __moHost.postMessage(JSON.stringify({id:'ga'+Date.now(), api:'ga.exec', args:{expr:expr}})); } catch(err) {}
}, true);
// 交互示踪：捕获阶段记录每次真实点击（触摸层诊断用）
window.__genClicks = 0;
document.addEventListener('click', function(e){
  __genClicks++;
  var t = e.target;
  var tag = t && t.tagName ? t.tagName.toLowerCase() : '?';
  var txt = t && t.textContent ? String(t.textContent).trim().slice(0, 16) : '';
  __genErrors.push('[click] <' + tag + '> ' + txt);
}, true);
var GEN_LOCAL = @GENLOCAL@;
document.addEventListener('error', function(e){
  var t = e.target;
  if (t && t.tagName === 'SCRIPT' && t.src && !t.dataset.genRetry) {
    var n = t.src.split('/').pop().split('?')[0];
    var local = GEN_LOCAL[n];
    if (local) {
      t.dataset.genRetry = 1;
      var s = document.createElement('script');
      s.src = local; s.async = false;
      (document.head || document.documentElement).appendChild(s);
    }
  }
}, true);
var _vue, pend = [];
function flush(){
  var q = pend; pend = [];
  q.forEach(function(f){ try { f(); } catch(e) {} });
}
Object.defineProperty(window, 'Vue', { configurable: true,
  get: function(){ return _vue; },
  set: function(v){
    _vue = v;
    try {
      var ca = v.createApp;
      v.createApp = function(){
        var app = ca.apply(this, arguments), m = app.mount.bind(app);
        app.mount = function(sel){
          if (typeof sel === 'string' && !document.querySelector(sel)) {
            pend.push(function(){ m(sel); });
            return app;
          }
          return m(sel);
        };
        return app;
      };
    } catch(e) {}
  }
});
// —— 流式期元素桩：AI 脚本先于 DOM 执行时，getElementById/querySelector 拿到的是
// 代理桩而非 null —— addEventListener/onclick/属性赋值进队列，DOM 就绪后统一回放。
// 没有它，首屏脚本一个 TypeError 就让整页交互全灭（实测最高频的"按钮全死"根因）。
var __domReady = false, __pend = [];
function __stub(sel){
  return new Proxy(function(){}, {
    get: function(t, k){
      if (k === 'addEventListener') return function(ev, fn, opt){ __pend.push({sel:sel, ev:ev, fn:fn, opt:opt}); };
      if (k === 'style') return {};
      if (k === 'value' || k === 'textContent' || k === 'innerHTML') return '';
      if (typeof k === 'string') return function(){ return __stub(sel); };
      return undefined;
    },
    set: function(t, k, v){ __pend.push({sel:sel, prop:k, val:v}); return true; }
  });
}
var _gid = document.getElementById.bind(document);
var _qs = document.querySelector.bind(document);
var _qsa = document.querySelectorAll.bind(document);
document.getElementById = function(id){ var el = _gid(id); return el || __stub('#'+id); };
document.querySelector = function(sel){ var el = _qs(sel); return el || __stub(sel); };
document.querySelectorAll = function(sel){ var l = _qsa(sel); return l.length ? l : __stub(sel); };
function __replay(){
  __domReady = true;
  __pend.forEach(function(b){
    var els = document.querySelectorAll(b.sel);
    for (var i = 0; i < els.length; i++) {
      if (b.prop !== undefined) { try { els[i][b.prop] = b.val; } catch(e){} }
      else if (b.fn) { try { els[i].addEventListener(b.ev, b.fn, b.opt); } catch(e){} }
    }
  });
  __pend = [];
  document.getElementById = _gid;
  document.querySelector = _qs;
  document.querySelectorAll = _qsa;
}
document.addEventListener('DOMContentLoaded', function(){ flush(); __replay(); });
window.__genFlushMounts = function(){ flush(); __replay(); };
})();</script>""".replace("@GENLOCAL@", genLocalJson)
    }
    private val pendingChunks = java.util.ArrayDeque<String>()

    /** 续写种子：非空时，begin() 先把它写进文档，随后 chunk 直接追加在它后面 */
    private var seedHtml: String? = null

    /** 已写入的全部字节数（供壳显示与「继续写」判断） */
    @Volatile var writtenBytes: Long = 0
        private set

    init {
        webView.configure()
        bridgeDispatcher = MoBridgeDispatcher(context, webView, onBridgeCall, onWidget)
        installWebMessageListener()
        // AI 自写后端扩展的常驻任务管道：画布就绪即接管触发，页面重开补跑过期任务
        com.genui.app.bridge.MoTasks.attach(context, webView)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebView.configure() {
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true              // AI 页面可用 localStorage（随沙箱销毁）
            allowFileAccess = false
            allowContentAccess = false
            // 视频引擎放行：AI 写的 <video>/<audio> 直接可播，不再要求手势才能出声
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
        }
        // Web 后端构架的引擎放行层：媒体、摄像头/麦克风（getUserMedia）、浏览器定位。
        // 这里只做"引擎可用"，权限仍由系统运行时权限弹窗把守——页面真实可用，边界不松动。
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                request?.let { req ->
                    val want = req.resources ?: return
                    val grant = want.filter { r ->
                        (r == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE &&
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.CAMERA
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED) ||
                            (r == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE &&
                                androidx.core.content.ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.RECORD_AUDIO
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    }
                    if (grant.isNotEmpty()) req.grant(grant.toTypedArray()) else req.deny()
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                val ok = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                callback?.invoke(origin, ok, false)
            }

            // JS 错误全程可见：以前 WebChromeClient 只管权限和定位，页面里
            // console.error 一响用户和收尾体检都不知道，Vue 静默失败就是这么藏住的
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                if (m?.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    jsErrors += (m.message() ?: "").take(300)
                    if (jsErrors.size > 20) jsErrors.removeAt(0)
                }
                return true
            }
        }
        setBackgroundColor(Color.parseColor("#FCFAF5"))
        isVerticalScrollBarEnabled = false
    }

    /** 与 AI 页面的通信端口：接收 {id, api, args}，回写 {id, ok, val} */
    private fun installWebMessageListener() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView, "__moHost", setOf("*"),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: android.net.Uri,
                        isMainFrame: Boolean,
                        replyProxy: androidx.webkit.JavaScriptReplyProxy
                    ) {
                        val raw = if (message.type == WebMessageCompat.TYPE_STRING) message.data ?: "{}" else "{}"
                        bridgeDispatcher?.dispatch(raw, replyProxy)
                    }
                }
            )
        }
    }

    // ---------- 生命周期：open → write×N → close ----------

    /**
     * 开始一次新的生成：重置文档进入"写入中"状态。
     * @param seedHtml 续写模式：把已产出的部分 HTML 先写回文档，chunk 追加其后。
     *                 传 null 表示全新生成。
     */
    fun begin(seedHtml: String? = null) {
        main.post {
            writing = true
            pageReady = false
            jsErrors.clear()
            // 新一代开始：清上一代注册的动态组件，防名字串场
            com.genui.app.render.DynamicComponents.clear()
            writtenBytes = 0
            pendingChunks.clear()
            this@A2UIRenderer.seedHtml = seedHtml
            webView.stopLoading()
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true   // 内容超宽时整体缩放适配，不留裁切
            webView.webViewClient = object : android.webkit.WebViewClient() {
                private var opened = false

                // 本地运行时（vue/react/htm/mermaid）经 /assets/runtimes/ 提供给 AI 的页面，
                // 不走外网、不破坏 genui.local origin
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    request?.url?.let { assetLoader.shouldInterceptRequest(it) }?.let { return it }
                    return intercept(request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (opened || !writing) return
                    opened = true
                    // 在 genui.local 文档上打开流式通道（document.open 清空并重开）：
                    // 先写文档头 + MoBridge 声明 —— 必须先于 AI 的任何 <script>，
                    // 因为流式期间 AI 的脚本是边写边执行的。
                    // 注意：不预闭合 </head>，AI 写来的 <style>/<title>/<script src>/<body> 自然归位。
                    evalJs(
                        "document.open();" +
                        "document.write(" + jsString(
                            "<!DOCTYPE html><html><head>" +
                            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">" +
                            "<meta charset=\"utf-8\">" +
                            "<meta name=\"color-scheme\" content=\"light dark\">" +
                            // 流式期间 origin 是 about:blank，没有 base 的话
                            // /assets/runtimes/* 相对请求全部解析失败（404）→ 运行时永远加载不到
                            "<base href=\"https://genui.local/\">"
                        ) + ");" +
                        "document.write(" + jsString(MoBridgeHost.JS_WRAPPER) + ");" +
                        // 离线字体先注册，AI 的 CSS 才能直接用 'Inter' / 'JetBrains Mono' / 'Space Grotesk'
                        "document.write(" + jsString(RuntimeRegistry.fontFaceCss()) + ");" +
                        // 移动端视口兜底：AI 忘写 viewport meta 时强制按设备宽布局，防桌面尺寸溢出
                        "document.write(" + jsString(
                            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">") + ");" +
                        // Vue 延迟挂载 + CDN 失败回落本地运行时 + JS 错误捕获
                        // 必须写在 MoBridge 之后、AI 任何脚本之前
                        "document.write(" + jsString(genShim) + ");" +
                        // 触摸兜底：cursor:pointer + 点击合成器（先于 AI 脚本，见 TAP_NORMALIZER_JS 注释）
                        // ★ document.write 写的是 HTML——必须自带 <script> 标签，
                        //   否则 JS 源码被当正文渲染（0.14.6/0.14.7 页面显示裸 JS 的根因）
                        "document.write(" + jsString("<script>" + TAP_NORMALIZER_JS + "</scr" + "ipt>") + ");"
                    )
                    // 续写：把已有 HTML 写回，后续 chunk 自然接在后面
                    this@A2UIRenderer.seedHtml?.let { seed ->
                        evalJs("document.write(${jsString(seed)});")
                        writtenBytes = seed.length.toLong()
                    }
                    pageReady = true
                    // 冲掉页面加载期间到达的 chunk（保持顺序）
                    while (pendingChunks.isNotEmpty()) {
                        val c = pendingChunks.poll()
                        if (c != null) evalJs("document.write(${jsString(c)});")
                    }
                    onFirstPaint()
                }
            }
            // 空文档打底，确立 https://genui.local origin（__moHost 在此 origin 上注入）
            webView.loadDataWithBaseURL(
                "https://genui.local/",
                "<!DOCTYPE html><html><head></head><body></body></html>",
                "text/html", "utf-8", null
            )
        }
    }

    /** 注入一个流式 chunk（原始 HTML 片段，任意截断均安全） */
    fun writeChunk(chunk: String) {
        if (!writing) return
        main.post {
            writtenBytes += chunk.length
            if (pageReady) evalJs("document.write(${jsString(chunk)});")
            else pendingChunks.addLast(chunk)
        }
    }

    /** 结束生成：闭合文档，清理未闭合标签，读取 <title> 给界面栈 */
    fun end() {
        main.post {
            if (!pageReady) { writing = false; return@post }
            // ★ 模板语法就地求值：AI（尤其快模型）常把 {{ 插值 }} / wx:for / wx:if 写进 HTML——
            // 浏览器不认识，页面满是裸括号。与其依赖重画（快模型免疫提示），不如端上兜底：
            // 能求值就求值（页面 script 定义的数据/函数直接生效），wx:for 按数组克隆节点，
            // 求值不了的残留文本清空——用户永远看到干净页面。
            evalJs(TEMPLATE_ENGINE_JS + ";try{__genuiTemplate(document.body);}catch(e){}")
            evalJs(
                "setTimeout(function(){try{__genuiTemplate(document.body);}catch(e){}},400);" +
                "setTimeout(function(){try{__genuiTemplate(document.body);}catch(e){}},1200);")
            // 容错闭合：模型偶尔漏掉 </html> / </body>，浏览器对 document.close() 已能自愈，
            // 但显式补齐可保证「回放」时 HTML 结构完整可独立打开
            evalJs("document.close();")
            // 就地清掉模型偶尔顺手包在结尾的 Markdown 闭合围栏（```），避免它作为文本残留在画布上
            evalJs("try{var t=document.body.innerHTML;document.body.innerHTML=t.replace(/\\n?```\\s*$/,'');}catch(e){}")
            // 收尾：闭合文档后 DOM 已完整，放出流式期间排队的 Vue mount
            evalJs("window.__genFlushMounts&&window.__genFlushMounts();")
            // 收尾体检：兜底保证用户永不看到 {{ }} 残留；命中即回调壳层做定向修复
            evalJs(
                "(function(){var t=document.body?document.body.innerText:'';" +
                    "var m=(t.match(/\\{\\{[^}]{0,40}\\}\\}/g)||[]).slice(0,3);" +
                    "var btns=document.querySelectorAll('button,[onclick],[data-action],input,select').length;" +
                    "return JSON.stringify({bad:m.length>0,sample:m,errs:(window.__genErrors||[]).slice(0,3)," +
                    "btns:btns,clicks:(window.__genClicks||0),scripts:document.scripts.length});})()"
            ) { v ->
                runCatching {
                    val first = org.json.JSONTokener(v ?: "{}").nextValue()
                    val o = when (first) {
                        is String -> runCatching { org.json.JSONObject(first) }.getOrNull()
                        is org.json.JSONObject -> first
                        else -> null
                    } ?: return@evalJs
                    val sample = mutableListOf<String>()
                    val errs = mutableListOf<String>()
                    (o.optJSONArray("sample") ?: org.json.JSONArray()).let { a ->
                        for (i in 0 until a.length()) sample.add(a.optString(i))
                    }
                    (o.optJSONArray("errs") ?: org.json.JSONArray()).let { a ->
                        for (i in 0 until a.length()) errs.add(a.optString(i))
                    }
                    // 花括号残留 或 页面 JS 报错 —— 任何一种都回壳层，
                    // 否则 init 失败的页面静默黑屏，用户和修复链路都拿不到信号
                    // 页面体检永远上报：按钮数/真实点击数/脚本数/错误数——
                    // 交互失联时一眼定位断在哪层（没写按钮 / 没碰到 / 碰到了脚本崩）
                    onHealth("按钮 " + o.optInt("btns") + " · 真实点击 " + o.optInt("clicks") +
                        " · 脚本 " + o.optInt("scripts") + " · JS错误 " + errs.size +
                        (if (sample.isNotEmpty()) " · 花括号残留 " + sample.size.toString() else ""))
                    if (o.optBoolean("bad") || errs.isNotEmpty()) {
                        onRenderIssue(sample, errs)
                    }
                }
            }
            // 正文前置旁白剥离：模型偶尔把"数据拿到了…落笔。"这类汇报写进 <body> 开头，
            // 渲染成页面顶部的大字——DOM 级摘除后转发到对话流，页面只留界面内容
            evalJs(
                "(function(){var b=document.body;if(!b)return '';var txt='';" +
                    "while(b.firstChild&&b.firstChild.nodeType===3){" +
                    "txt+=b.firstChild.textContent;b.removeChild(b.firstChild);}" +
                    "if(txt.trim().length>25){window.__genNarration=txt.trim();return txt.trim().slice(0,300);}" +
                    "return '';})()"
            ) { v ->
                val narr = v?.trim('"', ' ') ?: ""
                if (narr.isNotBlank() && narr != "''") onNarration(narr)
            }
            evalJs("document.title") { v ->
                val t = v?.trim('"').orEmpty().ifBlank { "未命名界面" }
                onPageTitle(t)
            }
            writing = false
        }
    }

    /** 回放栈里的历史界面（非流式，一次性加载） */
    fun replay(html: String) {
        main.post {
            writing = false
            webView.loadDataWithBaseURL("https://genui.local/", html, "text/html", "utf-8", null)
        }
    }

    /**
     * 中断当前生成，但保留已写入内容（可交互、可续写）。
     * 关键：必须 document.close()，否则浏览器认为文档仍在加载，已写部分不可交互。
     */
    fun stop() {
        writing = false
        main.post {
            webView.stopLoading()
            if (pageReady) {
                // 补齐可能被截断的闭合标签，让中断的半成品至少可用
                evalJs(
                    "try{" +
                    "document.write('</body></html>');" +
                    "document.close();" +
                    "}catch(e){}"
                )
            }
        }
    }

    /**
     * 把原生渲染层产生的交互回传给 AI 页面。
     *
     * 场景：AI 用 compose 通道画了界面，用户在端上渲染出的**真实 Compose 组件**
     * 里点了按钮 / 拖了滑杆 —— 这个事件必须回到 AI 的页面逻辑里，
     * 否则"界面活了但逻辑是死的"。派发 mo:compose 事件，AI 在页面里监听即可。
     */
    fun dispatchComposeAction(action: String) {
        main.post {
            evalJs(
                "try{window.dispatchEvent(new CustomEvent('mo:compose',{detail:" +
                    jsString(action) + "}))}catch(e){}"
            )
        }
    }

    /**
     * 把 GenCanvas 原生画布里的交互事件回传给 AI 页面。
     * 画布层是纯 Compose 渲染、不跑在 WebView 里，但它身上也可能挂着"点了这个要通知 AI"的
     * 意图（例如画布里的按钮想触发页面里的某个动作）。统一派发 mo:canvas 事件，
     * 与 mo:compose 对称，AI 在页面里 `addEventListener('mo:canvas', ...)` 即可。
     */
    fun dispatchCanvasAction(action: String) {
        main.post {
            evalJs(
                "try{window.dispatchEvent(new CustomEvent('mo:canvas',{detail:" +
                    jsString(action) + "}))}catch(e){}"
            )
        }
    }

    /**
     * 读取当前画布里的完整 HTML（供「中断后另存为一张纸」「导出」使用）。
     * 注意：必须在主线程回调，且回调参数是 JS 值的 JSON 编码字符串。
     */
    fun readHtml(callback: (String) -> Unit) {
        main.post {
            evalJs("document.documentElement.outerHTML") { v ->
                callback(decodeJsString(v))
            }
        }
    }

    /** evaluateJavascript 返回的是 JSON 编码的字符串字面量，这里解码回原始文本 */
    private fun decodeJsString(v: String?): String {
        if (v == null || v == "null") return ""
        return runCatching { org.json.JSONTokener(v).nextValue() as String }.getOrDefault(v)
    }

    // ---------- utils ----------

    private fun evalJs(js: String, callback: ((String?) -> Unit)? = null) {
        webView.evaluateJavascript(js) { callback?.invoke(it) }
    }

    /** 把任意字符串编码为安全的 JS 字符串字面量 */
    private fun jsString(s: String): String {
        val sb = StringBuilder(s.length + 16).append('"')
        for (c in s) when (c) {
            '"'  -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '<'  -> sb.append("\\u003c")   // 防 </script> 提前闭合
            '>'  -> sb.append("\\u003e")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }
}
