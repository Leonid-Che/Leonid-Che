package com.tablettouch.client

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Прозрачная область поверх SurfaceView: переводит MULTI_TOUCH события планшета
 * в JSON-команды сервера. Координаты отправляются В ПИКСЕЛЯХ экрана планшета —
 * всю геометрию (поворот планшета, доворот кадра сервером) считает сервер
 * в _map_point(), поэтому здесь нужна только сырая точка касания.
 */
@SuppressLint("ViewConstructor")
class TouchRelayView(context: Context, private val out: (String) -> Unit) : View(context) {

    private var primaryId = -1
    private var downX = 0f
    private var downY = 0f
    private var lastMoveTs = 0L
    private var longPressFired = false

    override fun performClick(): Boolean { super.performClick(); return true }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                primaryId = event.getPointerId(0)
                downX = event.x; downY = event.y
                longPressFired = false
                postDelayed(longPressRunnable, LONGPRESS_MS)
                send("down", event)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPressRunnable)
                // второй палец — щипок: сервер сам перейдёт в pinch-режим при 2+ точках
                send("down", event)
            }

            MotionEvent.ACTION_MOVE -> {
                val now = System.currentTimeMillis()
                if (now - lastMoveTs < MOVE_MIN_INTERVAL_MS &&
                    abs(event.x - downX) < 6 && abs(event.y - downY) < 6
                ) return true
                lastMoveTs = now
                sendAll("move", event)
            }

            MotionEvent.ACTION_POINTER_UP -> send("up", event)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (!longPressFired)
                    send(if (event.actionMasked == MotionEvent.ACTION_UP) "up" else "cancel", event)
                primaryId = -1
            }
        }
        return true
    }

    private val longPressRunnable = Runnable {
        longPressFired = true
        // правый клик на месте курсора (сервер эмулирует RBUTTONDOWN/UP)
        out("""{"type":"touch","action":"button","button":"right","x":$downX,"y":$downY,"pointerId":$primaryId}""")
    }

    /** Отправить все активные указатели одним сообщением (для щипка). */
    private fun sendAll(action: String, e: MotionEvent) {
        val sb = StringBuilder()
        sb.append("""{"type":"touch","action":"$action","pointerId":${e.getPointerId(e.actionIndex)},"points":[""")
        for (i in 0 until e.pointerCount) {
            if (i > 0) sb.append(',')
            sb.append('[').append(e.getX(i)).append(',').append(e.getY(i))
              .append(',').append(e.getPointerId(i)).append(']')
        }
        sb.append("]}")
        out(sb.toString())
    }

    private fun send(action: String, e: MotionEvent) {
        val idx = e.actionIndex.coerceAtMost(e.pointerCount - 1)
        val x = e.getX(idx); val y = e.getY(idx); val pid = e.getPointerId(idx)
        out("""{"type":"touch","action":"$action","pointerId":$pid,"points":[[$x,$y,$pid]]}""")
    }

    companion object {
        private const val LONGPRESS_MS = 650L          // как TouchState.LONGPRESS_S на сервере
        private const val MOVE_MIN_INTERVAL_MS = 8L    // ~120 Гц максимум
    }
}
