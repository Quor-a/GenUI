package com.yuanbao.miniapp.render

import android.graphics.Color

/** Length value: px / rpx (750rpx = screen width) / percentage / auto. */
data class Length(val value: Float, val unit: Unit) {
    enum class Unit { PX, RPX, PERCENT, AUTO }

    companion object {
        val AUTO = Length(0f, Unit.AUTO)
        val ZERO = Length(0f, Unit.PX)

        fun parse(raw: String?): Length {
            val s = (raw ?: "").trim()
            if (s.isEmpty() || s == "auto") return AUTO
            return when {
                s.endsWith("rpx") -> Length(s.removeSuffix("rpx").toFloatOrNull() ?: 0f, Unit.RPX)
                s.endsWith("%") -> Length(s.removeSuffix("%").toFloatOrNull() ?: 0f, Unit.PERCENT)
                s.endsWith("px") -> Length(s.removeSuffix("px").toFloatOrNull() ?: 0f, Unit.PX)
                else -> Length(s.toFloatOrNull() ?: 0f, Unit.PX)
            }
        }
    }

    /** Resolves against [parentSize]; returns null when auto. */
    fun resolve(parentSize: Float, screenWidth: Float, rpxRatio: Float): Float? = when (unit) {
        Unit.PX -> value
        Unit.RPX -> value * rpxRatio
        Unit.PERCENT -> parentSize * value / 100f
        Unit.AUTO -> null
    }
}

data class EdgeInsets(val top: Length, val right: Length, val bottom: Length, val left: Length) {
    companion object {
        val ZERO = EdgeInsets(Length.ZERO, Length.ZERO, Length.ZERO, Length.ZERO)
    }
}

/** Supported display / layout values (self-developed, not Android View layout). */
enum class Display { FLEX, BLOCK, NONE }

