package com.yuanbao.miniapp.webview

/**
 * WXML → HTML、WXSS → CSS 转换器（WebView 渲染通道）。
 *
 * 微信双轨架构里的「WebView 传统通道」：完整 CSS 兼容、系统内核渲染。
 * 转换原则：
 *  - 结构标签映射为 HTML 等价物（view→div、text→span…），block 脱壳
 *  - wx:for / wx:if / wx:elif / wx:else 与 {{}} 绑定**原样保留**为 data-gs-* 属性，
 *    由 WebView 逻辑层运行时（wv-runtime.js）展开——转换器不做数据求值
 *  - WXSS 的 rpx 直接换算为 calc(N * 100vw / 750)，语义与 750 设计稿宽严格一致
 */
object WxmlToHtml {

    private val TAG_MAP = mapOf(
        "view" to "div", "block" to "div", "scroll-view" to "div",
        "swiper" to "div", "swiper-item" to "div", "form" to "form",
        "text" to "span", "label" to "label", "navigator" to "a",
        "image" to "img", "cover-image" to "img", "button" to "button",
        "input" to "input", "textarea" to "textarea", "checkbox" to "input",
        "radio" to "input", "switch" to "input", "slider" to "input",
        "progress" to "progress", "video" to "video", "canvas" to "canvas",
        "map" to "div", "web-view" to "div", "picker" to "div",
        "rich-text" to "div", "cover-view" to "div"
    )

    private val VOID_TAGS = setOf("img", "input", "progress")

    /** WXML → HTML（wx: 指令保留为 data-gs-* 属性，交给运行时展开） */
    fun convertWxml(src: String): String {
        val sb = StringBuilder()
        var i = 0
        val n = src.length
        while (i < n) {
            if (src.startsWith("<!--", i)) {                       // 注释
                val end = src.indexOf("-->", i)
                i = if (end < 0) n else end + 3
                continue
            }
            if (src[i] != '<') {                                   // 文本（含 {{}}）
                val next = src.indexOf('<', i).let { if (it < 0) n else it }
                sb.append(escapeText(src.substring(i, next)))
                i = next
                continue
            }
            // 找标签结束（属性值里可能含 > 或 {{}}，需引号感知）
            var j = i + 1
            var inQuote: Char? = null
            while (j < n) {
                val c = src[j]
                if (inQuote != null) { if (c == inQuote) inQuote = null }
                else when (c) {
                    '"', '\'' -> inQuote = c
                    '>' -> break
                }
                j++
            }
            if (j >= n) { sb.append(escapeText(src.substring(i))); break }
            val raw = src.substring(i, j + 1)
            convertTag(raw, sb)
            i = j + 1
        }
        return sb.toString()
    }

    private fun convertTag(raw: String, sb: StringBuilder) {
        val selfClose = raw.endsWith("/>")
        val body = raw.trimStart('<').trimEnd('>', '/').trim()
        val m = Regex("^([a-zA-Z][a-zA-Z0-9-]*)([\\s\\S]*)$").find(body) ?: run {
            sb.append(escapeText(raw)); return
        }
        val tag = m.groupValues[1].lowercase()
        if (tag.startsWith("/")) {                                 // 闭合标签
            val close = tag.removePrefix("/")
            sb.append("</").append(TAG_MAP[close] ?: close).append(">")
            return
        }
        val html = TAG_MAP[tag] ?: "div"
        val attrs = convertAttrs(m.groupValues[2], tag)
        sb.append('<').append(html).append(attrs)
        if (selfClose || html in VOID_TAGS) sb.append(" />")
        else sb.append('>')
    }

    private fun convertAttrs(s: String, tag: String): String {
        val out = StringBuilder()
        val r = Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"[^\"]*\"|'[^']*')|(bind|catch)[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*')")
        var last = 0
        for (m in r.findAll(s)) {
            if (m.range.first > last) out.append(' ').append(s.substring(last, m.range.first).trim())
            last = m.range.last + 1
            // 事件绑定分支（bindtap="x"）必须取完整属性名；标准属性取 groupValues[1]
            val name = m.groupValues[1].ifEmpty { m.value.substringBefore('=').trim() }
            val rawVal = m.groupValues[2].ifEmpty { m.groupValues[4] }
            val value = rawVal.trim('"', '\'')
            emitAttr(name, value, tag, out)
        }
        if (last < s.length) out.append(' ').append(s.substring(last).trim())
        if (tag == "img") out.append(" alt=\"\"")
        return out.toString()
    }

    private fun emitAttr(name: String, value: String, tag: String, out: StringBuilder) {
        val n = name.lowercase()
        when {
            n.startsWith("bind") || n.startsWith("catch") -> {      // 事件 → data-gs-event
                val evt = n.removePrefix("bind").removePrefix("catch")
                out.append(" data-gs-on-").append(evt).append("=\"")
                    .append(escapeAttr(value)).append('"')
            }
            n.startsWith("wx:") ->                                   // 指令 → data-gs-wx-xxx（冒号转横线！）
                out.append(" data-gs-").append(n.replace(':', '-'))
                    .append("=\"").append(escapeAttr(value)).append('"')
            n == "src" && tag == "img" -> {
                val v = if (value.startsWith("{{") || value.contains("{{"))
                    value else resolveAssetPath(value)
                out.append(" data-gs-src=\"").append(escapeAttr(v)).append('"')
            }
            n == "style" -> out.append(" style=\"").append(escapeAttr(rpxToCss(value))).append('"')
            n == "class" || n == "id" || n == "type" || n == "value" || n == "placeholder" ||
                n == "disabled" || n == "checked" || n == "hidden" || n == "data-hi" ->
                out.append(' ').append(name).append("=\"").append(escapeAttr(value)).append('"')
            else -> {} // 其余（自定义 data-* 除外）静默跳过，与原生引擎"设不上就跳过"一致
        }
    }

    private fun resolveAssetPath(v: String): String =
        if (v.startsWith("http") || v.startsWith("data:")) v else v   // 相对路径由运行时解析

    /** WXSS → CSS：rpx → calc(N * 100vw / 750)（750rpx = 整屏宽，与原生引擎 rpxRatio 一致） */
    fun convertWxss(src: String): String = rpxToCss(src)

    private fun rpxToCss(src: String): String =
        Regex("(-?\\d+(?:\\.\\d+)?)rpx").replace(src) { mm ->
            "calc(${mm.groupValues[1]} * 100vw / 750)"
        }

    private fun escapeText(t: String): String =
        t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeAttr(t: String): String =
        t.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
}
