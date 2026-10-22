package br.com.moviai.visualizador3d;

import java.util.Arrays;

/**
 * Ajuste de primitivas (plano, cilindro, esfera) numa região da malha crescida a partir de um toque.
 * A região cresce enquanto a superfície é suave (normais vizinhas parecidas), parando em arestas vivas.
 */
final class Fit {
    private Fit() {}

    static final int PLANE = 0, CYLINDER = 1, SPHERE = 2;
    static final String[] NAMES = {"Plano", "Cilindro", "Esfera"};

    // ================================================================ topologia

    /** Vértices soldados, vizinhança e normais suaves (calculada sob demanda e guardada por malha). */
    static final class Topology {
        final int nu;
        final float[] pos;
        final float[] nrm;
        final int[] adjStart, adj;
        final boolean hasAdjacency;

        Topology(Mesh m) {
            int[] wid = MeshAnalysis.weld(m);
            int n = 0;
            for (int w : wid) if (w + 1 > n) n = w + 1;
            nu = n;
            pos = new float[n * 3];
            boolean[] set = new boolean[n];
            for (int i = 0; i < wid.length; i++) {
                int w = wid[i];
                if (set[w]) continue;
                set[w] = true;
                System.arraycopy(m.positions, i * 3, pos, w * 3, 3);
            }
            nrm = new float[n * 3];
            int tris = MeshAnalysis.triCount(m);
            long[] edges = new long[tris * 6];
            int ne = 0;
            for (int t = 0; t < tris; t++) {
                int a = wid[MeshAnalysis.vtx(m, t, 0)], b = wid[MeshAnalysis.vtx(m, t, 1)], c = wid[MeshAnalysis.vtx(m, t, 2)];
                if (a == b || b == c || a == c) continue;
                float ux = pos[b * 3] - pos[a * 3], uy = pos[b * 3 + 1] - pos[a * 3 + 1], uz = pos[b * 3 + 2] - pos[a * 3 + 2];
                float vx = pos[c * 3] - pos[a * 3], vy = pos[c * 3 + 1] - pos[a * 3 + 1], vz = pos[c * 3 + 2] - pos[a * 3 + 2];
                float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                for (int v : new int[]{a, b, c}) {
                    nrm[v * 3] += nx; nrm[v * 3 + 1] += ny; nrm[v * 3 + 2] += nz;
                }
                edges[ne++] = ((long) a << 32) | b;
                edges[ne++] = ((long) b << 32) | a;
                edges[ne++] = ((long) b << 32) | c;
                edges[ne++] = ((long) c << 32) | b;
                edges[ne++] = ((long) c << 32) | a;
                edges[ne++] = ((long) a << 32) | c;
            }
            if (tris == 0 && m.normals != null) {
                for (int i = 0; i < wid.length; i++) {
                    int w = wid[i];
                    nrm[w * 3] += m.normals[i * 3]; nrm[w * 3 + 1] += m.normals[i * 3 + 1]; nrm[w * 3 + 2] += m.normals[i * 3 + 2];
                }
            }
            for (int i = 0; i < n; i++) {
                float l = (float) Math.sqrt(nrm[i * 3] * nrm[i * 3] + nrm[i * 3 + 1] * nrm[i * 3 + 1] + nrm[i * 3 + 2] * nrm[i * 3 + 2]);
                if (l > 0) { nrm[i * 3] /= l; nrm[i * 3 + 1] /= l; nrm[i * 3 + 2] /= l; }
            }
            Arrays.sort(edges, 0, ne);
            adjStart = new int[n + 1];
            int[] tmp = new int[ne];
            int k = 0;
            long prev = -1;
            for (int i = 0; i < ne; i++) {
                if (edges[i] == prev) continue;
                prev = edges[i];
                int from = (int) (edges[i] >>> 32);
                tmp[k++] = (int) edges[i];
                adjStart[from + 1]++;
            }
            for (int i = 0; i < n; i++) adjStart[i + 1] += adjStart[i];
            adj = Arrays.copyOf(tmp, k);
            hasAdjacency = k > 0;
        }

