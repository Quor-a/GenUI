package com.yuanbao.miniapp.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * Canvas backend of the self-developed renderer: walks our render tree and
 * issues raw draw calls. No android.view.View is involved anywhere.
 */
class CanvasPainter {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val path = Path()

    /** Images resolved by the host (src -> bitmap). */
    var imageProvider: ((src: String) -> Bitmap?)? = null

    /** px per rpx, kept in sync with the layout engine. */
    var rpxRatio: Float = 1f

    fun draw(canvas: Canvas, root: RenderNode, viewportWidth: Float, viewportHeight: Float) {
        // 视口硬裁剪：AI 页面内容溢出（固定宽度/异常尺寸）也不画出屏幕
        canvas.save()
        canvas.clipRect(0f, 0f, viewportWidth, viewportHeight)
        // 底色跟随页面根背景（深色主题不再先涂白闪屏）
        val rootBg = root.style.backgroundColor
        canvas.drawColor(if (rootBg != 0 && rootBg != android.graphics.Color.TRANSPARENT) rootBg else Color.WHITE)
        drawNode(canvas, root, root, viewportWidth, viewportHeight)
        canvas.restore()
    }

    private fun drawNode(canvas: Canvas, node: RenderNode, root: RenderNode, vw: Float, vh: Float) {
        if (node.style.display == Display.NONE || node.width <= 0f || node.height <= 0f) return
        val st = node.style
        val alpha = st.opacity.coerceIn(0f, 1f)
        if (alpha <= 0f) return

        val scrollable = st.overflow == Overflow.SCROLL
        val saveCount = canvas.save()
        if (st.trSet) {                                          // transform：绘制期矩阵（AI 位置/形态自由）
            canvas.save()
            // 围绕节点中心变换；末尾回退绝对基准，使后续 absX/absY 坐标绘制正确落位
            canvas.translate(node.absX + node.width / 2f + st.trTranslateX,
                node.absY + node.height / 2f + st.trTranslateY)
            canvas.rotate(st.trRotate)
            canvas.scale(st.trScale, st.trScale)
            canvas.translate(-node.width / 2f - node.absX, -node.height / 2f - node.absY)
        }
        if (st.shadowSet) {                                      // box-shadow：卡片层次/浮层深度
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = Color.TRANSPARENT
            fillPaint.setShadowLayer(st.shadowBlur.coerceAtLeast(0.1f),
                st.shadowDx, st.shadowDy, st.shadowColor)
            if (st.borderRadius > 0f) {
                path.reset()
                path.addRoundRect(node.absX, node.absY, node.absX + node.width, node.absY + node.height,
                    st.borderRadius, st.borderRadius, Path.Direction.CW)
                canvas.drawPath(path, fillPaint)
            } else {
                canvas.drawRect(node.absX, node.absY, node.absX + node.width, node.absY + node.height, fillPaint)
            }
            fillPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        if (scrollable) {
            canvas.clipRect(node.absX, node.absY, node.absX + node.width, node.absY + node.height)
        } else if (st.overflow == Overflow.HIDDEN) {
            canvas.clipRect(node.absX, node.absY, node.absX + node.width, node.absY + node.height)
        }

        val alphaSave = if (alpha < 1f) {
            canvas.saveLayerAlpha(node.absX, node.absY, node.absX + node.width, node.absY + node.height,
                (alpha * 255).toInt())
        } else -1

        // background + border
        rect.set(node.absX, node.absY, node.absX + node.width, node.absY + node.height)
        if (st.bgGradient != null) {
            // linear-gradient：按角度铺满节点矩形，圆角同纯色路径（AI 背景自由——渐变/氛围底色全支持）
            val g = st.bgGradient!!
            val rad = Math.toRadians(g.angleDeg.toDouble())
            val cx = node.width / 2f; val cy = node.height / 2f
            val len = (Math.abs(node.width * Math.sin(rad)) + Math.abs(node.height * Math.cos(rad))).toFloat()
            val x0 = cx - (Math.sin(rad) * len / 2).toFloat(); val y0 = cy - (Math.cos(rad) * len / 2).toFloat()
            val x1 = cx + (Math.sin(rad) * len / 2).toFloat(); val y1 = cy + (Math.cos(rad) * len / 2).toFloat()
            fillPaint.style = Paint.Style.FILL
            fillPaint.shader = android.graphics.LinearGradient(
                x0, y0, x1, y1,
                g.stops.map { it.first }.toIntArray(),
                g.stops.map { it.second }.toFloatArray(),
                android.graphics.Shader.TileMode.CLAMP)
            if (st.borderRadius > 0f) {
                path.reset()
                path.addRoundRect(rect, st.borderRadius, st.borderRadius, Path.Direction.CW)
                canvas.drawPath(path, fillPaint)
            } else {
                canvas.drawRect(rect, fillPaint)
            }
            fillPaint.shader = null
        } else if (st.backgroundColor != Color.TRANSPARENT) {
            fillPaint.color = st.backgroundColor
            fillPaint.style = Paint.Style.FILL
            if (st.borderRadius > 0f) {
                path.reset()
                path.addRoundRect(rect, st.borderRadius, st.borderRadius, Path.Direction.CW)
                canvas.drawPath(path, fillPaint)
            } else {
                canvas.drawRect(rect, fillPaint)
            }
        }
        if (st.borderWidth > 0f && st.borderColor != Color.TRANSPARENT) {
            borderPaint.color = st.borderColor
            borderPaint.strokeWidth = st.borderWidth
            val half = st.borderWidth / 2f
            rect.set(node.absX + half, node.absY + half,
                node.absX + node.width - half, node.absY + node.height - half)
            if (st.borderRadius > 0f) {
                path.reset()
                path.addRoundRect(rect, st.borderRadius, st.borderRadius, Path.Direction.CW)
                canvas.drawPath(path, borderPaint)
            } else {
                canvas.drawRect(rect, borderPaint)
            }
            rect.set(node.absX, node.absY, node.absX + node.width, node.absY + node.height)
        }

        // scroll offset for children
        val childSave = canvas.save()
        if (scrollable) canvas.translate(-node.scrollLeft, -node.scrollTop)

        when (node.type) {
            NodeType.TEXT -> drawText(canvas, node)
            NodeType.BUTTON -> when (node.tag) {
                "switch", "checkbox", "radio" -> drawSwitchLike(canvas, node)
                else -> drawText(canvas, node)
            }
            NodeType.INPUT -> drawInput(canvas, node)
            NodeType.IMAGE -> drawImage(canvas, node)
            else -> Unit
        }

        for (child in node.children) {
            if (child.style.position == PositionType.ABSOLUTE) continue
            drawNode(canvas, child, root, vw, vh)
        }
        canvas.restoreToCount(childSave)

        // absolutely positioned children paint above the flow content
        for (child in node.children) {
            if (child.style.position == PositionType.ABSOLUTE) {
                drawNode(canvas, child, root, vw, vh)
            }
        }

        if (alphaSave != -1) canvas.restoreToCount(alphaSave)
        canvas.restoreToCount(saveCount)
    }

    private fun drawText(canvas: Canvas, node: RenderNode) {
        val st = node.style
        val lines = node.lines
        if (lines.isEmpty()) return
        val fs = st.fontSize
        val lh = if (st.lineHeight.isNaN()) fs * 1.25f else st.lineHeight
        textPaint.color = st.color
        textPaint.textSize = fs
        textPaint.isFakeBoldText = st.fontWeight == FontWeight.BOLD
        textPaint.textAlign = Paint.Align.LEFT

        val padL = st.padding.left.resolve(node.width, 0f, rpxRatio) ?: 0f
        val padR = st.padding.right.resolve(node.width, 0f, rpxRatio) ?: 0f
        val padT = st.padding.top.resolve(node.height, 0f, rpxRatio) ?: 0f
        val contentW = maxOf(0f, node.width - padL - padR)

        val fm = textPaint.fontMetrics
        val baselineOffset = (lh - (fm.descent - fm.ascent)) / 2f - fm.ascent
        var y = node.absY + padT + baselineOffset

        val maxLines = if (st.maxLines > 0) st.maxLines else lines.size
        for (i in 0 until minOf(lines.size, maxLines)) {
            val line = lines[i]
            val lw = textPaint.measureText(line)
            val x = when (st.textAlign) {
                TextAlign.CENTER -> node.absX + padL + (contentW - lw) / 2f
                TextAlign.RIGHT -> node.absX + padL + contentW - lw
                TextAlign.LEFT -> node.absX + padL
            }
            canvas.drawText(line, x, y, textPaint)
            y += lh
        }
    }

    /** switch/checkbox/radio 真形态：选中态来自 checked 属性绑定（AI 改 data 即重绘） */
    private fun drawSwitchLike(canvas: Canvas, node: RenderNode) {
        val st = node.style
        val checked = node.attributes["checked"] == "true" ||
            node.attributes["checked"].let { it != null && it != "false" && it != "" }
        val accent = if (st.backgroundColor != Color.TRANSPARENT) st.backgroundColor else 0xFF07C160.toInt()
        val h = node.height.coerceAtLeast(24f); val w = if (node.tag == "switch") h * 1.9f else h
        val cx = node.absX; val cy = node.absY + node.height / 2f - h / 2f
        fillPaint.style = Paint.Style.FILL
        when (node.tag) {
            "switch" -> {
                fillPaint.color = if (checked) accent else 0xFFE5E5E5.toInt()
                canvas.drawRoundRect(cx, cy, cx + w, cy + h, h / 2f, h / 2f, fillPaint)
                fillPaint.color = Color.WHITE
                val knobR = h / 2f - 3f
                val kx = if (checked) cx + w - h / 2f else cx + h / 2f
                canvas.drawCircle(kx, cy + h / 2f, knobR, fillPaint)
            }
            "radio" -> {
                fillPaint.style = Paint.Style.STROKE
                fillPaint.strokeWidth = 2f
                fillPaint.color = if (checked) accent else 0xFFC8C8C8.toInt()
                canvas.drawCircle(cx + h / 2f, cy + h / 2f, h / 2f - 2f, fillPaint)
                if (checked) {
                    fillPaint.style = Paint.Style.FILL
                    canvas.drawCircle(cx + h / 2f, cy + h / 2f, h / 2f - 6f, fillPaint)
                }
            }
            else -> { // checkbox
                fillPaint.style = Paint.Style.STROKE
                fillPaint.strokeWidth = 2f
                fillPaint.color = if (checked) accent else 0xFFC8C8C8.toInt()
                canvas.drawRoundRect(cx, cy, cx + h, cy + h, 4f, 4f, fillPaint)
                if (checked) {
                    fillPaint.style = Paint.Style.FILL
                    canvas.drawRoundRect(cx, cy, cx + h, cy + h, 4f, 4f, fillPaint)
                    strokePaint.style = Paint.Style.STROKE
                    strokePaint.strokeWidth = 2.4f
                    strokePaint.color = Color.WHITE
                    strokePaint.strokeCap = Paint.Cap.ROUND
                    val line = Path()
                    line.moveTo(cx + h * 0.25f, cy + h * 0.52f)
                    line.lineTo(cx + h * 0.44f, cy + h * 0.72f)
                    line.lineTo(cx + h * 0.78f, cy + h * 0.3f)
                    canvas.drawPath(line, strokePaint)
                }
            }
        }
        drawText(canvas, node)
    }

    private fun drawInput(canvas: Canvas, node: RenderNode) {
        val st = node.style
        val value = node.attributes["value"] ?: ""
        val placeholder = node.attributes["placeholder"] ?: ""
        val show = if (value.isNotEmpty()) value else placeholder
        textPaint.color = if (value.isNotEmpty()) st.color else Color.GRAY
        textPaint.textSize = st.fontSize
        textPaint.isFakeBoldText = st.fontWeight == FontWeight.BOLD
        val fm = textPaint.fontMetrics
        val padL = st.padding.left.resolve(node.width, 0f, rpxRatio) ?: 0f
        val baseline = node.absY + node.height / 2f - (fm.descent + fm.ascent) / 2f
        canvas.drawText(show, node.absX + padL, baseline, textPaint)
        // underline like a native input
        fillPaint.color = st.borderColor
        canvas.drawRect(node.absX, node.absY + node.height - 1f,
            node.absX + node.width, node.absY + node.height, fillPaint)
    }

    private fun drawImage(canvas: Canvas, node: RenderNode) {
        val src = node.attributes["src"] ?: return
        val bmp = imageProvider?.invoke(src) ?: return
        val st = node.style
        val dst = RectF(node.absX, node.absY, node.absX + node.width, node.absY + node.height)
        bitmapPaint.xfermode = null
        if (st.borderRadius > 0f) {
            val save = canvas.saveLayer(dst.left, dst.top, dst.right, dst.bottom, null)
            path.reset()
            path.addRoundRect(dst, st.borderRadius, st.borderRadius, Path.Direction.CW)
            canvas.clipPath(path)
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)
            canvas.restoreToCount(save)
        } else {
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        }
    }
}
