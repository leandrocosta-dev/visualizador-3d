package br.com.moviai.visualizador3d;

import java.util.Arrays;

/** Alinhamento do scan sobre a referência: grosso por eixos principais e fino por ICP (Horn). */
final class Icp {
    private Icp() {}

    static final class Result {
        float[] transform;
        double rms;
        double matchedFraction;
    }

    /** Amostra até max vértices do scan (coordenadas atuais). */
    static double[] sample(Mesh scan, int max) {
        int n = scan.vertexCount;
        int step = Math.max(1, n / max);
        int k = (n + step - 1) / step;
        double[] s = new double[k * 3];
        for (int i = 0, j = 0; j < k; i += step, j++) {
            s[j * 3] = scan.positions[i * 3];
            s[j * 3 + 1] = scan.positions[i * 3 + 1];
            s[j * 3 + 2] = scan.positions[i * 3 + 2];
        }
        return s;
    }

    /** Transformação rígida ótima que leva src em dst (método de Horn por quatérnios). */
    static float[] horn(double[] src, double[] dst, int n) {
        double csx = 0, csy = 0, csz = 0, cdx = 0, cdy = 0, cdz = 0;
        for (int i = 0; i < n; i++) {
            csx += src[i * 3]; csy += src[i * 3 + 1]; csz += src[i * 3 + 2];
            cdx += dst[i * 3]; cdy += dst[i * 3 + 1]; cdz += dst[i * 3 + 2];
        }
        csx /= n; csy /= n; csz /= n; cdx /= n; cdy /= n; cdz /= n;
        double sxx = 0, sxy = 0, sxz = 0, syx = 0, syy = 0, syz = 0, szx = 0, szy = 0, szz = 0;
        for (int i = 0; i < n; i++) {
            double ax = src[i * 3] - csx, ay = src[i * 3 + 1] - csy, az = src[i * 3 + 2] - csz;
            double bx = dst[i * 3] - cdx, by = dst[i * 3 + 1] - cdy, bz = dst[i * 3 + 2] - cdz;
            sxx += ax * bx; sxy += ax * by; sxz += ax * bz;
            syx += ay * bx; syy += ay * by; syz += ay * bz;
            szx += az * bx; szy += az * by; szz += az * bz;
        }
        double[][] N = {
                {sxx + syy + szz, syz - szy, szx - sxz, sxy - syx},
                {syz - szy, sxx - syy - szz, sxy + syx, szx + sxz},
                {szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy},
                {sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz}};
        double[] q = Align.maxEigenvector(N);
        double w = q[0], x = q[1], y = q[2], z = q[3];
        double l = Math.sqrt(w * w + x * x + y * y + z * z);
        w /= l; x /= l; y /= l; z /= l;
        float[] m = Mat4.identity();
        m[0] = (float) (1 - 2 * (y * y + z * z));
        m[4] = (float) (2 * (x * y - w * z));
        m[8] = (float) (2 * (x * z + w * y));
        m[1] = (float) (2 * (x * y + w * z));
        m[5] = (float) (1 - 2 * (x * x + z * z));
        m[9] = (float) (2 * (y * z - w * x));
        m[2] = (float) (2 * (x * z - w * y));
        m[6] = (float) (2 * (y * z + w * x));
        m[10] = (float) (1 - 2 * (x * x + y * y));
        m[12] = (float) (cdx - (m[0] * csx + m[4] * csy + m[8] * csz));
        m[13] = (float) (cdy - (m[1] * csx + m[5] * csy + m[9] * csz));
        m[14] = (float) (cdz - (m[2] * csx + m[6] * csy + m[10] * csz));
        return m;
    }

    private static void apply(float[] t, double[] pts, int n) {
        for (int i = 0; i < n; i++) {
            double x = pts[i * 3], y = pts[i * 3 + 1], z = pts[i * 3 + 2];
            pts[i * 3] = t[0] * x + t[4] * y + t[8] * z + t[12];
            pts[i * 3 + 1] = t[1] * x + t[5] * y + t[9] * z + t[13];
            pts[i * 3 + 2] = t[2] * x + t[6] * y + t[10] * z + t[14];
        }
    }

