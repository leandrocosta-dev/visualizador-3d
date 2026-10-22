package br.com.moviai.visualizador3d;

/** Matrizes 4x4 em ordem de coluna (mesma convenção do OpenGL). Java puro, testável fora do Android. */
final class Mat4 {
    private Mat4() {}

    static float[] identity() {
        float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1f;
        return m;
    }

    /** a * b */
    static float[] mul(float[] a, float[] b) {
        float[] o = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                float s = 0;
                for (int k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k];
                o[c * 4 + r] = s;
            }
        }
        return o;
    }

    static float[] translation(double x, double y, double z) {
        float[] m = identity();
        m[12] = (float) x;
        m[13] = (float) y;
        m[14] = (float) z;
        return m;
    }

    /** Rotação de angleRad em torno do eixo (x,y,z), regra da mão direita. */
    static float[] rotation(double angleRad, double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        float[] m = identity();
        if (l < 1e-12) return m;
        x /= l; y /= l; z /= l;
        double c = Math.cos(angleRad), s = Math.sin(angleRad), t = 1 - c;
        m[0] = (float) (t * x * x + c);
        m[1] = (float) (t * x * y + s * z);
        m[2] = (float) (t * x * z - s * y);
        m[4] = (float) (t * x * y - s * z);
        m[5] = (float) (t * y * y + c);
        m[6] = (float) (t * y * z + s * x);
        m[8] = (float) (t * x * z + s * y);
        m[9] = (float) (t * y * z - s * x);
        m[10] = (float) (t * z * z + c);
        return m;
    }

    /** Rotação em torno de um ponto: T(p) * R * T(-p). */
    static float[] about(float[] rot, double px, double py, double pz) {
        return mul(translation(px, py, pz), mul(rot, translation(-px, -py, -pz)));
    }

    /** Matriz que leva o vetor unitário a até o vetor unitário b pela menor rotação. */
    static float[] rotationBetween(double[] a, double[] b) {
        double cx = a[1] * b[2] - a[2] * b[1];
        double cy = a[2] * b[0] - a[0] * b[2];
        double cz = a[0] * b[1] - a[1] * b[0];
        double s = Math.sqrt(cx * cx + cy * cy + cz * cz);
        double d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        if (s < 1e-9) {
            if (d > 0) return identity();
            // 180 graus: qualquer eixo perpendicular a a
            double[] p = Math.abs(a[0]) < 0.9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
            double px = a[1] * p[2] - a[2] * p[1];
            double py = a[2] * p[0] - a[0] * p[2];
            double pz = a[0] * p[1] - a[1] * p[0];
            return rotation(Math.PI, px, py, pz);
        }
        return rotation(Math.atan2(s, d), cx, cy, cz);
    }

    /** Inversa geral. Retorna null se singular. */
    static float[] invert(float[] m) {
        double[] inv = new double[16];
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
        double det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
        if (Math.abs(det) < 1e-30) return null;
        float[] o = new float[16];
        for (int i = 0; i < 16; i++) o[i] = (float) (inv[i] / det);
        return o;
    }

    /** Aplica m a (x,y,z,w); resultado em out[4]. */
    static void transform(float[] m, double x, double y, double z, double w, double[] out) {
        for (int r = 0; r < 4; r++) {
            out[r] = m[r] * x + m[4 + r] * y + m[8 + r] * z + m[12 + r] * w;
        }
    }
}
