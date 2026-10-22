package br.com.moviai.visualizador3d;

/**
 * Comparação scan × referência (CAD): índice espacial da referência, ponto mais próximo,
 * desvio com sinal (+ sobra de material, − falta), estatísticas e mapa de cores.
 */
final class Deviation {
    private Deviation() {}

    // ================================================================ índice espacial

    /** Grade uniforme de triângulos da referência para busca do ponto mais próximo. */
    static final class RefIndex {
        final float[] p;
        final int[] tri;
        final float[] fn;
        final int tris;
        final double x0, y0, z0, h;
        final int nx, ny, nz;
        final int[] cellStart;
        final int[] cellTris;
        final double sign;
        private final int[] stamp;
        private int query = 0;
        final double diag;

        RefIndex(Mesh ref) {
            p = ref.positions;
            int t = MeshAnalysis.triCount(ref);
            if (t == 0) throw new IllegalArgumentException("A referência precisa ser uma malha (não nuvem de pontos)");
            tris = t;
            tri = new int[t * 3];
            for (int i = 0; i < t; i++) {
                for (int k = 0; k < 3; k++) tri[i * 3 + k] = MeshAnalysis.vtx(ref, i, k);
            }
            fn = new float[t * 3];
            double vol = 0;
            for (int i = 0; i < t; i++) {
                int a = tri[i * 3] * 3, b = tri[i * 3 + 1] * 3, c = tri[i * 3 + 2] * 3;
                double ux = p[b] - p[a], uy = p[b + 1] - p[a + 1], uz = p[b + 2] - p[a + 2];
                double vx = p[c] - p[a], vy = p[c + 1] - p[a + 1], vz = p[c + 2] - p[a + 2];
                double nx_ = uy * vz - uz * vy, ny_ = uz * vx - ux * vz, nz_ = ux * vy - uy * vx;
                double l = Math.sqrt(nx_ * nx_ + ny_ * ny_ + nz_ * nz_);
                if (l > 0) { fn[i * 3] = (float) (nx_ / l); fn[i * 3 + 1] = (float) (ny_ / l); fn[i * 3 + 2] = (float) (nz_ / l); }
                double ax = p[a] - ref.center[0], ay = p[a + 1] - ref.center[1], az = p[a + 2] - ref.center[2];
                vol += (ax * nx_ + ay * ny_ + az * nz_) / 6.0;
            }
            // normais da referência invertidas (volume negativo): inverte a convenção de sinal
            sign = vol < 0 ? -1 : 1;

            double ex = Math.max(ref.max[0] - ref.min[0], 1e-9);
            double ey = Math.max(ref.max[1] - ref.min[1], 1e-9);
            double ez = Math.max(ref.max[2] - ref.min[2], 1e-9);
            diag = Math.sqrt(ex * ex + ey * ey + ez * ez);
            double maxE = Math.max(ex, Math.max(ey, ez));
            int target = Math.max(1000, Math.min(1_500_000, t));
            double hh = Math.cbrt(Math.max(ex * ey * ez, maxE * maxE * maxE * 1e-6) / target);
            hh = Math.max(hh, maxE / 160.0);
            h = hh;
            nx = Math.max(1, (int) Math.ceil(ex / h));
            ny = Math.max(1, (int) Math.ceil(ey / h));
            nz = Math.max(1, (int) Math.ceil(ez / h));
            x0 = ref.min[0];
            y0 = ref.min[1];
            z0 = ref.min[2];

            int cells = nx * ny * nz;
            int[] count = new int[cells + 1];
            int[] r = new int[6];
            for (int i = 0; i < t; i++) {
                range(i, r);
                for (int z = r[2]; z <= r[5]; z++)
                    for (int y = r[1]; y <= r[4]; y++)
                        for (int x = r[0]; x <= r[3]; x++) count[(z * ny + y) * nx + x + 1]++;
            }
            for (int i = 0; i < cells; i++) count[i + 1] += count[i];
            cellStart = count;
            cellTris = new int[count[cells]];
            int[] fill = new int[cells];
            for (int i = 0; i < t; i++) {
                range(i, r);
                for (int z = r[2]; z <= r[5]; z++)
                    for (int y = r[1]; y <= r[4]; y++)
                        for (int x = r[0]; x <= r[3]; x++) {
                            int c = (z * ny + y) * nx + x;
                            cellTris[cellStart[c] + fill[c]++] = i;
                        }
            }
            stamp = new int[t];
        }