    /**
     * ICP com descarte dos 10% piores pares (tolera partes do scan que não existem no CAD).
     * @param pts amostras do scan nas coordenadas atuais (não são alteradas)
     * @param t0  transformação inicial
     */
    static Result refine(double[] pts0, Deviation.RefIndex idx, float[] t0, int iters, double startDist) {
        int n = pts0.length / 3;
        double[] pts = pts0.clone();
        apply(t0, pts, n);
        float[] total = t0.clone();
        double maxD = startDist;
        double[] out = new double[4];
        double[] src = new double[n * 3], dst = new double[n * 3];
        double[] dist = new double[n];
        Result r = new Result();
        double minD = idx.diag * 1e-4;
        for (int it = 0; it < iters; it++) {
            int k = 0;
            for (int i = 0; i < n; i++) {
                double d = idx.closest(pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2], maxD, out);
                if (Double.isNaN(d)) continue;
                src[k * 3] = pts[i * 3]; src[k * 3 + 1] = pts[i * 3 + 1]; src[k * 3 + 2] = pts[i * 3 + 2];
                dst[k * 3] = out[0]; dst[k * 3 + 1] = out[1]; dst[k * 3 + 2] = out[2];
                dist[k] = d;
                k++;
            }
            r.matchedFraction = n == 0 ? 0 : (double) k / n;
            if (k < 12) {
                r.rms = Double.NaN;
                break;
            }
            // descarta os 10% mais distantes
            double[] sorted = Arrays.copyOf(dist, k);
            Arrays.sort(sorted);
            double cut = sorted[Math.min(k - 1, (int) (k * 0.9))];
            double median = sorted[k / 2];
            int w = 0;
            double sum2 = 0;
            for (int i = 0; i < k; i++) {
                if (dist[i] > cut) continue;
                System.arraycopy(src, i * 3, src, w * 3, 3);
                System.arraycopy(dst, i * 3, dst, w * 3, 3);
                sum2 += dist[i] * dist[i];
                w++;
            }
            r.rms = Math.sqrt(sum2 / Math.max(1, w));
            float[] step = Icp.horn(src, dst, w);
            apply(step, pts, n);
            total = Mat4.mul(step, total);

            double ang = Math.acos(Math.max(-1, Math.min(1, (step[0] + step[5] + step[10] - 1) / 2)));
            double tr = Math.sqrt(step[12] * step[12] + step[13] * step[13] + step[14] * step[14]);
            maxD = Math.min(startDist, Math.max(4 * median, minD * 10));
            if (ang < 2e-6 && tr < minD * 0.01) break;
        }
        r.transform = total;
        return r;
    }

    /**
     * Alinhamento completo: casa os eixos principais (testando as 4 orientações possíveis)
     * e refina com ICP. Funciona melhor com scan e CAD da peça inteira.
     */
    static Result align(Mesh scan, Mesh ref, Deviation.RefIndex idx) {
        double[] ms = new double[3], mr = new double[3];
        Align.Eig es = Align.eigSym(Align.surfaceCovariance(scan, ms));
        Align.Eig er = Align.eigSym(Align.surfaceCovariance(ref, mr));
        double[][] Es = frame(es), Er = frame(er);

        // centros candidatos: centróide da superfície e centro da caixa no referencial principal
        // (a caixa é pouco afetada quando falta uma face no scan parcial)
        double[][] cs = {ms, boxCenter(scan, Es)};
        double[][] cr = {mr, boxCenter(ref, Er)};

        double[] coarse = sample(scan, 1500);
        Result best = null;
        double bestScore = Double.MAX_VALUE;
        // 1ª tentativa: eixos principais casados na mesma ordem (4 orientações)
        // 2ª, se não casou bem: as 24 orientações do cubo (scan parcial ou com sujeira)
        for (int pass = 0; pass < 2; pass++) {
            for (double[][] S : pass == 0 ? FLIPS : CUBE) {
                float[] t0 = Mat4.identity();
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        double v = 0;
                        for (int k = 0; k < 3; k++) for (int l = 0; l < 3; l++) v += Er[k][i] * S[k][l] * Es[l][j];
                        t0[j * 4 + i] = (float) v;
                    }
                }
                for (int c = 0; c < 2; c++) {
                    float[] t = Mat4.mul(Mat4.translation(cr[c][0], cr[c][1], cr[c][2]),
                            Mat4.mul(t0, Mat4.translation(-cs[c][0], -cs[c][1], -cs[c][2])));
                    Result r = refine(coarse, idx, t, 20, idx.diag * 0.25);
                    if (Double.isNaN(r.rms)) continue;
                    double score = r.rms / Math.max(0.02, r.matchedFraction * r.matchedFraction);
                    if (score < bestScore) {
                        bestScore = score;
                        best = r;
                    }
                }
            }
            if (best != null && best.matchedFraction > 0.75) break;
        }
        if (best == null) return null;
        double[] fine = sample(scan, 15000);
        Result r = refine(fine, idx, best.transform, 50, Math.max(best.rms * 6, idx.diag * 0.01));
        return Double.isNaN(r.rms) ? best : r;
    }

    private static final double[][][] FLIPS = {
            {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}},
            {{1, 0, 0}, {0, -1, 0}, {0, 0, -1}},
            {{-1, 0, 0}, {0, 1, 0}, {0, 0, -1}},
            {{-1, 0, 0}, {0, -1, 0}, {0, 0, 1}}};

    /** As 24 rotações que levam eixos em eixos (permutações com sinal e determinante +1). */
    private static final double[][][] CUBE = buildCube();

    private static double[][][] buildCube() {
        int[][] perms = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        double[][][] out = new double[24][][];
        int n = 0;
        for (int[] pm : perms) {
            for (int sgn = 0; sgn < 8; sgn++) {
                double[][] m = new double[3][3];
                for (int i = 0; i < 3; i++) m[i][pm[i]] = ((sgn >> i) & 1) == 0 ? 1 : -1;
                double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                        - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                        + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
                if (det > 0) out[n++] = m;
            }
        }
        return out;
    }

    /** Só o refinamento, partindo da posição atual (para peças já pré-alinhadas à mão). */
    static Result refineOnly(Mesh scan, Deviation.RefIndex idx) {
        double[] pts = sample(scan, 15000);
        Result r = refine(pts, idx, Mat4.identity(), 25, idx.diag * 0.08);
        if (r.rms != r.rms) return null;
        return refine(pts, idx, r.transform, 40, Math.max(r.rms * 6, idx.diag * 0.005));
    }

    /** Centro da caixa envolvente dos vértices medida nos eixos principais, em coordenadas do mundo. */
    private static double[] boxCenter(Mesh m, double[][] E) {
        double[] mn = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] mx = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        float[] p = m.positions;
        int step = Math.max(1, m.vertexCount / 400_000);
        for (int i = 0; i < m.vertexCount; i += step) {
            for (int k = 0; k < 3; k++) {
                double v = E[k][0] * p[i * 3] + E[k][1] * p[i * 3 + 1] + E[k][2] * p[i * 3 + 2];
                if (v < mn[k]) mn[k] = v;
                if (v > mx[k]) mx[k] = v;
            }
        }
        double[] c = new double[3];
        for (int k = 0; k < 3; k++) {
            double f = (mn[k] + mx[k]) / 2;
            c[0] += E[k][0] * f;
            c[1] += E[k][1] * f;
            c[2] += E[k][2] * f;
        }
        return c;
    }

    private static double[][] frame(Align.Eig e) {
        double[] x = e.vec[0], y = e.vec[1];
        double[] z = Align.cross(x, y);
        return new double[][]{x, y, z};
    }
}
