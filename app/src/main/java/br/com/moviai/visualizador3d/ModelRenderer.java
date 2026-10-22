package br.com.moviai.visualizador3d;

import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Renderização OpenGL ES 3.0: câmera orbital, perspectiva/ortográfica, plano de corte, marcadores e captura. */
final class ModelRenderer implements GLSurfaceView.Renderer {

    static final int MODE_SOLID = 0;
    static final int MODE_WIRE = 1;
    static final int MODE_POINTS = 2;

    /** Vistas padrão (Z para cima). Cada linha = eixo da tela (direita, cima, para o observador) em coordenadas da peça. */
    static final float[][] VIEW_FRONT = {{1, 0, 0}, {0, 0, 1}, {0, -1, 0}};
    static final float[][] VIEW_BACK = {{-1, 0, 0}, {0, 0, 1}, {0, 1, 0}};
    static final float[][] VIEW_RIGHT = {{0, 1, 0}, {0, 0, 1}, {1, 0, 0}};
    static final float[][] VIEW_LEFT = {{0, -1, 0}, {0, 0, 1}, {-1, 0, 0}};
    static final float[][] VIEW_TOP = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    static final float[][] VIEW_BOTTOM = {{1, 0, 0}, {0, -1, 0}, {0, 0, -1}};

    interface CaptureCallback {
        void onCaptured(Bitmap bmp);
    }

    private static final String TAG = "Visualizador3D";
    private static final float FOV = 45f;
    private static final float ORTHO_CAM = 10f;

    private static final String VS =
            "#version 300 es\n" +
            "layout(location=0) in vec3 aPos;\n" +
            "layout(location=1) in vec3 aNormal;\n" +
            "layout(location=2) in vec3 aColor;\n" +
            "uniform mat4 uMVP;\n" +
            "uniform mat4 uMV;\n" +
            "uniform float uPointSize;\n" +
            "out vec3 vNormal;\n" +
            "out vec3 vColor;\n" +
            "out vec3 vPos;\n" +
            "void main(){\n" +
            "  gl_Position = uMVP * vec4(aPos, 1.0);\n" +
            "  vNormal = mat3(uMV) * aNormal;\n" +
            "  vColor = aColor;\n" +
            "  vPos = aPos;\n" +
            "  gl_PointSize = uPointSize;\n" +
            "}\n";

    // Luz de farol e iluminação dos dois lados. Com corte ativo, o interior (faces de trás) aparece em laranja.
    private static final String FS =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "in vec3 vNormal;\n" +
            "in vec3 vColor;\n" +
            "in vec3 vPos;\n" +
            "uniform int uLit;\n" +
            "uniform int uClipOn;\n" +
            "uniform vec4 uClip;\n" +
            "out vec4 fragColor;\n" +
            "void main(){\n" +
            "  if (uClipOn == 1 && dot(vec4(vPos, 1.0), uClip) > 0.0) discard;\n" +
            "  vec3 c = vColor;\n" +
            "  if (uClipOn == 1 && !gl_FrontFacing) c = vec3(0.95, 0.55, 0.20);\n" +
            "  if (uLit == 1) {\n" +
            "    float l = length(vNormal);\n" +
            "    float d = l > 1e-6 ? abs(vNormal.z / l) : 1.0;\n" +
            "    c = c * (0.22 + 0.78 * d) + vec3(0.18) * pow(d, 32.0);\n" +
            "  }\n" +
            "  fragColor = vec4(c, 1.0);\n" +
            "}\n";

    private int program;
    private int uMVP, uMV, uPointSize, uLit, uClipOn, uClip;

    // buffers: 0 posição, 1 normal, 2 cor, 3 índices
    private final int[] vbo = new int[4];
    private int lineIbo = 0;
    private int lineCount = 0;

    private Mesh mesh;
    private boolean uploadPending = false;

    private int mode = MODE_SOLID;
    private final float pointSizePx;
    private final float markerSizePx;

    // estado da câmera (protegido por synchronized)
    private final float[] rot = new float[16];
    private float dist = 3f, panX = 0f, panY = 0f;
    private boolean fitPending = true;
    private boolean ortho = false;
    private int width = 1, height = 1;