        private void range(int i, int[] r) {
            double mnx = Double.MAX_VALUE, mny = Double.MAX_VALUE, mnz = Double.MAX_VALUE;
            double mxx = -Double.MAX_VALUE, mxy = -Double.MAX_VALUE, mxz = -Double.MAX_VALUE;
            for (int k = 0; k < 3; k++) {
                int v = tri[i * 3 + k] * 3;
                mnx = Math.min(mnx, p[v]); mny = Math.min(mny, p[v + 1]); mnz = Math.min(mnz, p[v + 2]);
                mxx = Math.max(mxx, p[v]); mxy = Math.max(mxy, p[v + 1]); mxz = Math.max(mxz, p[v + 2]);
            }
            r[0] = cx(mnx); r[1] = cy(mny); r[2] = cz(mnz);
            r[3] = cx(mxx); r[4] = cy(mxy); r[5] = cz(mxz);
        }

        private int cx(double v) { return clamp((int) Math.floor((v - x0) / h), nx); }
        private int cy(double v) { return clamp((int) Math.floor((v - y0) / h), ny); }
        private int cz(double v) { return clamp((int) Math.floor((v - z0) / h), nz); }

        private static int clamp(int v, int n) {
            return v < 0 ? 0 : (v >= n ? n - 1 : v);
        }

        /**
         * Ponto mais próximo da referência até maxDist. Retorna a distância (ou NaN se nada no raio).
         * out[0..2] = ponto, out[3] = índice do triângulo. Não é thread-safe (usar só no worker).
         */
        double closest(double qx, double qy, double qz, double maxDist, double[] out) {
            if (++query == Integer.MAX_VALUE) {
                java.util.Arrays.fill(stamp, 0);
                query = 1;
            }
            int ix = cx(qx), iy = cy(qy), iz = cz(qz);
            double best2 = maxDist * maxDist;
            int bestTri = -1;
            double[] cp = new double[3];
            int maxR = Math.max(nx, Math.max(ny, nz));
            for (int rr = 0; rr <= maxR; rr++) {
                int xa = Math.max(0, ix - rr), xb = Math.min(nx - 1, ix + rr);
                int ya = Math.max(0, iy - rr), yb = Math.min(ny - 1, iy + rr);
                int za = Math.max(0, iz - rr), zb = Math.min(nz - 1, iz + rr);
                for (int z = za; z <= zb; z++) {
                    boolean zEdge = z == iz - rr || z == iz + rr;
                    for (int y = ya; y <= yb; y++) {
                        boolean yEdge = y == iy - rr || y == iy + rr;
                        for (int x = xa; x <= xb; x++) {
                            if (!zEdge && !yEdge && x != ix - rr && x != ix + rr) continue; // só a casca do anel
                            int c = (z * ny + y) * nx + x;
                            for (int k = cellStart[c]; k < cellStart[c + 1]; k++) {
                                int ti = cellTris[k];
                                if (stamp[ti] == query) continue;
                                stamp[ti] = query;
                                double d2 = closestOnTri(ti, qx, qy, qz, cp);
                                if (d2 < best2) {
                                    best2 = d2;
                                    bestTri = ti;
                                    out[0] = cp[0]; out[1] = cp[1]; out[2] = cp[2];
                                }
                            }
                        }
                    }
                }
                double reach = rr * h;
                if (bestTri >= 0 && best2 <= reach * reach) break;
                if (reach > maxDist) break;
            }
            if (bestTri < 0) return Double.NaN;
            out[3] = bestTri;
            return Math.sqrt(best2);
        }

