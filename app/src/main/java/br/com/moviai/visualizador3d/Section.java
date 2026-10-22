package br.com.moviai.visualizador3d;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** Seção plana da malha: contornos encadeados em 2D e 3D, e gravação em DXF. */
final class Section {

    /** Eixo normal ao plano (0=X, 1=Y, 2=Z) e posição do plano. */
    final int axis;
    final double value;
    /** Eixos da tela 2D: u para a direita, v para cima. */
    final int uAxis, vAxis;
    /** Cada contorno: [u0, v0, u1, v1, ...]. */
    final List<double[]> polylines = new ArrayList<>();
    final List<Boolean> closed = new ArrayList<>();
    double minU, minV, maxU, maxV;

    private Section(int axis, double value) {
        this.axis = axis;
        this.value = value;
        if (axis == 0) { uAxis = 1; vAxis = 2; }
        else if (axis == 1) { uAxis = 0; vAxis = 2; }
        else { uAxis = 0; vAxis = 1; }
    }

    static String axisName(int a) {
        return "XYZ".substring(a, a + 1);
    }

    /** Coordenada 3D de um ponto (u,v) do contorno. */
    double[] to3d(double u, double v) {
        double[] p = new double[3];
        p[axis] = value;
        p[uAxis] = u;
        p[vAxis] = v;
        return p;
    }

    static Section slice(Mesh m, int axis, double value) {
        Section s = new Section(axis, value);
        if (m.pointCloud) return s;
        int tris = MeshAnalysis.triCount(m);
        float[] p = m.positions;
        // pequeno deslocamento evita vértices exatamente no plano
        double span = Math.max(m.max[axis] - m.min[axis], 1e-6);
        double val = value + span * 1e-7;
        List<double[]> segs = new ArrayList<>();
        double[] q = new double[6];
        for (int t = 0; t < tris; t++) {
            int[] v = {MeshAnalysis.vtx(m, t, 0) * 3, MeshAnalysis.vtx(m, t, 1) * 3, MeshAnalysis.vtx(m, t, 2) * 3};
            double d0 = p[v[0] + axis] - val, d1 = p[v[1] + axis] - val, d2 = p[v[2] + axis] - val;
            if ((d0 > 0 && d1 > 0 && d2 > 0) || (d0 < 0 && d1 < 0 && d2 < 0)) continue;
            double[] d = {d0, d1, d2};
            int k = 0;
            for (int e = 0; e < 3 && k < 2; e++) {
                int a = e, b = (e + 1) % 3;
                if ((d[a] > 0) == (d[b] > 0)) continue;
                // ordem canônica dos extremos para que triângulos vizinhos gerem o mesmo ponto
                int ia = v[a], ib = v[b];
                double da = d[a], db = d[b];
                if (less(p, ib, ia)) { int ti = ia; ia = ib; ib = ti; double td = da; da = db; db = td; }
                double f = da / (da - db);
                q[k * 2] = p[ia + s.uAxis] + f * (p[ib + s.uAxis] - p[ia + s.uAxis]);
                q[k * 2 + 1] = p[ia + s.vAxis] + f * (p[ib + s.vAxis] - p[ia + s.vAxis]);
                k++;
            }
            if (k == 2) segs.add(new double[]{q[0], q[1], q[2], q[3]});
        }
        s.chain(segs, Math.max(m.radius, 1e-6) * 1e-6);
        return s;
    }

    private static boolean less(float[] p, int a, int b) {
        if (p[a] != p[b]) return p[a] < p[b];
        if (p[a + 1] != p[b + 1]) return p[a + 1] < p[b + 1];
        return p[a + 2] < p[b + 2];
    }