    // corte e marcadores
    private float[] clip = null;
    private FloatBuffer ovPts, ovPtCol, ovLines, ovLineCol;
    private int ovPtCount, ovLineCount;

    private volatile CaptureCallback captureCallback;

    private final float[] mv = new float[16];
    private final float[] mvp = new float[16];

    private ByteBuffer staging;
    private static final int STAGING_BYTES = 1 << 22;

    ModelRenderer(float density) {
        pointSizePx = Math.max(2f, 2.2f * density);
        markerSizePx = 11f * density;
        resetRotation();
    }

    // ------------------------------------------------------------ chamado pela UI

    synchronized void setMesh(Mesh m) {
        mesh = m;
        uploadPending = true;
        clip = null;
        clearOverlayLocked();
        resetRotation();
        panX = panY = 0;
        fitPending = true;
    }

    /** A malha foi transformada (alinhamento): reenvia para a GPU e reenquadra. */
    synchronized void meshChanged() {
        uploadPending = true;
        panX = panY = 0;
        fitPending = true;
    }

    synchronized void setMode(int m) {
        mode = m;
    }

    synchronized void setOrtho(boolean o) {
        ortho = o;
    }

    synchronized void resetView() {
        resetRotation();
        panX = panY = 0;
        fitPending = true;
    }

    synchronized void setView(float[][] rows) {
        Matrix.setIdentityM(rot, 0);
        for (int i = 0; i < 3; i++) {
            rot[i] = rows[i][0];
            rot[4 + i] = rows[i][1];
            rot[8 + i] = rows[i][2];
        }
        panX = panY = 0;
        fitPending = true;
    }

    /** Plano (a,b,c,d) em coordenadas da peça; descarta onde a*x+b*y+c*z+d > 0. Null desliga. */
    synchronized void setClip(float[] plane) {
        clip = plane == null ? null : plane.clone();
    }

    synchronized float[] getClip() {
        return clip == null ? null : clip.clone();
    }

    /** Marcadores em coordenadas da peça: pontos e segmentos (pares de pontos), com cor RGB por vértice. */
    synchronized void setOverlay(float[] pts, float[] ptCol, float[] lines, float[] lineCol) {
        ovPts = fb(pts);
        ovPtCol = fb(ptCol);
        ovLines = fb(lines);
        ovLineCol = fb(lineCol);
        ovPtCount = pts == null ? 0 : pts.length / 3;
        ovLineCount = lines == null ? 0 : lines.length / 3;
    }

    synchronized void clearOverlay() {
        clearOverlayLocked();
    }

    private void clearOverlayLocked() {
        ovPts = ovPtCol = ovLines = ovLineCol = null;
        ovPtCount = ovLineCount = 0;
    }

    void capture(CaptureCallback cb) {
        captureCallback = cb;
    }

    private static FloatBuffer fb(float[] a) {
        if (a == null || a.length == 0) return null;
        FloatBuffer b = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(a).position(0);
        return b;
    }

    /** Vista isométrica, assumindo Z para cima (padrão CAD e scanner). */
    private void resetRotation() {
        Matrix.setIdentityM(rot, 0);
        Matrix.rotateM(rot, 0, -60f, 1f, 0f, 0f);
        Matrix.rotateM(rot, 0, -35f, 0f, 0f, 1f);
    }

    synchronized void rotate(float dxPx, float dyPx) {
        float len = (float) Math.sqrt(dxPx * dxPx + dyPx * dyPx);
        if (len < 1e-3f) return;
        float deg = len * 200f / Math.min(width, height);
        float[] r = new float[16];
        float[] o = new float[16];
        Matrix.setRotateM(r, 0, deg, dyPx / len, dxPx / len, 0f);
        Matrix.multiplyMM(o, 0, r, 0, rot, 0);
        System.arraycopy(o, 0, rot, 0, 16);
        orthonormalize();
    }

    synchronized void pan(float dxPx, float dyPx) {
        float wpp = (float) (2.0 * dist * Math.tan(Math.toRadians(FOV / 2)) / height);
        panX += dxPx * wpp;
        panY -= dyPx * wpp;
    }

    synchronized void zoom(float factor) {
        if (factor <= 0) return;
        dist /= factor;
        if (dist < 0.02f) dist = 0.02f;
        if (dist > 200f) dist = 200f;
    }