        /** Distância com sinal: positiva se o ponto está fora da referência (sobra de material). */
        double signedDistance(double qx, double qy, double qz, double maxDist, double[] out) {
            double d = closest(qx, qy, qz, maxDist, out);
            if (Double.isNaN(d)) return d;
            int ti = (int) out[3];
            double s = (qx - out[0]) * fn[ti * 3] + (qy - out[1]) * fn[ti * 3 + 1] + (qz - out[2]) * fn[ti * 3 + 2];
            return (s * sign >= 0) ? d : -d;
        }

        /**
         * Primeiro triângulo atingido pelo raio o + t·d (d unitário) com minT &lt; t &lt; maxT.
         * Se exitOnly, aceita só triângulos cuja normal externa aponta no sentido do raio
         * (o raio está saindo da peça pela parede oposta). Retorna t ou NaN.
         */
        double raycast(double ox, double oy, double oz, double dx, double dy, double dz,
                       double minT, double maxT, boolean exitOnly) {
            if (++query == Integer.MAX_VALUE) {
                java.util.Arrays.fill(stamp, 0);
                query = 1;
            }
            int ix = cx(ox), iy = cy(oy), iz = cz(oz);
            int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
            double inf = Double.POSITIVE_INFINITY;
            double tdx = dx != 0 ? h / Math.abs(dx) : inf;
            double tdy = dy != 0 ? h / Math.abs(dy) : inf;
            double tdz = dz != 0 ? h / Math.abs(dz) : inf;
            double nbx = x0 + (ix + (sx > 0 ? 1 : 0)) * h;
            double nby = y0 + (iy + (sy > 0 ? 1 : 0)) * h;
            double nbz = z0 + (iz + (sz > 0 ? 1 : 0)) * h;
            double tmx = dx != 0 ? Math.max(0, (nbx - ox) / dx) : inf;
            double tmy = dy != 0 ? Math.max(0, (nby - oy) / dy) : inf;
            double tmz = dz != 0 ? Math.max(0, (nbz - oz) / dz) : inf;
            double best = Double.NaN;
            for (int guard = 0; guard < nx + ny + nz + 3; guard++) {
                int c = (iz * ny + iy) * nx + ix;
                for (int k = cellStart[c]; k < cellStart[c + 1]; k++) {
                    int ti = cellTris[k];
                    if (stamp[ti] == query) continue;
                    stamp[ti] = query;
                    if (exitOnly) {
                        double f = (fn[ti * 3] * dx + fn[ti * 3 + 1] * dy + fn[ti * 3 + 2] * dz) * sign;
                        if (f <= 0) continue;
                    }
                    double t = rayTri(ti, ox, oy, oz, dx, dy, dz);
                    if (t > minT && t < maxT && (Double.isNaN(best) || t < best)) best = t;
                }
                double exit = Math.min(tmx, Math.min(tmy, tmz));
                if (!Double.isNaN(best) && best <= exit) return best;
                if (exit > maxT) break;
                if (tmx <= tmy && tmx <= tmz) { ix += sx; tmx += tdx; if (ix < 0 || ix >= nx) break; }
                else if (tmy <= tmz) { iy += sy; tmy += tdy; if (iy < 0 || iy >= ny) break; }
                else { iz += sz; tmz += tdz; if (iz < 0 || iz >= nz) break; }
            }
            return best;
        }

        /** Möller–Trumbore; retorna t (pode ser negativo) ou NaN se não intercepta. */
        private double rayTri(int ti, double ox, double oy, double oz, double dx, double dy, double dz) {
            int ia = tri[ti * 3] * 3, ib = tri[ti * 3 + 1] * 3, ic = tri[ti * 3 + 2] * 3;
            double ax = p[ia], ay = p[ia + 1], az = p[ia + 2];
            double e1x = p[ib] - ax, e1y = p[ib + 1] - ay, e1z = p[ib + 2] - az;
            double e2x = p[ic] - ax, e2y = p[ic + 1] - ay, e2z = p[ic + 2] - az;
            double px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
            double det = e1x * px + e1y * py + e1z * pz;
            if (det > -1e-20 && det < 1e-20) return Double.NaN;
            double inv = 1.0 / det;
            double tx = ox - ax, ty = oy - ay, tz = oz - az;
            double u = (tx * px + ty * py + tz * pz) * inv;
            if (u < 0 || u > 1) return Double.NaN;
            double qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
            double v = (dx * qx + dy * qy + dz * qz) * inv;
            if (v < 0 || u + v > 1) return Double.NaN;
            return (e2x * qx + e2y * qy + e2z * qz) * inv;
        }

