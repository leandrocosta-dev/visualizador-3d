package br.com.moviai.visualizador3d;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Gravação da malha (já alinhada) em STL binário ou PLY binário. */
final class Exporter {
    private Exporter() {}

    static void writeStl(Mesh m, OutputStream os) throws IOException {
        if (m.pointCloud) throw new IOException("Nuvem de pontos não tem triângulos para STL. Use PLY.");
        int tris = m.indices != null ? m.indices.length / 3 : m.vertexCount / 3;
        Out out = new Out(os);
        byte[] header = new byte[80];
        byte[] txt = "Visualizador 3D MoviAI".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(txt, 0, header, 0, txt.length);
        out.bytes(header);
        out.i32(tris);
        float[] p = m.positions;
        int[] idx = m.indices;
        int[] v = new int[3];
        for (int t = 0; t < tris; t++) {
            for (int k = 0; k < 3; k++) v[k] = (idx != null ? idx[t * 3 + k] : t * 3 + k) * 3;
            float ux = p[v[1]] - p[v[0]], uy = p[v[1] + 1] - p[v[0] + 1], uz = p[v[1] + 2] - p[v[0] + 2];
            float wx = p[v[2]] - p[v[0]], wy = p[v[2] + 1] - p[v[0] + 1], wz = p[v[2] + 2] - p[v[0] + 2];
            float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
            float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l > 0) { nx /= l; ny /= l; nz /= l; }
            out.f32(nx); out.f32(ny); out.f32(nz);
            for (int k = 0; k < 3; k++) {
                out.f32(p[v[k]]); out.f32(p[v[k] + 1]); out.f32(p[v[k] + 2]);
            }
            out.u16(0);
        }
        out.flush();
    }

    static void writePly(Mesh m, OutputStream os) throws IOException {
        int nv = m.vertexCount;
        int faces = m.pointCloud ? 0 : (m.indices != null ? m.indices.length / 3 : nv / 3);
        boolean hasN = m.normals != null, hasC = m.colors != null;
        StringBuilder h = new StringBuilder();
        h.append("ply\nformat binary_little_endian 1.0\ncomment Visualizador 3D MoviAI\n");
        h.append("element vertex ").append(nv).append('\n');
        h.append("property float x\nproperty float y\nproperty float z\n");
        if (hasN) h.append("property float nx\nproperty float ny\nproperty float nz\n");
        if (hasC) h.append("property uchar red\nproperty uchar green\nproperty uchar blue\n");
        if (faces > 0) h.append("element face ").append(faces).append("\nproperty list uchar int vertex_indices\n");
        h.append("end_header\n");

        Out out = new Out(os);
        out.bytes(h.toString().getBytes(StandardCharsets.US_ASCII));
        float[] p = m.positions;
        for (int i = 0; i < nv; i++) {
            out.f32(p[i * 3]); out.f32(p[i * 3 + 1]); out.f32(p[i * 3 + 2]);
            if (hasN) { out.f32(m.normals[i * 3]); out.f32(m.normals[i * 3 + 1]); out.f32(m.normals[i * 3 + 2]); }
            if (hasC) { out.u8(m.colors[i * 3]); out.u8(m.colors[i * 3 + 1]); out.u8(m.colors[i * 3 + 2]); }
        }
        for (int t = 0; t < faces; t++) {
            out.u8(3);
            for (int k = 0; k < 3; k++) out.i32(m.indices != null ? m.indices[t * 3 + k] : t * 3 + k);
        }
        out.flush();
    }

    /** Escrita little endian com buffer. */
    private static final class Out {
        private final OutputStream os;
        private final ByteBuffer b = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);

        Out(OutputStream os) {
            this.os = new BufferedOutputStream(os, 1 << 16);
        }

        private void need(int n) throws IOException {
            if (b.remaining() < n) drain();
        }

        private void drain() throws IOException {
            os.write(b.array(), 0, b.position());
            b.clear();
        }

        void bytes(byte[] a) throws IOException { drain(); os.write(a); }
        void u8(int v) throws IOException { need(1); b.put((byte) v); }
        void u16(int v) throws IOException { need(2); b.putShort((short) v); }
        void i32(int v) throws IOException { need(4); b.putInt(v); }
        void f32(float v) throws IOException { need(4); b.putFloat(v); }
        void flush() throws IOException { drain(); os.flush(); }
    }
}
