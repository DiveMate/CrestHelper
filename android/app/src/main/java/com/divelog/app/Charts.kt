package com.divelog.app

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

/**
 * 图表组件：全部用 Compose Canvas 自绘，不引入任何第三方图表库。
 * 四张图：深度剖面（叠加温度 + 最小安全深度）、NDL 剩余、GF99 曲线、通用柱状图。
 */

private val CEIL_COLOR = Color(0xFFE53935)      // 最小安全深度（天花板）
private val TEMP_COLOR = Color(0xFFEF6C00)      // 水温
private val NDL_COLOR = Color(0xFF00897B)       // NDL
private val DECO_COLOR = Color(0xFFC62828)      // 已进入减压

private fun DrawScope.label(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    sizeSp: Float = 9f,
    align: Paint.Align = Paint.Align.LEFT,
) {
    val paint = Paint().apply {
        this.color = color.toArgb()
        textSize = sizeSp.sp.toPx()
        textAlign = align
        isAntiAlias = true
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

/** 把最大深度向上取整到 5m 的整数倍，让坐标轴好看。 */
private fun niceCeil(v: Double): Double {
    if (v <= 5) return 5.0
    return ceil(v / 5.0) * 5.0
}

/**
 * 深度剖面图：横轴时间，纵轴深度（向下）。
 *
 * 画的东西（与详情页图例一一对应）：
 *  - 蓝色实线/面积：实际深度
 *  - 红色虚线：[Deco.SeriesPoint.ceiling] 最小安全深度（不能比它更浅）
 *  - 橙色细线：水温（右侧独立坐标轴）
 */
@Composable
fun DepthProfileChart(
    series: List<Deco.SeriesPoint>,
    modifier: Modifier = Modifier,
    showTemp: Boolean = true,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = lineColor.copy(alpha = 0.16f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))

    Canvas(modifier.fillMaxWidth().height(250.dp)) {
        if (series.size < 2) {
            label("无剖面数据", size.width / 2, size.height / 2, labelColor, 12f, Paint.Align.CENTER)
            return@Canvas
        }
        val left = 40.dp.toPx()
        val right = if (showTemp) 40.dp.toPx() else 12.dp.toPx()
        val top = 10.dp.toPx()
        val bottom = 22.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom

        val maxT = series.last().t.coerceAtLeast(1)
        val maxD = niceCeil(series.maxOf { it.depth })

        fun x(t: Int): Float = left + w * (t.toFloat() / maxT)
        fun y(d: Double): Float = top + h * (d / maxD).toFloat().coerceIn(0f, 1f)

        // 横向网格 + 深度标签
        val rows = 4
        for (i in 0..rows) {
            val d = maxD * i / rows
            val yy = y(d)
            drawLine(gridColor, Offset(left, yy), Offset(left + w, yy), 1f)
            label("${Fmt.one(d)}m", left - 6.dp.toPx(), yy + 3.dp.toPx(), labelColor, 8.5f, Paint.Align.RIGHT)
        }
        // 纵向网格 + 时间标签
        val cols = 4
        for (i in 0..cols) {
            val f = i.toFloat() / cols
            val xx = left + w * f
            drawLine(gridColor, Offset(xx, top), Offset(xx, top + h), 1f)
            label(
                Fmt.axisMinutes((maxT * f).toInt()),
                xx,
                size.height - 6.dp.toPx(),
                labelColor,
                8.5f,
                Paint.Align.CENTER,
            )
        }

        // ---- 水温（右轴）----
        if (showTemp) {
            val temps = series.mapNotNull { it.temp }
            if (temps.size >= 2) {
                var tMin = temps.min()
                var tMax = temps.max()
                if (tMax - tMin < 0.6) {           // 温度几乎恒定：给个上下留白，线才不贴在边上
                    tMin -= 0.3
                    tMax += 0.3
                }
                fun yt(t: Double): Float = top + h * (1f - ((t - tMin) / (tMax - tMin)).toFloat().coerceIn(0f, 1f))

                // 右轴刻度（只画 3 条，避免和深度刻度打架）
                for (i in 0..2) {
                    val tv = tMin + (tMax - tMin) * i / 2.0
                    label("${Fmt.one(tv)}°", left + w + 6.dp.toPx(), yt(tv) + 3.dp.toPx(), TEMP_COLOR, 8f)
                }

                val tp = Path()
                var started = false
                for (p in series) {
                    val tv = p.temp ?: continue
                    if (!started) { tp.moveTo(x(p.t), yt(tv)); started = true } else tp.lineTo(x(p.t), yt(tv))
                }
                if (started) drawPath(tp, TEMP_COLOR.copy(alpha = 0.85f), style = Stroke(width = 1.4.dp.toPx()))
            }
        }

        // ---- 剖面面积 + 曲线 ----
        val curve = Path().apply {
            moveTo(x(series[0].t), y(series[0].depth))
            for (i in 1 until series.size) lineTo(x(series[i].t), y(series[i].depth))
        }
        val area = Path().apply {
            addPath(curve)
            lineTo(x(series.last().t), top + h)
            lineTo(x(series[0].t), top + h)
            close()
        }
        drawPath(area, fillColor)
        drawPath(curve, lineColor, style = Stroke(width = 2.dp.toPx()))

        // ---- 最小安全深度（天花板）----
        var prev: Offset? = null
        for (i in series.indices) {
            val c = series[i].ceiling
            if (c > 0.01) {
                val p = Offset(x(series[i].t), y(c))
                prev?.let { drawLine(CEIL_COLOR, it, p, 1.6.dp.toPx(), pathEffect = dash) }
                prev = p
            } else {
                prev = null
            }
        }
    }
}

/**
 * NDL 剩余曲线：纵轴分钟，横轴时间。
 * 触底（=0）的区段用红色条标出来 —— 那就是"已经进入减压"的时间段。
 */
@Composable
fun NdlChart(series: List<Deco.SeriesPoint>, modifier: Modifier = Modifier) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))

    Canvas(modifier.fillMaxWidth().height(150.dp)) {
        if (series.size < 2) {
            label("无 NDL 数据", size.width / 2, size.height / 2, labelColor, 12f, Paint.Align.CENTER)
            return@Canvas
        }
        val left = 40.dp.toPx()
        val right = 12.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = 20.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom

        val maxT = series.last().t.coerceAtLeast(1)
        val maxMin = series.maxOf { it.ndl } / 60.0
        val topMin = maxOf(20.0, ceil(maxMin / 20.0) * 20.0)

        fun x(t: Int): Float = left + w * (t.toFloat() / maxT)
        fun y(sec: Int): Float = top + h * (1f - (sec / 60.0 / topMin).toFloat().coerceIn(0f, 1f))

        for (i in 0..4) {
            val m = topMin * i / 4
            val yy = y((m * 60).toInt())
            drawLine(gridColor, Offset(left, yy), Offset(left + w, yy), 1f)
            label("${m.toInt()}'", left - 6.dp.toPx(), yy + 3.dp.toPx(), labelColor, 8.5f, Paint.Align.RIGHT)
        }

        // 已进入减压的区段：底部红色条
        for (i in 1 until series.size) {
            if (series[i].ndl <= 0) {
                drawLine(
                    DECO_COLOR.copy(alpha = 0.55f),
                    Offset(x(series[i - 1].t), top + h - 1.5.dp.toPx()),
                    Offset(x(series[i].t), top + h - 1.5.dp.toPx()),
                    3.dp.toPx(),
                )
            }
        }

        val path = Path().apply {
            moveTo(x(series[0].t), y(series[0].ndl))
            for (i in 1 until series.size) lineTo(x(series[i].t), y(series[i].ndl))
        }
        drawPath(path, NDL_COLOR, style = Stroke(width = 2.dp.toPx()))

        // 20 分钟参考线：低于它就属于"余量紧张"
        drawLine(
            NDL_COLOR.copy(alpha = 0.5f),
            Offset(left, y(20 * 60)),
            Offset(left + w, y(20 * 60)),
            1.dp.toPx(),
            pathEffect = dash,
        )
    }
}