        /** Ericson, Real-Time Collision Detection, 5.1.5. Retorna a distância ao quadrado. */
        private double closestOnTri(int ti, double px, double py, double pz, double[] o) {
            int ia = tri[ti * 3] * 3, ib = tri[ti * 3 + 1] * 3, ic = tri[ti * 3 + 2] * 3;
            double ax = p[ia], ay = p[ia + 1], az = p[ia + 2];
            double bx = p[ib], by = p[ib + 1], bz = p[ib + 2];
            double cx = p[ic], cy = p[ic + 1], cz = p[ic + 2];
            double abx = bx - ax, aby = by - ay, abz = bz - az;
            double acx = cx - ax, acy = cy - ay, acz = cz - az;
            double apx = px - ax, apy = py - ay, apz = pz - az;
            double d1 = abx * apx + aby * apy + abz * apz;
            double d2 = acx * apx + acy * apy + acz * apz;
            if (d1 <= 0 && d2 <= 0) return set(o, ax, ay, az, px, py, pz);
            double bpx = px - bx, bpy = py - by, bpz = pz - bz;
            double d3 = abx * bpx + aby * bpy + abz * bpz;
            double d4 = acx * bpx + acy * bpy + acz * bpz;
            if (d3 >= 0 && d4 <= d3) return set(o, bx, by, bz, px, py, pz);
            double vc = d1 * d4 - d3 * d2;
            if (vc <= 0 && d1 >= 0 && d3 <= 0) {
                double v = d1 / (d1 - d3);
                return set(o, ax + v * abx, ay + v * aby, az + v * abz, px, py, pz);
            }
            double cpx = px - cx, cpy = py - cy, cpz = pz - cz;
            double d5 = abx * cpx + aby * cpy + abz * cpz;
            double d6 = acx * cpx + acy * cpy + acz * cpz;
            if (d6 >= 0 && d5 <= d6) return set(o, cx, cy, cz, px, py, pz);
            double vb = d5 * d2 - d1 * d6;
            if (vb <= 0 && d2 >= 0 && d6 <= 0) {
                double w = d2 / (d2 - d6);
                return set(o, ax + w * acx, ay + w * acy, az + w * acz, px, py, pz);
            }
            double va = d3 * d6 - d5 * d4;
            if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
                double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
                return set(o, bx + w * (cx - bx), by + w * (cy - by), bz + w * (cz - bz), px, py, pz);
            }
            double denom = va + vb + vc;
            if (denom == 0) return set(o, ax, ay, az, px, py, pz);
            denom = 1.0 / denom;
            double v = vb * denom, w = vc * denom;
            return set(o, ax + abx * v + acx * w, ay + aby * v + acy * w, az + abz * v + acz * w, px, py, pz);
        }

