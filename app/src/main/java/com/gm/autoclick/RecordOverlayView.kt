package com.gm.autoclick

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Camada transparente que fica na frente de tudo durante a gravacao.
 * O Android nao deixa um app comum espiar toques de outros apps sem consumir,
 * entao aqui a gente consome o toque, grava, e (opcional) reinjeta no app de baixo.
 */
@SuppressLint("ViewConstructor")
class RecordOverlayView(context: Context) : View(context) {

    /** Chamado quando um passo (toque/arrasto) termina de ser gravado. */
    var onStep: ((Step) -> Unit)? = null

    private val border = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = 0xFFE53935.toInt()
    }
    private val dotFill = Paint().apply {
        style = Paint.Style.FILL
        color = 0xAAE53935.toInt()
        isAntiAlias = true
    }
    private val dotText = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        isFakeBoldText = true
    }
    private val trail = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = 0xCCFFC107.toInt()
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val marks = mutableListOf<Pt>()
    private var current = mutableListOf<Pt>()
    private var tracking = false
    private var downAt = 0L
    private var lastUpAt = 0L
    private val loc = IntArray(2)

    fun undo() {
        if (marks.isNotEmpty()) marks.removeAt(marks.size - 1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(loc)
        canvas.drawRect(5f, 5f, width - 5f, height - 5f, border)

        if (current.size > 1) {
            for (i in 1 until current.size) {
                val a = current[i - 1]
                val b = current[i]
                canvas.drawLine(
                    a.x - loc[0], a.y - loc[1],
                    b.x - loc[0], b.y - loc[1], trail
                )
            }
        }

        for ((i, p) in marks.withIndex()) {
            val cx = p.x - loc[0]
            val cy = p.y - loc[1]
            canvas.drawCircle(cx, cy, 26f, dotFill)
            canvas.drawText((i + 1).toString(), cx, cy + 11f, dotText)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.rawX
        val y = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                downAt = SystemClock.uptimeMillis()
                current = mutableListOf(Pt(x, y))
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val last = current.last()
                if (abs(x - last.x) + abs(y - last.y) > 8f) {
                    current.add(Pt(x, y))
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                tracking = false
                val dur = SystemClock.uptimeMillis() - downAt
                current.add(Pt(x, y))
                val delay = if (lastUpAt == 0L) 0L else (downAt - lastUpAt).coerceIn(0L, 120_000L)
                lastUpAt = SystemClock.uptimeMillis()

                val step = if (spread(current) < 24f) {
                    Step(
                        Step.TAP,
                        listOf(current.first()),
                        if (dur < 400) 60L else dur.coerceAtMost(30_000L),
                        delay
                    )
                } else {
                    Step(Step.SWIPE, simplify(current), dur.coerceIn(60L, 30_000L), delay)
                }

                marks.add(step.pts.first())
                current = mutableListOf()
                invalidate()
                onStep?.invoke(step)
            }

            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                current = mutableListOf()
                invalidate()
            }
        }
        return true
    }

    private fun spread(list: List<Pt>): Float {
        if (list.isEmpty()) return 0f
        val f = list.first()
        var max = 0f
        for (p in list) {
            val d = abs(p.x - f.x) + abs(p.y - f.y)
            if (d > max) max = d
        }
        return max
    }

    /** Arrasto longo vira poucos pontos: dispatchGesture nao gosta de path gigante. */
    private fun simplify(src: List<Pt>): List<Pt> {
        if (src.size <= 60) return src
        val out = ArrayList<Pt>(62)
        val stride = src.size / 58f
        var i = 0f
        while (i < src.size - 1) {
            out.add(src[i.toInt()])
            i += stride
        }
        out.add(src.last())
        return out
    }
}
