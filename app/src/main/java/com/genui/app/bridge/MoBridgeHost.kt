package com.genui.app.bridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.webkit.JavaScriptReplyProxy
import com.genui.app.store.GenStore
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MoBridge —— AI 的界面 ↔ 设备真实功能 的唯一通道。
 *
 * 协议：页面 → {id, api:"ns.method", args:{...}} ；native → {id, ok, val}
 * 页面侧的 Promise 包装见 [JS_WRAPPER]（随文档头写入，先于 AI 的任何脚本）。
 *
 * 安全边界（v0.1）：
 * - net.proxy 仅 https，限 5 次/分钟，走原生 OkHttp（AI 页面自身处于 about:blank origin）
 * - notify 需系统通知权限
 * - 文件系统不暴露
 */
object MoBridgeHost {

    /**
     * 注入给 AI 页面的 Promise 包装（在 AI 任何脚本执行前写入，见 A2UIRenderer.begin）。
     *
     * 注意：JS 对象字面量里同名键「后者覆盖前者」，历史上 ui 键被写过两次，
     * 导致 ui.widget 被静默丢弃（8 种原生组件整条链路不可达）。此处只在末尾出现一次 ui，
     * 且 wrapper 必须能安全重复注入（中断续写/回放时会再次写入同一文档）。
     */
    const val JS_WRAPPER = """
<script>
(function(){
  // 幂等：重复注入时保留已有实例，避免续写/replay 场景下丢失未决 Promise
  if (window.__moReady) return;
  window.__moReady = true;

  var seq = 0, pend = {};
  function call(api, args){
    return new Promise(function(res, rej){
      var id = ++seq;
      pend[id] = {res: res, rej: rej};
      try { __moHost.postMessage(JSON.stringify({id:id, api:api, args:args||{}})); }
      catch(e){ delete pend[id]; rej(e); }
    });
  }
  // 原生侧回写：{id, ok, val}
  window.__moResolve = function(json){
    try {
      var m = (typeof json === 'string') ? JSON.parse(json) : json;
      var p = pend[m.id];
      if (!p) return;
      delete pend[m.id];
      if (m.ok) p.res(m.val); else p.rej(new Error(typeof m.val === 'string' ? m.val : 'MoBridge 调用失败'));
    } catch(e){}
  };
  // XMLHttpRequest 以 JSON 字符串投递，避免结构化克隆的兼容问题
  try { __moHost.onmessage = function(e){ window.__moResolve(e.data); }; } catch(e){}

  function noop(){ return Promise.resolve({ok:true}); }

  window.MoBridge = {
    call: call,
    time: {
      now:    function(){ return call('time.now'); },
      format: function(epochMs, tpl){ return call('time.format', {epochMs:epochMs, tpl:tpl}); }
    },
    store: {
      get:  function(k){ return call('store.get', {key:k}); },
      put:  function(k,v){ return call('store.put', {key:k, value:v}); },
      keys: function(){ return call('store.keys'); },
      del:  function(k){ return call('store.del', {key:k}); }
    },
    notify: {
      send: function(title, body){ return call('notify.send', {title:title, body:body}); }
    },
    haptics: {
      tap: function(light){ return call('haptics.tap', {light:light===true}); }
    },
    clipboard: {
      write: function(text){ return call('clipboard.write', {text:text}); }
    },
    // 通用导出：save(dataUrl 或 Blob, 文件名) → 落系统下载目录。
    // 导出什么内容完全由页面逻辑决定（报表/CSV/JSON/图片/Excel…）
    save: function(data, name){
      if (typeof data === 'string') return call('export.save', {dataUrl:data, name:name||''});
      return new Promise(function(res, rej){
        try {
          var r = new FileReader();
          r.onload = function(){ call('export.save', {dataUrl:r.result, name:name||''}).then(res, rej); };
          r.onerror = function(){ rej(new Error('读取 Blob 失败')); };
          r.readAsDataURL(data);
        } catch(e){ rej(e); }
      });
    },
    net: {
      proxy: function(url, method, headers, body, binary){
        return call('net.proxy', {url:url, method:method||'GET', headers:headers||{}, body:body, binary:binary===true});
      }
    },
    device: {
      info: function(){ return call('device.info'); }
    },
    // AI 自写后端扩展：常驻任务（代码体由 AI 写，画布就绪期间定时触发，重开补跑一次）
    task: {
      schedule: function(id, intervalMin, code){ return call('task.schedule', {id:id, intervalMin:intervalMin||5, code:code}); },
      cancel:  function(id){ return call('task.cancel', {id:id}); },
      list:    function(){ return call('task.list'); }
    },
    // 原创包管理：查询端上已装运行时包（id/名称/文件/用途），据此选依赖
    packages: {
      list: function(){ return call('packages.list'); }
    },
    // ---- 原生组件 + 标题（唯一一次定义 ui） ----
    ui: {
      title:  function(text){ try{ document.title = text; }catch(e){} return noop(); },
      widget: function(kind, data){ return call('ui.widget', {kind:kind, data:data||{}}); },
      // 动态组件注册：AI 现场写组件模板（JSON 组件树 + {{prop}} 占位 + {"slot":true} 插槽），
      // 注册后在组件树里以 {"type":"name", ...} 直接复用 —— 组件面由 AI 按需生长
      component: function(name, template){ return call('ui.component', {name:name, template:template||{}}); },
      // A2UI 协议通道：官方 A2UI-Android 引擎渲染（createSurface/updateComponents/updateDataModel）
      a2ui: function(message){ return call('ui.a2ui', {message:message||''}); },
      // 可视化弹窗：8 种原生组件居中弹出（数据协议同 ui.widget）
      popup: function(kind, data){ return call('ui.popup', {kind:kind, data:data||{}}); }
    }
  };
})();
</script>
"""

