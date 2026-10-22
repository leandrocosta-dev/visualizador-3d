package br.com.moviai.visualizador3d;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Desenho 2D do perfil de seção com zoom, arraste, grade e marcação de pontos (com encaixe). */
final class SectionView extends View {

    interface TapListener {
        void onTap(double u, double v, double tolUV);
    }

    private Section sec;
    private double scale = 1, ox = 0, oy = 0;
    private boolean fitPending = true;
    private final List<double[]> marks = new ArrayList<>();
    private boolean radiusMode = false;
    private TapListener listener;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Paint gridText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint measure = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final ScaleGestureDetector scaleDet;
    private final GestureDetector gestDet;
    private float lastX, lastY;
    private int lastCount = -1;

    SectionView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF12161C);
        line.setColor(0xFF7FD3FF);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.6f * density);
        line.setStrokeJoin(Paint.Join.ROUND);
        grid.setColor(0xFF263040);
        grid.setStrokeWidth(1);
        gridText.setColor(0xFF607D8B);
        gridText.setTextSize(10 * density);
        mark.setColor(0xFFFFD54F);
        mark.setStyle(Paint.Style.FILL);
        measure.setColor(0xFFFFD54F);
        measure.setStyle(Paint.Style.STROKE);
        measure.setStrokeWidth(1.5f * density);

        scaleDet = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                double f = d.getScaleFactor();
                double fx = d.getFocusX(), fy = d.getFocusY();
                ox = fx - (fx - ox) * f;
                oy = fy - (fy - oy) * f;
                scale *= f;
                invalidate();
                return true;
            }
        });
        gestDet = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null && sec != null) {
                    listener.onTap(toU(e.getX()), toV(e.getY()), 28 * density / scale);
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                fitPending = true;
                invalidate();
                return true;
            }
        });
    }

    void setSection(Section s) {
        sec = s;
        marks.clear();
        fitPending = true;
        invalidate();
    }

    void setTapListener(TapListener l) {
        listener = l;
    }

    void setMarks(List<double[]> m, boolean radius) {
        marks.clear();
        marks.addAll(m);
        radiusMode = radius;
        invalidate();
    }

    private double toU(float x) { return (x - ox) / scale; }
    private double toV(float y) { return (oy - y) / scale; }
    private float sx(double u) { return (float) (ox + u * scale); }
    private float sy(double v) { return (float) (oy - v * scale); }

    private void fit() {
        int w = getWidth(), h = getHeight();
        if (sec == null || w == 0 || h == 0) return;
        double du = Math.max(sec.maxU - sec.minU, 1e-6), dv = Math.max(sec.maxV - sec.minV, 1e-6);
        scale = Math.min(w * 0.85 / du, h * 0.70 / dv);
        double cu = (sec.minU + sec.maxU) / 2, cv = (sec.minV + sec.maxV) / 2;
        ox = w / 2.0 - cu * scale;
        oy = h * 0.45 + cv * scale;
        fitPending = false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDet.onTouchEvent(e);
        gestDet.onTouchEvent(e);
        int n = e.getPointerCount();
        float cx = 0, cy = 0;
        for (int i = 0; i < n; i++) { cx += e.getX(i); cy += e.getY(i); }
        cx /= n;
        cy /= n;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (n == lastCount) {
                    ox += cx - lastX;
                    oy += cy - lastY;
                    invalidate();
                }
                lastCount = n;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lastCount = -1;
                break;
            default:
                lastCount = -1;
                break;
        }
        lastX = cx;
        lastY = cy;
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (fitPending) fit();
        if (sec == null) return;
        drawGrid(c);
        Path path = new Path();
        for (int k = 0; k < sec.polylines.size(); k++) {
            double[] pl = sec.polylines.get(k);
            if (pl.length < 4) continue;
            path.reset();
            path.moveTo(sx(pl[0]), sy(pl[1]));
            for (int i = 2; i < pl.length; i += 2) path.lineTo(sx(pl[i]), sy(pl[i + 1]));
            if (sec.closed.get(k)) path.close();
            c.drawPath(path, line);
        }
        // medição
        if (!radiusMode && marks.size() >= 2) {
            double[] a = marks.get(0), b = marks.get(1);
            c.drawLine(sx(a[0]), sy(a[1]), sx(b[0]), sy(b[1]), measure);
        }
        if (radiusMode && marks.size() >= 3) {
            double[] cc = Align.circle(new double[]{marks.get(0)[0], marks.get(0)[1], 0},
                    new double[]{marks.get(1)[0], marks.get(1)[1], 0},
                    new double[]{marks.get(2)[0], marks.get(2)[1], 0});
            if (cc != null) {
                c.drawCircle(sx(cc[0]), sy(cc[1]), (float) (cc[3] * scale), measure);
                c.drawCircle(sx(cc[0]), sy(cc[1]), 3 * density, mark);
            }
        }
        for (double[] m : marks) c.drawCircle(sx(m[0]), sy(m[1]), 5 * density, mark);
    }

    private void drawGrid(Canvas c) {
        int w = getWidth(), h = getHeight();
        double target = 70 * density / scale;
        double step = Math.pow(10, Math.floor(Math.log10(target)));
        if (target / step > 5) step *= 5;
        else if (target / step > 2) step *= 2;
        double u0 = Math.floor(toU(0) / step) * step, u1 = toU(w);
        double v0 = Math.floor(toV(h) / step) * step, v1 = toV(0);
        String fmt = step >= 1 ? "%.0f" : step >= 0.1 ? "%.1f" : "%.2f";
        for (double u = u0; u <= u1; u += step) {
            float x = sx(u);
            c.drawLine(x, 0, x, h, grid);
            c.drawText(String.format(Locale.US, fmt, u), x + 2 * density, h - 4 * density, gridText);
        }
        for (double v = v0; v <= v1; v += step) {
            float y = sy(v);
            c.drawLine(0, y, w, y, grid);
            c.drawText(String.format(Locale.US, fmt, v), 2 * density, y - 2 * density, gridText);
        }
        Paint ax = new Paint(gridText);
        ax.setColor(0xFF90A4AE);
        ax.setTextSize(12 * density);
        c.drawText(Section.axisName(sec.uAxis) + " →", w - 40 * density, h - 18 * density, ax);
        c.drawText(Section.axisName(sec.vAxis) + " ↑", 6 * density, 16 * density + getPaddingTop(), ax);
        c.drawColor(Color.TRANSPARENT);
    }
}
