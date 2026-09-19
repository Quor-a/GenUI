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
    private val files: Map<String, String>
) {
    fun read(path: String): String? = files[normalize(path)]

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
        /** Loads from assets: assets/miniprograms/<appId>/... */
        fun fromAssets(context: Context, appId: String): MiniPackage {
            val assets = context.assets
            val base = "miniprograms/$appId"
            val out = HashMap<String, String>()
            collect(assets, base, "", out)
            return MiniPackage(appId, out)
        }

        private fun collect(
            assets: android.content.res.AssetManager,
            base: String,
            rel: String,
            out: HashMap<String, String>
        ) {
            val dir = if (rel.isEmpty()) base else "$base/$rel"
            val list = assets.list(dir)
            if (list.isNullOrEmpty()) {
                runCatching {
                    assets.open(dir).use { out[rel] = readText(it) }
                }
                return
            }
            for (name in list) {
                collect(assets, base, if (rel.isEmpty()) name else "$rel/$name", out)
            }
        }

        /** Loads from a real directory (e.g. context.filesDir/miniapps/<appId>/) — GenUI AI 生成的小程序落地目录。 */
        fun fromDirectory(root: java.io.File, appId: String): MiniPackage {
            val out = HashMap<String, String>()
            fun walk(dir: java.io.File, rel: String) {
                val list = dir.listFiles() ?: return
                for (f in list) {
                    val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                    if (f.isDirectory) walk(f, r)
                    else runCatching { out[r] = f.readText() }
                }
            }
            walk(root, "")
            return MiniPackage(appId, out)
        }

        /** Loads a .mapkg (ZIP) package. */
        fun fromZip(stream: InputStream, appId: String): MiniPackage {
            val out = HashMap<String, String>()
            ZipInputStream(stream.buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) continue
                    out[entry.name.trimStart('/')] = readText(zis)
                }
            }
            return MiniPackage(appId, out)
        }

        private fun readText(input: InputStream): String {
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
            }
            return String(bos.toByteArray(), Charsets.UTF_8)
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
