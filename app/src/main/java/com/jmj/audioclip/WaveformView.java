package com.jmj.audioclip;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/** 波形显示控件：支持选中区间高亮、进度指针、点击/拖动定位 */
public class WaveformView extends View {

    private short[] peaks;
    private long durationUs = 1;
    private long cursorUs;      // 当前光标
    private long startUs, endUs; // 剪辑区间
    private int waveColor = 0xFF3F51B5;
    private int dimColor  = 0xFFC5CAE9;
    private int selBg     = 0x333F51B5;
    private int lineColor = 0xFFFF5722;

    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selPaint  = new Paint();
    private final Paint curPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector detector;
    private OnSeekListener listener;

    public interface OnSeekListener {
        void onSeek(long positionUs);
    }

    public WaveformView(Context c) { this(c, null); }
    public WaveformView(Context c, AttributeSet a) {
        super(c, a);
        wavePaint.setColor(waveColor);
        wavePaint.setStrokeWidth(2f);
        selPaint.setColor(selBg);
        curPaint.setColor(lineColor);
        curPaint.setStrokeWidth(3f);
        detector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                seekTo(e.getX());
                return true;
            }
            @Override public boolean onScroll(MotionEvent d, MotionEvent e, float dx, float dy) {
                seekTo(e.getX());
                return true;
            }
        });
    }

    public void setOnSeekListener(OnSeekListener l) { listener = l; }

    public void setPeaks(short[] p, long durUs) {
        peaks = p; durationUs = Math.max(1, durUs);
        endUs = durationUs;
        cursorUs = 0;
        invalidate();
    }

    public void setRange(long s, long e) { startUs = s; endUs = e; invalidate(); }
    public void setCursor(long us) { cursorUs = us; invalidate(); }

    private void seekTo(float x) {
        long us = (long) (x / getWidth() * durationUs);
        us = Math.max(0, Math.min(durationUs, us));
        cursorUs = us;
        invalidate();
        if (listener != null) listener.onSeek(us);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float mid = h * 0.5f;

        // 选中区间背景
        float sx = usToX(startUs), ex = usToX(endUs);
        canvas.drawRect(sx, 0, ex, h, selPaint);

        if (peaks == null || peaks.length == 0) {
            wavePaint.setColor(dimColor);
            canvas.drawLine(0, mid, w, mid, wavePaint);
            wavePaint.setColor(waveColor);
            return;
        }

        int n = peaks.length;
        float step = (float) w / n;
        wavePaint.setColor(dimColor);
        // 未选中的波形（区间外）
        for (int i = 0; i < n; i++) {
            float x = i * step;
            if (x >= sx && x <= ex) continue;
            float amp = peaks[i] / 32767f * (h * 0.45f);
            if (amp < 1) amp = 1;
            canvas.drawLine(x, mid - amp, x, mid + amp, wavePaint);
        }
        wavePaint.setColor(waveColor);
        // 选中区间波形
        for (int i = 0; i < n; i++) {
            float x = i * step;
            if (x < sx || x > ex) continue;
            float amp = peaks[i] / 32767f * (h * 0.45f);
            if (amp < 1) amp = 1;
            canvas.drawLine(x, mid - amp, x, mid + amp, wavePaint);
        }

        // 光标
        float cx = usToX(cursorUs);
        canvas.drawLine(cx, 0, cx, h, curPaint);
    }

    private float usToX(long us) {
        return durationUs <= 0 ? 0 : us * 1f / durationUs * getWidth();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return detector.onTouchEvent(e);
    }
}