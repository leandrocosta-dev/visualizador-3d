package br.com.moviai.visualizador3d;

import java.util.Arrays;

/**
 * Versão leve da malha para girar com fluidez (agrupamento de vértices em grade).
 * Usada só enquanto o dedo está na tela; parado, o app desenha a malha completa.
 */
final class Decimator {

    /** Acima disso, vale a pena ter a versão leve. */
    static final int TRI_THRESHOLD = 1_200_000;
    static final int POINT_THRESHOLD = 2_500_000;
    static final int TARGET_TRIS = 450_000;
    static final int TARGET_POINTS = 1_000_000;

    private Decimator() {}

    static boolean worthIt(Mesh m) {
        return m.pointCloud ? m.vertexCount > POINT_THRESHOLD : MeshAnalysis.triCount(m) > TRI_THRESHOLD;
    }

    static Mesh build(Mesh m) {
        if (m.pointCloud) return subsample(m, TARGET_POINTS);
        int res = 220;
        Mesh d = cluster(m, res);
        int tris = MeshAnalysis.triCount(d);
        if (tris > TARGET_TRIS * 1.3 || tris < TARGET_TRIS * 0.5) {
            res = (int) Math.max(32, Math.min(1500, res * Math.sqrt((double) TARGET_TRIS / Math.max(1, tris))));
            d = cluster(m, res);
        }
        return d;
    }

    private static Mesh subsample(Mesh m, int target) {
        int step = Math.max(1, (int) Math.ceil(m.vertexCount / (double) target));
        int n = (m.vertexCount + step - 1) / step;
        Mesh o = new Mesh();
        o.pointCloud = true;
        o.vertexCount = n;
        o.positions = new float[n * 3];
        if (m.normals != null) o.normals = new float[n * 3];
        if (m.colors != null) o.colors = new byte[n * 3];
        for (int i = 0, j = 0; j < n; i += step, j++) {
            System.arraycopy(m.positions, i * 3, o.positions, j * 3, 3);
            if (o.normals != null) System.arraycopy(m.normals, i * 3, o.normals, j * 3, 3);
            if (o.colors != null) System.arraycopy(m.colors, i * 3, o.colors, j * 3, 3);
        }
        o.computeBounds();
        return o;
    }

    static Mesh cluster(Mesh m, int res) {
        int n = m.vertexCount;
        long[] keys = MeshAnalysis.cellKeys(m, res);
        long[] cells = keys.clone();
        Arrays.sort(cells);
        int u = 0;
        for (int i = 0; i < n; i++) if (i == 0 || cells[i] != cells[i - 1]) cells[u++] = cells[i];

        int[] cid = new int[n];
        double[] sum = new double[u * 3];
        int[] cnt = new int[u];
        int[] col = m.colors != null ? new int[u * 3] : null;
        float[] p = m.positions;
        for (int i = 0; i < n; i++) {
            int c = Arrays.binarySearch(cells, 0, u, keys[i]);
            cid[i] = c;
            sum[c * 3] += p[i * 3];
            sum[c * 3 + 1] += p[i * 3 + 1];
            sum[c * 3 + 2] += p[i * 3 + 2];
            cnt[c]++;
            if (col != null) {
                col[c * 3] += m.colors[i * 3] & 0xFF;
                col[c * 3 + 1] += m.colors[i * 3 + 1] & 0xFF;
                col[c * 3 + 2] += m.colors[i * 3 + 2] & 0xFF;
            }
        }
        keys = null;

        int tris = MeshAnalysis.triCount(m);
        int[] idx = new int[Math.min(tris, u * 4) * 3 + 3];
        int w = 0;
        for (int t = 0; t < tris; t++) {
            int a = cid[MeshAnalysis.vtx(m, t, 0)], b = cid[MeshAnalysis.vtx(m, t, 1)], c = cid[MeshAnalysis.vtx(m, t, 2)];
            if (a == b || b == c || a == c) continue;
            if (w + 3 > idx.length) idx = Arrays.copyOf(idx, idx.length * 2);
            idx[w++] = a; idx[w++] = b; idx[w++] = c;
        }

        Mesh o = new Mesh();
        o.vertexCount = u;
        o.positions = new float[u * 3];
        for (int c = 0; c < u; c++) {
            int k = Math.max(1, cnt[c]);
            o.positions[c * 3] = (float) (sum[c * 3] / k);
            o.positions[c * 3 + 1] = (float) (sum[c * 3 + 1] / k);
            o.positions[c * 3 + 2] = (float) (sum[c * 3 + 2] / k);
        }
        if (col != null) {
            o.colors = new byte[u * 3];
            for (int c = 0; c < u; c++) {
                int k = Math.max(1, cnt[c]);
                for (int j = 0; j < 3; j++) o.colors[c * 3 + j] = (byte) (col[c * 3 + j] / k);
            }
        }
        o.indices = Arrays.copyOf(idx, w);
        o.triangleCount = w / 3;
        o.computeBounds();
        o.computeSmoothNormals();
        return o;
    }
}