    /** Matriz MVP atual e tamanho da tela, para o picking. out[16] e size[2]. */
    synchronized boolean snapshot(Mesh m, float[] mvpOut, int[] size) {
        if (m == null) return false;
        computeLocked(m, mvpOut, new float[16]);
        size[0] = width;
        size[1] = height;
        return true;
    }

    private void computeLocked(Mesh m, float[] mvpOut, float[] mvOut) {
        float aspect = (float) width / height;
        float t = (float) Math.tan(Math.toRadians(FOV / 2));
        if (fitPending) {
            dist = 1.15f / (t * Math.min(1f, aspect));
            fitPending = false;
        }
        float[] proj = new float[16];
        float[] view = new float[16];
        float[] model = new float[16];
        float[] tmp = new float[16];
        if (ortho) {
            float hh = dist * t;
            Matrix.orthoM(proj, 0, -hh * aspect, hh * aspect, -hh, hh, 0.01f, ORTHO_CAM * 2f);
        } else {
            Matrix.perspectiveM(proj, 0, FOV, aspect, Math.max(0.001f, dist * 0.01f), dist + 10f);
        }
        Matrix.setIdentityM(view, 0);
        Matrix.translateM(view, 0, panX, panY, ortho ? -ORTHO_CAM : -dist);
        float s = 1f / m.radius;
        Matrix.setIdentityM(model, 0);
        Matrix.scaleM(model, 0, s, s, s);
        Matrix.translateM(model, 0, -m.center[0], -m.center[1], -m.center[2]);
        Matrix.multiplyMM(tmp, 0, rot, 0, model, 0);
        Matrix.multiplyMM(mvOut, 0, view, 0, tmp, 0);
        Matrix.multiplyMM(mvpOut, 0, proj, 0, mvOut, 0);
    }

    /** Evita acúmulo de erro numérico na matriz de rotação. */
    private void orthonormalize() {
        float[] r = rot;
        float l = (float) Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
        r[0] /= l; r[1] /= l; r[2] /= l;
        float d = r[0] * r[4] + r[1] * r[5] + r[2] * r[6];
        r[4] -= d * r[0]; r[5] -= d * r[1]; r[6] -= d * r[2];
        l = (float) Math.sqrt(r[4] * r[4] + r[5] * r[5] + r[6] * r[6]);
        r[4] /= l; r[5] /= l; r[6] /= l;
        r[8] = r[1] * r[6] - r[2] * r[5];
        r[9] = r[2] * r[4] - r[0] * r[6];
        r[10] = r[0] * r[5] - r[1] * r[4];
    }

    // ------------------------------------------------------------ GL

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        program = buildProgram(VS, FS);
        uMVP = GLES30.glGetUniformLocation(program, "uMVP");
        uMV = GLES30.glGetUniformLocation(program, "uMV");
        uPointSize = GLES30.glGetUniformLocation(program, "uPointSize");
        uLit = GLES30.glGetUniformLocation(program, "uLit");
        uClipOn = GLES30.glGetUniformLocation(program, "uClipOn");
        uClip = GLES30.glGetUniformLocation(program, "uClip");

        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glClearColor(0.11f, 0.13f, 0.16f, 1f);

        // contexto novo: buffers antigos não existem mais
        vbo[0] = vbo[1] = vbo[2] = vbo[3] = 0;
        lineIbo = 0;
        lineCount = 0;
        synchronized (this) {
            if (mesh != null) uploadPending = true;
        }
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        GLES30.glViewport(0, 0, w, h);
        synchronized (this) {
            width = Math.max(1, w);
            height = Math.max(1, h);
        }
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        Mesh m;
        int curMode;
        boolean doUpload;
        float[] curClip;
        FloatBuffer oPts, oPtCol, oLines, oLineCol;
        int oPtN, oLineN;
        synchronized (this) {
            m = mesh;
            curMode = mode;
            doUpload = uploadPending;
            uploadPending = false;
            curClip = clip;
            oPts = ovPts; oPtCol = ovPtCol; oLines = ovLines; oLineCol = ovLineCol;
            oPtN = ovPtCount; oLineN = ovLineCount;
            if (m != null) computeLocked(m, mvp, mv);
        }

