package br.com.moviai.visualizador3d;

import java.util.Arrays;

/** Diagnóstico da malha: área, volume, bordas abertas, furos, ilhas e triângulos degenerados. */
final class MeshAnalysis {

    static final int MAX_OVERLAY_EDGES = 300_000;

    double area;
    double volume;
    boolean closed;
    boolean inverted;
    long boundaryEdges;
    int holes;
    long nonManifoldEdges;
    long degenerate;
    int shells;
    int uniqueVertices;
    /** Segmentos das bordas abertas (x,y,z pares) para desenhar em vermelho. Pode estar truncado. */
    float[] boundaryLines;

    private MeshAnalysis() {}

    static int triCount(Mesh m) {
        if (m.pointCloud) return 0;
        return m.indices != null ? m.indices.length / 3 : m.vertexCount / 3;
    }

    static int vtx(Mesh m, int t, int k) {
        return m.indices != null ? m.indices[t * 3 + k] : t * 3 + k;
    }

    static MeshAnalysis run(Mesh m) {
        MeshAnalysis r = new MeshAnalysis();
        int tris = triCount(m);
        if (tris == 0) return r;
        float[] p = m.positions;

        // área, volume e degenerados (sem precisar soldar)
        double area = 0, vol = 0;
        float d = (m.max[0] - m.min[0]) + (m.max[1] - m.min[1]) + (m.max[2] - m.min[2]);
        double eps = 1e-12 * d * d;
        long degen = 0;
        double cx = m.center[0], cy = m.center[1], cz = m.center[2];
        for (int t = 0; t < tris; t++) {
            int a = vtx(m, t, 0) * 3, b = vtx(m, t, 1) * 3, c = vtx(m, t, 2) * 3;
            double ax = p[a] - cx, ay = p[a + 1] - cy, az = p[a + 2] - cz;
            double bx = p[b] - cx, by = p[b + 1] - cy, bz = p[b + 2] - cz;
            double qx = p[c] - cx, qy = p[c + 1] - cy, qz = p[c + 2] - cz;
            double ux = bx - ax, uy = by - ay, uz = bz - az;
            double vx = qx - ax, vy = qy - ay, vz = qz - az;
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            double a2 = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (a2 <= eps) degen++;
            area += a2 * 0.5;
            vol += (ax * (by * qz - bz * qy) - ay * (bx * qz - bz * qx) + az * (bx * qy - by * qx)) / 6.0;
        }
        r.area = area;
        r.degenerate = degen;

        // soldagem de vértices coincidentes (STL repete os vértices em cada triângulo)
        int[] wid = weld(m);
        int nu = 0;
        for (int w : wid) if (w + 1 > nu) nu = w + 1;
        r.uniqueVertices = nu;
        int[] rep = new int[nu];
        Arrays.fill(rep, -1);
        for (int i = 0; i < wid.length; i++) if (rep[wid[i]] < 0) rep[wid[i]] = i;

        // arestas: chave (menor<<32 | maior), ordenadas para contar ocorrências
        long[] edges = new long[tris * 3];
        int ne = 0;
        int[] parent = new int[nu];
        for (int i = 0; i < nu; i++) parent[i] = i;
        for (int t = 0; t < tris; t++) {
            int a = wid[vtx(m, t, 0)], b = wid[vtx(m, t, 1)], c = wid[vtx(m, t, 2)];
            if (a == b || b == c || a == c) continue;
            edges[ne++] = key(a, b);
            edges[ne++] = key(b, c);
            edges[ne++] = key(c, a);
            union(parent, a, b);
            union(parent, b, c);
        }
        Arrays.sort(edges, 0, ne);

        // ilhas: componentes conectados que têm triângulos
        boolean[] used = new boolean[nu];
        for (int i = 0; i < ne; i++) {
            used[(int) (edges[i] >>> 32)] = true;
        }
        int shells = 0;
        for (int i = 0; i < nu; i++) if (used[i] && find(parent, i) == i) shells++;
        r.shells = shells;

        long boundary = 0, nonMan = 0;
        int[] bParent = new int[nu];
        for (int i = 0; i < nu; i++) bParent[i] = i;
        boolean[] onBoundary = new boolean[nu];
        int maxLines = Math.min(MAX_OVERLAY_EDGES, ne);
        float[] lines = new float[maxLines * 6];
        int nl = 0;
        int i = 0;
        while (i < ne) {
            int j = i + 1;
            while (j < ne && edges[j] == edges[i]) j++;
            int run = j - i;
            if (run == 1) {
                boundary++;
                int a = (int) (edges[i] >>> 32), b = (int) edges[i];
                onBoundary[a] = onBoundary[b] = true;
                union(bParent, a, b);
                if (nl < maxLines) {
                    int pa = rep[a] * 3, pb = rep[b] * 3;
                    int o = nl * 6;
                    lines[o] = p[pa]; lines[o + 1] = p[pa + 1]; lines[o + 2] = p[pa + 2];
                    lines[o + 3] = p[pb]; lines[o + 4] = p[pb + 1]; lines[o + 5] = p[pb + 2];
                    nl++;
                }
            } else if (run > 2) {
                nonMan++;
            }
            i = j;
        }
        int holes = 0;
        for (int v = 0; v < nu; v++) if (onBoundary[v] && find(bParent, v) == v) holes++;
        r.boundaryEdges = boundary;
        r.nonManifoldEdges = nonMan;
        r.holes = holes;
        r.boundaryLines = Arrays.copyOf(lines, nl * 6);
        r.closed = boundary == 0 && nonMan == 0;
        r.inverted = vol < 0;
        r.volume = Math.abs(vol);
        return r;
    }