/** GF99 曲线：>100% 即为超出 M 值线（红色虚线为阈值）。 */
@Composable
fun Gf99Chart(series: List<Deco.SeriesPoint>, modifier: Modifier = Modifier) {
    val lineColor = Color(0xFFFB8C00)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val limitColor = CEIL_COLOR
    val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))

    Canvas(modifier.fillMaxWidth().height(140.dp)) {
        if (series.size < 2) return@Canvas
        val left = 40.dp.toPx()
        val right = 12.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = 20.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom

        val maxT = series.last().t.coerceAtLeast(1)
        val maxG = maxOf(100.0, ceil(series.maxOf { it.gf99 } / 25.0) * 25.0)

        fun x(t: Int): Float = left + w * (t.toFloat() / maxT)
        fun y(g: Double): Float = top + h * (1f - (g / maxG).toFloat().coerceIn(0f, 1f))

        for (i in 0..4) {
            val g = maxG * i / 4
            val yy = y(g)
            drawLine(gridColor, Offset(left, yy), Offset(left + w, yy), 1f)
            label("${g.toInt()}%", left - 6.dp.toPx(), yy + 3.dp.toPx(), labelColor, 8.5f, Paint.Align.RIGHT)
        }

        // 100% 阈值线
        drawLine(limitColor, Offset(left, y(100.0)), Offset(left + w, y(100.0)), 1.2.dp.toPx(), pathEffect = dash)

        val path = Path().apply {
            moveTo(x(series[0].t), y(series[0].gf99))
            for (i in 1 until series.size) lineTo(x(series[i].t), y(series[i].gf99))
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))

        // 峰值标记
        val peak = series.maxByOrNull { it.gf99 }
        if (peak != null) {
            drawCircle(limitColor, 3.dp.toPx(), Offset(x(peak.t), y(peak.gf99)))
            label(
                "${Fmt.one(peak.gf99)}%",
                x(peak.t).coerceAtMost(size.width - 40.dp.toPx()),
                y(peak.gf99) - 6.dp.toPx(),
                limitColor,
                9f,
            )
        }
    }
}

/** 通用柱状图（年度/月度/直方图共用）。 */
@Composable
fun BarChart(
    labels: List<String>,
    values: List<Int>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier.fillMaxWidth().height(170.dp)) {
        if (values.isEmpty()) return@Canvas
        val left = 8.dp.toPx()
        val right = 8.dp.toPx()
        val top = 16.dp.toPx()
        val bottom = 22.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        val maxV = (values.maxOrNull() ?: 1).coerceAtLeast(1)

        val slot = w / values.size
        val barW = (slot * 0.58f).coerceAtMost(36.dp.toPx())

        for (i in values.indices) {
            val cx = left + slot * i + slot / 2
            val v = values[i]
            val bh = h * (v.toFloat() / maxV)
            if (v > 0) {
                drawRect(
                    color = color,
                    topLeft = Offset(cx - barW / 2, top + h - bh),
                    size = Size(barW, bh),
                )
                label("$v", cx, top + h - bh - 4.dp.toPx(), labelColor, 8.5f, Paint.Align.CENTER)
            }
            label(labels.getOrElse(i) { "" }, cx, size.height - 6.dp.toPx(), labelColor, 8.5f, Paint.Align.CENTER)
        }
        drawLine(gridColor, Offset(left, top + h), Offset(left + w, top + h), 1f)
    }
}