    /** Encadeia segmentos soltos em contornos, unindo extremos coincidentes. */
    private void chain(List<double[]> segs, double tol) {
        int n = segs.size();
        HashMap<Long, int[]> ends = new HashMap<>();
        long[] keys = new long[n * 2];
        for (int i = 0; i < n; i++) {
            double[] s = segs.get(i);
            keys[i * 2] = key(s[0], s[1], tol);
            keys[i * 2 + 1] = key(s[2], s[3], tol);
            if (keys[i * 2] == keys[i * 2 + 1]) continue; // comprimento zero
            for (int e = 0; e < 2; e++) {
                long key = keys[i * 2 + e];
                int[] lst = ends.get(key);
                if (lst == null) ends.put(key, new int[]{i * 2 + e, -1});
                else if (lst[1] < 0) lst[1] = i * 2 + e;
                // mais de 2 extremos no mesmo ponto (malha não manifold): os excedentes viram início de outro contorno
            }
        }
        boolean[] used = new boolean[n];
        // segmentos de comprimento zero (plano passando por um vértice) não ligam nada: ignora
        for (int i = 0; i < n; i++) {
            if (keys[i * 2] == keys[i * 2 + 1]) used[i] = true;
        }
        minU = minV = Double.MAX_VALUE;
        maxU = maxV = -Double.MAX_VALUE;
        for (int start = 0; start < n; start++) {
            if (used[start]) continue;
            // anda para trás até a ponta do contorno (ou dá a volta)
            int seg = start, endIdx = 0;
            for (int guard = 0; guard < n; guard++) {
                int other = partner(ends, keys[seg * 2 + endIdx], seg * 2 + endIdx);
                if (other < 0) break;
                int os = other / 2;
                if (os == start || used[os]) break;
                seg = os;
                endIdx = 1 - (other % 2);
            }
            // agora percorre para frente a partir de seg, saindo pela ponta oposta a endIdx
            ArrayList<Double> pts = new ArrayList<>();
            int cur = seg, entry = endIdx;
            double[] s0 = segs.get(cur);
            pts.add(s0[entry * 2]);
            pts.add(s0[entry * 2 + 1]);
            boolean isClosed = false;
            while (true) {
                used[cur] = true;
                double[] s = segs.get(cur);
                int exit = 1 - entry;
                pts.add(s[exit * 2]);
                pts.add(s[exit * 2 + 1]);
                int other = partner(ends, keys[cur * 2 + exit], cur * 2 + exit);
                if (other < 0) break;
                int ns = other / 2;
                if (used[ns]) {
                    isClosed = ns == seg;
                    break;
                }
                cur = ns;
                entry = other % 2;
            }
            double[] arr = new double[pts.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = pts.get(i);
            if (isClosed && arr.length >= 4) arr = java.util.Arrays.copyOf(arr, arr.length - 2);
            for (int i = 0; i < arr.length; i += 2) {
                minU = Math.min(minU, arr[i]); maxU = Math.max(maxU, arr[i]);
                minV = Math.min(minV, arr[i + 1]); maxV = Math.max(maxV, arr[i + 1]);
            }
            polylines.add(arr);
            closed.add(isClosed);
        }
    }

    private static int partner(HashMap<Long, int[]> ends, long key, int self) {
        int[] l = ends.get(key);
        if (l == null) return -1;
        if (l[0] == self) return l[1];
        if (l[1] == self) return l[0];
        return -1;
    }

    private static long key(double u, double v, double tol) {
        long a = Math.round(u / tol), b = Math.round(v / tol);
        return a * 0x9E3779B97F4A7C15L ^ (b + 0x632BE59BD9B4E019L);
    }

    int pointCount() {
        int c = 0;
        for (double[] p : polylines) c += p.length / 2;
        return c;
    }

    /** Ponto do contorno mais próximo de (u,v): retorna {u, v, distância}. */
    double[] nearest(double u, double v) {
        double bd = Double.MAX_VALUE, bu = 0, bv = 0;
        for (double[] pl : polylines) {
            for (int i = 0; i < pl.length; i += 2) {
                double du = pl[i] - u, dv = pl[i + 1] - v;
                double d = du * du + dv * dv;
                if (d < bd) { bd = d; bu = pl[i]; bv = pl[i + 1]; }
            }
        }
        return new double[]{bu, bv, Math.sqrt(bd)};
    }

    // ================================================================ DXF

    /** DXF R12 ASCII com POLYLINE 3D em coordenadas reais da peça (mm), camada SECAO. */
    void writeDxf(OutputStream os) throws IOException {
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.US_ASCII));
        g(w, 0, "SECTION"); g(w, 2, "HEADER");
        g(w, 9, "$ACADVER"); g(w, 1, "AC1009");
        g(w, 0, "ENDSEC");
        g(w, 0, "SECTION"); g(w, 2, "TABLES");
        g(w, 0, "TABLE"); g(w, 2, "LAYER"); g(w, 70, "1");
        g(w, 0, "LAYER"); g(w, 2, "SECAO"); g(w, 70, "0"); g(w, 62, "1"); g(w, 6, "CONTINUOUS");
        g(w, 0, "ENDTAB");
        g(w, 0, "ENDSEC");
        g(w, 0, "SECTION"); g(w, 2, "ENTITIES");
        for (int k = 0; k < polylines.size(); k++) {
            double[] pl = polylines.get(k);
            if (pl.length < 4) continue;
            g(w, 0, "POLYLINE"); g(w, 8, "SECAO"); g(w, 66, "1");
            g(w, 10, "0.0"); g(w, 20, "0.0"); g(w, 30, "0.0");
            g(w, 70, String.valueOf(8 | (closed.get(k) ? 1 : 0)));
            for (int i = 0; i < pl.length; i += 2) {
                double[] p = to3d(pl[i], pl[i + 1]);
                g(w, 0, "VERTEX"); g(w, 8, "SECAO");
                g(w, 10, num(p[0])); g(w, 20, num(p[1])); g(w, 30, num(p[2]));
                g(w, 70, "32");
            }
            g(w, 0, "SEQEND"); g(w, 8, "SECAO");
        }
        g(w, 0, "ENDSEC");
        g(w, 0, "EOF");
        w.flush();
    }

    private static void g(BufferedWriter w, int code, String v) throws IOException {
        w.write(String.valueOf(code));
        w.write("\r\n");
        w.write(v);
        w.write("\r\n");
    }

    private static String num(double v) {
        return String.format(Locale.US, "%.6f", v);
    }
}
