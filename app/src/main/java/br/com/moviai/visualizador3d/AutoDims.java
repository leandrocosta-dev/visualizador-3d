package br.com.moviai.visualizador3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Cotas automáticas: dimensões gerais, furos, pinos e raios (cilindros detectados na malha)
 * e distâncias centro a centro entre eixos paralelos.
 */
final class AutoDims {
    private AutoDims() {}

    static final int HOLE = 0, BOSS = 1, ARC = 2;
    private static final Locale BR = new Locale("pt", "BR");

    static final class Feature {
        int kind;
        double radius, depth, coverage, rms;
        double[] center, axis;
        /** Posição ao longo do eixo (mínimo e máximo) em relação a center, no sentido de axis. */
        double h0, h1;
        int count;

        double diameter() { return 2 * radius; }
    }

    static final class Pair {
        int a, b;
        double dist;
    }

    static final class Result {
        double[] size = new double[3];
        final List<Feature> features = new ArrayList<>();
        final List<Pair> pairs = new ArrayList<>();
        /** Números de ordem (1..n) dos furos/pinos na ordem de exibição (índice em features). */
        int patches;
    }

    private static final double SMOOTH_DEG = 25;
    private static final int MIN_TRI = 10;
    private static final int BINS = 12;

    /** Segmenta a malha em superfícies suaves (por normal de face, para respeitar arestas vivas) e testa cada uma como cilindro. */
    static Result compute(Mesh m, Fit.Topology t, Deviation.Progress prog) {
        Result res = new Result();
        for (int k = 0; k < 3; k++) res.size[k] = m.max[k] - m.min[k];
        int nt = MeshAnalysis.triCount(m);
        if (m.pointCloud || nt == 0) return res;
        double diag = Math.sqrt(res.size[0] * res.size[0] + res.size[1] * res.size[1] + res.size[2] * res.size[2]);
        float[] pos = t.pos;
        int[] tri = new int[nt * 3];
        float[] fn = new float[nt * 3];
        float[] fa = new float[nt];
        for (int i = 0; i < nt; i++) {
            int a = t.wid[MeshAnalysis.vtx(m, i, 0)], b = t.wid[MeshAnalysis.vtx(m, i, 1)], c = t.wid[MeshAnalysis.vtx(m, i, 2)];
            tri[i * 3] = a; tri[i * 3 + 1] = b; tri[i * 3 + 2] = c;
            double ux = pos[b * 3] - pos[a * 3], uy = pos[b * 3 + 1] - pos[a * 3 + 1], uz = pos[b * 3 + 2] - pos[a * 3 + 2];
            double vx = pos[c * 3] - pos[a * 3], vy = pos[c * 3 + 1] - pos[a * 3 + 1], vz = pos[c * 3 + 2] - pos[a * 3 + 2];
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l > 0) { fn[i * 3] = (float) (nx / l); fn[i * 3 + 1] = (float) (ny / l); fn[i * 3 + 2] = (float) (nz / l); }
            fa[i] = (float) (l / 2);
        }
        // união de triângulos vizinhos cuja diferença de normal é pequena
        int[] parent = new int[nt];
        for (int i = 0; i < nt; i++) parent[i] = i;
        int cap = Integer.highestOneBit(Math.max(16, nt * 3 * 2 - 1)) << 1;
        long[] keys = new long[cap];
        int[] owner = new int[cap];
        Arrays.fill(owner, -1);
        double cosS = Math.cos(Math.toRadians(SMOOTH_DEG));
        int step = Math.max(1, nt / 50);
        for (int i = 0; i < nt; i++) {
            if (prog != null && i % step == 0) prog.update(i * 50 / nt);
            if (fa[i] == 0) continue;
            for (int e = 0; e < 3; e++) {
                int u = tri[i * 3 + e], w = tri[i * 3 + (e + 1) % 3];
                if (u == w) continue;
                long key = u < w ? ((long) u << 32) | w : ((long) w << 32) | u;
                int h = (int) ((key * 0x9E3779B97F4A7C15L) >>> 40) & (cap - 1);
                while (owner[h] >= 0 && keys[h] != key) h = (h + 1) & (cap - 1);
                if (owner[h] < 0) { owner[h] = i; keys[h] = key; continue; }
                int j = owner[h];
                if (j == i) continue;
                double c = fn[i * 3] * fn[j * 3] + fn[i * 3 + 1] * fn[j * 3 + 1] + fn[i * 3 + 2] * fn[j * 3 + 2];
                if (c >= cosS) {
                    int ri = find(parent, i), rj = find(parent, j);
                    if (ri != rj) parent[ri] = rj;
                }
            }
        }
        keys = null;
        owner = null;
        // agrupa triângulos por componente
        int[] cnt = new int[nt + 1];
        int[] root = new int[nt];
        for (int i = 0; i < nt; i++) { root[i] = find(parent, i); cnt[root[i] + 1]++; }
        for (int i = 0; i < nt; i++) cnt[i + 1] += cnt[i];
        int[] fill = Arrays.copyOf(cnt, nt);
        int[] order = new int[nt];
        for (int i = 0; i < nt; i++) order[fill[root[i]]++] = i;
        int[] stamp = new int[t.nu];
        Arrays.fill(stamp, -1);
        List<Feature> raw = new ArrayList<>();
        int patches = 0;
        for (int r = 0; r < nt; r++) {
            int n = cnt[r + 1] - cnt[r];
            if (n == 0) continue;
            patches++;
            if (n < MIN_TRI || n > 3_000_000) continue;
            Feature f = classify(t, tri, fn, fa, order, cnt[r], cnt[r + 1], stamp, r, diag);
            if (f != null) raw.add(f);
        }
        res.patches = patches;
        merge(raw);
        raw.sort((x, y) -> {
            boolean f1 = x.kind != ARC, f2 = y.kind != ARC;
            if (f1 != f2) return f1 ? -1 : 1;
            return Double.compare(y.radius, x.radius);
        });
        if (raw.size() > 60) raw.subList(60, raw.size()).clear();
        res.features.addAll(raw);
        pairs(res);
        return res;
    }

    private static int find(int[] p, int x) {
        while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; }
        return x;
    }

    private static Feature classify(Fit.Topology t, int[] tri, float[] fn, float[] fa, int[] order, int from, int to,
                                    int[] stamp, int id, double diag) {
        double[][] nn = new double[3][3];
        for (int k = from; k < to; k++) {
            int i = order[k];
            double w = fa[i];
            double[] q = {fn[i * 3], fn[i * 3 + 1], fn[i * 3 + 2]};
            for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) nn[a][b] += w * q[a] * q[b];
        }
        Align.Eig eg = Align.eigSym(nn);
        double tot = eg.val[0] + eg.val[1] + eg.val[2];
        // plano (normais paralelas) ou superfície não cilíndrica (normais em 3 direções)
        if (tot <= 0 || eg.val[1] / tot < 0.12 || eg.val[2] / tot > 0.06) return null;
        double[] ax = eg.vec[2].clone();
        if (!Align.normalize(ax)) return null;
        // vértices únicos do pedaço
        int[] vs = new int[(to - from) * 3];
        int nv = 0;
        for (int k = from; k < to; k++) {
            int i = order[k];
            for (int e = 0; e < 3; e++) {
                int v = tri[i * 3 + e];
                if (stamp[v] != id) { stamp[v] = id; vs[nv++] = v; }
            }
        }
        if (nv < 8) return null;
        double[] u = Math.abs(ax[0]) < 0.9 ? Align.cross(ax, new double[]{1, 0, 0}) : Align.cross(ax, new double[]{0, 1, 0});
        Align.normalize(u);
        double[] w = Align.cross(ax, u);
        float[] p = t.pos;
        double[] c0 = new double[3];
        for (int k = 0; k < nv; k++) for (int c = 0; c < 3; c++) c0[c] += p[vs[k] * 3 + c];
        for (int c = 0; c < 3; c++) c0[c] /= nv;
        // círculo por mínimos quadrados com descarte de pontos distantes (raios de concordância, ruído)
        double cx = 0, cy = 0, rad = 0, rms = 0;
        boolean[] keep = new boolean[nv];
        Arrays.fill(keep, true);
        for (int pass = 0; pass < 3; pass++) {
            double[][] A = new double[3][3];
            double[] bb = new double[3];
            int used = 0;
            for (int k = 0; k < nv; k++) {
                if (!keep[k]) continue;
                double dx = p[vs[k] * 3] - c0[0], dy = p[vs[k] * 3 + 1] - c0[1], dz = p[vs[k] * 3 + 2] - c0[2];
                double x = dx * u[0] + dy * u[1] + dz * u[2], y = dx * w[0] + dy * w[1] + dz * w[2];
                double[] row = {2 * x, 2 * y, 1};
                double rhs = x * x + y * y;
                for (int a = 0; a < 3; a++) {
                    bb[a] += row[a] * rhs;
                    for (int b = 0; b < 3; b++) A[a][b] += row[a] * row[b];
                }
                used++;
            }
            if (used < 8) return null;
            double[] sol = Fit.solve(A, bb);
            if (sol == null) return null;
            double r2 = sol[2] + sol[0] * sol[0] + sol[1] * sol[1];
            if (!(r2 > 0)) return null;
            cx = sol[0]; cy = sol[1]; rad = Math.sqrt(r2);
            double s2 = 0;
            double[] res = new double[nv];
            for (int k = 0; k < nv; k++) {
                double dx = p[vs[k] * 3] - c0[0], dy = p[vs[k] * 3 + 1] - c0[1], dz = p[vs[k] * 3 + 2] - c0[2];
                double x = dx * u[0] + dy * u[1] + dz * u[2] - cx, y = dx * w[0] + dy * w[1] + dz * w[2] - cy;
                res[k] = Math.sqrt(x * x + y * y) - rad;
                if (keep[k]) s2 += res[k] * res[k];
            }
            rms = Math.sqrt(s2 / used);
            double lim = Math.max(2.5 * rms, 1e-6);
            int still = 0;
            for (int k = 0; k < nv; k++) { keep[k] = Math.abs(res[k]) <= lim; if (keep[k]) still++; }
            if (still < nv * 0.5) return null;
        }
        if (rad > 0.6 * diag || rad < 0.0015 * diag) return null;
        if (rms > 0.04 * rad + 0.02) return null;
        double[] center = new double[3];
        for (int c = 0; c < 3; c++) center[c] = c0[c] + cx * u[c] + cy * w[c];
        // cobertura angular, comprimento e sentido
        boolean[] bin = new boolean[BINS];
        double h0 = Double.MAX_VALUE, h1 = -Double.MAX_VALUE;
        for (int k = 0; k < nv; k++) {
            if (!keep[k]) continue;
            double dx = p[vs[k] * 3] - center[0], dy = p[vs[k] * 3 + 1] - center[1], dz = p[vs[k] * 3 + 2] - center[2];
            double h = dx * ax[0] + dy * ax[1] + dz * ax[2];
            if (h < h0) h0 = h;
            if (h > h1) h1 = h;
            double px = dx - h * ax[0], py = dy - h * ax[1], pz = dz - h * ax[2];
            if (px * px + py * py + pz * pz < 1e-18) continue;
            double a = Math.atan2(px * w[0] + py * w[1] + pz * w[2], px * u[0] + py * u[1] + pz * u[2]);
            int b = (int) Math.floor((a + Math.PI) / (2 * Math.PI) * BINS);
            bin[Math.min(BINS - 1, Math.max(0, b))] = true;
        }
        double out = 0, wsum = 0;
        for (int k = from; k < to; k++) {
            int i = order[k];
            double mx = 0, my = 0, mz = 0;
            for (int e = 0; e < 3; e++) {
                int v = tri[i * 3 + e];
                mx += p[v * 3]; my += p[v * 3 + 1]; mz += p[v * 3 + 2];
            }
            double dx = mx / 3 - center[0], dy = my / 3 - center[1], dz = mz / 3 - center[2];
            double h = dx * ax[0] + dy * ax[1] + dz * ax[2];
            double px = dx - h * ax[0], py = dy - h * ax[1], pz = dz - h * ax[2];
            double pl = Math.sqrt(px * px + py * py + pz * pz);
            if (pl < 1e-12) continue;
            out += fa[i] * (fn[i * 3] * px + fn[i * 3 + 1] * py + fn[i * 3 + 2] * pz) / pl;
            wsum += fa[i];
        }
        int filled = 0;
        for (boolean b : bin) if (b) filled++;
        double cov = filled * 360.0 / BINS;
        if (cov < 60) return null;
        Feature f = new Feature();
        f.radius = rad;
        f.rms = rms;
        f.center = center;
        f.axis = ax;
        f.h0 = h0;
        f.h1 = h1;
        f.depth = h1 - h0;
        f.coverage = cov;
        f.count = nv;
        f.kind = cov >= 300 ? (out < 0 ? HOLE : BOSS) : ARC;
        if (f.depth < 0.15 * f.radius) return null; // anel muito raso: provável chanfro
        // centro no meio do comprimento
        double mid = (h0 + h1) / 2;
        for (int c = 0; c < 3; c++) f.center[c] += mid * ax[c];
        f.h0 = h0 - mid;
        f.h1 = h1 - mid;
        return f;
    }

    /** Une pedaços do mesmo cilindro (mesmo eixo e raio), como um furo cortado por uma costura. */
    private static void merge(List<Feature> l) {
        boolean again = true;
        while (again) {
            again = false;
            outer:
            for (int i = 0; i < l.size(); i++) {
                for (int j = i + 1; j < l.size(); j++) {
                    Feature a = l.get(i), b = l.get(j);
                    if (a.kind != b.kind) continue;
                    if (Math.abs(a.radius - b.radius) > 0.03 * a.radius) continue;
                    if (Math.abs(Align.dot(a.axis, b.axis)) < Math.cos(Math.toRadians(3))) continue;
                    double[] d = Align.sub(b.center, a.center);
                    double k = Align.dot(d, a.axis);
                    double[] perp = {d[0] - k * a.axis[0], d[1] - k * a.axis[1], d[2] - k * a.axis[2]};
                    if (Math.sqrt(Align.dot(perp, perp)) > 0.05 * a.radius + 0.02) continue;
                    // intervalos ao longo do eixo de a
                    double sgn = Align.dot(a.axis, b.axis) >= 0 ? 1 : -1;
                    double b0 = k + (sgn > 0 ? b.h0 : -b.h1), b1 = k + (sgn > 0 ? b.h1 : -b.h0);
                    double lo = Math.min(a.h0, b0), hi = Math.max(a.h1, b1);
                    // só une se os trechos se tocam ou se sobrepõem (lacuna < raio)
                    double gap = Math.max(a.h0, b0) - Math.min(a.h1, b1);
                    if (gap > a.radius) continue;
                    double mid = (lo + hi) / 2, oldMid = (a.h0 + a.h1) / 2;
                    for (int c = 0; c < 3; c++) a.center[c] += (mid - oldMid) * a.axis[c];
                    a.h0 = lo - mid;
                    a.h1 = hi - mid;
                    a.depth = hi - lo;
                    a.coverage = Math.max(a.coverage, b.coverage);
                    a.radius = (a.radius * a.count + b.radius * b.count) / (a.count + b.count);
                    a.count += b.count;
                    if (a.kind == ARC && a.coverage >= 300) a.kind = HOLE;
                    l.remove(j);
                    again = true;
                    break outer;
                }
            }
        }
    }

    private static void pairs(Result r) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < r.features.size(); i++) if (r.features.get(i).kind != ARC) idx.add(i);
        boolean all = idx.size() <= 5;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int ii = 0; ii < idx.size(); ii++) {
            int i = idx.get(ii);
            Feature a = r.features.get(i);
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int jj = 0; jj < idx.size(); jj++) {
                int j = idx.get(jj);
                if (j == i) continue;
                Feature b = r.features.get(j);
                if (Math.abs(Align.dot(a.axis, b.axis)) < Math.cos(Math.toRadians(3))) continue;
                double d = axisDistance(a, b);
                if (d < 0.05 * Math.max(a.radius, b.radius) + 0.05) continue; // coaxiais
                if (d < bd) { bd = d; best = j; }
                if (all) addPair(r, seen, i, j, d);
            }
            if (!all && best >= 0) addPair(r, seen, i, best, bd);
        }
        r.pairs.sort((x, y) -> Double.compare(x.dist, y.dist));
        if (r.pairs.size() > 30) r.pairs.subList(30, r.pairs.size()).clear();
    }

    private static void addPair(Result r, java.util.Set<Long> seen, int i, int j, double d) {
        int a = Math.min(i, j), b = Math.max(i, j);
        if (!seen.add(((long) a << 32) | b)) return;
        Pair p = new Pair();
        p.a = a;
        p.b = b;
        p.dist = d;
        r.pairs.add(p);
    }

    private static double axisDistance(Feature a, Feature b) {
        double[] d = Align.sub(b.center, a.center);
        double k = Align.dot(d, a.axis);
        double[] perp = {d[0] - k * a.axis[0], d[1] - k * a.axis[1], d[2] - k * a.axis[2]};
        return Math.sqrt(Align.dot(perp, perp));
    }

    // ================================================================ texto

    static String axisName(double[] ax) {
        String[] nm = {"X", "Y", "Z"};
        for (int i = 0; i < 3; i++) if (Math.abs(ax[i]) > Math.cos(Math.toRadians(2))) return "∥ " + nm[i];
        return String.format(BR, "(%.2f; %.2f; %.2f)", ax[0], ax[1], ax[2]);
    }

    static String label(Result r, int i) {
        Feature f = r.features.get(i);
        int n = 0;
        for (int k = 0; k <= i; k++) if (r.features.get(k).kind == f.kind) n++;
        String p = f.kind == HOLE ? "F" : f.kind == BOSS ? "P" : "R";
        return p + n;
    }

    /** Resumo compacto para a tela e o relatório. */
    static String describe(Result r) {
        StringBuilder s = new StringBuilder();
        s.append(String.format(BR, "Dimensões gerais: X %.2f · Y %.2f · Z %.2f mm\n", r.size[0], r.size[1], r.size[2]));
        if (r.features.isEmpty()) {
            s.append("Nenhum furo, pino ou raio cilíndrico detectado.");
            return s.toString();
        }
        s.append("F = furo · P = pino · R = raio (arco)\n");
        for (int i = 0; i < r.features.size(); i++) {
            Feature f = r.features.get(i);
            String what = f.kind == HOLE ? "Ø" : f.kind == BOSS ? "Ø" : "R";
            double val = f.kind == ARC ? f.radius : f.diameter();
            s.append(label(r, i)).append(": ").append(what).append(' ').append(String.format(BR, "%.2f", val)).append(" mm");
            if (f.kind == ARC) s.append(String.format(BR, " (arco de %.0f°)", f.coverage));
            s.append(String.format(BR, " · comp. %.2f", f.depth));
            s.append(" · eixo ").append(axisName(f.axis));
            s.append(String.format(BR, " · centro (%.2f; %.2f; %.2f)\n", f.center[0], f.center[1], f.center[2]));
        }
        if (!r.pairs.isEmpty()) {
            s.append("Distância entre eixos paralelos:\n");
            for (Pair p : r.pairs) {
                s.append(label(r, p.a)).append(" ↔ ").append(label(r, p.b)).append(": ")
                        .append(String.format(BR, "%.2f", p.dist)).append(" mm\n");
            }
        }
        return s.toString().trim();
    }
}
