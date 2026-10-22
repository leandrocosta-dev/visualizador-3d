package br.com.moviai.visualizador3d;

/** Espessura de parede: raio de cada ponto para dentro da peça até a parede oposta. */
final class Thickness {
    private Thickness() {}

    static final class Result {
        /** Espessura por vértice (+∞ = acima do alcance ou sem parede oposta; NaN = sem normal). */
        float[] perVertex;
        double min = Double.NaN;
        double[] minPoint;
        long valid, total;
    }

    /** @param reach alcance máximo do raio (acima disso a parede é considerada grossa) */
    static Result compute(Mesh m, Fit.Topology t, Deviation.RefIndex idx, double reach, Deviation.Progress prog) {
        int nu = t.nu;
        float[] uniq = new float[nu];
        double eps = Math.max(idx.diag * 1e-5, 1e-4);
        double maxT = Math.min(idx.diag, reach);
        double sg = idx.sign;
        Result r = new Result();
        int step = Math.max(1, nu / 100);
        for (int i = 0; i < nu; i++) {
            if (prog != null && i % step == 0) prog.update(i * 100 / nu);
            double nx = t.nrm[i * 3] * sg, ny = t.nrm[i * 3 + 1] * sg, nz = t.nrm[i * 3 + 2] * sg;
            if (nx == 0 && ny == 0 && nz == 0) { uniq[i] = Float.NaN; continue; }
            double ox = t.pos[i * 3], oy = t.pos[i * 3 + 1], oz = t.pos[i * 3 + 2];
            double d = idx.raycast(ox, oy, oz, -nx, -ny, -nz, eps, maxT, true);
            uniq[i] = Double.isNaN(d) ? Float.POSITIVE_INFINITY : (float) d;
            if (!Double.isNaN(d)) {
                r.valid++;
                if (Double.isNaN(r.min) || d < r.min) {
                    r.min = d;
                    r.minPoint = new double[]{ox, oy, oz};
                }
            }
        }
        r.total = nu;
        float[] pv = new float[m.vertexCount];
        for (int i = 0; i < pv.length; i++) pv[i] = uniq[t.wid[i]];
        r.perVertex = pv;
        return r;
    }

    /** Percentual de vértices válidos abaixo da espessura mínima. */
    static double percentBelow(float[] th, double minT) {
        long below = 0, valid = 0;
        for (float v : th) {
            if (Float.isNaN(v)) continue;
            valid++;
            if (v < minT) below++;
        }
        return valid == 0 ? 0 : 100.0 * below / valid;
    }

    /** Vermelho abaixo do mínimo, amarelo até 2×, verde até 3×, azul a partir de 5×. Cinza sem leitura. */
    static void color(double v, double minT, float[] rgb) {
        if (Double.isNaN(v)) { rgb[0] = 0.45f; rgb[1] = 0.47f; rgb[2] = 0.50f; return; }
        double x = Double.isInfinite(v) ? 99 : v / minT;
        if (x < 1) { rgb[0] = 0.95f; rgb[1] = 0.15f; rgb[2] = 0.12f; return; }
        if (x < 2) { double u = x - 1; rgb[0] = 1f; rgb[1] = (float) (0.55 + 0.35 * u); rgb[2] = 0.12f; return; }
        if (x < 3) { double u = x - 2; rgb[0] = (float) (1 - 0.8 * u); rgb[1] = (float) (0.9 - 0.1 * u); rgb[2] = (float) (0.12 + 0.18 * u); return; }
        double u = Math.min(1, (x - 3) / 2);
        rgb[0] = (float) (0.2 - 0.05 * u);
        rgb[1] = (float) (0.8 - 0.35 * u);
        rgb[2] = (float) (0.3 + 0.7 * u);
    }

    static byte[] colorize(float[] th, double minT) {
        byte[] c = new byte[th.length * 3];
        float[] rgb = new float[3];
        for (int i = 0; i < th.length; i++) {
            color(th[i], minT, rgb);
            c[i * 3] = (byte) Math.round(rgb[0] * 255);
            c[i * 3 + 1] = (byte) Math.round(rgb[1] * 255);
            c[i * 3 + 2] = (byte) Math.round(rgb[2] * 255);
        }
        return c;
    }
}
