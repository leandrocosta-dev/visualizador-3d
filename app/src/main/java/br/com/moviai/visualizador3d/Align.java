package br.com.moviai.visualizador3d;

import java.util.List;

/** Geometria de alinhamento: PCA, ajuste de plano, rotações restritas e círculo por 3 pontos. */
final class Align {
    private Align() {}

    static final class Eig {
        /** Autovalores em ordem decrescente. */
        final double[] val = new double[3];
        /** vec[i] = autovetor unitário do autovalor val[i]. */
        final double[][] vec = new double[3][3];
    }

    /** Autovalores/autovetores de matriz simétrica 3x3 (método de Jacobi). */
    static Eig eigSym(double[][] in) {
        double[][] a = {in[0].clone(), in[1].clone(), in[2].clone()};
        double[][] v = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (int sweep = 0; sweep < 50; sweep++) {
            double off = a[0][1] * a[0][1] + a[0][2] * a[0][2] + a[1][2] * a[1][2];
            if (off < 1e-30) break;
            for (int p = 0; p < 2; p++) {
                for (int q = p + 1; q < 3; q++) {
                    if (Math.abs(a[p][q]) < 1e-300) continue;
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    if (theta == 0) t = 1;
                    double c = 1 / Math.sqrt(t * t + 1), s = t * c;
                    for (int k = 0; k < 3; k++) {
                        double akp = a[k][p], akq = a[k][q];
                        a[k][p] = c * akp - s * akq;
                        a[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < 3; k++) {
                        double apk = a[p][k], aqk = a[q][k];
                        a[p][k] = c * apk - s * aqk;
                        a[q][k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < 3; k++) {
                        double vkp = v[k][p], vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq;
                        v[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        Integer[] ord = {0, 1, 2};
        final double[] d = {a[0][0], a[1][1], a[2][2]};
        java.util.Arrays.sort(ord, (x, y) -> Double.compare(d[y], d[x]));
        Eig e = new Eig();
        for (int i = 0; i < 3; i++) {
            int j = ord[i];
            e.val[i] = d[j];
            e.vec[i] = new double[]{v[0][j], v[1][j], v[2][j]};
        }
        return e;
    }

    /** Autovetor do maior autovalor de matriz simétrica n×n (Jacobi). */
    static double[] maxEigenvector(double[][] in) {
        int n = in.length;
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) a[i] = in[i].clone();
        double[][] v = new double[n][n];
        for (int i = 0; i < n; i++) v[i][i] = 1;
        for (int sweep = 0; sweep < 60; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) off += a[p][q] * a[p][q];
            if (off < 1e-30) break;
            for (int p = 0; p < n - 1; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(a[p][q]) < 1e-300) continue;
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = theta == 0 ? 1 : Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    double c = 1 / Math.sqrt(t * t + 1), s = t * c;
                    for (int k = 0; k < n; k++) {
                        double akp = a[k][p], akq = a[k][q];
                        a[k][p] = c * akp - s * akq;
                        a[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < n; k++) {
                        double apk = a[p][k], aqk = a[q][k];
                        a[p][k] = c * apk - s * aqk;
                        a[q][k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < n; k++) {
                        double vkp = v[k][p], vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq;
                        v[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        int best = 0;
        for (int i = 1; i < n; i++) if (a[i][i] > a[best][best]) best = i;
        double[] e = new double[n];
        for (int k = 0; k < n; k++) e[k] = v[k][best];
        return e;
    }

    /**
     * Covariância da superfície (integral exata sobre os triângulos, ponderada pela área).
     * Independe de como a malha foi triangulada: um CAD com poucos triângulos grandes
     * e um scan denso da mesma peça dão o mesmo resultado. Nuvem de pontos usa os vértices.
     */
    static double[][] surfaceCovariance(Mesh m, double[] mean) {
        int tris = MeshAnalysis.triCount(m);
        if (tris == 0) return covariance(m, mean);
        float[] p = m.positions;
        double A = 0, gx = 0, gy = 0, gz = 0;
        double xx = 0, yy = 0, zz = 0, xy = 0, xz = 0, yz = 0;
        double ox = m.center[0], oy = m.center[1], oz = m.center[2];
        for (int t = 0; t < tris; t++) {
            int a = MeshAnalysis.vtx(m, t, 0) * 3, b = MeshAnalysis.vtx(m, t, 1) * 3, c = MeshAnalysis.vtx(m, t, 2) * 3;
            double ax = p[a] - ox, ay = p[a + 1] - oy, az = p[a + 2] - oz;
            double bx = p[b] - ox, by = p[b + 1] - oy, bz = p[b + 2] - oz;
            double cx = p[c] - ox, cy = p[c + 1] - oy, cz = p[c + 2] - oz;
            double ux = bx - ax, uy = by - ay, uz = bz - az, vx = cx - ax, vy = cy - ay, vz = cz - az;
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            double area = 0.5 * Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (area <= 0) continue;
            double sx = ax + bx + cx, sy = ay + by + cy, sz = az + bz + cz;
            A += area;
            gx += area * sx / 3; gy += area * sy / 3; gz += area * sz / 3;
            double k = area / 12.0;
            xx += k * (ax * ax + bx * bx + cx * cx + sx * sx);
            yy += k * (ay * ay + by * by + cy * cy + sy * sy);
            zz += k * (az * az + bz * bz + cz * cz + sz * sz);
            xy += k * (ax * ay + bx * by + cx * cy + sx * sy);
            xz += k * (ax * az + bx * bz + cx * cz + sx * sz);
            yz += k * (ay * az + by * bz + cy * cz + sy * sz);
        }
        if (A <= 0) return covariance(m, mean);
        double mx = gx / A, my = gy / A, mz = gz / A;
        if (mean != null) { mean[0] = mx + ox; mean[1] = my + oy; mean[2] = mz + oz; }
        return new double[][]{
                {xx / A - mx * mx, xy / A - mx * my, xz / A - mx * mz},
                {xy / A - mx * my, yy / A - my * my, yz / A - my * mz},
                {xz / A - mx * mz, yz / A - my * mz, zz / A - mz * mz}};
    }

    /** Covariância dos vértices (amostrada para malhas enormes). mean[3] recebe a média. */
    static double[][] covariance(Mesh m, double[] mean) {
        float[] p = m.positions;
        int n = m.vertexCount;
        int stride = Math.max(1, n / 600_000);
        double sx = 0, sy = 0, sz = 0, sxx = 0, syy = 0, szz = 0, sxy = 0, sxz = 0, syz = 0;
        long c = 0;
        for (int i = 0; i < n; i += stride) {
            double x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2];
            sx += x; sy += y; sz += z;
            sxx += x * x; syy += y * y; szz += z * z;
            sxy += x * y; sxz += x * z; syz += y * z;
            c++;
        }
        return finishCov(c, sx, sy, sz, sxx, syy, szz, sxy, sxz, syz, mean);
    }

    private static double[][] finishCov(long c, double sx, double sy, double sz, double sxx, double syy,
                                        double szz, double sxy, double sxz, double syz, double[] mean) {
        if (c == 0) return null;
        double mx = sx / c, my = sy / c, mz = sz / c;
        if (mean != null) { mean[0] = mx; mean[1] = my; mean[2] = mz; }
        return new double[][]{
                {sxx / c - mx * mx, sxy / c - mx * my, sxz / c - mx * mz},
                {sxy / c - mx * my, syy / c - my * my, syz / c - my * mz},
                {sxz / c - mx * mz, syz / c - my * mz, szz / c - mz * mz}};
    }

    /**
     * Alinhamento automático por eixos principais: maior dimensão em X, menor em Z.
     * Retorna a matriz de rotação em torno do centróide.
     */
    static float[] autoRotation(Mesh m) {
        double[] mean = new double[3];
        double[][] cov = covariance(m, mean);
        if (cov == null) return Mat4.identity();
        Eig e = eigSym(cov);
        double[] ex = e.vec[0], ey = e.vec[1];
        double[] ez = cross(ex, ey);
        float[] r = Mat4.identity();
        double[][] rows = {ex, ey, ez};
        for (int i = 0; i < 3; i++) {
            r[i] = (float) rows[i][0];
            r[4 + i] = (float) rows[i][1];
            r[8 + i] = (float) rows[i][2];
        }
        return Mat4.about(r, mean[0], mean[1], mean[2]);
    }

    /** Caixa envolvente (minX,minY,minZ,maxX,maxY,maxZ) dos vértices depois de aplicar t, sem alterar a malha. */
    static float[] boundsAfter(Mesh m, float[] t) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        float[] p = m.positions;
        for (int i = 0; i < m.vertexCount * 3; i += 3) {
            float x = p[i], y = p[i + 1], z = p[i + 2];
            float qx = t[0] * x + t[4] * y + t[8] * z + t[12];
            float qy = t[1] * x + t[5] * y + t[9] * z + t[13];
            float qz = t[2] * x + t[6] * y + t[10] * z + t[14];
            if (qx < b[0]) b[0] = qx;
            if (qy < b[1]) b[1] = qy;
            if (qz < b[2]) b[2] = qz;
            if (qx > b[3]) b[3] = qx;
            if (qy > b[4]) b[4] = qy;
            if (qz > b[5]) b[5] = qz;
        }
        return b;
    }

    /**
     * Normal de um plano ajustado aos vértices próximos dos pontos tocados.
     * Orientada para o observador (contra a direção do raio). Null se não houver pontos suficientes.
     */
    static double[] fitPlaneNormal(Mesh m, List<Picker.Hit> picks, double radius) {
        float[] p = m.positions;
        double r2 = radius * radius;
        double sx = 0, sy = 0, sz = 0, sxx = 0, syy = 0, szz = 0, sxy = 0, sxz = 0, syz = 0;
        long c = 0;
        for (int i = 0; i < m.vertexCount; i++) {
            double x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2];
            boolean near = false;
            for (Picker.Hit h : picks) {
                double dx = x - h.p[0], dy = y - h.p[1], dz = z - h.p[2];
                if (dx * dx + dy * dy + dz * dz <= r2) { near = true; break; }
            }
            if (!near) continue;
            sx += x; sy += y; sz += z;
            sxx += x * x; syy += y * y; szz += z * z;
            sxy += x * y; sxz += x * z; syz += y * z;
            c++;
        }
        double[] n;
        if (c >= 6) {
            double[][] cov = finishCov(c, sx, sy, sz, sxx, syy, szz, sxy, sxz, syz, null);
            n = eigSym(cov).vec[2];
        } else if (picks.size() >= 3) {
            double[] a = picks.get(0).p, b = picks.get(1).p, d = picks.get(2).p;
            n = cross(sub(b, a), sub(d, a));
        } else if (picks.get(0).normal != null) {
            n = picks.get(0).normal.clone();
        } else {
            return null;
        }
        if (!normalize(n)) return null;
        double[] ray = picks.get(0).dir;
        if (dot(n, ray) > 0) { n[0] = -n[0]; n[1] = -n[1]; n[2] = -n[2]; }
        return n;
    }

    /**
     * Rotação que leva o vetor v até o eixo alvo. Se houver eixo travado perpendicular ao alvo,
     * gira somente em torno do eixo travado (preserva o alinhamento anterior). Null se impossível.
     */
    static float[] alignVector(double[] v, double[] target, double[] locked) {
        double[] u = v.clone();
        if (!normalize(u)) return null;
        if (locked != null && Math.abs(dot(target, locked)) < 1e-6) {
            double k = dot(u, locked);
            double[] w = {u[0] - k * locked[0], u[1] - k * locked[1], u[2] - k * locked[2]};
            if (!normalize(w)) return null;
            double ang = Math.atan2(dot(cross(w, target), locked), dot(w, target));
            return Mat4.rotation(ang, locked[0], locked[1], locked[2]);
        }
        return Mat4.rotationBetween(u, target);
    }

    /** Centro do círculo que passa por 3 pontos; out[3] recebe o raio. Null se colineares. */
    static double[] circle(double[] a, double[] b, double[] c) {
        double[] ab = sub(b, a), ac = sub(c, a);
        double[] n = cross(ab, ac);
        double n2 = dot(n, n);
        if (n2 < 1e-18) return null;
        double[] t1 = cross(n, ab);
        double[] t2 = cross(ac, n);
        double ab2 = dot(ab, ab), ac2 = dot(ac, ac);
        double[] center = new double[4];
        for (int i = 0; i < 3; i++) {
            center[i] = a[i] + (ac2 * t1[i] + ab2 * t2[i]) / (2 * n2);
        }
        double[] rr = sub(new double[]{center[0], center[1], center[2]}, a);
        center[3] = Math.sqrt(dot(rr, rr));
        return center;
    }

    // ------------------------------------------------------------ vetores

    static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static boolean normalize(double[] v) {
        double l = Math.sqrt(dot(v, v));
        if (l < 1e-12) return false;
        v[0] /= l; v[1] /= l; v[2] /= l;
        return true;
    }
}