/** CSS linear-gradient 线性渐变（AI 背景自由的一部分） */
data class Gradient(val angleDeg: Float, val stops: List<Pair<Int, Float>>) {
    companion object {
        private val CSS_COLORS = mapOf(
            "white" to 0xFFFFFFFF, "black" to 0xFF000000, "red" to 0xFFFF0000,
            "green" to 0xFF008000, "blue" to 0xFF0000FF, "yellow" to 0xFFFFFF00,
            "orange" to 0xFFFFA500, "purple" to 0xFF800080, "gray" to 0xFF808080,
            "grey" to 0xFF808080, "pink" to 0xFFFFC0CB, "brown" to 0xFFA52A2A,
            "transparent" to 0x00000000
        )
        fun parse(v: String): Gradient? {
            val inner = v.substringAfter('(').substringBeforeLast('(').let { s ->
                v.substring(v.indexOf('(') + 1, v.lastIndexOf(')'))
            }
            val parts = splitTopLevel(inner)
            if (parts.isEmpty()) return null
            var angle = 180f                                  // CSS 默认 to bottom = 180deg
            var first = parts[0].trim()
            if (first.endsWith("deg")) {
                angle = first.removeSuffix("deg").toFloatOrNull() ?: 180f
                return build(angle, parts.drop(1))
            }
            if (first.startsWith("to ")) {
                angle = when (first.removePrefix("to ").trim()) {
                    "top" -> 0f; "right" -> 90f; "bottom" -> 180f; "left" -> 270f
                    "top right" -> 45f; "bottom right" -> 135f
                    "bottom left" -> 225f; "top left" -> 315f
                    else -> 180f
                }
                return build(angle, parts.drop(1))
            }
            return build(angle, parts)
        }
        private fun build(angle: Float, stops: List<String>): Gradient? {
            if (stops.size < 2) return null
            val list = ArrayList<Pair<Int, Float>>()
            val n = stops.size
            stops.forEachIndexed { i, raw ->
                val s = raw.trim()
                val colorPart = s.substringBefore(' ').trim()
                val pos = s.substringAfter(' ', "").trim().removeSuffix("%").toFloatOrNull()
                    ?.div(100f) ?: (if (n == 1) 0f else i.toFloat() / (n - 1))
                val argb = parseCssColor(colorPart) ?: return null
                list.add(argb to pos.coerceIn(0f, 1f))
            }
            return Gradient(angle, list.sortedBy { it.second })
        }
        /** #rgb/#rrggbb/#aarrggbb/rgb()/rgba()/CSS 命名色 → ARGB int */
        fun parseCssColor(c: String): Int? {
            val t = c.trim().removePrefix("#")
            t.toLongOrNull(16)?.let { hex ->
                return when (t.length) {
                    3 -> {
                        val r = hex shr 8 and 0xF; val g = hex shr 4 and 0xF; val b = hex and 0xF
                        (0xFF000000L or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)).toInt()
                    }
                    6 -> (0xFF000000L or hex).toInt()
                    8 -> hex.toInt()
                    else -> null
                }
            }
            val low = t.lowercase()
            if (low.startsWith("rgba(") || low.startsWith("rgb(")) {
                val nums = t.substringAfter('(').substringBefore(')').split(',')
                if (nums.size >= 3) {
                    val r = nums[0].trim().toFloatOrNull()?.toInt() ?: return null
                    val g = nums[1].trim().toFloatOrNull()?.toInt() ?: return null
                    val b = nums[2].trim().toFloatOrNull()?.toInt() ?: return null
                    val a = if (nums.size > 3) ((nums[3].trim().toFloatOrNull() ?: 1f) * 255).toInt() else 255
                    return (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or
                        (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
                }
            }
            return CSS_COLORS[low]?.toInt()
        }
        /** 按逗号切分但忽略括号/rgba() 内的逗号 */
        private fun splitTopLevel(s: String): List<String> {
            val out = ArrayList<String>(); var depth = 0; var cur = StringBuilder()
            for (c in s) when {
                c == '(' -> { depth++; cur.append(c) }
                c == ')' -> { depth--; cur.append(c) }
                c == ',' && depth == 0 -> { out.add(cur.toString()); cur = StringBuilder() }
                else -> cur.append(c)
            }
            if (cur.isNotBlank()) out.add(cur.toString())
            return out
        }
    }
}
enum class FlexDirection { ROW, ROW_REVERSE, COLUMN, COLUMN_REVERSE }
enum class JustifyContent { FLEX_START, FLEX_END, CENTER, SPACE_BETWEEN, SPACE_AROUND }
enum class AlignItems { FLEX_START, FLEX_END, CENTER, STRETCH }
enum class FlexWrap { NOWRAP, WRAP }
enum class PositionType { RELATIVE, ABSOLUTE }
enum class TextAlign { LEFT, CENTER, RIGHT }
enum class Overflow { VISIBLE, HIDDEN, SCROLL }
enum class FontWeight { NORMAL, BOLD }

/**
 * Resolved style for a render node. All layout performed by our own FlexLayout,
 * nothing is delegated to the Android View system.
 */
class Style {
    // 微信小程序规范：view 默认块级（纵向堆叠）；只有显式 display:flex 才是弹性横排。
    // 旧默认 FLEX+强制ROW 曾导致所有未写 display 的容器被横排渲染（v0.26.7 修复）。
    var display: Display = Display.BLOCK
    var flexDirection: FlexDirection = FlexDirection.COLUMN
    var justifyContent: JustifyContent = JustifyContent.FLEX_START
    var alignItems: AlignItems = AlignItems.STRETCH
    var flexWrap: FlexWrap = FlexWrap.NOWRAP

    var width: Length = Length.AUTO
    var height: Length = Length.AUTO
    var minWidth: Length = Length.ZERO
    var minHeight: Length = Length.ZERO
    var maxWidth: Length = Length.AUTO
    var maxHeight: Length = Length.AUTO

    var flexGrow: Float = 0f
    var flexShrink: Float = 0f
    var flexBasis: Length = Length.AUTO

    var margin: EdgeInsets = EdgeInsets.ZERO
    var padding: EdgeInsets = EdgeInsets.ZERO

    var position: PositionType = PositionType.RELATIVE
    var left: Length = Length.AUTO
    var top: Length = Length.AUTO
    var right: Length = Length.AUTO
    var bottom: Length = Length.AUTO