        int nearest(double[] p) {
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < nu; i++) {
                double dx = pos[i * 3] - p[0], dy = pos[i * 3 + 1] - p[1], dz = pos[i * 3 + 2] - p[2];
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bd) { bd = d; best = i; }
            }
            return best;
        }
    }

    // ================================================================ região

    /**
     * Cresce a região a partir do vértice semente.
     * @param maxRadius limite de distância à semente
     * @param smoothDeg ângulo máximo entre normais vizinhas
     * @param planeDeg  para plano: ângulo máximo em relação à normal da semente (ou ≤0 para ignorar)
     */
    static int[] grow(Topology t, int seed, double maxRadius, double smoothDeg, double planeDeg) {
        double cosS = Math.cos(Math.toRadians(smoothDeg));
        double cosP = planeDeg > 0 ? Math.cos(Math.toRadians(planeDeg)) : -2;
        double r2 = maxRadius * maxRadius;
        float[] p = t.pos, n = t.nrm;
        double sx = p[seed * 3], sy = p[seed * 3 + 1], sz = p[seed * 3 + 2];
        double snx = n[seed * 3], sny = n[seed * 3 + 1], snz = n[seed * 3 + 2];
        int limit = 300_000;
        if (!t.hasAdjacency) {
            // nuvem de pontos: tudo dentro do raio (com filtro de normal se houver)
            int[] out = new int[Math.min(t.nu, limit)];
            int k = 0;
            for (int i = 0; i < t.nu && k < out.length; i++) {
                double dx = p[i * 3] - sx, dy = p[i * 3 + 1] - sy, dz = p[i * 3 + 2] - sz;
                if (dx * dx + dy * dy + dz * dz > r2) continue;
                if (cosP > -2 && n[i * 3] * snx + n[i * 3 + 1] * sny + n[i * 3 + 2] * snz < cosP) continue;
                out[k++] = i;
            }
            return Arrays.copyOf(out, k);
        }
        boolean[] seen = new boolean[t.nu];
        int[] queue = new int[Math.min(t.nu, limit)];
        int head = 0, tail = 0;
        queue[tail++] = seed;
        seen[seed] = true;
        while (head < tail && tail < queue.length) {
            int v = queue[head++];
            for (int e = t.adjStart[v]; e < t.adjStart[v + 1]; e++) {
                int w = t.adj[e];
                if (seen[w]) continue;
                double dx = p[w * 3] - sx, dy = p[w * 3 + 1] - sy, dz = p[w * 3 + 2] - sz;
                if (dx * dx + dy * dy + dz * dz > r2) continue;
                double c = n[v * 3] * n[w * 3] + n[v * 3 + 1] * n[w * 3 + 1] + n[v * 3 + 2] * n[w * 3 + 2];
                if (c < cosS) continue;
                if (cosP > -2 && n[w * 3] * snx + n[w * 3 + 1] * sny + n[w * 3 + 2] * snz < cosP) continue;
                seen[w] = true;
                if (tail < queue.length) queue[tail++] = w;
            }
        }
        return Arrays.copyOf(queue, tail);
    }

    // ================================================================ resultados

    static final class Result {
        int type;
        /** Plano: centróide. Cilindro: ponto no eixo (no meio da altura). Esfera: centro. */
        double[] point;
        /** Plano: normal. Cilindro: direção do eixo. Esfera: null. */
        double[] dir;
        double radius;
        double rms;
        /** Plano: planicidade (pico a vale). Cilindro: altura ao longo do eixo. */
        double extent;
        int count;
        /** Índices (soldados) dos vértices usados no ajuste final. */
        int[] region;
    }

    /** Ajusta o tipo pedido na região, descartando pontos fora (3 passadas). */
    static Result fit(Topology t, int[] region, int type) {
        int[] reg = region;
        Result r = null;
        for (int pass = 0; pass < 4; pass++) {
            if (reg.length < (type == PLANE ? 3 : 6)) return r;
            Result cur = type == PLANE ? plane(t, reg) : type == SPHERE ? sphere(t, reg) : cylinder(t, reg);
            if (cur == null) return r;
            r = cur;
            if (pass == 3) break;
            // descarta pontos distantes da superfície ajustada (raios de concordância, ruído)
            double lim = Math.max(2.5 * r.rms, 1e-9);
            int[] keep = new int[reg.length];
            int k = 0;
            for (int v : reg) if (Math.abs(residual(t, v, r)) <= lim) keep[k++] = v;
            if (k == reg.length || k < reg.length * 0.5) break;
            reg = Arrays.copyOf(keep, k);
        }
        if (r != null) r.region = reg;
        return r;
    }

    static double residual(Topology t, int v, Result r) {
        double x = t.pos[v * 3] - r.point[0], y = t.pos[v * 3 + 1] - r.point[1], z = t.pos[v * 3 + 2] - r.point[2];
        if (r.type == PLANE) return x * r.dir[0] + y * r.dir[1] + z * r.dir[2];
        if (r.type == SPHERE) return Math.sqrt(x * x + y * y + z * z) - r.radius;
        double a = x * r.dir[0] + y * r.dir[1] + z * r.dir[2];
        double px = x - a * r.dir[0], py = y - a * r.dir[1], pz = z - a * r.dir[2];
        return Math.sqrt(px * px + py * py + pz * pz) - r.radius;
    }

    private static Result plane(Topology t, int[] reg) {
        double[] c = new double[3];
        double[][] cov = covOf(t.pos, reg, c);
        double[] n = Align.eigSym(cov).vec[2];
        Result r = new Result();
        r.type = PLANE;
        r.point = c;
        r.dir = n.clone();
        Align.normalize(r.dir);
        // orienta a normal para o lado das normais da superfície
        double s = 0;
        for (int v : reg) s += t.nrm[v * 3] * r.dir[0] + t.nrm[v * 3 + 1] * r.dir[1] + t.nrm[v * 3 + 2] * r.dir[2];
        if (s < 0) { r.dir[0] = -r.dir[0]; r.dir[1] = -r.dir[1]; r.dir[2] = -r.dir[2]; }
        finish(t, reg, r);
        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        for (int v : reg) {
            double d = residual(t, v, r);
            if (d < mn) mn = d;
            if (d > mx) mx = d;
        }
        r.extent = mx - mn;
        return r;
    }

    private static Result sphere(Topology t, int[] reg) {
        // x²+y²+z² = 2ax + 2by + 2cz + d  (mínimos quadrados lineares, coordenadas relativas ao centróide)
        double[] c0 = mean(t.pos, reg);
        double[][] A = new double[4][4];
        double[] b = new double[4];
        for (int v : reg) {
            double x = t.pos[v * 3] - c0[0], y = t.pos[v * 3 + 1] - c0[1], z = t.pos[v * 3 + 2] - c0[2];
            double[] row = {2 * x, 2 * y, 2 * z, 1};
            double rhs = x * x + y * y + z * z;
            for (int i = 0; i < 4; i++) {
                b[i] += row[i] * rhs;
                for (int j = 0; j < 4; j++) A[i][j] += row[i] * row[j];
            }
        }
        double[] s = solve(A, b);
        if (s == null) return null;
        double r2 = s[3] + s[0] * s[0] + s[1] * s[1] + s[2] * s[2];
        if (!(r2 > 0)) return null;
        Result r = new Result();
        r.type = SPHERE;
        r.point = new double[]{c0[0] + s[0], c0[1] + s[1], c0[2] + s[2]};
        r.radius = Math.sqrt(r2);
        finish(t, reg, r);
        return r;
    }

    private static Result cylinder(Topology t, int[] reg) {
        // eixo: direção perpendicular a todas as normais (menor autovetor de Σ n nᵀ)
        double[][] nn = new double[3][3];
        for (int v : reg) {
            double[] n = {t.nrm[v * 3], t.nrm[v * 3 + 1], t.nrm[v * 3 + 2]};
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) nn[i][j] += n[i] * n[j];
        }
        double[] ax = Align.eigSym(nn).vec[2].clone();
        if (!Align.normalize(ax)) return null;
        // base do plano perpendicular ao eixo
        double[] u = Math.abs(ax[0]) < 0.9 ? Align.cross(ax, new double[]{1, 0, 0}) : Align.cross(ax, new double[]{0, 1, 0});
        Align.normalize(u);
        double[] w = Align.cross(ax, u);
        double[] c0 = mean(t.pos, reg);
        // círculo 2D: x²+y² = 2ax + 2by + c
        double[][] A = new double[3][3];
        double[] b = new double[3];
        double amin = Double.MAX_VALUE, amax = -Double.MAX_VALUE;
        for (int v : reg) {
            double dx = t.pos[v * 3] - c0[0], dy = t.pos[v * 3 + 1] - c0[1], dz = t.pos[v * 3 + 2] - c0[2];
            double x = dx * u[0] + dy * u[1] + dz * u[2];
            double y = dx * w[0] + dy * w[1] + dz * w[2];
            double h = dx * ax[0] + dy * ax[1] + dz * ax[2];
            if (h < amin) amin = h;
            if (h > amax) amax = h;
            double[] row = {2 * x, 2 * y, 1};
            double rhs = x * x + y * y;
            for (int i = 0; i < 3; i++) {
                b[i] += row[i] * rhs;
                for (int j = 0; j < 3; j++) A[i][j] += row[i] * row[j];
            }
        }
        double[] s = solve(A, b);
        if (s == null) return null;
        double r2 = s[2] + s[0] * s[0] + s[1] * s[1];
        if (!(r2 > 0)) return null;
        Result r = new Result();
        r.type = CYLINDER;
        double mid = (amin + amax) / 2;
        r.point = new double[3];
        for (int i = 0; i < 3; i++) r.point[i] = c0[i] + s[0] * u[i] + s[1] * w[i] + mid * ax[i];
        r.dir = ax;
        r.radius = Math.sqrt(r2);
        r.extent = amax - amin;
        finish(t, reg, r);
        return r;
    }

    private static void finish(Topology t, int[] reg, Result r) {
        double s2 = 0;
        for (int v : reg) {
            double d = residual(t, v, r);
            s2 += d * d;
        }
        r.rms = Math.sqrt(s2 / Math.max(1, reg.length));
        r.count = reg.length;
    }

    // ================================================================ relação entre dois ajustes

    /** Ângulo (graus, 0..90) entre direções; NaN se algum não tiver direção. */
    static double angle(Result a, Result b) {
        if (a.dir == null || b.dir == null) return Double.NaN;
        double c = Math.abs(Align.dot(a.dir, b.dir));
        return Math.toDegrees(Math.acos(Math.min(1, c)));
    }

    /**
     * Distância entre dois ajustes: plano × qualquer = do ponto do outro ao plano;
     * cilindro × cilindro = entre os eixos; demais = entre os pontos (centros).
     */
    static double distance(Result a, Result b) {
        if (a.type == PLANE) return Math.abs(Align.dot(Align.sub(b.point, a.point), a.dir));
        if (b.type == PLANE) return Math.abs(Align.dot(Align.sub(a.point, b.point), b.dir));
        if (a.type == CYLINDER && b.type == CYLINDER) {
            double[] d = Align.sub(b.point, a.point);
            double[] c = Align.cross(a.dir, b.dir);
            double cl = Math.sqrt(Align.dot(c, c));
            if (cl < 1e-6) {
                // paralelos: distância do ponto de b à reta de a
                double k = Align.dot(d, a.dir);
                double[] perp = {d[0] - k * a.dir[0], d[1] - k * a.dir[1], d[2] - k * a.dir[2]};
                return Math.sqrt(Align.dot(perp, perp));
            }
            return Math.abs(Align.dot(d, c)) / cl;
        }
        double[] d = Align.sub(b.point, a.point);
        return Math.sqrt(Align.dot(d, d));
    }

    // ================================================================ álgebra

    private static double[] mean(float[] p, int[] reg) {
        double[] c = new double[3];
        for (int v : reg) { c[0] += p[v * 3]; c[1] += p[v * 3 + 1]; c[2] += p[v * 3 + 2]; }
        for (int i = 0; i < 3; i++) c[i] /= Math.max(1, reg.length);
        return c;
    }

    private static double[][] covOf(float[] p, int[] reg, double[] meanOut) {
        double[] c = mean(p, reg);
        System.arraycopy(c, 0, meanOut, 0, 3);
        double[][] cov = new double[3][3];
        for (int v : reg) {
            double[] d = {p[v * 3] - c[0], p[v * 3 + 1] - c[1], p[v * 3 + 2] - c[2]};
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) cov[i][j] += d[i] * d[j];
        }
        return cov;
    }

    /** Eliminação de Gauss com pivotamento parcial. Null se singular. */
    static double[] solve(double[][] A0, double[] b0) {
        int n = b0.length;
        double[][] A = new double[n][];
        for (int i = 0; i < n; i++) A[i] = A0[i].clone();
        double[] b = b0.clone();
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(A[r][c]) > Math.abs(A[piv][c])) piv = r;
            if (Math.abs(A[piv][c]) < 1e-14) return null;
            double[] tr = A[c]; A[c] = A[piv]; A[piv] = tr;
            double tb = b[c]; b[c] = b[piv]; b[piv] = tb;
            for (int r = c + 1; r < n; r++) {
                double f = A[r][c] / A[c][c];
                for (int k = c; k < n; k++) A[r][k] -= f * A[c][k];
                b[r] -= f * b[c];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = b[r];
            for (int k = r + 1; k < n; k++) s -= A[r][k] * x[k];
            x[r] = s / A[r][r];
        }
        return x;
    }
}