    // ---------- 供 Agent 工具层（BuiltinTools）复用的静态能力 ----------

    fun deviceInfoStatic(context: Context): JSONObject {
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val network = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "none"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
        } catch (e: Exception) { "none" }
        return JSONObject()
            .put("model", Build.MODEL)
            .put("os", "Android ${Build.VERSION.RELEASE}")
            .put("battery", JSONObject().put("pct", if (level >= 0) level * 100 / scale else -1).put("charging", plugged != 0))
            .put("network", network)
    }

    fun notifyStatic(context: Context, title: String, body: String): JSONObject {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) throw IllegalStateException("通知权限未授权，请在系统设置中开启")
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chId = "mo_bridge"
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(chId, "GenUI · 界面通知", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = NotificationCompat.Builder(context, chId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title.take(64)).setContentText(body.take(200))
            .setAutoCancel(true).build()
        nm.notify(UUID.randomUUID().hashCode(), n)
        return JSONObject().put("ok", true)
    }
}

/**
 * 原生侧分发器：解析页面消息 → 后台线程执行真实能力 → UI 线程回写结果。
 */
class MoBridgeDispatcher(
    private val context: Context,
    private val webView: WebView,
    private val onCall: (String) -> Unit,     // 供状态行展示 bridge 调用计数/名称
    private val onWidget: (kind: String, payload: String) -> Unit = { _, _ -> } // 原生组件请求
) {
    private val store = GenStore(context)
    private val io = Executors.newSingleThreadExecutor()
    private var netCallsThisMinute = 0
    private var minuteWindow = 0L

    /**
     * WebView 的 onPostMessage 在 UI 线程回调；重活转 IO，结果回 UI 线程。
     *
     * 回写有两条路（都走，互为兜底）：
     *  1) reply.postMessage → 触发页面侧 __moHost.onmessage（JS_WRAPPER 已注册处理器）
     *  2) evaluateJavascript 直接调 window.__moResolve —— 某些 WebView 版本上
     *     WebMessageListener 的 reply 代理在流式 document.write 期间可能不可靠
     */
    fun dispatch(raw: String, reply: JavaScriptReplyProxy) {
        val msg = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val id = msg.optInt("id", -1)
        val api = msg.optString("api")
        val args = msg.optJSONObject("args") ?: JSONObject()
        onCall(api)

        io.execute {
            val replyJson = try {
                val value = handle(api, args)
                JSONObject().put("id", id).put("ok", true).put("val", value ?: JSONObject.NULL)
            } catch (e: Exception) {
                JSONObject().put("id", id).put("ok", false).put("val", e.message ?: "error")
            }.toString()
            webView.post {
                runCatching { reply.postMessage(replyJson) }
                // 兜底通道：直接调用页面侧解析器
                runCatching {
                    webView.evaluateJavascript(
                        "try{window.__moResolve(" +
                            JSONObject.quote(replyJson) + ")}catch(e){}", null
                    )
                }
            }
        }
    }

    private fun handle(api: String, a: JSONObject): Any? = when (api) {

        "time.now" -> JSONObject()
            .put("epoch", System.currentTimeMillis())
            .put("iso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).format(Date()))
            .put("text", SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA).format(Date()))

        "time.format" -> JSONObject().put("text",
            SimpleDateFormat(a.optString("tpl", "yyyy-MM-dd HH:mm"), Locale.CHINA)
                .format(Date(a.optLong("epochMs", System.currentTimeMillis()))))

        "store.get" -> JSONObject().put("value", store.getPageValue(a.optString("key")) ?: JSONObject.NULL)

        "store.put" -> { store.putPageValue(a.optString("key"), a.opt("value")); JSONObject().put("ok", true) }

        "store.keys" -> JSONObject().put("keys", store.pageKeys())

        "store.del" -> { store.deletePageValue(a.optString("key")); JSONObject().put("ok", true) }

        "notify.send" -> {
            requireNotifyPermission()
            sendNotification(a.optString("title", "GenUI"), a.optString("body", ""))
            JSONObject().put("ok", true)
        }

        "haptics.tap" -> {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            val ms = if (a.optBoolean("light")) 12L else 35L
            vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            JSONObject().put("ok", true)
        }

        "clipboard.write" -> {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("gen", a.optString("text")))
            JSONObject().put("ok", true)
        }

        "net.proxy" -> {
            checkRate()
            val url = a.optString("url")
            require(url.startsWith("https://")) { "net.proxy 仅支持 https" }
            val method = a.optString("method", "GET").uppercase()
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS).build()
            val mime = "application/json".toMediaTypeOrNull()
            var builder = Request.Builder().url(url)
            val headers = a.optJSONObject("headers") ?: JSONObject()
            headers.keys().forEach { k -> builder = builder.header(k, headers.optString(k)) }
            builder = if (method == "GET") builder.get()
            else builder.method(method, a.optString("body").toRequestBody(mime))
            client.newCall(builder.build()).execute().use { resp ->
                if (a.optBoolean("binary", false)) {
                    // 二进制通道：真实文件下载的"后端"——任意 https 文件取回为 base64，
                    // 页面拼 data:URL 后交给 MoBridge.save 落系统下载目录
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    require(bytes.size <= 25_000_000) { "二进制响应过大（>25MB），换分块或更小的资源" }
                    JSONObject()
                        .put("status", resp.code)
                        .put("mime", (resp.header("Content-Type") ?: "application/octet-stream").substringBefore(';'))
                        .put("size", bytes.size)
                        .put("b64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                } else {
                    JSONObject()
                        .put("status", resp.code)
                        .put("body", resp.body?.string()?.take(200_000) ?: "")
                }
            }
        }

        "device.info" -> JSONObject()
            .put("model", Build.MODEL)
            .put("os", "Android ${Build.VERSION.RELEASE}")
            .put("battery", readBattery())
            .put("network", readNetwork())

        // AI 自写后端扩展：常驻任务（代码体由 AI 写，画布就绪即触发，重开补跑）
        "task.schedule" -> MoTasks.schedule(
            a.optString("id"), a.optLong("intervalMin", 5L), a.optString("code")
        )
        "task.cancel" -> MoTasks.cancel(a.optString("id"))
                "task.list" -> JSONObject().put("tasks", MoTasks.list())

        // 原创包管理：页面可查询端上已装的 Web 运行时包清单
        "packages.list" -> com.genui.app.render.RuntimeRegistry.catalogJson()

        // 通用导出管道：AI 页面产生的任何字节流（报表/CSV/JSON/图片/Excel…）落系统下载目录。
        // 这里不定义"导出什么"——内容由 AI 自己写；只保证它写的东西能真正落到用户手里。
        "export.save" -> {
            checkExportRate()
            val dataUrl = a.optString("dataUrl")
            require(dataUrl.isNotBlank()) { "dataUrl 不能为空" }
            require(dataUrl.startsWith("data:")) { "仅支持 data:URL（用 FileReader 把 Blob 转成 data:URL 再传入）" }
            val name = a.optString("name").ifBlank {
                "genui-export-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
            }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val mime = dataUrl.removePrefix("data:").substringBefore(',').substringBefore(';')
                .ifBlank { "application/octet-stream" }
            val b64 = dataUrl.substringAfter(',')
            require(b64.length <= 12_000_000) { "导出内容过大（解码前 >12MB），请压缩或分文件" }
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)

            if (Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv
                ) ?: throw IllegalStateException("系统下载目录不可写")
                context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                cv.clear()
                cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, cv, null, null)
                JSONObject().put("ok", true).put("name", name).put("where", "系统下载目录")
            } else {
                val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?: context.filesDir
                val f = java.io.File(dir, name)
                f.writeBytes(bytes)
                JSONObject().put("ok", true).put("name", name).put("where", f.absolutePath)
                    .put("hint", "Android 10 以下导出到应用导出目录（可通过系统文件管理器访问 Android/data）")
            }
        }

        "ga.exec" -> {
            // 结构性真实交互：data-ga 表达式由端上执行，AI 不写 JS 也能有真按钮
            val expr = a.optString("expr")
            val out = try { GaActions.exec(context, expr) } catch (t: Throwable) {
                JSONObject().put("ok", false).put("error", t.message ?: "执行失败")
            }
            out
        }

        "ui.popup" -> {
            // 可视化弹窗：居中 Dialog 渲染原生组件（非 BottomSheet）
            val kind = a.optString("kind")
            val data = a.optJSONObject("data")?.toString() ?: a.optString("data", "{}")
            webView.post { onWidget("popup", JSONObject().put("kind", kind).put("data", data).toString()) }
            JSONObject().put("ok", true)
        }

        "ui.a2ui" -> {
            // A2UI 协议消息 → 全屏原生场景（官方 A2UI-Android 引擎）
            val msg = a.optString("message")
            if (msg.isNotBlank()) webView.post { onWidget("a2ui", msg) }
            JSONObject().put("ok", true)
        }

        "ui.component" -> {
            // AI 动态组件注册：写模板 → 注册 → 组件树复用 → 原生渲染（GenUI 路线）
            val name = a.optString("name")
            val template = a.optJSONObject("template")?.toString() ?: "{}"
            com.genui.app.render.DynamicComponents.register(name, template)
        }

        "ui.widget" -> {
            // AI 的页面请求"原生组件"：端上用 Compose BottomSheet 渲染真实原生控件
            val kind = a.optString("kind")
            val payload = a.optJSONObject("data")?.toString() ?: a.optString("data", "{}")
            webView.post { onWidget(kind, payload) }
            JSONObject().put("ok", true)
        }

        else -> throw IllegalArgumentException("未知 API: $api")
    }

    // ---------- 权限与限流 ----------

    private fun requireNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) throw IllegalStateException("通知权限未授权，请在系统设置中开启")
    }

    private fun checkRate() {
        val now = System.currentTimeMillis() / 60_000
        if (now != minuteWindow) { minuteWindow = now; netCallsThisMinute = 0 }
        require(++netCallsThisMinute <= 60) { "net.proxy 限流：每分钟 60 次" }
    }

    /** 导出独立限流（比 net.proxy 宽松：本地落盘无网络成本，但要防页面死循环刷盘） */
    private var exportCallsThisMinute = 0
    private fun checkExportRate() {
        val now = System.currentTimeMillis() / 60_000
        if (now != minuteWindow) { minuteWindow = now; netCallsThisMinute = 0; exportCallsThisMinute = 0 }
        require(++exportCallsThisMinute <= 20) { "export.save 限流：每分钟 20 次" }
    }

    private fun sendNotification(title: String, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chId = "mo_bridge"
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(chId, "GenUI · 界面通知", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = NotificationCompat.Builder(context, chId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title.take(64)).setContentText(body.take(200))
            .setAutoCancel(true).build()
        nm.notify(UUID.randomUUID().hashCode(), n)
    }

    private fun readBattery(): JSONObject {
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return JSONObject().put("pct", if (level >= 0) level * 100 / scale else -1)
            .put("charging", plugged != 0)
    }

    private fun readNetwork(): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "none"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    } catch (e: Exception) { "none" }
}
