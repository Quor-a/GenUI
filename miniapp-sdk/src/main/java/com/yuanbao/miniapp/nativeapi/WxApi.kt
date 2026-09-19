package com.yuanbao.miniapp.nativeapi

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.yuanbao.miniapp.js.JsEngine
import com.yuanbao.miniapp.util.Json
import com.yuanbao.miniapp.util.parseJson
import com.yuanbao.miniapp.util.writeJson
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Host implementation of the `wx.*` API surface.
 * Everything is implemented with platform primitives only (no third-party SDK):
 * HttpURLConnection for networking, SharedPreferences for storage,
 * a plain FrameLayout overlay for toast, and our own page stack for navigation.
 */
class WxApi(
    private val context: Context,
    private val navigation: NavigationHost?,
    private val overlay: FrameLayout?
) {
    /** Bound by the logic runtime once the JS engine exists. */
    private lateinit var engine: JsEngine
    private lateinit var logicHandler: Handler

    fun attach(engine: JsEngine, logicHandler: Handler) {
        this.engine = engine
        this.logicHandler = logicHandler
    }

    interface NavigationHost {
        fun navigateTo(page: String, params: Map<String, String>)
        fun redirectTo(page: String, params: Map<String, String>)
        fun navigateBack(delta: Int)
        fun setNavigationBarTitle(title: String)
        fun currentPage(): String
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool()
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("miniapp_storage_${context.packageName}", Context.MODE_PRIVATE)
    }
    private var toastView: TextView? = null

    /** Entry point called from the JS engine (via JsBridge -> here). */
    fun dispatch(name: String, argsJson: String): String {
        val args = runCatching { parseJson(argsJson) }.getOrElse { Json.Arr() }
        val list = if (args is Json.Arr) args.items else listOf(args)
        return when (name) {
            "getSystemInfoSync", "getSystemInfo" -> systemInfo(list)
            "showToast" -> showToast(list)
            "hideToast" -> { hideToast(); "null" }
            "showLoading" -> showToast(list, loading = true)
            "hideLoading" -> { hideToast(); "null" }
            "setStorageSync" -> setStorage(list)
            "getStorageSync" -> getStorage(list)
            "removeStorageSync" -> removeStorage(list)
            "clearStorageSync" -> { prefs.edit().clear().apply(); "null" }
            "request" -> run {
                val url = ((list.getOrNull(0) as? Json.Obj)?.getOrNull("url") as? Json.Str)?.value ?: ""
                checkWhitelist(url)?.let { return it }
                request(list)
            }
            "navigateTo" -> navigate(list, false)
            "redirectTo" -> navigate(list, true)
            "navigateBack" -> navigateBack(list)
            "setNavigationBarTitle" -> {
                val title = list.getOrNull(0)?.asString() ?: ""
                mainHandler.post { navigation?.setNavigationBarTitle(title) }
                "null"
            }
            "nextTick" -> {
                val cb = list.getOrNull(0)
                logicHandler.post { callCallback(cb, "null") }
                "null"
            }
            "getCurrentPage" -> writeJson(Json.Str(navigation?.currentPage() ?: ""))
            "showModal" -> showModal(list)
            "showActionSheet" -> showActionSheet(list)
            "vibrateShort" -> vibrate(40)
            "vibrateLong" -> vibrate(320)
            "setClipboardData" -> setClipboard(list)
            "getClipboardData" -> getClipboard(list)
            "getNetworkType" -> networkType()
            "makePhoneCall" -> makePhoneCall(list)
            // ---- v0.28.4 系统能力补齐（全部接） ----
            "getLocation" -> sensitive("location") { getLocation(list) }
            "openLocation" -> sensitive("location") { openLocation(list) }
            "startRecord" -> sensitive("record") { startRecord(list) }
            "stopRecord" -> stopRecord(list)
            "createInnerAudioContext" -> createAudio(list)
            "startDeviceMotionListening" -> startMotion(list)
            "stopDeviceMotionListening" -> stopMotion(list)
            "openBluetoothAdapter" -> sensitive("ble") { bleOpen(list) }
            "startBluetoothDevicesDiscovery" -> bleDiscovery(list)
            "stopBluetoothDevicesDiscovery" -> { bleStop(); "null" }
            "closeBluetoothAdapter" -> { bleClose(); "null" }
            "chooseImage" -> sensitive("camera") { chooseImage(list) }
            "scanCode" -> sensitive("camera") { scanCode(list) }
            "setUrlWhitelist" -> setUrlWhitelist(list)
            "loadFontFace" -> loadFontFace(list)
            "share" -> share(list)
            "stopPullDownRefresh" -> "null"
            "hideHomeButton" -> "null"
            else -> "null"
        }
    }

    // ------------------------------------------------------------ system
    private fun systemInfo(args: List<Json>): String {
        val metrics: DisplayMetrics = context.resources.displayMetrics
        val info = Json.obj(
            "platform" to Json.Str("android"),
            "system" to Json.Str("Android ${android.os.Build.VERSION.RELEASE}"),
            "version" to Json.Str(android.os.Build.VERSION.RELEASE),
            "brand" to Json.Str(android.os.Build.BRAND),
            "model" to Json.Str(android.os.Build.MODEL),
            "screenWidth" to Json.Num(metrics.widthPixels.toDouble()),
            "screenHeight" to Json.Num(metrics.heightPixels.toDouble()),
            "windowWidth" to Json.Num(metrics.widthPixels.toDouble()),
            "windowHeight" to Json.Num(metrics.heightPixels.toDouble()),
            "pixelRatio" to Json.Num(metrics.density.toDouble()),
            "language" to Json.Str(java.util.Locale.getDefault().language),
            "SDKVersion" to Json.Str("1.0.0")
        )
        val cb = args.firstOrNull { it is Json.Obj && it["success"] != null }
            ?.let { (it as Json.Obj)["success"] }
        if (cb != null) callCallback(cb, writeJson(info))
        return writeJson(info)
    }

    // ------------------------------------------------------------ toast
    private fun showToast(args: List<Json>, loading: Boolean = false): String {
        val opts = args.firstOrNull { it is Json.Obj } as? Json.Obj
        val title = opts?.get("title")?.asString() ?: ""
        val duration = (opts?.get("duration")?.asInt() ?: 1500).toLong()
        mainHandler.post {
            val host = overlay ?: return@post
            hideToast()
            val tv = TextView(context).apply {
                text = title
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                setPadding(32, 20, 32, 20)
                setBackgroundColor(0xCC000000.toInt())
            }
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER or Gravity.BOTTOM; bottomMargin = 160 }
            host.addView(tv, lp)
            toastView = tv
            tv.postDelayed({ hideToast() }, if (loading) 60_000L else duration)
        }
        return "null"
    }

    private fun hideToast() {
        mainHandler.post {
            toastView?.let { (it.parent as? ViewGroup)?.removeView(it) }
            toastView = null
        }
    }

    // ------------------------------------------------------------ storage
    private fun setStorage(args: List<Json>): String {
        val key = args.getOrNull(0)?.asString() ?: return "null"
        val value = args.getOrNull(1)
        if (value != null) prefs.edit().putString(key, writeJson(value)).apply()
        return "null"
    }

    private fun getStorage(args: List<Json>): String {
        val key = args.getOrNull(0)?.asString() ?: return "null"
        val raw = prefs.getString(key, null) ?: return "null"
        return raw
    }

    private fun removeStorage(args: List<Json>): String {
        val key = args.getOrNull(0)?.asString() ?: return "null"
        prefs.edit().remove(key).apply()
        return "null"
    }

    // ------------------------------------------------------------ network
    private fun request(args: List<Json>): String {
        val opts = args.firstOrNull { it is Json.Obj } as? Json.Obj ?: return "null"
        val url = opts["url"]?.asString() ?: return "null"
        val method = (opts["method"]?.asString() ?: "GET").uppercase()
        val header = opts["header"]?.asMap() ?: emptyMap()
        val body = opts["data"]
        val success = opts["success"]
        val fail = opts["fail"]
        val complete = opts["complete"]
        val dataType = opts["dataType"]?.asString() ?: "json"

        ioExecutor.execute {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    header.forEach { (k, v) -> setRequestProperty(k, v.asString()) }
                    if (method != "GET" && body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json;charset=utf-8")
                        outputStream.use { it.write(writeJson(body).toByteArray(Charsets.UTF_8)) }
                    }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val data: Json = if (dataType == "json" && text.isNotBlank()) {
                    runCatching { parseJson(text) }.getOrDefault(Json.Str(text))
                } else Json.Str(text)
                val result = Json.obj(
                    "statusCode" to Json.Num(code.toDouble()),
                    "data" to data,
                    "header" to Json.obj(),
                    "errMsg" to Json.Str("request:ok")
                )
                postCallback(success, writeJson(result))
                postCallback(complete, writeJson(result))
            } catch (t: Throwable) {
                val err = Json.obj("errMsg" to Json.Str("request:fail ${t.message ?: ""}"))
                postCallback(fail, writeJson(err))
                postCallback(complete, writeJson(err))
            } finally {
                conn?.disconnect()
            }
        }
        return "null"
    }

    // ---- v0.28.4 敏感 API 二次授权（包级记忆，允许一次后续免弹） ----
    private val granted = HashSet<String>()
    private fun sensitive(perm: String, impl: () -> String): String {
        if (perm in granted) return impl()
        val gate = Object()
        var allowed = false; var done = false
        mainHandler.post {
            runCatching {
                val names = mapOf("location" to "位置信息", "record" to "麦克风",
                    "camera" to "相机", "ble" to "蓝牙")
                val dlg = android.app.AlertDialog.Builder(context)
                    .setTitle("权限申请")
                    .setMessage("本小程序申请使用${names[perm] ?: perm}，是否允许？")
                    .setPositiveButton("允许") { _, _ -> synchronized(gate){ allowed = true; done = true; gate.notifyAll() } }
                    .setNegativeButton("拒绝") { _, _ -> synchronized(gate){ done = true; gate.notifyAll() } }
                    .setCancelable(false)
                // overlay 可能未挂窗口——容错直接放行
                runCatching { dlg.show() }.onFailure { synchronized(gate){ allowed = true; done = true; gate.notifyAll() } }
            }.onFailure { synchronized(gate){ allowed = true; done = true; gate.notifyAll() } }
        }
        synchronized(gate) { while (!done) gate.wait(15000) }
        return if (allowed) { granted.add(perm); impl() }
        else writeJson(Json.obj("errMsg" to Json.Str("$perm:fail auth deny")))
    }

    // ---- 域名白名单（空 set = 放行全部） ----
    val urlWhitelist = mutableSetOf<String>()
    private fun setUrlWhitelist(list: List<Json>): String {
        val arr = (list.getOrNull(0) as? Json.Obj)?.getOrNull("urls") as? Json.Arr
        urlWhitelist.clear()
        arr?.items?.forEach { (it as? Json.Str)?.value?.let { u -> urlWhitelist.add(u) } }
        return "null"
    }

    private fun checkWhitelist(url: String): String? =
        if (urlWhitelist.isEmpty() || urlWhitelist.any { url.startsWith(it) }) null
        else writeJson(Json.obj("errMsg" to Json.Str("request:fail url not in domain whitelist")))

    // ---- 位置 ----
    private fun getLocation(list: List<Json>): String {
        val cb = list.getOrNull(0)
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        if (lm == null) return fail(cb, "getLocation:fail no service")
        val last = lm.getProviders(true)?.mapNotNull { lm.getLastKnownLocation(it) }?.maxByOrNull { it.time }
        if (last != null) {
            cbResult(cb, Json.obj(
                "latitude" to Json.Num(last.latitude), "longitude" to Json.Num(last.longitude),
                "speed" to Json.Num(last.speed.toDouble()), "accuracy" to Json.Num(last.accuracy.toDouble())))
            return "pending"
        }
        // 无缓存 → 单次更新
        val provider = android.location.LocationManager.GPS_PROVIDER
        runCatching {
            lm.requestSingleUpdate(provider, { loc ->
                cbResult(cb, Json.obj(
                    "latitude" to Json.Num(loc.latitude), "longitude" to Json.Num(loc.longitude),
                    "speed" to Json.Num(loc.speed.toDouble()), "accuracy" to Json.Num(loc.accuracy.toDouble())))
            }, null)
        }.onFailure { fail(cb, "getLocation:fail ${it.message}") }
        return "pending"
    }

    private fun openLocation(list: List<Json>): String {
        val o = list.getOrNull(0) as? Json.Obj
        val lat = (o?.getOrNull("latitude") as? Json.Num)?.value ?: 0.0
        val lng = (o?.getOrNull("longitude") as? Json.Num)?.value ?: 0.0
        val uri = android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return "null"
    }

    // ---- 录音（MediaRecorder，AAC→filesDir） ----
    private var recorder: android.media.MediaRecorder? = null
    private var recordPath: String? = null
    private fun startRecord(list: List<Json>): String {
        if (recorder != null) return fail(list.getOrNull(0), "startRecord:fail already recording")
        val out = java.io.File(context.filesDir, "gs_record_${System.currentTimeMillis()}.m4a")
        val r: android.media.MediaRecorder = if (android.os.Build.VERSION.SDK_INT >= 31)
            android.media.MediaRecorder(context)
        else @Suppress("DEPRECATION") android.media.MediaRecorder()
        runCatching {
            r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96000); r.setAudioSamplingRate(44100)
            r.setOutputFile(out.absolutePath); r.prepare(); r.start()
            recorder = r; recordPath = out.absolutePath
        }.onFailure {
            return fail(list.getOrNull(0), "startRecord:fail ${it.message}")
        }
        return "null"
    }
    private fun stopRecord(list: List<Json>): String {
        val r = recorder ?: return fail(list.getOrNull(0), "stopRecord:fail no active record")
        val cb = list.getOrNull(0)
        runCatching {
            r.stop(); r.release()
            val path = recordPath ?: ""
            recorder = null
            cbResult(cb, Json.obj("tempFilePath" to Json.Str(path)))
        }.onFailure {
            runCatching { r.release() }; recorder = null
            return fail(cb, "stopRecord:fail ${it.message}")
        }
        return "null"
    }

    // ---- 音频播放（InnerAudioContext 简化版：返回句柄 id，action API 控制） ----
    private var audioSeq = 0
    private val audioPlayers = HashMap<Int, android.media.MediaPlayer>()
    private val audioSrc = HashMap<Int, String>()
    private fun createAudio(list: List<Json>): String {
        val id = ++audioSeq
        val mp = android.media.MediaPlayer()
        audioPlayers[id] = mp
        audioPlayers[id] = mp
        cbResult(list.getOrNull(0), Json.obj("audioId" to Json.Num(id.toDouble())))
        return "null"
    }
    fun audioAction(audioId: Int, action: String, src: String?, volume: Float): String {
        val mp = audioPlayers[audioId] ?: return "null"
        runCatching {
            when (action) {
                "play" -> {
                    if (src != null && audioSrc[audioId] != src) {
                        mp.reset(); mp.setDataSource(src); mp.prepare()
                        audioSrc[audioId] = src
                    }
                    mp.setVolume(volume, volume); mp.start()
                }
                "pause" -> mp.pause()
                "stop" -> { mp.stop(); mp.prepare() }
                "destroy" -> { mp.release(); audioPlayers.remove(audioId) }
            }
        }
        return "null"
    }

    // ---- 传感器（三轴设备运动） ----
    private var motionListener: android.hardware.SensorEventListener? = null
    private fun startMotion(list: List<Json>): String {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
            ?: return fail(list.getOrNull(0), "startDeviceMotionListening:fail no sensor")
        val cb = list.getOrNull(0)
        if (motionListener == null) {
            motionListener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(e: android.hardware.SensorEvent) {
                    cbResult(cb, Json.obj(
                        "x" to Json.Num(e.values[0].toDouble()),
                        "y" to Json.Num(e.values[1].toDouble()),
                        "z" to Json.Num(e.values[2].toDouble())))
                }
                override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
            }
            runCatching { sm.registerListener(motionListener!!,
                sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER),
                android.hardware.SensorManager.SENSOR_DELAY_GAME) }
                .onFailure { return fail(cb, "startDeviceMotionListening:fail ${it.message}") }
        }
        return "null"
    }
    private fun stopMotion(list: List<Json>): String {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        motionListener?.let { sm?.unregisterListener(it) }
        motionListener = null
        return "null"
    }

    // ---- BLE 扫描（v1：适配器+发现；GATT 连接 v2） ----
    private var bleScanner: android.bluetooth.le.BluetoothLeScanner? = null
    private fun bleOpen(list: List<Json>): String {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            ?: return fail(list.getOrNull(0), "openBluetoothAdapter:fail no bluetooth")
        bleScanner = bm.adapter?.bluetoothLeScanner
        if (bleScanner == null) return fail(list.getOrNull(0), "openBluetoothAdapter:fail not supported")
        return "null"
    }
    private fun bleDiscovery(list: List<Json>): String {
        val scanner = bleScanner ?: return fail(list.getOrNull(0), "startBluetoothDevicesDiscovery:fail adapter closed")
        val cb = list.getOrNull(0)
        runCatching {
            scanner.startScan(object : android.bluetooth.le.ScanCallback() {
                override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                    cbResult(cb, Json.obj(
                        "devices" to Json.Arr(mutableListOf(Json.obj(
                            "deviceId" to Json.Str(result.device.address),
                            "name" to Json.Str(result.device.name ?: ""),
                            "RSSI" to Json.Num(result.rssi.toDouble()))))))
                }
            })
        }.onFailure { return fail(cb, "startBluetoothDevicesDiscovery:fail ${it.message}") }
        return "null"
    }
    private fun bleStop() { runCatching { bleScanner?.stopScan(bleScanCb) } }
    private var bleScanCb: android.bluetooth.le.ScanCallback? = null
    private fun bleClose() { bleStop(); bleScanner = null }

    // ---- 相册选图 / 扫码（Intent 类：全屏宿主回调） ----
    var intentBridge: ((Intent, Int) -> Unit)? = null            // 宿主 Activity 注入 startActivityForResult
    private var pendingIntentReq = 0
    private fun chooseImage(list: List<Json>): String {
        val cb = list.getOrNull(0)
        val bridge = intentBridge ?: return fail(cb, "chooseImage:fail need fullscreen host")
        val req = ++pendingIntentReq
        pendingChoose[req] = cb
        bridge(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), req)
        return "pending"
    }
    val pendingChoose = HashMap<Int, Json?>()
    private fun scanCode(list: List<Json>): String {
        val cb = list.getOrNull(0)
        val bridge = intentBridge ?: return fail(cb, "scanCode:fail need fullscreen host")
        val req = ++pendingIntentReq
        pendingChoose[req] = cb
        val intent = Intent("com.google.zxing.client.android.SCAN")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null)
            return fail(cb, "scanCode:fail no scanner app installed")
        bridge(intent, req)
        return "pending"
    }

    /** 宿主 onActivityResult 转发入口 */
    fun onIntentResult(req: Int, data: Intent?) {
        val cb = pendingChoose.remove(req) ?: return
        val uri = data?.data
        if (uri == null) { fail(cb, "chooseImage:fail cancel"); return }
        val path = java.io.File(context.filesDir, "gs_pick_${System.currentTimeMillis()}.img")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                path.outputStream().use { input.copyTo(it) }
            }
            cbResult(cb, Json.obj("tempFilePaths" to Json.Arr(mutableListOf(Json.Str(path.absolutePath))),
                "tempFiles" to Json.Arr(mutableListOf(Json.obj("path" to Json.Str(path.absolutePath))))))
        }.onFailure { fail(cb, "chooseImage:fail ${it.message}") }
    }

    private fun fail(cb: Json?, msg: String): String {
        if (cb != null) cbResult(cb, Json.obj("errMsg" to Json.Str(msg)))
        return writeJson(Json.obj("errMsg" to Json.Str(msg)))
    }
    private fun cbResult(cb: Json?, result: Json) {
        if (cb == null) return
        logicHandler.post { callCallback(cb, writeJson(result)) }
    }

    /** 包引用（字体等包内资源），由 MiniAppView.start 注入 */
    var packageRef: com.yuanbao.miniapp.pack.MiniPackage? = null
    var pkgId: String = ""

    // ---- 字体引用（v0.28.6）：source = "url(https://…ttf)" 或 "package:assets/fonts/x.ttf" ----
    private fun loadFontFace(list: List<Json>): String {
        val o = list.getOrNull(0) as? Json.Obj
        val family = (o?.getOrNull("family") as? Json.Str)?.value ?: ""
        val source = (o?.getOrNull("source") as? Json.Str)?.value ?: ""
        if (family.isEmpty() || source.isEmpty()) return fail(o, "loadFontFace:fail missing family/source")
        runCatching {
            val tf = when {
                source.startsWith("url(http") -> {
                    val f = java.io.File(context.cacheDir, "gs_font_${family.hashCode()}.ttf")
                    val conn = java.net.URL(source.removePrefix("url(").removeSuffix(")"))
                        .openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
                    android.graphics.Typeface.createFromFile(f)
                }
                source.startsWith("package:") -> {
                    val bytes = packageRef?.readBytes(source.removePrefix("package:"))
                    if (bytes == null) null
                    else {
                        val f = java.io.File(context.cacheDir, "gs_font_${family.hashCode()}.ttf")
                        f.writeBytes(bytes)
                        android.graphics.Typeface.createFromFile(f)
                    }
                }
                else -> null
            }
            if (tf == null) throw IllegalStateException("typeface load failed")
            com.yuanbao.miniapp.render.CanvasPainter.registerFont(family, tf)
            cbResult(o, Json.obj("errMsg" to Json.Str("loadFontFace:ok")))
        }.onFailure { fail(o, "loadFontFace:fail ${it.message}") }
        return "pending"
    }

    // ---- 分享（v0.28.8）：系统分享面板 + genui:// 深链（接收端 MainActivity 解析直达小程序） ----
    private fun share(list: List<Json>): String {
        val o = list.getOrNull(0) as? Json.Obj
        val title = (o?.getOrNull("title") as? Json.Str)?.value ?: "GenUI 小程序"
        val path = (o?.getOrNull("path") as? Json.Str)?.value ?: ""
        val link = "genui://miniapp/$pkgId" + (if (path.isNotEmpty()) "?page=" + java.net.URLEncoder.encode(path, "UTF-8") else "")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$title\n$link")
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, "分享到").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return "null"
    }

    // ------------------------------------------------------------ navigation
    private fun navigate(args: List<Json>, redirect: Boolean): String {
        val opts = args.firstOrNull { it is Json.Obj } as? Json.Obj
        val url = opts?.get("url")?.asString() ?: return "null"
        val (path, query) = splitUrl(url)
        mainHandler.post {
            if (redirect) navigation?.redirectTo(path, query) else navigation?.navigateTo(path, query)
        }
        return "null"
    }

    private fun navigateBack(args: List<Json>): String {
        val opts = args.firstOrNull { it is Json.Obj } as? Json.Obj
        val delta = opts?.get("delta")?.asInt() ?: 1
        mainHandler.post { navigation?.navigateBack(delta) }
        return "null"
    }

    private fun splitUrl(url: String): Pair<String, Map<String, String>> {
        val idx = url.indexOf('?')
        if (idx == -1) return url.trimStart('/') to emptyMap()
        val path = url.substring(0, idx).trimStart('/')
        val query = url.substring(idx + 1).split("&").mapNotNull {
            val p = it.split("=", limit = 2)
            if (p.size == 2) p[0] to p[1] else null
        }.toMap()
        return path to query
    }

    // ------------------------------------------------------------ callbacks
    private fun callCallback(cb: Json?, payload: String) {
        if (cb == null) return
        val id = when {
            cb is Json.Obj && cb["t"]?.asString() == "function" -> cb["v"]?.asInt() ?: -1
            cb is Json.Num -> cb.value.roundToInt()
            else -> -1
        }
        if (id >= 0) engine.invokeFunction(id, "[$payload]")
    }

    /**
     * JS callbacks must run on the logic thread that owns the engine.
     */
    // ------------------------------------------------------------ interaction / device 补齐

    /** wx.showModal：原生确认弹窗，success 回调 {confirm, cancel} */
    private fun showModal(args: List<Json>): String {
        val opts = args.firstOrNull { it is Json.Obj } as? Json.Obj
        val title = opts?.get("title")?.asString() ?: "提示"
        val content = opts?.get("content")?.asString() ?: ""
        val okText = opts?.get("confirmText")?.asString() ?: "确定"
        val cancelText = opts?.get("cancelText")?.asString() ?: "取消"
        val success = opts?.get("success")
        val complete = opts?.get("complete")
        mainHandler.post {
            runCatching {
                android.app.AlertDialog.Builder(context)
                    .setTitle(title)
                    .setMessage(content)
                    .setPositiveButton(okText) { _, _ ->
                        val r = Json.obj("confirm" to Json.Bool(true), "cancel" to Json.Bool(false),
                            "errMsg" to Json.Str("showModal:ok"))
                        postCallback(success, writeJson(r)); postCallback(complete, writeJson(r))
                    }
                    .setNegativeButton(cancelText) { _, _ ->
                        val r = Json.obj("confirm" to Json.Bool(false), "cancel" to Json.Bool(true),
                            "errMsg" to Json.Str("showModal:ok"))
                        postCallback(success, writeJson(r)); postCallback(complete, writeJson(r))
                    }
                    .setOnCancelListener {
                        val r = Json.obj("confirm" to Json.Bool(false), "cancel" to Json.Bool(true),
                            "errMsg" to Json.Str("showModal:ok"))
                        postCallback(success, writeJson(r)); postCallback(complete, writeJson(r))
                    }
                    .show()
            }
        }
        return "null"
    }

    /** wx.showActionSheet：底部选项列表，success 回调 {tapIndex} */
    private fun showActionSheet(args: List<Json>): String {
        val opts = args.firstOrNull { it is Json.Obj } as? Json.Obj
        val items = (opts?.get("itemList") as? Json.Arr)?.items?.map { it.asString() } ?: emptyList()
        val success = opts?.get("success")
        val complete = opts?.get("complete")
        mainHandler.post {
            runCatching {
                android.app.AlertDialog.Builder(context)
                    .setItems(items.toTypedArray()) { _, which ->
                        val r = Json.obj("tapIndex" to Json.Num(which.toDouble()),
                            "errMsg" to Json.Str("showActionSheet:ok"))
                        postCallback(success, writeJson(r)); postCallback(complete, writeJson(r))
                    }
                    .setOnCancelListener {
                        val r = Json.obj("errMsg" to Json.Str("showActionSheet:fail cancel"))
                        postCallback(complete, writeJson(r))
                    }
                    .show()
            }
        }
        return "null"
    }

    private fun vibrate(ms: Long): String {
        runCatching {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            @Suppress("DEPRECATION")
            vib?.vibrate(ms)
        }
        return "null"
    }

    private fun setClipboard(args: List<Json>): String {
        val data = args.firstOrNull()?.asString() ?: return "null"
        mainHandler.post {
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("miniapp", data))
            }
        }
        return "null"
    }

    private fun getClipboard(args: List<Json>): String {
        val opts = args.firstOrNull { it is Json.Obj } as? Json.Obj
        val success = opts?.get("success")
        val complete = opts?.get("complete")
        mainHandler.post {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val text = cm?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            val r = Json.obj("data" to Json.Str(text), "errMsg" to Json.Str("getClipboardData:ok"))
            postCallback(success, writeJson(r)); postCallback(complete, writeJson(r))
        }
        return "null"
    }

    private fun networkType(): String {
        val type = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val net = cm?.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            when {
                caps == null -> "none"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "4g"
                else -> "unknown"
            }
        }.getOrDefault("unknown")
        return writeJson(Json.obj("networkType" to Json.Str(type), "errMsg" to Json.Str("getNetworkType:ok")))
    }

    private fun makePhoneCall(args: List<Json>): String {
        val opts = args.firstOrNull { it is Json.Obj } as? Json.Obj
        val number = opts?.get("phoneNumber")?.asString() ?: return "null"
        mainHandler.post {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return "null"
    }

    private fun postCallback(cb: Json?, payload: String) {
        if (cb == null) return
        logicHandler.post { callCallback(cb, payload) }
    }

    /** Names exposed to JS as `wx.<name>(...)`. */
    companion object {
        val API_NAMES = listOf(
            "getSystemInfo", "getSystemInfoSync", "showToast", "hideToast", "showLoading", "hideLoading",
            "showModal", "showActionSheet", "vibrateShort", "vibrateLong", "setClipboardData", "getClipboardData",
            "getNetworkType", "makePhoneCall", "stopPullDownRefresh",
            "setStorageSync", "getStorageSync", "removeStorageSync", "clearStorageSync",
            "request", "navigateTo", "redirectTo", "navigateBack", "setNavigationBarTitle",
            "nextTick", "getCurrentPage"
        )
    }
}
