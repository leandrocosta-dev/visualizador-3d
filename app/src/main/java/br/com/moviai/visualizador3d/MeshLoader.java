package br.com.moviai.visualizador3d;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Leitura de STL (binário e ASCII) e PLY (ASCII, binário little e big endian). */
final class MeshLoader {

    interface Source {
        InputStream open() throws IOException;
    }

    private MeshLoader() {}

    /** @param size tamanho do arquivo em bytes, ou -1 se desconhecido. */
    static Mesh load(Source src, String name, long size) throws IOException {
        byte[] head = readHead(src, 1024);
        String headTxt = new String(head, StandardCharsets.US_ASCII);
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);

        Mesh m;
        if (headTxt.startsWith("ply") || (lower.endsWith(".ply") && !looksLikeStl(head, size))) {
            m = loadPly(src);
        } else {
            m = loadStl(src, head, size);
        }
        m.name = name == null ? "" : name;
        m.computeBounds();
        return m;
    }

    private static byte[] readHead(Source src, int max) throws IOException {
        try (InputStream in = src.open()) {
            byte[] b = new byte[max];
            int n = 0;
            while (n < max) {
                int r = in.read(b, n, max - n);
                if (r < 0) break;
                n += r;
            }
            return Arrays.copyOf(b, n);
        }
    }

    private static boolean looksLikeStl(byte[] head, long size) {
        if (head.length >= 84 && size > 0) {
            long n = le32(head, 80) & 0xFFFFFFFFL;
            if (84 + 50 * n == size) return true;
        }
        String t = new String(head, StandardCharsets.US_ASCII).trim().toLowerCase(Locale.ROOT);
        return t.startsWith("solid") && t.contains("facet");
    }

    /** STL ASCII só tem texto; qualquer byte de controle após o cabeçalho indica binário. */
    private static boolean hasBinaryBytes(byte[] head) {
        for (int i = 80; i < head.length; i++) {
            int b = head[i] & 0xFF;
            if (b < 9 || (b > 13 && b < 32) || b > 126) return true;
        }
        return false;
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    // ------------------------------------------------------------------ STL

    private static Mesh loadStl(Source src, byte[] head, long size) throws IOException {
        boolean binary;
        long declared = head.length >= 84 ? (le32(head, 80) & 0xFFFFFFFFL) : -1;
        String t = new String(head, StandardCharsets.US_ASCII).trim().toLowerCase(Locale.ROOT);
        boolean startsSolid = t.startsWith("solid");

        if (size > 0 && declared >= 0 && 84 + 50 * declared == size) {
            binary = true;
        } else if (hasBinaryBytes(head)) {
            binary = true;
        } else {
            binary = !startsSolid;
        }
        if (head.length < 84 && binary) throw new IOException("Arquivo STL vazio ou corrompido");

        try (InputStream in = src.open()) {
            BinReader r = new BinReader(in);
            return binary ? stlBinary(r, declared) : stlAscii(r);
        }
    }

    private static Mesh stlBinary(BinReader r, long declared) throws IOException {
        if (declared <= 0) throw new IOException("STL binário sem triângulos");
        if (declared > 40_000_000L) throw new IOException("STL grande demais: " + declared + " triângulos");
        int n = (int) declared;
        r.skip(80);
        r.i32();
        float[] p = new float[n * 9];
        float[] nr = new float[n * 9];
        int read = 0;
        try {
            for (int t = 0; t < n; t++) {
                r.f32(); r.f32(); r.f32();
                int o = t * 9;
                for (int k = 0; k < 9; k++) p[o + k] = r.f32();
                r.u16();
                read++;
            }
        } catch (java.io.EOFException e) {
            if (read == 0) throw e; // arquivo truncado: mostra o que foi possível ler
        }
        if (read < n) {
            p = Arrays.copyOf(p, read * 9);
            nr = Arrays.copyOf(nr, read * 9);
        }
        return finishStl(p, nr, read, "STL binário");
    }

    private static Mesh stlAscii(BinReader r) throws IOException {
        FloatList p = new FloatList(1 << 16);
        String tok;
        while ((tok = r.token()) != null) {
            if (tok.equalsIgnoreCase("vertex")) {
                p.add((float) r.tokenDouble());
                p.add((float) r.tokenDouble());
                p.add((float) r.tokenDouble());
            }
        }
        int tris = p.size / 9;
        if (tris == 0) throw new IOException("Nenhum triângulo encontrado no STL");
        float[] pos = Arrays.copyOf(p.data, tris * 9);
        return finishStl(pos, new float[tris * 9], tris, "STL ASCII");
    }

    /** Normais de face recalculadas a partir dos vértices (as do arquivo muitas vezes vêm zeradas). */
    private static Mesh finishStl(float[] p, float[] nr, int tris, String fmt) {
        for (int t = 0; t < tris; t++) {
            int o = t * 9;
            float ux = p[o + 3] - p[o], uy = p[o + 4] - p[o + 1], uz = p[o + 5] - p[o + 2];
            float vx = p[o + 6] - p[o], vy = p[o + 7] - p[o + 1], vz = p[o + 8] - p[o + 2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l > 0) { nx /= l; ny /= l; nz /= l; } else { nx = 0; ny = 0; nz = 1; }
            for (int v = 0; v < 3; v++) {
                nr[o + v * 3] = nx;
                nr[o + v * 3 + 1] = ny;
                nr[o + v * 3 + 2] = nz;
            }
        }
        Mesh m = new Mesh();
        m.positions = p;
        m.normals = nr;
        m.vertexCount = tris * 3;
        m.triangleCount = tris;
        m.format = fmt;
        return m;
    }

    // ------------------------------------------------------------------ PLY

    private static final int I8 = 0, U8 = 1, I16 = 2, U16 = 3, I32 = 4, U32 = 5, F32 = 6, F64 = 7;

    private static final class Prop {
        String name;
        int type;
        boolean list;
        int countType;
    }

    private static final class Element {
        String name;
        long count;
        final List<Prop> props = new ArrayList<>();
    }

    private static int plyType(String s) throws IOException {
        switch (s) {
            case "char": case "int8": return I8;
            case "uchar": case "uint8": return U8;
            case "short": case "int16": return I16;
            case "ushort": case "uint16": return U16;
            case "int": case "int32": return I32;
            case "uint": case "uint32": return U32;
            case "float": case "float32": return F32;
            case "double": case "float64": return F64;
            default: throw new IOException("Tipo PLY desconhecido: " + s);
        }
    }

    private static double readVal(BinReader r, int type, boolean ascii) throws IOException {
        if (ascii) return r.tokenDouble();
        switch (type) {
            case I8: return (byte) r.u8();
            case U8: return r.u8();
            case I16: return (short) r.u16();
            case U16: return r.u16();
            case I32: return r.i32();
            case U32: return r.i32() & 0xFFFFFFFFL;
            case F32: return r.f32();
            default: return r.f64();
        }
    }

    private static Mesh loadPly(Source src) throws IOException {
        try (InputStream in = src.open()) {
            BinReader r = new BinReader(in);
            String line = r.readLine();
            if (line == null || !line.trim().equals("ply")) throw new IOException("Cabeçalho PLY inválido");

            boolean ascii = true;
            String fmtName = "PLY";
            List<Element> elements = new ArrayList<>();
            Element cur = null;
            while (true) {
                line = r.readLine();
                if (line == null) throw new IOException("Cabeçalho PLY incompleto");
                String[] t = line.trim().split("\\s+");
                if (t.length == 0 || t[0].isEmpty()) continue;
                switch (t[0]) {
                    case "format":
                        if (t[1].equals("ascii")) { ascii = true; fmtName = "PLY ASCII"; }
                        else if (t[1].equals("binary_little_endian")) { ascii = false; r.bigEndian = false; fmtName = "PLY binário"; }
                        else if (t[1].equals("binary_big_endian")) { ascii = false; r.bigEndian = true; fmtName = "PLY binário (big endian)"; }
                        else throw new IOException("Formato PLY não suportado: " + t[1]);
                        break;
                    case "element":
                        cur = new Element();
                        cur.name = t[1];
                        cur.count = Long.parseLong(t[2]);
                        elements.add(cur);
                        break;
                    case "property":
                        if (cur == null) throw new IOException("Propriedade PLY fora de elemento");
                        Prop p = new Prop();
                        if (t[1].equals("list")) {
                            p.list = true;
                            p.countType = plyType(t[2]);
                            p.type = plyType(t[3]);
                            p.name = t[4];
                        } else {
                            p.type = plyType(t[1]);
                            p.name = t[2];
                        }
                        cur.props.add(p);
                        break;
                    default:
                        break; // comment, obj_info...
                }
                if (t[0].equals("end_header")) break;
            }

            float[] pos = null;
            float[] nrm = null;
            byte[] col = null;
            int nv = 0;
            IntList faces = new IntList(1 << 16);

            for (Element e : elements) {
                if (e.name.equals("vertex")) {
                    if (e.count > 200_000_000L) throw new IOException("Nuvem grande demais");
                    nv = (int) e.count;
                    int np = e.props.size();
                    int[] role = new int[np];
                    boolean hasN = false, hasC = false;
                    for (int i = 0; i < np; i++) {
                        role[i] = roleOf(e.props.get(i).name);
                        if (role[i] >= 3 && role[i] <= 5) hasN = true;
                        if (role[i] >= 6) hasC = true;
                    }
                    pos = new float[nv * 3];
                    if (hasN) nrm = new float[nv * 3];
                    if (hasC) col = new byte[nv * 3];
                    for (int v = 0; v < nv; v++) {
                        for (int i = 0; i < np; i++) {
                            Prop p = e.props.get(i);
                            if (p.list) {
                                int c = (int) readVal(r, p.countType, ascii);
                                for (int k = 0; k < c; k++) readVal(r, p.type, ascii);
                                continue;
                            }
                            double val = readVal(r, p.type, ascii);
                            int ro = role[i];
                            if (ro < 0) continue;
                            if (ro < 3) pos[v * 3 + ro] = (float) val;
                            else if (ro < 6) nrm[v * 3 + ro - 3] = (float) val;
                            else col[v * 3 + ro - 6] = (byte) colorByte(val, p.type);
                        }
                    }
                } else if (e.name.equals("face") || e.name.equals("tristrips")) {
                    int idxProp = -1;
                    for (int i = 0; i < e.props.size(); i++) {
                        Prop p = e.props.get(i);
                        if (p.list && (p.name.equals("vertex_indices") || p.name.equals("vertex_index"))) { idxProp = i; break; }
                    }
                    if (idxProp < 0) {
                        for (int i = 0; i < e.props.size(); i++) if (e.props.get(i).list) { idxProp = i; break; }
                    }
                    boolean strip = e.name.equals("tristrips");
                    int[] tmp = new int[64];
                    for (long f = 0; f < e.count; f++) {
                        for (int i = 0; i < e.props.size(); i++) {
                            Prop p = e.props.get(i);
                            if (!p.list) { readVal(r, p.type, ascii); continue; }
                            int c = (int) readVal(r, p.countType, ascii);
                            if (i != idxProp) {
                                for (int k = 0; k < c; k++) readVal(r, p.type, ascii);
                                continue;
                            }
                            if (c > tmp.length) tmp = new int[c];
                            for (int k = 0; k < c; k++) tmp[k] = (int) readVal(r, p.type, ascii);
                            if (strip) addStrip(faces, tmp, c);
                            else for (int k = 1; k + 1 < c; k++) faces.add3(tmp[0], tmp[k], tmp[k + 1]);
                        }
                    }
                } else {
                    for (long i = 0; i < e.count; i++) {
                        for (Prop p : e.props) {
                            if (p.list) {
                                int c = (int) readVal(r, p.countType, ascii);
                                for (int k = 0; k < c; k++) readVal(r, p.type, ascii);
                            } else {
                                readVal(r, p.type, ascii);
                            }
                        }
                    }
                }
            }

            if (pos == null || nv == 0) throw new IOException("PLY sem vértices");

            Mesh m = new Mesh();
            m.positions = pos;
            m.colors = col;
            m.vertexCount = nv;

            int[] idx = validTriangles(faces, nv);
            if (idx.length == 0) {
                m.pointCloud = true;
                m.normals = nrm;
                m.format = fmtName + " (nuvem de pontos)";
            } else {
                m.indices = idx;
                m.triangleCount = idx.length / 3;
                m.format = fmtName;
                if (nrm != null && normalsUsable(nrm)) m.normals = nrm;
                else m.computeSmoothNormals();
            }
            return m;
        }
    }

    private static int roleOf(String n) {
        switch (n) {
            case "x": return 0;
            case "y": return 1;
            case "z": return 2;
            case "nx": return 3;
            case "ny": return 4;
            case "nz": return 5;
            case "red": case "r": case "diffuse_red": return 6;
            case "green": case "g": case "diffuse_green": return 7;
            case "blue": case "b": case "diffuse_blue": return 8;
            default: return -1;
        }
    }

    private static int colorByte(double v, int type) {
        double c;
        if (type == F32 || type == F64) c = v * 255.0;
        else if (type == U16) c = v / 257.0;
        else c = v;
        if (c < 0) c = 0;
        if (c > 255) c = 255;
        return (int) Math.round(c);
    }

    private static void addStrip(IntList out, int[] s, int c) {
        for (int k = 0; k + 2 < c; k++) {
            int a = s[k], b = s[k + 1], d = s[k + 2];
            if (a < 0 || b < 0 || d < 0) continue; // -1 reinicia a faixa
            if ((k & 1) == 0) out.add3(a, b, d); else out.add3(b, a, d);
        }
    }

    private static int[] validTriangles(IntList f, int nv) {
        int[] d = f.data;
        int n = f.size - f.size % 3;
        int w = 0;
        for (int i = 0; i < n; i += 3) {
            int a = d[i], b = d[i + 1], c = d[i + 2];
            if (a < 0 || b < 0 || c < 0 || a >= nv || b >= nv || c >= nv) continue;
            d[w++] = a; d[w++] = b; d[w++] = c;
        }
        return Arrays.copyOf(d, w);
    }

    private static boolean normalsUsable(float[] n) {
        int step = Math.max(3, (n.length / 3 / 1000) * 3);
        int ok = 0, tot = 0;
        for (int i = 0; i + 2 < n.length; i += step) {
            tot++;
            if (n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2] > 1e-6f) ok++;
        }
        return tot > 0 && ok * 2 > tot;
    }

    // ------------------------------------------------------------------ listas crescentes

    static final class FloatList {
        float[] data;
        int size;
        FloatList(int cap) { data = new float[cap]; }
        void add(float v) {
            if (size == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = v;
        }
    }

    static final class IntList {
        int[] data;
        int size;
        IntList(int cap) { data = new int[cap]; }
        void add3(int a, int b, int c) {
            if (size + 3 > data.length) data = Arrays.copyOf(data, Math.max(data.length * 2, size + 3));
            data[size++] = a; data[size++] = b; data[size++] = c;
        }
    }
}
