package br.com.moviai.visualizador3d;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Relatório técnico em PDF (A4) com identidade da MoviAI. */
final class Report {

    static final class Data {
        String title = "Relatório dimensional";
        String date = "";
        final List<String[]> info = new ArrayList<>();
        final List<Bitmap> images = new ArrayList<>();
        final List<String> captions = new ArrayList<>();
        /** Tolerância da legenda de desvios, ou null se não houver mapa. */
        Double legendTol = null;
        final Map<String, List<String>> sections = new LinkedHashMap<>();
    }

    private static final int W = 595, H = 842, M = 40;
    private static final int C_DARK = 0xFF1B2430, C_ACCENT = 0xFF1E88E5, C_TEXT = 0xFF263238, C_MUTED = 0xFF607D8B;

    private PdfDocument doc;
    private PdfDocument.Page page;
    private Canvas c;
    private int pageNo = 0;
    private float y;
    private Data data;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bold = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint muted = new Paint(Paint.ANTI_ALIAS_FLAG);

    Report() {
        text.setColor(C_TEXT);
        text.setTextSize(10);
        bold.setColor(C_TEXT);
        bold.setTextSize(10);
        bold.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        muted.setColor(C_MUTED);
        muted.setTextSize(8.5f);
    }

    void write(Data d, OutputStream os) throws IOException {
        data = d;
        doc = new PdfDocument();
        try {
            newPage();
            drawInfo();
            drawImages();
            if (d.legendTol != null) drawLegend(d.legendTol);
            for (Map.Entry<String, List<String>> e : d.sections.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                heading(e.getKey());
                for (String line : e.getValue()) paragraph(line);
                y += 6;
            }
            finishPage();
            doc.writeTo(os);
        } finally {
            doc.close();
        }
    }