    private static long key(int a, int b) {
        return a < b ? (((long) a) << 32) | (b & 0xFFFFFFFFL) : (((long) b) << 32) | (a & 0xFFFFFFFFL);
    }

    static int find(int[] p, int x) {
        while (p[x] != x) {
            p[x] = p[p[x]];
            x = p[x];
        }
        return x;
    }

    static void union(int[] p, int a, int b) {
        int ra = find(p, a), rb = find(p, b);
        if (ra != rb) p[ra] = rb;
    }

    /** Id único por posição: vértices em coordenadas iguais (até ~1e-6 da peça) recebem o mesmo id. */
    static int[] weld(Mesh m) {
        int n = m.vertexCount;
        long[] keys = cellKeys(m, 2_000_000);
        long[] sorted = keys.clone();
        Arrays.sort(sorted);
        int u = 0;
        for (int i = 0; i < n; i++) if (i == 0 || sorted[i] != sorted[i - 1]) sorted[u++] = sorted[i];
        int[] id = new int[n];
        for (int i = 0; i < n; i++) id[i] = Arrays.binarySearch(sorted, 0, u, keys[i]);
        return id;
    }

    /** Chave de célula de grade para cada vértice, com res divisões na maior dimensão (21 bits por eixo). */
    static long[] cellKeys(Mesh m, int res) {
        float size = Math.max(m.max[0] - m.min[0], Math.max(m.max[1] - m.min[1], m.max[2] - m.min[2]));
        if (!(size > 0)) size = 1;
        double s = res / (double) size;
        long lim = (1L << 21) - 1;
        float[] p = m.positions;
        float x0 = m.min[0], y0 = m.min[1], z0 = m.min[2];
        int n = m.vertexCount;
        long[] k = new long[n];
        for (int i = 0; i < n; i++) {
            long qx = Math.min(lim, Math.max(0, (long) ((p[i * 3] - x0) * s)));
            long qy = Math.min(lim, Math.max(0, (long) ((p[i * 3 + 1] - y0) * s)));
            long qz = Math.min(lim, Math.max(0, (long) ((p[i * 3 + 2] - z0) * s)));
            k[i] = (qx << 42) | (qy << 21) | qz;
        }
        return k;
    }
}
