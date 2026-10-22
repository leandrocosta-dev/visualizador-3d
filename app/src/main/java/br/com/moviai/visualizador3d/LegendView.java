package br.com.moviai.visualizador3d;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.Locale;

/** Barra de cores do mapa de desvios com as marcas −faixa, −tol, 0, +tol, +faixa. */
final class LegendView extends View {

    private double tol = 0.1;
    private final Paint bar = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint();
    private final float[] rgb = new float[3];
    private final float density;

    LegendView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        text.setColor(0xFFCFD8DC);
        text.setTextSize(11 * density);
        text.setTextAlign(Paint.Align.CENTER);
        tick.setColor(Color.WHITE);
        tick.setStrokeWidth(density);
    }

    void setTolerance(double t) {
        tol = t;
        invalidate();
    }

    static double range(double tol) {
        return 5 * tol;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float pad = 18 * density;
        float barH = 14 * density;
        float top = 4 * density;
        double range = range(tol);
        float usable = w - 2 * pad;
        for (int x = 0; x < (int) usable; x++) {
            double d = -range + 2 * range * x / usable;
            Deviation.color(d, tol, range, rgb);
            bar.setColor(Color.rgb((int) (rgb[0] * 255), (int) (rgb[1] * 255), (int) (rgb[2] * 255)));
            c.drawRect(pad + x, top, pad + x + 1, top + barH, bar);
        }
        double[] marks = {-range, -tol, tol, range};
        for (double m : marks) {
            float x = (float) (pad + (m + range) / (2 * range) * usable);
            c.drawLine(x, top + barH, x, top + barH + 4 * density, tick);
            String s = String.format(new Locale("pt", "BR"), "%+.2f", m);
            c.drawText(s, x, top + barH + 16 * density, text);
        }
        if (h > 0) {
            Paint sub = new Paint(text);
            sub.setTextAlign(Paint.Align.LEFT);
            sub.setColor(0xFF90A4AE);
            c.drawText("falta de material", pad, h - 2 * density, sub);
            sub.setTextAlign(Paint.Align.RIGHT);
            c.drawText("sobra de material", w - pad, h - 2 * density, sub);
        }
    }
}
