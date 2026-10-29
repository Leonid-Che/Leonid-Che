package ru.touchcursor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Тачпад-зона: палец перемещает курсор ПК АБСОЛЮТНО (позиция на тачпаде
 * пропорциональна позиции на экране компьютера). Прокрутка двумя пальцами -
 * колесо мыши.
 */
public class TouchPadView extends View {

    public interface OnTouchFraction {
        void onTouch(double fx, double fy);
    }

    private OnTouchFraction listener;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean active = false;
    private float fingerX = -1, fingerY = -1;
    private float lastFingerX = -1, lastFingerY = -1;
    private int pointerCount = 0;

    // для прокрутки двумя пальцами
    private float twoFingerLastY = 0;
    private static final float SCROLL_PIXELS_PER_NOTCH = 40f;

    public TouchPadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        bgPaint.setStyle(Paint.Style.FILL);
        crossPaint.setColor(Color.parseColor("#66FFFFFF"));
        crossPaint.setStrokeWidth(2f);
        textPaint.setColor(Color.parseColor("#80FFFFFF"));
        textPaint.setTextSize(36f);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setOnTouchFraction(OnTouchFraction l) {
        this.listener = l;
    }

    public void setActive(boolean a) {
        this.active = a;
        if (!a) {
            fingerX = fingerY = lastFingerX = lastFingerY = -1;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();

        // фон: активный тачпад чуть светлее
        bgPaint.setColor(active ? Color.parseColor("#1E2A38") : Color.parseColor("#141B23"));
        canvas.drawRect(0, 0, w, h, bgPaint);

        if (!active) {
            // У View нет собственного метода getString(int) — берём его из контекста
            String hint = getContext().getString(R.string.touchpad_hint);
            canvas.drawText(hint, w / 2f, h / 2f, textPaint);
            return;
        }

        // рамка и перекрестие в центре как ориентир
        crossPaint.setStyle(Paint.Style.STROKE);
        canvas.drawRect(4, 4, w - 4, h - 4, crossPaint);
        canvas.drawLine(w / 2f - 30, h / 2f, w / 2f + 30, h / 2f, crossPaint);
        canvas.drawLine(w / 2f, h / 2f - 30, w / 2f, h / 2f + 30, crossPaint);

        // точка касания
        if (fingerX >= 0 && fingerY >= 0) {
            crossPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(fingerX, fingerY, 18f, crossPaint);
            crossPaint.setStyle(Paint.Style.STROKE);
            canvas.drawCircle(fingerX, fingerY, 30f, crossPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!active) {
            return false; // при выключенном управлении тачпад пассивен
        }
        int action = event.getActionMasked();
        pointerCount = event.getPointerCount();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (pointerCount == 2) {
                    // начали прокрутку двумя пальцами
                    twoFingerLastY = (event.getY(0) + event.getY(1)) / 2f;
                }
                updateFinger(event);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pointerCount >= 2) {
                    float midY = (event.getY(0) + event.getY(1)) / 2f;
                    float dyPx = midY - twoFingerLastY;
                    if (Math.abs(dyPx) >= SCROLL_PIXELS_PER_NOTCH) {
                        int notches = (int) (dyPx / SCROLL_PIXELS_PER_NOTCH);
                        if (listener != null) {
                            ((MainActivity) getContext()).onScrollFromPad(notches);
                        }
                        twoFingerLastY += notches * SCROLL_PIXELS_PER_NOTCH;
                    }
                } else {
                    updateFinger(event);
                    sendPosition(event.getX(), event.getY());
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (pointerCount <= 1) {
                    fingerX = fingerY = lastFingerX = lastFingerY = -1;
                } else {
                    updateFinger(event);
                }
                break;
            }
        }
        invalidate();
        return true;
    }

    private void updateFinger(MotionEvent event) {
        if (event.getPointerCount() >= 1) {
            fingerX = event.getX(0);
            fingerY = event.getY(0);
        }
    }

    private void sendPosition(float x, float y) {
        if (listener == null || getWidth() == 0 || getHeight() == 0) return;
        double fx = clamp01((double) x / getWidth());
        double fy = clamp01((double) y / getHeight());
        listener.onTouch(fx, fy);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