    var backgroundColor: Int = Color.TRANSPARENT
    /** linear-gradient 解析结果：角度(度) + 色标列表；null=纯色 */
    var bgGradient: Gradient? = null
    var shadowColor: Int = Color.TRANSPARENT
    var shadowBlur: Float = 0f
    var shadowDx: Float = 0f
    var shadowDy: Float = 0f
    var shadowSet = false
    // transform: translate/rotate/scale（绘制期矩阵，不参与布局计算）
    var trTranslateX: Float = 0f; var trTranslateY: Float = 0f
    var trRotate: Float = 0f; var trScale: Float = 1f
    var trSet = false
    var fontFamily: String = ""
    var fontFamilySet = false
    var color: Int = Color.BLACK
    var fontSize: Float = 16f          // px
    var fontWeight: FontWeight = FontWeight.NORMAL
    var lineHeight: Float = Float.NaN  // px; NaN => auto (1.2 * fontSize)
    var textAlign: TextAlign = TextAlign.LEFT
    var borderRadius: Float = 0f
    var borderWidth: Float = 0f
    var borderColor: Int = Color.TRANSPARENT
    var opacity: Float = 1f
    var zIndex: Int = 0
    var overflow: Overflow = Overflow.VISIBLE
    var maxLines: Int = 0             // 0 = unlimited

    /** Merges another style on top of this one (inline style wins). */
    fun merge(other: Style) {
        if (other.displaySet) display = other.display
        if (other.flexDirectionSet) flexDirection = other.flexDirection
        if (other.justifySet) justifyContent = other.justifyContent
        if (other.alignSet) alignItems = other.alignItems
        if (other.wrapSet) flexWrap = other.flexWrap
        if (other.widthSet) width = other.width
        if (other.heightSet) height = other.height
        if (other.minWidthSet) minWidth = other.minWidth
        if (other.minHeightSet) minHeight = other.minHeight
        if (other.maxWidthSet) maxWidth = other.maxWidth
        if (other.maxHeightSet) maxHeight = other.maxHeight
        if (other.growSet) flexGrow = other.flexGrow
        if (other.shrinkSet) flexShrink = other.flexShrink
        if (other.basisSet) flexBasis = other.flexBasis
        if (other.marginSet) margin = other.margin
        if (other.paddingSet) padding = other.padding
        if (other.positionSet) position = other.position
        if (other.leftSet) left = other.left
        if (other.topSet) top = other.top
        if (other.rightSet) right = other.right
        if (other.bottomSet) bottom = other.bottom
        if (other.bgSet) { backgroundColor = other.backgroundColor; bgGradient = other.bgGradient }
        if (other.shadowSet) {
            shadowColor = other.shadowColor; shadowBlur = other.shadowBlur
            shadowDx = other.shadowDx; shadowDy = other.shadowDy; shadowSet = true
        }
        if (other.trSet) {
            trTranslateX = other.trTranslateX; trTranslateY = other.trTranslateY
            trRotate = other.trRotate; trScale = other.trScale; trSet = true
        }
        if (other.fontFamilySet) { fontFamily = other.fontFamily; fontFamilySet = true }
        if (other.colorSet) color = other.color
        if (other.fontSizeSet) fontSize = other.fontSize
        if (other.weightSet) fontWeight = other.fontWeight
        if (other.lineHeightSet) lineHeight = other.lineHeight
        if (other.textAlignSet) textAlign = other.textAlign
        if (other.radiusSet) borderRadius = other.borderRadius
        if (other.borderWidthSet) borderWidth = other.borderWidth
        if (other.borderColorSet) borderColor = other.borderColor
        if (other.opacitySet) opacity = other.opacity
        if (other.zSet) zIndex = other.zIndex
        if (other.overflowSet) overflow = other.overflow
        if (other.maxLinesSet) maxLines = other.maxLines
    }

    // "was explicitly set" flags, so merging only overrides real declarations
    var displaySet = false
    var flexDirectionSet = false
    var justifySet = false
    var alignSet = false
    var wrapSet = false
    var widthSet = false
    var heightSet = false
    var minWidthSet = false
    var minHeightSet = false
    var maxWidthSet = false
    var maxHeightSet = false
    var growSet = false
    var shrinkSet = false
    var basisSet = false
    var marginSet = false
    var paddingSet = false
    var positionSet = false
    var leftSet = false
    var topSet = false
    var rightSet = false
    var bottomSet = false
    var bgSet = false
    var colorSet = false
    var fontSizeSet = false
    var weightSet = false
    var lineHeightSet = false
    var textAlignSet = false
    var radiusSet = false
    var borderWidthSet = false
    var borderColorSet = false
    var opacitySet = false
    var zSet = false
    var overflowSet = false
    var maxLinesSet = false

