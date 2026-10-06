package com.antifraud.guard.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 轻量轨迹画布：不依赖第三方地图 SDK，把服务端返回的经纬度轨迹点
 * 归一化后绘制为折线 + 节点。最新一点用红色高亮。
 */
class TrackView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Point(val lat: Double, val lng: Double, val address: String)

    private var points: List<Point> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6")
        style = Paint.Style.FILL
    }

    private val latestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4444")
        style = Paint.Style.FILL
    }

    private val latestRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FECACA")
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#475569")
        textSize = 30f
    }

    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    fun setPoints(list: List<Point>) {
        points = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) {
            canvas.drawText("暂无轨迹数据", width / 2f, height / 2f, emptyPaint)
            return
        }
        if (points.size == 1) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, 28f, latestRingPaint)
            canvas.drawCircle(cx, cy, 18f, latestPaint)
            return
        }

        // 计算经纬度包围盒
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }
        val maxLng = points.maxOf { it.lng }
        val spanLat = (maxLat - minLat).takeIf { it > 1e-9 } ?: 0.01
        val spanLng = (maxLng - minLng).takeIf { it > 1e-9 } ?: 0.01

        val pad = 80f
        val usableW = width - pad * 2
        val usableH = height - pad * 2

        fun toXY(p: Point): Pair<Float, Float> {
            val fx = ((p.lng - minLng) / spanLng).toFloat()
            // 纬度向上为北 → 屏幕 y 向下，需翻转
            val fy = ((maxLat - p.lat) / spanLat).toFloat()
            return Pair(pad + fx * usableW, pad + fy * usableH)
        }

        // 折线
        val path = android.graphics.Path()
        points.forEachIndexed { i, p ->
            val (x, y) = toXY(p)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)

        // 节点
        points.forEach { p ->
            val (x, y) = toXY(p)
            canvas.drawCircle(x, y, 12f, dotPaint)
        }

        // 最新点高亮（列表按时间倒序，第 0 个最新）
        val latest = points.first()
        val (lx, ly) = toXY(latest)
        canvas.drawCircle(lx, ly, 26f, latestRingPaint)
        canvas.drawCircle(lx, ly, 16f, latestPaint)
        canvas.drawText("最新", lx + 34f, ly + 10f, labelPaint)
    }
}
