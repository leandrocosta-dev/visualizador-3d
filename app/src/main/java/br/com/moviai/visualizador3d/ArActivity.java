package br.com.moviai.visualizador3d;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.LinearLayout;
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

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
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
    /** Escala do usuário: 1 = tamanho real; menor reduz, maior amplia. */
    private volatile float userScale = 1f;
    private float lastDist = Float.NaN;
    /** Cor da peça: -1 = cores originais da malha (ou mapa de desvio/espessura); senão índice em SWATCHES. */
    private volatile int colorIndex = 0;
    private static final String[] SWATCH_NAMES = {"Azul", "Laranja", "Verde", "Vermelho", "Cinza", "Branco"};
    private static final float[][] SWATCHES = {
            {0.20f, 0.52f, 0.95f}, {1.00f, 0.50f, 0.10f}, {0.20f, 0.78f, 0.35f},
            {0.90f, 0.20f, 0.18f}, {0.45f, 0.48f, 0.53f}, {0.95f, 0.95f, 0.95f}};
    private static final int[] SWATCH_RGB = {0xFF3385F2, 0xFFFF801A, 0xFF33C759, 0xFFE63330, 0xFF737A87, 0xFFF2F2F2};
    private volatile boolean displayChanged = true;
    private int viewW = 1, viewH = 1;

    // GL
    private int camTex;
    private int bgProg, meshProg;
    private int bgPos, bgUv, bgTex;
    private int mPos, mNrm, mCol, mMvp, mMv, mL, mUp, mTint, mUseTint;
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
        surface.setEGLConfigChooser(new AaChooser());
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

        colorIndex = mesh.colors != null ? -1 : 0;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x99000000);
        pill.setCornerRadius(dp(24));
        row.setBackground(pill);
        final View[] dots = new View[SWATCHES.length + 1];
        Runnable refresh = () -> {
            for (int i = 0; i < dots.length; i++) {
                if (dots[i] == null) continue;
                int idx = i - 1;
                GradientDrawable g = new GradientDrawable();
                g.setShape(idx < 0 ? GradientDrawable.RECTANGLE : GradientDrawable.OVAL);
                if (idx < 0) g.setCornerRadius(dp(14));
                g.setColor(idx < 0 ? 0xFF455A64 : SWATCH_RGB[idx]);
                g.setStroke(dp(colorIndex == idx ? 3 : 1), colorIndex == idx ? 0xFFFFFFFF : 0x66FFFFFF);
                dots[i].setBackground(g);
            }
        };
        if (mesh.colors != null) {
            TextView orig = new TextView(this);
            orig.setText("Original");
            orig.setTextColor(Color.WHITE);
            orig.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            orig.setGravity(Gravity.CENTER);
            orig.setPadding(dp(10), 0, dp(10), 0);
            orig.setOnClickListener(v -> { colorIndex = -1; refresh.run(); });
            dots[0] = orig;
            row.addView(orig, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        }
        for (int i = 0; i < SWATCHES.length; i++) {
            final int k = i;
            View d = new View(this);
            d.setContentDescription(SWATCH_NAMES[i]);
            d.setOnClickListener(v -> { colorIndex = k; refresh.run(); });
            dots[i + 1] = d;
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(30), dp(30));
            dl.setMargins(dp(5), 0, dp(5), 0);
            row.addView(d, dl);
        }
        refresh.run();
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        rl.setMargins(0, 0, 0, dp(96));
        root.addView(row, rl);

        LinearLayout sRow = new LinearLayout(this);
        sRow.setOrientation(LinearLayout.HORIZONTAL);
        sRow.setGravity(Gravity.CENTER);
        sRow.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable sPill = new GradientDrawable();
        sPill.setColor(0x99000000);
        sPill.setCornerRadius(dp(24));
        sRow.setBackground(sPill);
        String[] sl = {"−", "1:1 real", "+"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            Button sb = new Button(this);
            sb.setText(sl[i]);
            sb.setAllCaps(false);
            sb.setTextColor(Color.WHITE);
            sb.setTextSize(TypedValue.COMPLEX_UNIT_SP, i == 1 ? 14 : 20);
            sb.setBackground(null);
            sb.setMinWidth(0);
            sb.setMinimumWidth(0);
            sb.setPadding(dp(14), 0, dp(14), 0);
            sb.setOnClickListener(v -> {
                if (k == 0) setScale(userScale / 1.5f);
                else if (k == 1) setScale(1f);
                else setScale(userScale * 1.5f);
            });
            sRow.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        }
        FrameLayout.LayoutParams sl2 = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sl2.setMargins(0, 0, 0, dp(150));
        root.addView(sRow, sl2);

        Button close = new Button(this);
        close.setText("Fechar");
        close.setAllCaps(false);
        close.setOnClickListener(v -> finish());
        Button shot = new Button(this);
        shot.setText("Foto");
        shot.setAllCaps(false);
        shot.setOnClickListener(v -> photoRequested = true);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.setMargins(dp(6), 0, dp(6), 0);
        bar.addView(shot, bp);
        bar.addView(close, bp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(0, 0, 0, dp(36));
        root.addView(bar, lp);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            status.setPadding(dp(16), dp(16) + insets.getSystemWindowInsetTop(), dp(16), dp(16));
            return insets;
        });
        setContentView(root);
    }

    /** Prefere 24 bits de profundidade e antisserrilhado 4x; cai para configurações mais simples se faltar. */
    private static final class AaChooser implements GLSurfaceView.EGLConfigChooser {
        @Override
        public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            int[][] tries = {{24, 4}, {16, 4}, {24, 0}, {16, 0}};
            for (int[] t : tries) {
                int[] attr = {
                        EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                        EGL10.EGL_DEPTH_SIZE, t[0],
                        EGL10.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT
                        EGL10.EGL_SAMPLE_BUFFERS, t[1] > 0 ? 1 : 0,
                        EGL10.EGL_SAMPLES, t[1],
                        EGL10.EGL_NONE};
                EGLConfig[] cfg = new EGLConfig[1];
                int[] n = new int[1];
                if (egl.eglChooseConfig(display, attr, cfg, 1, n) && n[0] > 0) return cfg[0];
            }
            throw new IllegalStateException("Nenhuma configuração EGL");
        }
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

    private void setScale(float v) {
        userScale = Math.max(0.02f, Math.min(50f, v));
    }

    private boolean onTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downTime = e.getEventTime();
                lastAngle = Float.NaN;
                lastDist = Float.NaN;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                lastAngle = Float.NaN;
                lastDist = Float.NaN;
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
                    float dx = e.getX(1) - e.getX(0), dy = e.getY(1) - e.getY(0);
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    if (!Float.isNaN(lastDist) && lastDist > 1f) {
                        setScale(userScale * dist / lastDist);
                    }
                    lastDist = dist;
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
                "varying vec3 vN;\nvarying vec3 vV;\nvarying vec3 vC;\n" +
                "void main(){\n" +
                "  gl_Position = uMvp * vec4(aPos, 1.0);\n" +
                "  vec3 vp = (uMv * vec4(aPos, 1.0)).xyz;\n" +
                "  vec3 v = normalize(-vp);\n" +
                "  vec3 n = mat3(uMv) * aNrm;\n" +
                "  float ln = length(n);\n" +
                "  n = ln > 1e-9 ? n / ln : v;\n" +
                "  if (dot(n, v) < 0.0) n = -n;\n" +
                "  vN = n; vV = v; vC = aCol;\n" +
                "}\n",
                "precision mediump float;\nvarying vec3 vN;\nvarying vec3 vV;\nvarying vec3 vC;\n" +
                "uniform vec3 uL;\nuniform vec3 uUp;\nuniform vec3 uTint;\nuniform float uUseTint;\n" +
                "void main(){\n" +
                "  vec3 n = normalize(vN); vec3 v = normalize(vV);\n" +
                "  vec3 base = mix(vC, uTint, uUseTint);\n" +
                "  float diff = max(dot(n, uL), 0.0);\n" +
                "  float head = max(dot(n, v), 0.0);\n" +
                "  float hemi = 0.5 + 0.5 * dot(n, uUp);\n" +
                "  float spec = pow(max(dot(n, normalize(uL + v)), 0.0), 48.0);\n" +
                "  vec3 c = base * (0.18 + 0.22 * hemi + 0.50 * diff + 0.18 * head) + vec3(0.22) * spec;\n" +
                "  gl_FragColor = vec4(c, 1.0);\n" +
                "}\n");
        mPos = GLES30.glGetAttribLocation(meshProg, "aPos");
        mNrm = GLES30.glGetAttribLocation(meshProg, "aNrm");
        mCol = GLES30.glGetAttribLocation(meshProg, "aCol");
        mMvp = GLES30.glGetUniformLocation(meshProg, "uMvp");
        mMv = GLES30.glGetUniformLocation(meshProg, "uMv");
        mL = GLES30.glGetUniformLocation(meshProg, "uL");
        mUp = GLES30.glGetUniformLocation(meshProg, "uUp");
        mTint = GLES30.glGetUniformLocation(meshProg, "uTint");
        mUseTint = GLES30.glGetUniformLocation(meshProg, "uUseTint");

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
        boolean drew = false;
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
            drew = true;

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
            float us = userScale;
            if (Math.abs(us - 1f) < 0.005f) {
                setStatus(String.format(new java.util.Locale("pt", "BR"),
                        "Escala real 1:1 (%.0f × %.0f × %.0f mm). Dois dedos giram e ampliam; toque em outro ponto para mover.",
                        mesh.max[0] - mesh.min[0], mesh.max[1] - mesh.min[1], mesh.max[2] - mesh.min[2]));
            } else {
                setStatus(String.format(new java.util.Locale("pt", "BR"),
                        "Escala %.0f%% (NÃO é o tamanho real). Toque em \"1:1 real\" para voltar.", us * 100));
            }

            float[] proj = new float[16], view = new float[16];
            cam.getProjectionMatrix(proj, 0, 0.03f, 8f);
            cam.getViewMatrix(view, 0);
            drawMesh(proj, view);
        } catch (Exception e) {
            Log.e(TAG, "Erro no quadro AR", e);
        } finally {
            if (drew && photoRequested) {
                photoRequested = false;
                takePhoto();
            }
        }
    }

    // ================================================================ foto

    private volatile boolean photoRequested = false;

    /** Lê os pixels do quadro atual (câmera + peça), grava um PNG com legenda e abre o compartilhamento. */
    private void takePhoto() {
        final int w = viewW, h = viewH;
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf);
        final int[] px = new int[w * h];
        buf.rewind();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            int dst = (h - 1 - y) * w;
            for (int x = 0; x < w; x++) {
                int r = buf.get() & 0xFF, g = buf.get() & 0xFF, b = buf.get() & 0xFF, a = buf.get() & 0xFF;
                row[x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            System.arraycopy(row, 0, px, dst, w);
        }
        final float us = userScale;
        final Mesh m = mesh;
        new Thread(() -> {
            try {
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                bmp.setPixels(px, 0, w, 0, 0, w, h);
                android.graphics.Canvas c = new android.graphics.Canvas(bmp);
                float d = getResources().getDisplayMetrics().density;
                android.graphics.Paint bg = new android.graphics.Paint();
                bg.setColor(0xB3000000);
                float bh = 58 * d;
                c.drawRect(0, h - bh, w, h, bg);
                android.graphics.Paint t = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                t.setColor(Color.WHITE);
                t.setTextSize(14 * d);
                String scale = Math.abs(us - 1f) < 0.005f ? "escala real 1:1"
                        : String.format(new java.util.Locale("pt", "BR"), "escala %.0f%% (não é o tamanho real)", us * 100);
                String nm = m.name == null || m.name.isEmpty() ? "Peça" : m.name;
                c.drawText(nm + "   " + String.format(new java.util.Locale("pt", "BR"), "%.0f × %.0f × %.0f mm",
                        m.max[0] - m.min[0], m.max[1] - m.min[1], m.max[2] - m.min[2]), 12 * d, h - bh + 22 * d, t);
                t.setColor(0xFFFFD54F);
                c.drawText("Realidade aumentada · " + scale, 12 * d, h - bh + 41 * d, t);
                t.setColor(0xFF90A4AE);
                t.setTextSize(11 * d);
                t.setTextAlign(android.graphics.Paint.Align.RIGHT);
                c.drawText("MoviAI Engenharia Digital", w - 12 * d, h - 10 * d, t);
                java.io.File dir = ShareProvider.dir(this);
                java.io.File[] old = dir.listFiles();
                if (old != null) for (java.io.File o : old) o.delete();
                String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
                java.io.File f = new java.io.File(dir, "ar_" + ts + ".png");
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os);
                }
                runOnUiThread(() -> {
                    android.net.Uri uri = ShareProvider.uriFor(f);
                    android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
                    i.setType("image/png");
                    i.putExtra(android.content.Intent.EXTRA_STREAM, uri);
                    i.setClipData(android.content.ClipData.newRawUri(f.getName(), uri));
                    i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(android.content.Intent.createChooser(i, "Compartilhar foto em RA"));
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Erro na foto: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
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
        float sc = 0.001f * userScale;
        Matrix.scaleM(model, 0, sc, sc, sc);
        Matrix.translateM(model, 0, -m.center[0], -m.center[1], -m.min[2]);
        float[] world = new float[16], mv = new float[16], mvp = new float[16];
        Matrix.multiplyMM(world, 0, anchorM, 0, model, 0);
        Matrix.multiplyMM(mv, 0, view, 0, world, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0);

        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glUseProgram(meshProg);
        GLES30.glUniformMatrix4fv(mMvp, 1, false, mvp, 0);
        GLES30.glUniformMatrix4fv(mMv, 1, false, mv, 0);
        // luz fixa no mundo (de cima, levemente de lado), convertida para o espaço da câmera
        float lx = 0.35f, ly = 0.85f, lz = 0.40f;
        float ll = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= ll; ly /= ll; lz /= ll;
        GLES30.glUniform3f(mL,
                view[0] * lx + view[4] * ly + view[8] * lz,
                view[1] * lx + view[5] * ly + view[9] * lz,
                view[2] * lx + view[6] * ly + view[10] * lz);
        GLES30.glUniform3f(mUp, view[4], view[5], view[6]);
        int ci = colorIndex;
        float[] tc = ci >= 0 && ci < SWATCHES.length ? SWATCHES[ci] : null;
        if (tc != null) {
            GLES30.glUniform3f(mTint, tc[0], tc[1], tc[2]);
            GLES30.glUniform1f(mUseTint, 1f);
        } else {
            GLES30.glUniform3f(mTint, 0.7f, 0.7f, 0.7f);
            GLES30.glUniform1f(mUseTint, 0f);
        }

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
            GLES30.glVertexAttrib3f(mCol, 0.55f, 0.58f, 0.64f);
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
