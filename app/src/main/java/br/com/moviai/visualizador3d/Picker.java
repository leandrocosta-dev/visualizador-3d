package br.com.moviai.visualizador3d;

/** Seleção de ponto na malha a partir de um toque na tela (raycast em coordenadas da malha). */
final class Picker {
    private Picker() {}

    static final class Hit {
        /** Ponto atingido, coordenadas do arquivo. */
        final double[] p;
        /** Direção do raio (do observador para a cena). */
        final double[] dir;
        /** Normal do triângulo atingido (null em nuvem de pontos). */
        final double[] normal;

        Hit(double[] p, double[] dir, double[] normal) {
            this.p = p;
            this.dir = dir;
            this.normal = normal;
        }
    }

    /**
     * @param mvp   matriz projeção*vista*modelo usada no desenho
     * @param clip  plano de corte (a,b,c,d) com descarte quando a*x+b*y+c*z+d > 0, ou null
     * @param tolPx tolerância em pixels para nuvem de pontos
     */
    static Hit pick(Mesh m, float[] mvp, int w, int h, float sx, float sy, float[] clip, float tolPx) {
        float[] inv = Mat4.invert(mvp);
        if (inv == null) return null;
        double nx = 2.0 * sx / w - 1.0, ny = 1.0 - 2.0 * sy / h;
        double[] a = new double[4], b = new double[4];
        Mat4.transform(inv, nx, ny, -1, 1, a);
        Mat4.transform(inv, nx, ny, 1, 1, b);
        double[] o = {a[0] / a[3], a[1] / a[3], a[2] / a[3]};
        double[] f = {b[0] / b[3], b[1] / b[3], b[2] / b[3]};
        double[] dir = Align.sub(f, o);
        if (!Align.normalize(dir)) return null;

        boolean cloud = m.pointCloud || m.vertexCount < 3;
        return cloud ? pickPoint(m, mvp, w, h, sx, sy, clip, tolPx, dir)
                     : pickTriangle(m, o, dir, clip);
    }

    private static boolean clipped(float[] clip, double x, double y, double z) {
        return clip != null && clip[0] * x + clip[1] * y + clip[2] * z + clip[3] > 0;
    }

    private static Hit pickTriangle(Mesh m, double[] o, double[] d, float[] clip) {
        float[] p = m.positions;
        int[] idx = m.indices;
        int tris = idx != null ? idx.length / 3 : m.vertexCount / 3;
        double ox = o[0], oy = o[1], oz = o[2], dx = d[0], dy = d[1], dz = d[2];
        double best = Double.MAX_VALUE;
        int bestTri = -1;
        for (int t = 0; t < tris; t++) {
            int i0, i1, i2;
            if (idx != null) { i0 = idx[t * 3] * 3; i1 = idx[t * 3 + 1] * 3; i2 = idx[t * 3 + 2] * 3; }
            else { i0 = t * 9; i1 = i0 + 3; i2 = i0 + 6; }
            double ax = p[i0], ay = p[i0 + 1], az = p[i0 + 2];
            double e1x = p[i1] - ax, e1y = p[i1 + 1] - ay, e1z = p[i1 + 2] - az;
            double e2x = p[i2] - ax, e2y = p[i2 + 1] - ay, e2z = p[i2 + 2] - az;
            double px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
            double det = e1x * px + e1y * py + e1z * pz;
            if (det > -1e-20 && det < 1e-20) continue;
            double invDet = 1.0 / det;
            double tx = ox - ax, ty = oy - ay, tz = oz - az;
            double u = (tx * px + ty * py + tz * pz) * invDet;
            if (u < 0 || u > 1) continue;
            double qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
            double v = (dx * qx + dy * qy + dz * qz) * invDet;
            if (v < 0 || u + v > 1) continue;
            double dist = (e2x * qx + e2y * qy + e2z * qz) * invDet;
            if (dist <= 0 || dist >= best) continue;
            if (clipped(clip, ox + dx * dist, oy + dy * dist, oz + dz * dist)) continue;
            best = dist;
            bestTri = t;
        }
        if (bestTri < 0) return null;
        double[] hit = {ox + dx * best, oy + dy * best, oz + dz * best};
        int i0, i1, i2;
        if (idx != null) { i0 = idx[bestTri * 3] * 3; i1 = idx[bestTri * 3 + 1] * 3; i2 = idx[bestTri * 3 + 2] * 3; }
        else { i0 = bestTri * 9; i1 = i0 + 3; i2 = i0 + 6; }
        double[] e1 = {p[i1] - p[i0], p[i1 + 1] - p[i0 + 1], p[i1 + 2] - p[i0 + 2]};
        double[] e2 = {p[i2] - p[i0], p[i2 + 1] - p[i0 + 1], p[i2 + 2] - p[i0 + 2]};
        double[] n = Align.cross(e1, e2);
        if (!Align.normalize(n)) n = null;
        return new Hit(hit, d, n);
    }

    private static Hit pickPoint(Mesh m, float[] mvp, int w, int h, float sx, float sy,
                                 float[] clip, float tolPx, double[] dir) {
        float[] p = m.positions;
        double tol2 = tolPx * tolPx;
        double bestDepth = Double.MAX_VALUE;
        int best = -1;
        for (int i = 0; i < m.vertexCount; i++) {
            float x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2];
            double cw = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15];
            if (cw <= 0) continue;
            double cx = (mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]) / cw;
            double cy = (mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]) / cw;
            double cz = (mvp[2] * x + mvp[6] * y + mvp[10] * z + mvp[14]) / cw;
            double px = (cx + 1) * 0.5 * w - sx, py = (1 - cy) * 0.5 * h - sy;
            if (px * px + py * py > tol2) continue;
            if (cz >= bestDepth || cz < -1) continue;
            if (clipped(clip, x, y, z)) continue;
            bestDepth = cz;
            best = i;
        }
        if (best < 0) return null;
        double[] n = null;
        if (m.normals != null) {
            n = new double[]{m.normals[best * 3], m.normals[best * 3 + 1], m.normals[best * 3 + 2]};
            if (!Align.normalize(n)) n = null;
        }
        return new Hit(new double[]{p[best * 3], p[best * 3 + 1], p[best * 3 + 2]}, dir, n);
    }
}
