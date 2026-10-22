package br.com.moviai.visualizador3d;

/** Malha ou nuvem de pontos carregada na memória. */
final class Mesh {
    /** xyz por vértice. */
    float[] positions;
    /** Normal por vértice (pode ser null). */
    float[] normals;
    /** Cor RGB por vértice, 0..255 (pode ser null). */
    byte[] colors;
    /** Índices de triângulos. Null = triângulos sequenciais (STL) ou nuvem de pontos. */
    int[] indices;

    int vertexCount;
    int triangleCount;
    boolean pointCloud;

    String name = "";
    String format = "";

    final float[] min = new float[3];
    final float[] max = new float[3];
    final float[] center = new float[3];
    float radius = 1f;

    void computeBounds() {
        min[0] = min[1] = min[2] = Float.POSITIVE_INFINITY;
        max[0] = max[1] = max[2] = Float.NEGATIVE_INFINITY;
        float[] p = positions;
        for (int i = 0; i < vertexCount * 3; i += 3) {
            float x = p[i], y = p[i + 1], z = p[i + 2];
            if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z)) continue;
            if (x < min[0]) min[0] = x;
            if (y < min[1]) min[1] = y;
            if (z < min[2]) min[2] = z;
            if (x > max[0]) max[0] = x;
            if (y > max[1]) max[1] = y;
            if (z > max[2]) max[2] = z;
        }
        if (min[0] > max[0]) {
            for (int k = 0; k < 3; k++) { min[k] = 0; max[k] = 0; }
        }
        float r2 = 0;
        for (int k = 0; k < 3; k++) {
            center[k] = (min[k] + max[k]) * 0.5f;
            float h = (max[k] - min[k]) * 0.5f;
            r2 += h * h;
        }
        radius = (float) Math.sqrt(r2);
        if (!(radius > 1e-12f)) radius = 1f;
    }

    /** Normais suaves por vértice, ponderadas pela área de cada face. */
    void computeSmoothNormals() {
        float[] p = positions;
        float[] n = new float[vertexCount * 3];
        int[] idx = indices;
        for (int t = 0; t < idx.length; t += 3) {
            int a = idx[t] * 3, b = idx[t + 1] * 3, c = idx[t + 2] * 3;
            float ux = p[b] - p[a], uy = p[b + 1] - p[a + 1], uz = p[b + 2] - p[a + 2];
            float vx = p[c] - p[a], vy = p[c + 1] - p[a + 1], vz = p[c + 2] - p[a + 2];
            float nx = uy * vz - uz * vy;
            float ny = uz * vx - ux * vz;
            float nz = ux * vy - uy * vx;
            n[a] += nx; n[a + 1] += ny; n[a + 2] += nz;
            n[b] += nx; n[b + 1] += ny; n[b + 2] += nz;
            n[c] += nx; n[c + 1] += ny; n[c + 2] += nz;
        }
        for (int i = 0; i < n.length; i += 3) {
            float l = (float) Math.sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2]);
            if (l > 0) { n[i] /= l; n[i + 1] /= l; n[i + 2] /= l; }
            else { n[i + 2] = 1f; }
        }
        normals = n;
    }
}
