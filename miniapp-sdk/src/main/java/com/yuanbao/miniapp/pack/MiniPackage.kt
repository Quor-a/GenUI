package com.yuanbao.miniapp.pack

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Mini program package loader.
 *
 * Two sources are supported:
 *  1. an asset directory (assets/miniprograms/<appId>/...)
 *  2. a .mapkg file, which is a plain ZIP archive (read with java.util.zip)
 */
class MiniPackage(
    val appId: String,
    private val files: Map<String, String>,
    private val resources: Map<String, ByteArray> = emptyMap()
) {
    fun read(path: String): String? = files[normalize(path)]

    /** 二进制资源读取（image/字体/音频）：相对路径即可，与 WXML 引用一致 */
    fun readBytes(path: String): ByteArray? = resources[normalize(path)]

    fun hasBinary(path: String): Boolean = resources.containsKey(normalize(path))

    fun has(path: String): Boolean = files.containsKey(normalize(path))

    private fun normalize(path: String): String = path.trim().trimStart('/')

    val appJson: String get() = read("app.json") ?: "{}"
    val appJs: String? get() = read("app.js")
    val appWxss: String? get() = read("app.wxss")

    fun pageWxml(page: String): String? = read("$page.wxml")
    fun pageWxss(page: String): String? = read("$page.wxss")
    fun pageJs(page: String): String? = read("$page.js")
    fun pageJson(page: String): String? = read("$page.json")

    companion object {
        /** 二进制资源扩展名（打进包的图片/字体/媒体） */
        val BINARY_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "ico",
            "ttf", "otf", "woff", "woff2", "mp3", "wav", "ogg", "m4a", "mp4")
        fun isBinary(path: String): Boolean =
            path.substringAfterLast('.', "").lowercase() in BINARY_EXT

        /** Loads from assets: assets/miniprograms/<appId>/... */
        fun fromAssets(context: Context, appId: String): MiniPackage {
            val assets = context.assets
            val base = "miniprograms/$appId"
            val out = HashMap<String, String>()
            val bin = HashMap<String, ByteArray>()
            collect(assets, base, "", out, bin)
            return MiniPackage(appId, out, bin)
        }

        private fun collect(
            assets: android.content.res.AssetManager,
            base: String,
            rel: String,
            out: HashMap<String, String>,
            bin: HashMap<String, ByteArray>
        ) {
            val dir = if (rel.isEmpty()) base else "$base/$rel"
            val list = assets.list(dir)
            if (list.isNullOrEmpty()) {
                runCatching {
                    if (isBinary(rel)) assets.open(dir).use { bin[rel] = readBytes(it) }
                    else assets.open(dir).use { out[rel] = readText(it) }
                }
                return
            }
            for (name in list) {
                collect(assets, base, if (rel.isEmpty()) name else "$rel/$name", out, bin)
            }
        }

        /** Loads from a real directory (e.g. context.filesDir/miniapps/<appId>/) — GenUI AI 生成的小程序落地目录。 */
        fun fromDirectory(root: java.io.File, appId: String): MiniPackage {
            val out = HashMap<String, String>()
            val bin = HashMap<String, ByteArray>()
            fun walk(dir: java.io.File, rel: String) {
                val list = dir.listFiles() ?: return
                for (f in list) {
                    val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                    if (f.isDirectory) walk(f, r)
                    else runCatching {
                        if (isBinary(r)) bin[r] = f.readBytes()
                        else out[r] = f.readText()
                    }
                }
            }
            walk(root, "")
            return MiniPackage(appId, out, bin)
        }

        /** Loads a .mapkg (ZIP) package. */
        fun fromZip(stream: InputStream, appId: String): MiniPackage {
            val out = HashMap<String, String>()
            val bin = HashMap<String, ByteArray>()
            ZipInputStream(stream.buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.trimStart('/')
                    if (isBinary(name)) bin[name] = readBytes(zis)
                    else out[name] = readText(zis)
                }
            }
            return MiniPackage(appId, out, bin)
        }

        private fun readText(input: InputStream): String = String(readBytes(input), Charsets.UTF_8)

        private fun readBytes(input: InputStream): ByteArray {
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
            }
            return bos.toByteArray()
        }
    }
}

/** app.json 描述 */
class AppConfig(private val json: Map<String, Any?>) {
    val pages: List<String> get() = (json["pages"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
    val window: Map<String, Any?> get() = (json["window"] as? Map<*, *>)
        ?.mapKeys { it.key.toString() } ?: emptyMap()
    val entry: String get() = pages.firstOrNull() ?: ""

    companion object {
        fun empty(): AppConfig = AppConfig(emptyMap())

        fun parse(text: String): AppConfig {
            return runCatching { AppConfig(parseAppJson(text)) }.getOrDefault(AppConfig(emptyMap()))
        }

        /** Tiny JSON reader for app.json (objects, arrays, strings, numbers, bools). */
        private fun parseAppJson(text: String): Map<String, Any?> {
            var i = 0
            fun skipWs() { while (i < text.length && text[i].isWhitespace()) i++ }
            fun string(): String {
                if (text[i] != '"') return ""
                i++
                val sb = StringBuilder()
                while (i < text.length && text[i] != '"') {
                    if (text[i] == '\\') { i++; sb.append(text[i]); i++; continue }
                    sb.append(text[i]); i++
                }
                i++
                return sb.toString()
            }
            fun value(): Any? {
                skipWs()
                if (i >= text.length) return null
                return when (text[i]) {
                    '{' -> {
                        i++
                        val map = LinkedHashMap<String, Any?>()
                        while (i < text.length) {
                            skipWs()
                            if (text[i] == '}') { i++; break }
                            val k = string()
                            skipWs()
                            if (text[i] == ':') i++
                            map[k] = value()
                            skipWs()
                            if (text[i] == ',') i++
                        }
                        map
                    }
                    '[' -> {
                        i++
                        val list = ArrayList<Any?>()
                        while (i < text.length) {
                            skipWs()
                            if (text[i] == ']') { i++; break }
                            list.add(value())
                            skipWs()
                            if (text[i] == ',') i++
                        }
                        list
                    }
                    '"' -> string()
                    't' -> { i += 4; true }
                    'f' -> { i += 5; false }
                    'n' -> { i += 4; null }
                    else -> {
                        val start = i
                        while (i < text.length && (text[i].isDigit() || text[i] == '.' || text[i] == '-')) i++
                        text.substring(start, i).toDoubleOrNull()
                    }
                }
            }
            @Suppress("UNCHECKED_CAST")
            return (value() as? Map<String, Any?>) ?: emptyMap()
        }
    }
}