        if (doUpload && m != null) upload(m);

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        if (m != null && vbo[0] != 0) {
            drawMesh(m, curMode, curClip);
            drawOverlay(oPts, oPtCol, oPtN, oLines, oLineCol, oLineN);
        }

        CaptureCallback cb = captureCallback;
        if (cb != null) {
            captureCallback = null;
            cb.onCaptured(readPixels());
        }
    }

    private void drawMesh(Mesh m, int curMode, float[] curClip) {
        boolean points = m.pointCloud || curMode == MODE_POINTS;
        boolean wire = !points && curMode == MODE_WIRE;

        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glUseProgram(program);
        GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES30.glUniformMatrix4fv(uMV, 1, false, mv, 0);
        GLES30.glUniform1f(uPointSize, pointSizePx);
        if (curClip != null) {
            GLES30.glUniform1i(uClipOn, 1);
            GLES30.glUniform4f(uClip, curClip[0], curClip[1], curClip[2], curClip[3]);
        } else {
            GLES30.glUniform1i(uClipOn, 0);
        }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0]);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0);

        boolean lit = !wire && vbo[1] != 0;
        if (lit) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[1]);
            GLES30.glEnableVertexAttribArray(1);
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 0, 0);
        } else {
            GLES30.glDisableVertexAttribArray(1);
            GLES30.glVertexAttrib3f(1, 0f, 0f, 1f);
        }

        if (!wire && vbo[2] != 0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[2]);
            GLES30.glEnableVertexAttribArray(2);
            GLES30.glVertexAttribPointer(2, 3, GLES30.GL_UNSIGNED_BYTE, true, 0, 0);
        } else {
            GLES30.glDisableVertexAttribArray(2);
            if (wire) GLES30.glVertexAttrib3f(2, 0.45f, 0.78f, 1.0f);
            else GLES30.glVertexAttrib3f(2, 0.74f, 0.76f, 0.80f);
        }
        GLES30.glUniform1i(uLit, lit ? 1 : 0);

        if (points) {
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, m.vertexCount);
        } else if (wire) {
            if (lineIbo == 0) buildLines(m);
            if (lineIbo != 0) {
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, lineIbo);
                GLES30.glDrawElements(GLES30.GL_LINES, lineCount, GLES30.GL_UNSIGNED_INT, 0);
            }
        } else if (vbo[3] != 0) {
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[3]);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.indices.length, GLES30.GL_UNSIGNED_INT, 0);
        } else {
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, m.vertexCount);
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    /** Marcadores sempre visíveis (sem teste de profundidade), a partir de arrays do cliente. */
    private void drawOverlay(FloatBuffer pts, FloatBuffer ptCol, int ptN,
                             FloatBuffer lines, FloatBuffer lineCol, int lineN) {
        if (ptN == 0 && lineN == 0) return;
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glUniform1i(uLit, 0);
        GLES30.glUniform1i(uClipOn, 0);
        GLES30.glDisableVertexAttribArray(1);
        GLES30.glVertexAttrib3f(1, 0f, 0f, 1f);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glEnableVertexAttribArray(2);

        if (lineN > 0) {
            GLES30.glLineWidth(3f);
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, lines);
            GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, 0, lineCol);
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, lineN);
        }
        if (ptN > 0) {
            GLES30.glUniform1f(uPointSize, markerSizePx);
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, pts);
            GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, 0, ptCol);
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, ptN);
        }
        GLES30.glDisableVertexAttribArray(2);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
    }

    private Bitmap readPixels() {
        int w, h;
        synchronized (this) {
            w = width;
            h = height;
        }
        IntBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1);
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf);
        int[] src = new int[w * h];
        buf.get(src);
        int[] dst = new int[w * h];
        boolean little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
        for (int y = 0; y < h; y++) {
            int so = y * w, d0 = (h - 1 - y) * w;
            for (int x = 0; x < w; x++) {
                int px = src[so + x];
                int r, g, b;
                if (little) { r = px & 0xFF; g = (px >> 8) & 0xFF; b = (px >> 16) & 0xFF; }
                else { r = (px >>> 24) & 0xFF; g = (px >> 16) & 0xFF; b = (px >> 8) & 0xFF; }
                dst[d0 + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(dst, w, h, Bitmap.Config.ARGB_8888);
    }

    private void upload(Mesh m) {
        int[] old = {vbo[0], vbo[1], vbo[2], vbo[3], lineIbo};
        for (int id : old) {
            if (id != 0) GLES30.glDeleteBuffers(1, new int[]{id}, 0);
        }
        vbo[0] = vbo[1] = vbo[2] = vbo[3] = 0;
        lineIbo = 0;
        lineCount = 0;

        if (staging == null) staging = ByteBuffer.allocateDirect(STAGING_BYTES).order(ByteOrder.nativeOrder());

        vbo[0] = genBuffer();
        uploadFloats(GLES30.GL_ARRAY_BUFFER, vbo[0], m.positions, m.vertexCount * 3);
        if (m.normals != null) {
            vbo[1] = genBuffer();
            uploadFloats(GLES30.GL_ARRAY_BUFFER, vbo[1], m.normals, m.vertexCount * 3);
        }
        if (m.colors != null) {
            vbo[2] = genBuffer();
            uploadBytes(vbo[2], m.colors);
        }
        if (m.indices != null && m.indices.length > 0) {
            vbo[3] = genBuffer();
            uploadInts(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[3], m.indices, m.indices.length);
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    /** Arestas geradas sob demanda só quando o modo arame é usado. */
    private void buildLines(Mesh m) {
        int tris = m.indices != null ? m.indices.length / 3 : m.vertexCount / 3;
        int[] lines;
        try {
            lines = new int[tris * 6];
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Sem memória para o modo arame", e);
            return;
        }
        for (int t = 0; t < tris; t++) {
            int a, b, c;
            if (m.indices != null) {
                a = m.indices[t * 3]; b = m.indices[t * 3 + 1]; c = m.indices[t * 3 + 2];
            } else {
                a = t * 3; b = a + 1; c = a + 2;
            }
            int o = t * 6;
            lines[o] = a; lines[o + 1] = b;
            lines[o + 2] = b; lines[o + 3] = c;
            lines[o + 4] = c; lines[o + 5] = a;
        }
        lineIbo = genBuffer();
        uploadInts(GLES30.GL_ELEMENT_ARRAY_BUFFER, lineIbo, lines, lines.length);
        lineCount = lines.length;
    }

    private static int genBuffer() {
        int[] id = new int[1];
        GLES30.glGenBuffers(1, id, 0);
        return id[0];
    }

    private void uploadFloats(int target, int id, float[] a, int count) {
        GLES30.glBindBuffer(target, id);
        GLES30.glBufferData(target, count * 4, null, GLES30.GL_STATIC_DRAW);
        int step = STAGING_BYTES / 4;
        for (int off = 0; off < count; off += step) {
            int n = Math.min(step, count - off);
            staging.clear();
            staging.asFloatBuffer().put(a, off, n);
            staging.position(0);
            GLES30.glBufferSubData(target, off * 4, n * 4, staging);
        }
    }

    private void uploadInts(int target, int id, int[] a, int count) {
        GLES30.glBindBuffer(target, id);
        GLES30.glBufferData(target, count * 4, null, GLES30.GL_STATIC_DRAW);
        int step = STAGING_BYTES / 4;
        for (int off = 0; off < count; off += step) {
            int n = Math.min(step, count - off);
            staging.clear();
            staging.asIntBuffer().put(a, off, n);
            staging.position(0);
            GLES30.glBufferSubData(target, off * 4, n * 4, staging);
        }
    }

    private void uploadBytes(int id, byte[] a) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, a.length, null, GLES30.GL_STATIC_DRAW);
        for (int off = 0; off < a.length; off += STAGING_BYTES) {
            int n = Math.min(STAGING_BYTES, a.length - off);
            staging.clear();
            staging.put(a, off, n);
            staging.position(0);
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, off, n, staging);
        }
    }

    private static int buildProgram(String vs, String fs) {
        int v = compile(GLES30.GL_VERTEX_SHADER, vs);
        int f = compile(GLES30.GL_FRAGMENT_SHADER, fs);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "Erro ao ligar shaders: " + GLES30.glGetProgramInfoLog(p));
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "Erro no shader: " + GLES30.glGetShaderInfoLog(s));
        return s;
    }
}
