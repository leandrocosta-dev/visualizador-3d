package br.com.moviai.visualizador3d;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Peça em escala real (arquivo em mm) sobre uma superfície detectada pela câmera (ARCore). */
public class ArActivity extends Activity implements GLSurfaceView.Renderer {

    /** Malha a exibir, entregue pela tela principal. */
    static volatile Mesh pending;

    private static final String TAG = "Visualizador3D-AR";
    private static final int REQ_CAMERA = 10;

    private GLSurfaceView surface;
    private TextView status;
    private Session session;
    private boolean installRequested = false;
    private Mesh mesh;

    private final ConcurrentLinkedQueue<float[]> taps = new ConcurrentLinkedQueue<>();
    private volatile float yaw = 0f;
    private volatile boolean displayChanged = true;
    private int viewW = 1, viewH = 1;

    // GL
    private int camTex;
    private int bgProg, meshProg;
    private int bgPos, bgUv, bgTex;
    private int mPos, mNrm, mCol, mMvp, mMv;
    private final int[] vbo = new int[4];
    private FloatBuffer quadNdc, quadUv;
    private Anchor anchor;
    private boolean everTracked = false;
    private String lastStatus = "";

    // gesto de giro com dois dedos
    private float lastAngle = Float.NaN;
    private float downX, downY;
    private long downTime;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        mesh = pending;
        if (mesh == null) {
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        surface = new GLSurfaceView(this);
        surface.setPreserveEGLContextOnPause(true);
        surface.setEGLContextClientVersion(3);
        surface.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        surface.setRenderer(this);
        surface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        surface.setOnTouchListener((v, e) -> onTouch(e));
        root.addView(surface);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setShadowLayer(5f, 0f, 1f, 0xFF000000);
        status.setPadding(dp(16), dp(16), dp(16), dp(16));
        status.setText("Iniciando a câmera...");
        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        Button close = new Button(this);
        close.setText("Fechar");
        close.setAllCaps(false);
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(0, 0, 0, dp(36));
        root.addView(close, lp);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            status.setPadding(dp(16), dp(16) + insets.getSystemWindowInsetTop(), dp(16), dp(16));
            return insets;
        });
        setContentView(root);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String s) {
        if (s.equals(lastStatus)) return;
        lastStatus = s;
        runOnUiThread(() -> status.setText(s));
    }

    // ================================================================ ciclo de vida

    @Override
    protected void onResume() {
        super.onResume();
        if (mesh == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        try {
            if (session == null) {
                ArCoreApk.InstallStatus st = ArCoreApk.getInstance().requestInstall(this, !installRequested);
                if (st == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true;
                    return;
                }
                session = new Session(this);
                Config c = new Config(session);
                c.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL);
                c.setFocusMode(Config.FocusMode.AUTO);
                c.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
                session.configure(c);
            }
            session.resume();
        } catch (Exception e) {
            Log.e(TAG, "ARCore indisponível", e);
            fail(friendly(e));
            return;
        }
        surface.onResume();
        displayChanged = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (session != null) {
            surface.onPause();
            session.pause();
        }
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_CAMERA && (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED)) {
            fail("A realidade aumentada precisa da permissão de câmera.");
        }
    }

    private String friendly(Exception e) {
        String n = e.getClass().getSimpleName();
        if (n.contains("DeviceNotCompatible")) return "Este celular não é compatível com o ARCore do Google.";
        if (n.contains("NotInstalled") || n.contains("ApkTooOld")) return "Instale ou atualize o \"Google Play Services para RA\" na Play Store.";
        if (n.contains("SdkTooOld")) return "Atualize o app Visualizador 3D.";
        if (n.contains("UserDeclinedInstallation")) return "A instalação do Google Play Services para RA foi recusada.";
        if (n.contains("CameraNotAvailable")) return "A câmera está em uso por outro app.";
        return "Não foi possível iniciar a realidade aumentada: " + n;
    }

    private void fail(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        finish();
    }

    // ================================================================ toque

    private boolean onTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downTime = e.getEventTime();
                lastAngle = Float.NaN;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                lastAngle = Float.NaN;
                downTime = 0; // não é toque simples
                break;
            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() >= 2) {
                    float a = (float) Math.toDegrees(Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)));
                    if (!Float.isNaN(lastAngle)) {
                        float d = a - lastAngle;
                        if (d > 180) d -= 360;
                        if (d < -180) d += 360;
                        yaw -= d;
                    }
                    lastAngle = a;
                }
                break;
            case MotionEvent.ACTION_UP:
                float mx = e.getX() - downX, my = e.getY() - downY;
                if (downTime != 0 && e.getEventTime() - downTime < 400 && mx * mx + my * my < dp(16) * dp(16)) {
                    taps.add(new float[]{e.getX(), e.getY()});
                }
                break;
            default:
                break;
        }
        return true;
    }

    // ================================================================ GL

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        GLES30.glClearColor(0, 0, 0, 1);
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        camTex = t[0];
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);

        bgProg = program(
                "attribute vec2 aPos;\nattribute vec2 aUv;\nvarying vec2 vUv;\n" +
                "void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vUv = aUv; }\n",
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\nvarying vec2 vUv;\nuniform samplerExternalOES uTex;\n" +
                "void main(){ gl_FragColor = texture2D(uTex, vUv); }\n");
        bgPos = GLES30.glGetAttribLocation(bgProg, "aPos");
        bgUv = GLES30.glGetAttribLocation(bgProg, "aUv");
        bgTex = GLES30.glGetUniformLocation(bgProg, "uTex");

        meshProg = program(
                "uniform mat4 uMvp;\nuniform mat4 uMv;\nattribute vec3 aPos;\nattribute vec3 aNrm;\nattribute vec3 aCol;\n" +
                "varying vec3 vN;\nvarying vec3 vC;\n" +
                "void main(){ gl_Position = uMvp * vec4(aPos, 1.0); vN = mat3(uMv) * aNrm; vC = aCol; }\n",
                "precision mediump float;\nvarying vec3 vN;\nvarying vec3 vC;\n" +
                "void main(){ float l = length(vN); float d = l > 1e-6 ? abs(vN.z / l) : 1.0;\n" +
                "  gl_FragColor = vec4(vC * (0.35 + 0.65 * d) + vec3(0.12) * pow(d, 24.0), 1.0); }\n");
        mPos = GLES30.glGetAttribLocation(meshProg, "aPos");
        mNrm = GLES30.glGetAttribLocation(meshProg, "aNrm");
        mCol = GLES30.glGetAttribLocation(meshProg, "aCol");
        mMvp = GLES30.glGetUniformLocation(meshProg, "uMvp");
        mMv = GLES30.glGetUniformLocation(meshProg, "uMv");

        quadNdc = fb(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
        quadUv = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        uploadMesh();
        displayChanged = true;
    }

    private void uploadMesh() {
        Mesh m = mesh;
        GLES30.glGenBuffers(4, vbo, 0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0]);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.vertexCount * 12, fb(m.positions, m.vertexCount * 3), GLES30.GL_STATIC_DRAW);
        if (m.normals != null) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[1]);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.vertexCount * 12, fb(m.normals, m.vertexCount * 3), GLES30.GL_STATIC_DRAW);
        }
        if (m.colors != null) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[2]);
            ByteBuffer cb = ByteBuffer.allocateDirect(m.vertexCount * 3).order(ByteOrder.nativeOrder());
            cb.put(m.colors, 0, m.vertexCount * 3).position(0);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.vertexCount * 3, cb, GLES30.GL_STATIC_DRAW);
        }
        if (m.indices != null) {
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[3]);
            IntBuffer ib = ByteBuffer.allocateDirect(m.indices.length * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
            ib.put(m.indices).position(0);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.indices.length * 4, ib, GLES30.GL_STATIC_DRAW);
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES30.glViewport(0, 0, w, h);
        viewW = w;
        viewH = h;
        displayChanged = true;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        Session s = session;
        if (s == null) return;
        try {
            if (displayChanged) {
                int rot = getWindowManager().getDefaultDisplay().getRotation();
                s.setDisplayGeometry(rot, viewW, viewH);
                displayChanged = false;
            }
            s.setCameraTextureName(camTex);
            Frame frame = s.update();
            Camera cam = frame.getCamera();

            if (frame.hasDisplayGeometryChanged()) {
                quadNdc.position(0);
                quadUv.position(0);
                frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadNdc,
                        Coordinates2d.TEXTURE_NORMALIZED, quadUv);
            }
            if (frame.getTimestamp() == 0) return;
            drawBackground();

            if (cam.getTrackingState() != TrackingState.TRACKING) {
                setStatus(everTracked ? "Rastreamento perdido. Aponte para a mesa e mova devagar."
                        : "Mova o celular devagar, apontando para a mesa, até ela ser reconhecida.");
                return;
            }
            everTracked = true;

            float[] tap = taps.poll();
            if (tap != null) place(frame, tap[0], tap[1]);

            boolean anyPlane = false;
            for (Plane p : s.getAllTrackables(Plane.class)) {
                if (p.getTrackingState() == TrackingState.TRACKING && p.getSubsumedBy() == null) { anyPlane = true; break; }
            }
            if (anchor == null) {
                setStatus(anyPlane ? "Superfície encontrada. Toque nela para colocar a peça em escala real."
                        : "Mova o celular devagar, apontando para a mesa, até ela ser reconhecida.");
                return;
            }
            if (anchor.getTrackingState() != TrackingState.TRACKING) return;
            setStatus(String.format(new java.util.Locale("pt", "BR"),
                    "Escala real (%.0f × %.0f × %.0f mm). Dois dedos giram; toque em outro ponto para mover.",
                    mesh.max[0] - mesh.min[0], mesh.max[1] - mesh.min[1], mesh.max[2] - mesh.min[2]));

            float[] proj = new float[16], view = new float[16];
            cam.getProjectionMatrix(proj, 0, 0.01f, 50f);
            cam.getViewMatrix(view, 0);
            drawMesh(proj, view);
        } catch (Exception e) {
            Log.e(TAG, "Erro no quadro AR", e);
        }
    }

    private void place(Frame frame, float x, float y) {
        for (HitResult hit : frame.hitTest(x, y)) {
            Trackable t = hit.getTrackable();
            if (t instanceof Plane && ((Plane) t).isPoseInPolygon(hit.getHitPose())
                    && ((Plane) t).getType() == Plane.Type.HORIZONTAL_UPWARD_FACING) {
                if (anchor != null) anchor.detach();
                anchor = hit.createAnchor();
                return;
            }
        }
    }

    private void drawBackground() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthMask(false);
        GLES30.glUseProgram(bgProg);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex);
        GLES30.glUniform1i(bgTex, 0);
        quadNdc.position(0);
        quadUv.position(0);
        GLES30.glEnableVertexAttribArray(bgPos);
        GLES30.glVertexAttribPointer(bgPos, 2, GLES30.GL_FLOAT, false, 0, quadNdc);
        GLES30.glEnableVertexAttribArray(bgUv);
        GLES30.glVertexAttribPointer(bgUv, 2, GLES30.GL_FLOAT, false, 0, quadUv);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glDisableVertexAttribArray(bgPos);
        GLES30.glDisableVertexAttribArray(bgUv);
        GLES30.glDepthMask(true);
    }

    private void drawMesh(float[] proj, float[] view) {
        Mesh m = mesh;
        // modelo: âncora · giro em Y · Z da peça vira Y (para cima) · mm → m · base da peça no plano
        float[] anchorM = new float[16];
        anchor.getPose().toMatrix(anchorM, 0);
        float[] model = new float[16];
        Matrix.setIdentityM(model, 0);
        Matrix.rotateM(model, 0, yaw, 0, 1, 0);
        Matrix.rotateM(model, 0, -90, 1, 0, 0);
        Matrix.scaleM(model, 0, 0.001f, 0.001f, 0.001f);
        Matrix.translateM(model, 0, -m.center[0], -m.center[1], -m.min[2]);
        float[] world = new float[16], mv = new float[16], mvp = new float[16];
        Matrix.multiplyMM(world, 0, anchorM, 0, model, 0);
        Matrix.multiplyMM(mv, 0, view, 0, world, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0);

        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glUseProgram(meshProg);
        GLES30.glUniformMatrix4fv(mMvp, 1, false, mvp, 0);
        GLES30.glUniformMatrix4fv(mMv, 1, false, mv, 0);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0]);
        GLES30.glEnableVertexAttribArray(mPos);
        GLES30.glVertexAttribPointer(mPos, 3, GLES30.GL_FLOAT, false, 0, 0);
        if (m.normals != null) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[1]);
            GLES30.glEnableVertexAttribArray(mNrm);
            GLES30.glVertexAttribPointer(mNrm, 3, GLES30.GL_FLOAT, false, 0, 0);
        } else {
            GLES30.glDisableVertexAttribArray(mNrm);
            GLES30.glVertexAttrib3f(mNrm, 0, 0, 1);
        }
        if (m.colors != null) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[2]);
            GLES30.glEnableVertexAttribArray(mCol);
            GLES30.glVertexAttribPointer(mCol, 3, GLES30.GL_UNSIGNED_BYTE, true, 0, 0);
        } else {
            GLES30.glDisableVertexAttribArray(mCol);
            GLES30.glVertexAttrib3f(mCol, 0.78f, 0.80f, 0.84f);
        }
        if (m.pointCloud) {
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, m.vertexCount);
        } else if (m.indices != null) {
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[3]);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.indices.length, GLES30.GL_UNSIGNED_INT, 0);
        } else {
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, m.vertexCount);
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
        GLES30.glDisableVertexAttribArray(mPos);
        GLES30.glDisableVertexAttribArray(mNrm);
        GLES30.glDisableVertexAttribArray(mCol);
    }

    private static FloatBuffer fb(float[] a) {
        return fb(a, a.length);
    }

    private static FloatBuffer fb(float[] a, int n) {
        FloatBuffer b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(a, 0, n).position(0);
        return b;
    }

    private static int program(String vs, String fs) {
        int v = shader(GLES30.GL_VERTEX_SHADER, vs), f = shader(GLES30.GL_FRAGMENT_SHADER, fs);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "link: " + GLES30.glGetProgramInfoLog(p));
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "shader: " + GLES30.glGetShaderInfoLog(s));
        return s;
    }
}
