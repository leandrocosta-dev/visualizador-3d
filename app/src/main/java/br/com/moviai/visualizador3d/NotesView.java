package br.com.moviai.visualizador3d;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Etiquetas numeradas presas a pontos da peça, desenhadas por cima da vista 3D. */
final class NotesView extends View {

    static final class Note {
        double[] p;
        String text;

        Note(double[] p, String text) {
            this.p = p;
            this.text = text;
        }
    }

    private final List<Note> notes = new ArrayList<>();
    private float[] mvp;
    private int vw, vh;
    private final float density;

    NotesView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
    }

    void setNotes(List<Note> n) {
        notes.clear();
        notes.addAll(n);
        invalidate();
    }

    /** Chamado a cada quadro com a matriz usada no desenho (em qualquer thread). */
    void onFrame(float[] m, int w, int h) {
        mvp = m;
        vw = w;
        vh = h;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float[] m = mvp;
        if (m == null || notes.isEmpty()) return;
        draw(c, notes, m, vw, vh, density, (float) getWidth() / Math.max(1, vw));
    }

    /** Desenha as etiquetas num canvas qualquer (tela, foto ou relatório). */
    static void draw(Canvas c, List<Note> notes, float[] m, int w, int h, float density, float scale) {
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(0xFFFF7043);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setColor(0xFFFFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.5f * density);
        Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
        num.setColor(0xFFFFFFFF);
        num.setTextSize(11 * density);
        num.setTextAlign(Paint.Align.CENTER);
        num.setFakeBoldText(true);
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(0xFFFFFFFF);
        txt.setTextSize(12 * density);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xCC1B2430);
        Paint lead = new Paint(Paint.ANTI_ALIAS_FLAG);
        lead.setColor(0xFFFF7043);
        lead.setStrokeWidth(1.5f * density);
        float r = 9 * density;
        for (int i = 0; i < notes.size(); i++) {
            double[] p = notes.get(i).p;
            double cw = m[3] * p[0] + m[7] * p[1] + m[11] * p[2] + m[15];
            if (cw <= 0) continue;
            double nx = (m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12]) / cw;
            double ny = (m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13]) / cw;
            float x = (float) ((nx + 1) / 2 * w) * scale;
            float y = (float) ((1 - ny) / 2 * h) * scale;
            String t = notes.get(i).text;
            if (t.length() > 40) t = t.substring(0, 39) + "…";
            float tx = x + r + 10 * density, ty = y - r - 8 * density;
            float tw = txt.measureText(t);
            c.drawLine(x, y, tx, ty, lead);
            RectF box = new RectF(tx - 4 * density, ty - 14 * density, tx + tw + 6 * density, ty + 5 * density);
            c.drawRoundRect(box, 5 * density, 5 * density, bg);
            c.drawText(t, tx + density, ty, txt);
            c.drawCircle(x, y, r, dot);
            c.drawCircle(x, y, r, ring);
            c.drawText(String.valueOf(i + 1), x, y + 4 * density, num);
        }
    }
}