        private static double set(double[] o, double x, double y, double z, double px, double py, double pz) {
            o[0] = x; o[1] = y; o[2] = z;
            double dx = px - x, dy = py - y, dz = pz - z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    // ================================================================ desvios

    interface Progress {
        void update(int percent);
    }

    /**
     * Desvio com sinal de cada vértice do scan até a referência (NaN = sem correspondência no raio).
     * Em STL os vértices repetidos são calculados uma vez só.
     */
    static float[] compute(Mesh scan, RefIndex idx, double maxDist, Progress prog) {
        int n = scan.vertexCount;
        float[] dev = new float[n];
        float[] p = scan.positions;
        double[] out = new double[4];
        int[] wid = null;
        float[] cache = null;
        if (scan.indices == null && !scan.pointCloud) {
            wid = MeshAnalysis.weld(scan);
            int nu = 0;
            for (int w : wid) if (w + 1 > nu) nu = w + 1;
            cache = new float[nu];
            java.util.Arrays.fill(cache, Float.POSITIVE_INFINITY);
        }
        int step = Math.max(1, n / 100);
        for (int i = 0; i < n; i++) {
            if (prog != null && i % step == 0) prog.update(i * 100 / n);
            if (wid != null) {
                float c = cache[wid[i]];
                if (c != Float.POSITIVE_INFINITY) { dev[i] = c; continue; }
            }
            double d = idx.signedDistance(p[i * 3], p[i * 3 + 1], p[i * 3 + 2], maxDist, out);
            float f = (float) d;
            dev[i] = f;
            if (wid != null) cache[wid[i]] = f;
        }
        return dev;
    }

    static final class Stats {
        long matched, unmatched;
        double mean, std, rms, min, max;
        double inTol, above, below; // percentuais
    }

    /** Além desse desvio o ponto é tratado como "sem correspondência" (mesa, fixação, sujeira do scan). */
    static double cutoff(double tol) {
        return 15 * tol;
    }

    /** Raio de busca usado no cálculo (cobre o maior corte possível na interface). */
    static final double MAX_SEARCH = 15.0;

    static Stats stats(float[] dev, double tol) {
        double cut = cutoff(tol);
        Stats s = new Stats();
        double sum = 0, sum2 = 0;
        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        long in = 0, ab = 0, be = 0;
        for (float d : dev) {
            if (Float.isNaN(d) || Math.abs(d) > cut) { s.unmatched++; continue; }
            s.matched++;
            sum += d;
            sum2 += (double) d * d;
            if (d < mn) mn = d;
            if (d > mx) mx = d;
            if (d > tol) ab++;
            else if (d < -tol) be++;
            else in++;
        }
        if (s.matched > 0) {
            s.mean = sum / s.matched;
            s.rms = Math.sqrt(sum2 / s.matched);
            s.std = Math.sqrt(Math.max(0, sum2 / s.matched - s.mean * s.mean));
            s.min = mn;
            s.max = mx;
            s.inTol = 100.0 * in / s.matched;
            s.above = 100.0 * ab / s.matched;
            s.below = 100.0 * be / s.matched;
        }
        return s;
    }

    // ================================================================ cores

    /** Cor RGB (0..1) do desvio d: verde dentro de ±tol, até vermelho (+range) ou azul (−range). */
    static void color(double d, double tol, double range, float[] rgb) {
        if (Double.isNaN(d) || Math.abs(d) > cutoff(tol)) { rgb[0] = 0.45f; rgb[1] = 0.47f; rgb[2] = 0.50f; return; }
        double a = Math.abs(d);
        if (a <= tol) { rgb[0] = 0.20f; rgb[1] = 0.80f; rgb[2] = 0.30f; return; }
        double t = Math.min(1.0, (a - tol) / Math.max(1e-9, range - tol));
        if (d > 0) {
            // verde amarelado -> amarelo -> vermelho
            if (t < 0.5) { double u = t / 0.5; rgb[0] = (float) (0.75 + 0.25 * u); rgb[1] = (float) (0.90 - 0.05 * u); rgb[2] = 0.15f; }
            else { double u = (t - 0.5) / 0.5; rgb[0] = 1f; rgb[1] = (float) (0.85 * (1 - u) + 0.10 * u); rgb[2] = 0.12f; }
        } else {
            // ciano -> azul
            if (t < 0.5) { double u = t / 0.5; rgb[0] = 0.15f; rgb[1] = (float) (0.85 - 0.15 * u); rgb[2] = (float) (0.75 + 0.25 * u); }
            else { double u = (t - 0.5) / 0.5; rgb[0] = (float) (0.15 - 0.05 * u); rgb[1] = (float) (0.70 * (1 - u) + 0.25 * u); rgb[2] = 1f; }
        }
    }

    static byte[] colorize(float[] dev, double tol, double range) {
        byte[] c = new byte[dev.length * 3];
        float[] rgb = new float[3];
        for (int i = 0; i < dev.length; i++) {
            color(dev[i], tol, range, rgb);
            c[i * 3] = (byte) Math.round(rgb[0] * 255);
            c[i * 3 + 1] = (byte) Math.round(rgb[1] * 255);
            c[i * 3 + 2] = (byte) Math.round(rgb[2] * 255);
        }
        return c;
    }
}