    private void newPage() {
        if (page != null) finishPage();
        pageNo++;
        page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo).create());
        c = page.getCanvas();
        Paint bg = new Paint();
        bg.setColor(C_DARK);
        c.drawRect(0, 0, W, 64, bg);
        bg.setColor(C_ACCENT);
        c.drawRect(0, 64, W, 67, bg);
        Paint h1 = new Paint(Paint.ANTI_ALIAS_FLAG);
        h1.setColor(Color.WHITE);
        h1.setTextSize(17);
        h1.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText("MoviAI Engenharia Digital", M, 30, h1);
        Paint h2 = new Paint(Paint.ANTI_ALIAS_FLAG);
        h2.setColor(0xFFB0BEC5);
        h2.setTextSize(10.5f);
        c.drawText(data.title, M, 49, h2);
        h2.setTextAlign(Paint.Align.RIGHT);
        c.drawText(data.date, W - M, 30, h2);
        y = 92;
    }

    private void finishPage() {
        if (page == null) return;
        Paint f = new Paint(muted);
        c.drawLine(M, H - 34, W - M, H - 34, f);
        c.drawText("Gerado pelo Visualizador 3D · MoviAI Engenharia Digital", M, H - 20, f);
        f.setTextAlign(Paint.Align.RIGHT);
        c.drawText("Página " + pageNo, W - M, H - 20, f);
        doc.finishPage(page);
        page = null;
    }

    private void ensure(float h) {
        if (y + h > H - 50) newPage();
    }

    private void drawInfo() {
        for (String[] kv : data.info) {
            ensure(15);
            c.drawText(kv[0], M, y, bold);
            float x = M + 150;
            List<String> lines = wrap(kv[1], text, W - M - x);
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) { y += 13; ensure(13); }
                c.drawText(lines.get(i), x, y, text);
            }
            y += 15;
        }
        y += 6;
    }

    private void drawImages() {
        int n = data.images.size();
        if (n == 0) return;
        float gap = 10;
        float cw = (W - 2 * M - gap) / 2f;
        float ch = cw * 0.80f;
        for (int i = 0; i < n; i += 2) {
            ensure(ch + 22);
            for (int k = 0; k < 2 && i + k < n; k++) {
                float x = M + k * (cw + gap);
                RectF cell = new RectF(x, y, x + cw, y + ch);
                Paint frame = new Paint();
                frame.setColor(0xFFF4F6F8);
                c.drawRect(cell, frame);
                Bitmap b = data.images.get(i + k);
                // recorte central mantendo a proporção da célula
                float ra = cell.width() / cell.height(), rb = (float) b.getWidth() / b.getHeight();
                Rect src;
                if (rb > ra) {
                    int sw = Math.round(b.getHeight() * ra);
                    int sx = (b.getWidth() - sw) / 2;
                    src = new Rect(sx, 0, sx + sw, b.getHeight());
                } else {
                    int sh = Math.round(b.getWidth() / ra);
                    int sy = (b.getHeight() - sh) / 2;
                    src = new Rect(0, sy, b.getWidth(), sy + sh);
                }
                Paint fp = new Paint(Paint.FILTER_BITMAP_FLAG);
                c.drawBitmap(b, src, cell, fp);
                frame.setStyle(Paint.Style.STROKE);
                frame.setColor(0xFFCFD8DC);
                c.drawRect(cell, frame);
                String cap = i + k < data.captions.size() ? data.captions.get(i + k) : "";
                c.drawText(cap, x, y + ch + 12, muted);
            }
            y += ch + 22;
        }
    }

    private void drawLegend(double tol) {
        ensure(46);
        c.drawText("Mapa de desvios (mm)", M, y, bold);
        y += 8;
        double range = LegendView.range(tol);
        float bw = W - 2 * M, bh = 12;
        float[] rgb = new float[3];
        Paint p = new Paint();
        for (int i = 0; i < (int) bw; i++) {
            double d = -range + 2 * range * i / bw;
            Deviation.color(d, tol, range, rgb);
            p.setColor(Color.rgb((int) (rgb[0] * 255), (int) (rgb[1] * 255), (int) (rgb[2] * 255)));
            c.drawRect(M + i, y, M + i + 1, y + bh, p);
        }
        Paint t = new Paint(muted);
        t.setTextAlign(Paint.Align.CENTER);
        for (double m : new double[]{-range, -tol, tol, range}) {
            float x = (float) (M + (m + range) / (2 * range) * bw);
            c.drawLine(x, y + bh, x, y + bh + 3, t);
            c.drawText(String.format(new Locale("pt", "BR"), "%+.2f", m), x, y + bh + 12, t);
        }
        t.setTextAlign(Paint.Align.LEFT);
        c.drawText("falta de material", M, y + bh + 24, t);
        t.setTextAlign(Paint.Align.RIGHT);
        c.drawText("sobra de material   ·   cinza: sem correspondência", W - M, y + bh + 24, t);
        y += bh + 40;
    }

    private void heading(String s) {
        ensure(34);
        Paint p = new Paint(bold);
        p.setTextSize(12);
        p.setColor(C_ACCENT);
        c.drawText(s, M, y, p);
        y += 4;
        Paint l = new Paint();
        l.setColor(0xFFCFD8DC);
        c.drawRect(M, y, W - M, y + 1, l);
        y += 14;
    }

    private void paragraph(String s) {
        for (String raw : s.split("\n")) {
            for (String line : wrap(raw, text, W - 2 * M)) {
                ensure(14);
                c.drawText(line, M, y, text);
                y += 14;
            }
        }
    }

    private static List<String> wrap(String s, Paint p, float max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : s.split(" ")) {
            String t = cur.length() == 0 ? w : cur + " " + w;
            if (p.measureText(t) > max && cur.length() > 0) {
                out.add(cur.toString());
                cur = new StringBuilder(w);
            } else {
                cur = new StringBuilder(t);
            }
        }
        if (cur.length() > 0 || out.isEmpty()) out.add(cur.toString());
        return out;
    }
}