    fun copy(): Style {
        val s = Style()
        s.display = display; s.flexDirection = flexDirection
        s.justifyContent = justifyContent; s.alignItems = alignItems; s.flexWrap = flexWrap
        s.width = width; s.height = height; s.minWidth = minWidth; s.minHeight = minHeight
        s.maxWidth = maxWidth; s.maxHeight = maxHeight
        s.flexGrow = flexGrow; s.flexShrink = flexShrink; s.flexBasis = flexBasis
        s.margin = margin; s.padding = padding
        s.position = position; s.left = left; s.top = top; s.right = right; s.bottom = bottom
        s.backgroundColor = backgroundColor; s.color = color
        s.fontFamily = fontFamily; s.fontFamilySet = fontFamilySet
        s.fontSize = fontSize; s.fontWeight = fontWeight; s.lineHeight = lineHeight
        s.textAlign = textAlign; s.borderRadius = borderRadius
        s.borderWidth = borderWidth; s.borderColor = borderColor
        s.opacity = opacity; s.zIndex = zIndex; s.overflow = overflow; s.maxLines = maxLines
        return s
    }

    companion object {
        fun parseColor(raw: String?): Int? {
            val s = (raw ?: "").trim()
            if (s.isEmpty()) return null
            return when {
                s.startsWith("#") -> runCatching { Color.parseColor(s) }.getOrNull()
                s == "transparent" -> Color.TRANSPARENT
                s == "white" -> Color.WHITE
                s == "black" -> Color.BLACK
                s == "red" -> Color.RED
                s == "green" -> Color.GREEN
                s == "blue" -> Color.BLUE
                s == "gray" || s == "grey" -> Color.GRAY
                s.startsWith("rgb") -> {
                    val nums = s.substringAfter("(").substringBefore(")").split(",")
                    if (nums.size >= 3) Color.rgb(
                        nums[0].trim().toIntOrNull() ?: 0,
                        nums[1].trim().toIntOrNull() ?: 0,
                        nums[2].trim().toIntOrNull() ?: 0
                    ) else null
                }
                else -> runCatching { Color.parseColor(s) }.getOrNull()
            }
        }

        /** Builds a Style from a declaration map (keys already normalized to lower case). */
        fun fromDeclarations(decls: Map<String, String>): Style {
            val s = Style()
            for ((rawKey, rawValue) in decls) {
                val key = rawKey.trim().lowercase()
                val v = rawValue.trim()
                when (key) {
                    "display" -> {
                        s.displaySet = true
                        when (v) {
                            "none" -> Display.NONE
                            "block" -> Display.BLOCK
                            "grid" -> { // 网格布局降级模拟：横排+换行（格子自带定宽即可成行成列）
                                s.flexDirection = FlexDirection.ROW
                                s.flexWrap = FlexWrap.WRAP
                                s.wrapSet = true
                                Display.FLEX
                            }
                            else -> Display.FLEX
                        }.also { d -> s.display = d }
                    }
                    "flex-direction" -> { s.flexDirection = parseDirection(v); s.flexDirectionSet = true }
                    "justify-content" -> { s.justifyContent = parseJustify(v); s.justifySet = true }
                    "align-items" -> { s.alignItems = parseAlign(v); s.alignSet = true }
                    "flex-wrap" -> { s.flexWrap = if (v == "wrap") FlexWrap.WRAP else FlexWrap.NOWRAP; s.wrapSet = true }
                    "width" -> { s.width = Length.parse(v); s.widthSet = true }
                    "height" -> { s.height = Length.parse(v); s.heightSet = true }
                    "min-width" -> { s.minWidth = Length.parse(v); s.minWidthSet = true }
                    "min-height" -> { s.minHeight = Length.parse(v); s.minHeightSet = true }
                    "max-width" -> { s.maxWidth = Length.parse(v); s.maxWidthSet = true }
                    "max-height" -> { s.maxHeight = Length.parse(v); s.maxHeightSet = true }
                    "flex" -> {
                        val parts = v.split(" ").filter { it.isNotEmpty() }
                        if (parts.size >= 3) {
                            s.flexGrow = parts[0].toFloatOrNull() ?: 0f
                            s.flexShrink = parts[1].toFloatOrNull() ?: 1f
                            s.flexBasis = Length.parse(parts[2])
                        } else if (parts.size == 1) {
                            val n = parts[0].toFloatOrNull()
                            if (n != null && n == 0f) {
                                s.flexGrow = 0f; s.flexShrink = 0f; s.flexBasis = Length.ZERO
                            } else {
                                s.flexGrow = n ?: 1f; s.flexShrink = 1f; s.flexBasis = Length.ZERO
                            }
                        }
                        s.growSet = true; s.shrinkSet = true; s.basisSet = true
                    }
                    "flex-grow" -> { s.flexGrow = v.toFloatOrNull() ?: 0f; s.growSet = true }
                    "flex-shrink" -> { s.flexShrink = v.toFloatOrNull() ?: 1f; s.shrinkSet = true }
                    "flex-basis" -> { s.flexBasis = Length.parse(v); s.basisSet = true }
                    "margin" -> { s.margin = parseInsets(v); s.marginSet = true }
                    "margin-top" -> { s.margin = s.margin.copy(top = Length.parse(v)); s.marginSet = true }
                    "margin-right" -> { s.margin = s.margin.copy(right = Length.parse(v)); s.marginSet = true }
                    "margin-bottom" -> { s.margin = s.margin.copy(bottom = Length.parse(v)); s.marginSet = true }
                    "margin-left" -> { s.margin = s.margin.copy(left = Length.parse(v)); s.marginSet = true }
                    "padding" -> { s.padding = parseInsets(v); s.paddingSet = true }
                    "padding-top" -> { s.padding = s.padding.copy(top = Length.parse(v)); s.paddingSet = true }
                    "padding-right" -> { s.padding = s.padding.copy(right = Length.parse(v)); s.paddingSet = true }
                    "padding-bottom" -> { s.padding = s.padding.copy(bottom = Length.parse(v)); s.paddingSet = true }
                    "padding-left" -> { s.padding = s.padding.copy(left = Length.parse(v)); s.paddingSet = true }
                    "position" -> { s.position = if (v == "absolute") PositionType.ABSOLUTE else PositionType.RELATIVE; s.positionSet = true }
                    "box-shadow" -> {
                        // 语法：X Y [blur] [spread] color（spread 忽略）
                        val p = v.trim().split(Regex("\\s+"))
                        if (p.size >= 3) {
                            val colorPart = p.last()
                            s.shadowColor = Gradient.parseCssColor(colorPart) ?: parseColor(colorPart) ?: Color.TRANSPARENT
                            val nums = p.dropLast(1).mapNotNull { it.removeSuffix("rpx").toFloatOrNull() }
                            if (nums.isNotEmpty()) {
                                s.shadowDx = nums.getOrElse(0) { 0f }
                                s.shadowDy = nums.getOrElse(1) { 0f }
                                s.shadowBlur = nums.getOrElse(2) { 0f }
                                s.shadowSet = s.shadowColor != Color.TRANSPARENT
                            }
                        }
                    }
                    "font-family" -> { s.fontFamily = v.trim('"', '\''); s.fontFamilySet = true }
                    "transform" -> {
                        // 支持 translate(x,y) / rotate(deg) / scale(n) 空格分隔组合
                        for (fn in Regex("(translate|rotate|scale)\\(([^)]*)\\)").findAll(v)) {
                            val args = fn.groupValues[2].split(",").mapNotNull { it.trim().toFloatOrNull() }
                            when (fn.groupValues[1]) {
                                "translate" -> {
                                    s.trTranslateX += args.getOrElse(0) { 0f }
                                    s.trTranslateY += args.getOrElse(1) { 0f }
                                    s.trSet = true
                                }
                                "rotate" -> { s.trRotate += args.getOrElse(0) { 0f }; s.trSet = true }
                                "scale" -> {
                                    val sc = args.getOrElse(0) { 1f }
                                    s.trScale *= if (args.size > 1) 1f else sc
                                    s.trSet = true
                                }
                            }
                        }
                    }
                    "left" -> { s.left = Length.parse(v); s.leftSet = true }
                    "top" -> { s.top = Length.parse(v); s.topSet = true }
                    "right" -> { s.right = Length.parse(v); s.rightSet = true }
                    "bottom" -> { s.bottom = Length.parse(v); s.bottomSet = true }
                    "background", "background-color" -> {
                        val tv = v.trim()
                        if (tv.startsWith("linear-gradient")) {
                            // linear-gradient([Ndeg|to bottom/right/...], color, color, ...) —— AI 背景自由
                            Gradient.parse(tv)?.let { s.bgGradient = it; s.bgSet = true }
                        } else parseColor(tv)?.let { s.backgroundColor = it; s.bgSet = true }
                    }
                    "color" -> { parseColor(v)?.let { s.color = it; s.colorSet = true } }
                    "font-size" -> { s.fontSize = parsePx(v); s.fontSizeSet = true }
                    "font-weight" -> { s.fontWeight = if (v == "bold" || v.toIntOrNull() ?: 0 >= 600) FontWeight.BOLD else FontWeight.NORMAL; s.weightSet = true }
                    "line-height" -> { s.lineHeight = parsePx(v); s.lineHeightSet = true }
                    "text-align" -> { s.textAlign = when (v) { "center" -> TextAlign.CENTER; "right" -> TextAlign.RIGHT; else -> TextAlign.LEFT }; s.textAlignSet = true }
                    "border-radius" -> { s.borderRadius = parsePx(v); s.radiusSet = true }
                    "border-width" -> { s.borderWidth = parsePx(v); s.borderWidthSet = true }
                    "border-color" -> { parseColor(v)?.let { s.borderColor = it; s.borderColorSet = true } }
                    "opacity" -> { s.opacity = v.toFloatOrNull() ?: 1f; s.opacitySet = true }
                    "z-index" -> { s.zIndex = v.toIntOrNull() ?: 0; s.zSet = true }
                    "overflow" -> { s.overflow = when (v) { "hidden" -> Overflow.HIDDEN; "scroll", "auto" -> Overflow.SCROLL; else -> Overflow.VISIBLE }; s.overflowSet = true }
                    "-webkit-line-clamp", "max-lines" -> { s.maxLines = v.toIntOrNull() ?: 0; s.maxLinesSet = true }
                    else -> Unit
                }
            }
            return s
        }

        private fun parseDirection(v: String) = when (v) {
            "row" -> FlexDirection.ROW
            "row-reverse" -> FlexDirection.ROW_REVERSE
            "column-reverse" -> FlexDirection.COLUMN_REVERSE
            else -> FlexDirection.COLUMN
        }

        private fun parseJustify(v: String) = when (v) {
            "flex-end" -> JustifyContent.FLEX_END
            "center" -> JustifyContent.CENTER
            "space-between" -> JustifyContent.SPACE_BETWEEN
            "space-around" -> JustifyContent.SPACE_AROUND
            else -> JustifyContent.FLEX_START
        }

        private fun parseAlign(v: String) = when (v) {
            "flex-start" -> AlignItems.FLEX_START
            "flex-end" -> AlignItems.FLEX_END
            "center" -> AlignItems.CENTER
            else -> AlignItems.STRETCH
        }

        private fun parsePx(v: String): Float {
            val s = v.trim()
            return when {
                s.endsWith("rpx") -> s.removeSuffix("rpx").toFloatOrNull() ?: 0f
                s.endsWith("px") -> s.removeSuffix("px").toFloatOrNull() ?: 0f
                else -> s.toFloatOrNull() ?: 0f
            }
        }

        private fun parseInsets(v: String): EdgeInsets {
            val parts = v.trim().split(Regex("\\s+")).map { Length.parse(it) }
            return when (parts.size) {
                1 -> EdgeInsets(parts[0], parts[0], parts[0], parts[0])
                2 -> EdgeInsets(parts[0], parts[1], parts[0], parts[1])
                3 -> EdgeInsets(parts[0], parts[1], parts[2], parts[1])
                4 -> EdgeInsets(parts[0], parts[1], parts[2], parts[3])
                else -> EdgeInsets.ZERO
            }
        }
    }
}
