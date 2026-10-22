package br.com.moviai.visualizador3d;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_OPEN = 1;
    private static final int REQ_SAVE_STL = 2;
    private static final int REQ_SAVE_PLY = 3;
    private static final int REQ_OPEN_REF = 4;
    private static final Locale BR = new Locale("pt", "BR");

    private static final int TOOL_NONE = -1, TOOL_OPEN = 0, TOOL_VIEW = 1, TOOL_MEASURE = 2, TOOL_CUT = 3,
            TOOL_ALIGN = 4, TOOL_ANALYZE = 5, TOOL_COMPARE = 6, TOOL_SAVE = 7;
    private static final int MAX_RECENT = 10;

    private static final int C_BTN = 0xCC2E3B4E;
    private static final int C_SEL = 0xFF1E88E5;
    private static final int C_PANEL = 0xE61A2029;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private ModelView glView;
    private TextView info, center;
    private ProgressBar progress;
    private LinearLayout bottom, toolbar;
    private ScrollView panelScroll;
    private final View[] panels = new View[8];
    private final Button[] toolButtons = new Button[8];
    private int tool = TOOL_NONE;

    private Mesh current;
    private volatile Mesh currentLite;
    private volatile int loadId = 0;
    private boolean busy = false;

    // Vista
    private boolean ortho = false;
    private int mode = ModelRenderer.MODE_SOLID;
    private Button orthoBtn, modeBtn;

    // Medir
    private boolean diameterMode = false;
    private final List<Picker.Hit> mPicks = new ArrayList<>();
    private TextView measureText;
    private Button distBtn, diamBtn;

    // Corte
    private int clipAxis = -1;
    private float clipFrac = 0.5f;
    private boolean clipFlip = false;
    private TextView clipText;
    private SeekBar clipSeek;
    private final Button[] clipBtns = new Button[4];

    // Alinhar
    private boolean edgeMode = false;
    private final List<Picker.Hit> aPicks = new ArrayList<>();
    private double[] lockedAxis = null;
    private String lockedName = null;
    private final ArrayList<float[]> history = new ArrayList<>();
    private int fineAxis = 2;
    private TextView alignText;
    private Button faceBtn, edgeBtn, fineAxisBtn;

    // Salvar
    private TextView saveText;

    // Abrir
    private LinearLayout recentList;

    // Análise
    private TextView analyzeText;
    private MeshAnalysis analysis;
    private boolean showBorders = true;
    private int material = 0;
    private static final String[] MAT_NAMES = {"PLA", "PETG", "ABS", "Resina", "Alumínio", "Aço"};
    private static final double[] MAT_DENS = {1.24, 1.27, 1.04, 1.15, 2.70, 7.85};
    private Button materialBtn, bordersBtn;

    // Comparar
    private static final double[] TOLS = {0.02, 0.05, 0.1, 0.2, 0.5, 1.0};
    private Mesh refMesh;
    private Deviation.RefIndex refIndex;
    private float[] scanDev, liteDev;
    private byte[] scanOrigColors, liteOrigColors;
    private Mesh devMesh, devLite;
    private boolean devActive = false;
    private int tolIdx = 2;
    private String alignInfo = null;
    private String pointInfo = null;
    private double[] comparePick = null;
    private boolean showRefMesh = true;
    private TextView compareText;
    private LegendView legend;
    private Button showRefBtn;
    private final Button[] tolBtns = new Button[TOLS.length];

    // Apresentação
    private boolean presenting = false;
    private TextView watermark;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(28, 33, 41));

        glView = new ModelView(this);
        glView.setTapListener(this::onTap);
        root.addView(glView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        info = new TextView(this);
        info.setTextColor(Color.WHITE);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        info.setPadding(dp(10), dp(6), dp(10), dp(6));
        info.setBackground(roundBg(0x99000000));
        info.setVisibility(View.GONE);
        FrameLayout.LayoutParams lpInfo = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lpInfo.setMargins(dp(10), dp(10), dp(10), dp(10));
        root.addView(info, lpInfo);

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setGravity(Gravity.CENTER);
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        middle.addView(progress);
        center = new TextView(this);
        center.setTextColor(0xFFCFD8DC);
        center.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        center.setGravity(Gravity.CENTER);
        center.setPadding(dp(24), dp(12), dp(24), dp(12));
        center.setText("Toque em Abrir para carregar\num arquivo .stl ou .ply\n\n1 dedo: girar\nPinça: zoom\n2 dedos: mover\nToque duplo: reenquadrar");
        middle.addView(center);
        root.addView(middle, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);

        panelScroll = new ScrollView(this);
        panelScroll.setBackground(roundBg(C_PANEL));
        panelScroll.setVisibility(View.GONE);
        LinearLayout.LayoutParams lpPanel = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpPanel.setMargins(dp(8), 0, dp(8), dp(6));
        bottom.addView(panelScroll, lpPanel);

        panels[TOOL_OPEN] = buildOpenPanel();
        panels[TOOL_VIEW] = buildViewPanel();
        panels[TOOL_MEASURE] = buildMeasurePanel();
        panels[TOOL_CUT] = buildCutPanel();
        panels[TOOL_ALIGN] = buildAlignPanel();
        panels[TOOL_ANALYZE] = buildAnalyzePanel();
        panels[TOOL_COMPARE] = buildComparePanel();
        panels[TOOL_SAVE] = buildSavePanel();

        HorizontalScrollView tbScroll = new HorizontalScrollView(this);
        tbScroll.setHorizontalScrollBarEnabled(false);
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(dp(6), dp(4), dp(6), dp(8));
        String[] names = {"Abrir", "Vista", "Medir", "Corte", "Alinhar", "Análise", "Comparar", "Salvar"};
        for (int i = 0; i < names.length; i++) {
            final int t = i;
            toolButtons[i] = toolButton(names[i], v -> selectTool(t));
            toolbar.addView(toolButtons[i]);
        }
        tbScroll.addView(toolbar);
        bottom.addView(tbScroll);

        root.addView(bottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        watermark = new TextView(this);
        watermark.setText("MoviAI Engenharia Digital");
        watermark.setTextColor(0xB3FFFFFF);
        watermark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        watermark.setShadowLayer(4f, 0f, 1f, 0xFF000000);
        watermark.setVisibility(View.GONE);
        FrameLayout.LayoutParams lpWm = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lpWm.setMargins(dp(16), dp(16), dp(16), dp(28));
        root.addView(watermark, lpWm);

        // Android 15 desenha atrás das barras do sistema: afasta os controles delas.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bot = insets.getSystemWindowInsetBottom();
            int left = insets.getSystemWindowInsetLeft();
            int right = insets.getSystemWindowInsetRight();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) info.getLayoutParams();
            lp.setMargins(dp(10) + left, dp(10) + top, dp(10) + right, dp(10));
            info.setLayoutParams(lp);
            bottom.setPadding(left, 0, right, bot);
            return insets;
        });

        setContentView(root);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
    }

    @Override
    protected void onPause() {
        glView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (presenting) {
            setPresenting(false);
            return;
        }
        if (tool != TOOL_NONE) {
            selectTool(tool);
            return;
        }
        super.onBackPressed();
    }

    // ================================================================ abrir arquivo

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Object extra = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (extra instanceof Uri) uri = (Uri) extra;
        }
        if (uri != null) load(uri);
    }

    private void openPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_OPEN);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_OPEN) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
                // nem todo provedor permite; o arquivo abre do mesmo jeito
            }
            if (tool == TOOL_OPEN) selectTool(TOOL_OPEN);
            load(uri);
        }
        else if (req == REQ_OPEN_REF) loadReference(uri);
        else if (req == REQ_SAVE_STL) exportTo(uri, false);
        else if (req == REQ_SAVE_PLY) exportTo(uri, true);
    }

    private void load(Uri uri) {
        final int id = ++loadId;
        final ContentResolver cr = getContentResolver();
        progress.setVisibility(View.VISIBLE);
        center.setVisibility(View.VISIBLE);
        center.setText("Carregando...");

        worker.execute(() -> {
            long t0 = SystemClock.elapsedRealtime();
            String name = "arquivo";
            long size = -1;
            try {
                if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                    File f = new File(uri.getPath());
                    name = f.getName();
                    size = f.length();
                } else {
                    try (Cursor c = cr.query(uri, null, null, null, null)) {
                        if (c != null && c.moveToFirst()) {
                            int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            int si = c.getColumnIndex(OpenableColumns.SIZE);
                            if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                            if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                        }
                    } catch (Exception ignored) {
                    }
                }
                Mesh m = MeshLoader.load(() -> {
                    InputStream in = cr.openInputStream(uri);
                    if (in == null) throw new java.io.IOException("Não foi possível abrir o arquivo");
                    return in;
                }, name, size);
                long ms = SystemClock.elapsedRealtime() - t0;
                final String fname = name;
                currentLite = null;
                runOnUiThread(() -> {
                    if (id != loadId) return;
                    showMesh(m, ms);
                    addRecent(uri, fname);
                });
                if (Decimator.worthIt(m)) {
                    try {
                        Mesh l = Decimator.build(m);
                        if (id == loadId) {
                            currentLite = l;
                            runOnUiThread(() -> {
                                if (current == m) glView.renderer.setLite(m, l);
                            });
                        }
                    } catch (OutOfMemoryError e) {
                        currentLite = null;
                    }
                }
            } catch (OutOfMemoryError e) {
                final String n = name;
                runOnUiThread(() -> showError(n, "Arquivo grande demais para a memória do aparelho."));
            } catch (Exception e) {
                final String n = name;
                final String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> showError(n, msg));
            }
        });
    }

    private void showMesh(Mesh m, long ms) {
        current = m;
        progress.setVisibility(View.GONE);
        center.setVisibility(View.GONE);
        mPicks.clear();
        aPicks.clear();
        history.clear();
        lockedAxis = null;
        lockedName = null;
        clipAxis = -1;
        analysis = null;
        devActive = false;
        scanDev = liteDev = null;
        scanOrigColors = liteOrigColors = null;
        devMesh = devLite = null;
        alignInfo = null;
        pointInfo = null;
        comparePick = null;
        glView.renderer.setMesh(m);
        glView.requestRender();
        updateInfo(String.format(BR, "Carregado em %.1f s", ms / 1000f));
        refreshMeasure();
        refreshAlign();
        refreshClipButtons();
        refreshClipText();
        refreshAnalysis();
        refreshCompare();
    }

    private void showError(String name, String msg) {
        progress.setVisibility(View.GONE);
        center.setVisibility(View.VISIBLE);
        center.setText("Não foi possível abrir " + name + "\n\n" + msg);
        if (current == null) info.setVisibility(View.GONE);
        else center.postDelayed(() -> center.setVisibility(View.GONE), 4000);
    }

    private void updateInfo(String extra) {
        Mesh m = current;
        if (m == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(m.name).append('\n');
        sb.append(m.format).append('\n');
        sb.append(String.format(BR, "%,d vértices", m.vertexCount));
        if (!m.pointCloud) sb.append(String.format(BR, " · %,d triângulos", m.triangleCount));
        sb.append('\n');
        sb.append("X ").append(f2(m.max[0] - m.min[0]))
          .append(" · Y ").append(f2(m.max[1] - m.min[1]))
          .append(" · Z ").append(f2(m.max[2] - m.min[2]));
        if (!history.isEmpty()) sb.append("\nAlinhada (").append(history.size()).append(history.size() == 1 ? " operação)" : " operações)");
        if (extra != null) sb.append('\n').append(extra);
        info.setText(sb.toString());
        info.setVisibility(View.VISIBLE);
    }

    // ================================================================ ferramentas

    private void selectTool(int t) {
        tool = (tool == t) ? TOOL_NONE : t;
        for (int i = 0; i < toolButtons.length; i++) setSel(toolButtons[i], i == tool);
        panelScroll.removeAllViews();
        if (tool == TOOL_OPEN) refreshRecent();
        if (tool == TOOL_NONE) {
            panelScroll.setVisibility(View.GONE);
        } else {
            panelScroll.addView(panels[tool]);
            panelScroll.setVisibility(View.VISIBLE);
            panelScroll.scrollTo(0, 0);
        }
        updateOverlay();
    }

    private void onTap(float x, float y) {
        if (presenting) {
            setPresenting(false);
            return;
        }
        final Mesh m = current;
        if (m == null || busy) return;
        if (tool != TOOL_MEASURE && tool != TOOL_ALIGN && tool != TOOL_COMPARE) return;
        if (tool == TOOL_COMPARE && refIndex == null) { toast("Abra o CAD de referência primeiro"); return; }
        final float[] mvp = new float[16];
        final int[] size = new int[2];
        if (!glView.renderer.snapshot(m, mvp, size)) return;
        final float[] clip = glView.renderer.getClip();
        final float tol = dp(24);
        final int forTool = tool;
        worker.execute(() -> {
            Picker.Hit h = Picker.pick(m, mvp, size[0], size[1], x, y, clip, tol);
            runOnUiThread(() -> {
                if (m != current || tool != forTool) return;
                if (h == null) {
                    toast("Nenhum ponto da peça nesse toque");
                    return;
                }
                if (forTool == TOOL_MEASURE) addMeasurePick(h);
                else if (forTool == TOOL_COMPARE) comparePoint(h);
                else addAlignPick(h);
            });
        });
    }

    // ---------------------------------------------------------------- Vista

    private View buildViewPanel() {
        LinearLayout p = panel();
        LinearLayout views = new LinearLayout(this);
        views.setOrientation(LinearLayout.HORIZONTAL);
        views.addView(chip("Iso", v -> { glView.renderer.resetView(); glView.requestRender(); }));
        views.addView(chip("Frente", v -> setView(ModelRenderer.VIEW_FRONT)));
        views.addView(chip("Topo", v -> setView(ModelRenderer.VIEW_TOP)));
        views.addView(chip("Direita", v -> setView(ModelRenderer.VIEW_RIGHT)));
        views.addView(chip("Esquerda", v -> setView(ModelRenderer.VIEW_LEFT)));
        views.addView(chip("Trás", v -> setView(ModelRenderer.VIEW_BACK)));
        views.addView(chip("Baixo", v -> setView(ModelRenderer.VIEW_BOTTOM)));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(views);
        p.addView(hs);

        orthoBtn = btn("Perspectiva", v -> {
            ortho = !ortho;
            orthoBtn.setText(ortho ? "Ortográfica" : "Perspectiva");
            glView.renderer.setOrtho(ortho);
            glView.requestRender();
        });
        modeBtn = btn("Sólido", v -> {
            mode = (mode + 1) % 3;
            String[] n = {"Sólido", "Arame", "Pontos"};
            modeBtn.setText(n[mode]);
            glView.renderer.setMode(mode);
            glView.requestRender();
        });
        Button fit = btn("Enquadrar", v -> { glView.renderer.resetView(); glView.requestRender(); });
        p.addView(row(orthoBtn, modeBtn, fit));
        p.addView(row(btn("Modo apresentação", v -> setPresenting(true))));
        return p;
    }

    private void setView(float[][] rows) {
        glView.renderer.setView(rows);
        glView.requestRender();
    }

    // ---------------------------------------------------------------- Medir

    private View buildMeasurePanel() {
        LinearLayout p = panel();
        measureText = text();
        p.addView(measureText);
        distBtn = btn("Distância", v -> { diameterMode = false; mPicks.clear(); refreshMeasure(); });
        diamBtn = btn("Diâmetro", v -> { diameterMode = true; mPicks.clear(); refreshMeasure(); });
        Button clear = btn("Limpar", v -> { mPicks.clear(); refreshMeasure(); });
        p.addView(row(distBtn, diamBtn, clear));
        refreshMeasure();
        return p;
    }

    private void addMeasurePick(Picker.Hit h) {
        int need = diameterMode ? 3 : 2;
        if (mPicks.size() >= need) mPicks.clear();
        mPicks.add(h);
        refreshMeasure();
    }

    private void refreshMeasure() {
        if (measureText == null) return;
        setSel(distBtn, !diameterMode);
        setSel(diamBtn, diameterMode);
        int n = mPicks.size();
        String s;
        if (!diameterMode) {
            if (n < 2) {
                s = n == 0 ? "Toque o primeiro ponto na peça." : "Toque o segundo ponto.";
                if (n == 1) s = "Ponto 1: " + xyz(mPicks.get(0).p) + "\nToque o segundo ponto.";
            } else {
                double[] a = mPicks.get(0).p, b = mPicks.get(1).p;
                double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                s = "Distância: " + f2(d) + " mm\nΔX " + f2(Math.abs(dx)) + " · ΔY " + f2(Math.abs(dy)) + " · ΔZ " + f2(Math.abs(dz));
            }
        } else {
            if (n < 3) {
                s = "Toque 3 pontos na borda do furo ou do arco (" + n + " de 3).";
            } else {
                double[] c = Align.circle(mPicks.get(0).p, mPicks.get(1).p, mPicks.get(2).p);
                if (c == null) s = "Os 3 pontos estão alinhados. Toque novamente.";
                else s = "Ø " + f2(2 * c[3]) + " mm · raio " + f2(c[3]) + "\nCentro " + xyz(c);
            }
        }
        measureText.setText(s);
        updateOverlay();
    }

    // ---------------------------------------------------------------- Corte

    private View buildCutPanel() {
        LinearLayout p = panel();
        clipText = text();
        p.addView(clipText);
        String[] n = {"Desligado", "X", "Y", "Z"};
        for (int i = 0; i < 4; i++) {
            final int axis = i - 1;
            clipBtns[i] = btn(n[i], v -> { clipAxis = axis; refreshClipButtons(); applyClip(); });
        }
        Button flip = btn("Inverter", v -> { clipFlip = !clipFlip; applyClip(); });
        p.addView(row(clipBtns[0], clipBtns[1], clipBtns[2], clipBtns[3], flip));
        clipSeek = new SeekBar(this);
        clipSeek.setMax(1000);
        clipSeek.setProgress(500);
        clipSeek.setPadding(dp(16), dp(10), dp(16), dp(10));
        clipSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int prog, boolean fromUser) {
                clipFrac = prog / 1000f;
                applyClip();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        p.addView(clipSeek);
        refreshClipButtons();
        refreshClipText();
        return p;
    }

    private void refreshClipButtons() {
        for (int i = 0; i < 4; i++) if (clipBtns[i] != null) setSel(clipBtns[i], clipAxis == i - 1);
    }

    private void applyClip() {
        Mesh m = current;
        if (m == null || clipAxis < 0) {
            glView.renderer.setClip(null);
        } else {
            float pos = m.min[clipAxis] + clipFrac * (m.max[clipAxis] - m.min[clipAxis]);
            float s = clipFlip ? -1f : 1f;
            float[] plane = {0, 0, 0, -s * pos};
            plane[clipAxis] = s;
            glView.renderer.setClip(plane);
        }
        glView.requestRender();
        refreshClipText();
    }

    private void refreshClipText() {
        if (clipText == null) return;
        Mesh m = current;
        if (m == null || clipAxis < 0) {
            clipText.setText("Escolha o eixo do plano de corte. O interior cortado aparece em laranja.");
            return;
        }
        String ax = "XYZ".substring(clipAxis, clipAxis + 1);
        float pos = m.min[clipAxis] + clipFrac * (m.max[clipAxis] - m.min[clipAxis]);
        clipText.setText("Corte em " + ax + " = " + f2(pos) + " mm (removendo " + (clipFlip ? "abaixo" : "acima") + ")");
    }

    // ---------------------------------------------------------------- Alinhar

    private View buildAlignPanel() {
        LinearLayout p = panel();
        alignText = text();
        p.addView(alignText);

        faceBtn = btn("Face plana", v -> { edgeMode = false; aPicks.clear(); refreshAlign(); });
        edgeBtn = btn("Aresta", v -> { edgeMode = true; aPicks.clear(); refreshAlign(); });
        Button clr = btn("Limpar pontos", v -> { aPicks.clear(); refreshAlign(); });
        p.addView(row(faceBtn, edgeBtn, clr));

        p.addView(label("Levar a seleção para o eixo:"));
        String[] tn = {"+X", "−X", "+Y", "−Y", "+Z", "−Z"};
        double[][] tv = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        Button[] tb = new Button[6];
        for (int i = 0; i < 6; i++) {
            final double[] target = tv[i];
            final String name = tn[i];
            tb[i] = btn(name, v -> alignToAxis(target, name));
        }
        p.addView(row(tb));

        p.addView(row(
                btn("Automático", v -> autoAlign()),
                btn("Desfazer", v -> undo()),
                btn("Original", v -> resetAlign())));

        p.addView(label("Girar a peça:"));
        fineAxisBtn = btn("Eixo Z", v -> {
            fineAxis = (fineAxis + 1) % 3;
            fineAxisBtn.setText("Eixo " + "XYZ".charAt(fineAxis));
        });
        p.addView(row(fineAxisBtn,
                btn("−90°", v -> rotateFine(-90)),
                btn("−1°", v -> rotateFine(-1)),
                btn("+1°", v -> rotateFine(1)),
                btn("+90°", v -> rotateFine(90))));

        p.addView(label("Origem (0,0,0):"));
        p.addView(row(
                btn("Centro", v -> setOrigin(0)),
                btn("Base", v -> setOrigin(1)),
                btn("Ponto", v -> setOrigin(2)),
                btn("Furo", v -> setOrigin(3))));
        refreshAlign();
        return p;
    }

    private void addAlignPick(Picker.Hit h) {
        if (edgeMode && aPicks.size() >= 2) aPicks.clear();
        if (!edgeMode && aPicks.size() >= 8) aPicks.remove(0);
        aPicks.add(h);
        refreshAlign();
    }

    private void refreshAlign() {
        if (alignText == null) return;
        setSel(faceBtn, !edgeMode);
        setSel(edgeBtn, edgeMode);
        int n = aPicks.size();
        StringBuilder s = new StringBuilder();
        if (edgeMode) {
            s.append("Toque 2 pontos ao longo de uma aresta reta (").append(Math.min(n, 2)).append(" de 2). ")
             .append("A direção vai do 1º para o 2º ponto.");
        } else {
            s.append("Toque 1 ou mais pontos numa face plana (").append(n).append(" marcado").append(n == 1 ? "" : "s").append("). ")
             .append("Ex.: base da peça → −Z.");
        }
        if (lockedName != null) {
            s.append("\nEixo ").append(lockedName).append(" travado: a próxima face/aresta gira só em torno dele.");
        }
        alignText.setText(s.toString());
        updateOverlay();
    }

    private void alignToAxis(double[] target, String name) {
        final Mesh m = current;
        if (m == null) return;
        if (edgeMode && aPicks.size() < 2) { toast("Marque 2 pontos na aresta"); return; }
        if (!edgeMode && aPicks.isEmpty()) { toast("Marque pontos na face plana"); return; }
        final List<Picker.Hit> picks = new ArrayList<>(aPicks);
        final boolean edge = edgeMode;
        final double[] locked = lockedAxis;
        runTransform(() -> {
            double[] v;
            if (edge) {
                v = Align.sub(picks.get(1).p, picks.get(0).p);
            } else {
                float dx = m.max[0] - m.min[0], dy = m.max[1] - m.min[1], dz = m.max[2] - m.min[2];
                double diag = Math.sqrt(dx * dx + dy * dy + dz * dz);
                v = Align.fitPlaneNormal(m, picks, Math.max(diag * 0.025, 1e-6));
                if (v == null) throw new IllegalStateException("Não foi possível ajustar um plano nesses pontos");
            }
            float[] r = Align.alignVector(v, target, locked);
            if (r == null) throw new IllegalStateException("Direção paralela ao eixo travado. Escolha outro eixo.");
            return Mat4.about(r, m.center[0], m.center[1], m.center[2]);
        }, () -> {
            boolean constrained = locked != null && Math.abs(Align.dot(target, locked)) < 1e-6;
            if (!constrained) {
                lockedAxis = target;
                lockedName = name;
            }
        });
    }

    private void autoAlign() {
        final Mesh m = current;
        if (m == null) return;
        runTransform(() -> {
            float[] r = Align.autoRotation(m);
            float[] b = Align.boundsAfter(m, r);
            float[] t = Mat4.translation(-(b[0] + b[3]) / 2, -(b[1] + b[4]) / 2, -b[2]);
            return Mat4.mul(t, r);
        }, () -> {
            lockedAxis = null;
            lockedName = null;
        });
    }

    private void rotateFine(double deg) {
        final Mesh m = current;
        if (m == null) return;
        final int ax = fineAxis;
        runTransform(() -> {
            double[] a = {0, 0, 0};
            a[ax] = 1;
            float[] r = Mat4.rotation(Math.toRadians(deg), a[0], a[1], a[2]);
            return Mat4.about(r, m.center[0], m.center[1], m.center[2]);
        }, null);
    }

    private void setOrigin(int kind) {
        final Mesh m = current;
        if (m == null) return;
        double[] o;
        switch (kind) {
            case 0: o = new double[]{m.center[0], m.center[1], m.center[2]}; break;
            case 1: o = new double[]{m.center[0], m.center[1], m.min[2]}; break;
            case 2:
                if (aPicks.isEmpty()) { toast("Marque um ponto na peça"); return; }
                o = aPicks.get(aPicks.size() - 1).p;
                break;
            default:
                if (aPicks.size() < 3) { toast("Marque 3 pontos na borda do furo"); return; }
                int k = aPicks.size();
                o = Align.circle(aPicks.get(k - 3).p, aPicks.get(k - 2).p, aPicks.get(k - 1).p);
                if (o == null) { toast("Os 3 pontos estão alinhados"); return; }
                break;
        }
        final double[] org = o;
        runTransform(() -> Mat4.translation(-org[0], -org[1], -org[2]), null);
    }

    private void undo() {
        final Mesh m = current;
        if (m == null || history.isEmpty() || busy) return;
        final float[] last = history.remove(history.size() - 1);
        final float[] inv = Mat4.invert(last);
        if (inv == null) return;
        busy = true;
        worker.execute(() -> {
            m.applyTransform(inv);
            Mesh l = currentLite;
            if (l != null) l.applyTransform(inv);
            runOnUiThread(() -> afterTransform(m, null));
        });
    }

    private void resetAlign() {
        final Mesh m = current;
        if (m == null || history.isEmpty() || busy) return;
        float[] total = Mat4.identity();
        for (float[] t : history) total = Mat4.mul(t, total);
        final float[] inv = Mat4.invert(total);
        history.clear();
        if (inv == null) return;
        busy = true;
        worker.execute(() -> {
            m.applyTransform(inv);
            Mesh l = currentLite;
            if (l != null) l.applyTransform(inv);
            runOnUiThread(() -> afterTransform(m, null));
        });
    }

    interface TransformJob {
        float[] compute() throws Exception;
    }

    /** Calcula a transformação em segundo plano, aplica na malha e registra no histórico. */
    private void runTransform(TransformJob job, Runnable onSuccess) {
        final Mesh m = current;
        if (m == null || busy) return;
        busy = true;
        progress.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            float[] applied = null;
            String err = null;
            try {
                applied = job.compute();
                m.applyTransform(applied);
                Mesh l = currentLite;
                if (l != null) l.applyTransform(applied);
            } catch (Exception e) {
                applied = null;
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final float[] res = applied;
            final String msg = err;
            runOnUiThread(() -> {
                if (res == null) {
                    busy = false;
                    progress.setVisibility(View.GONE);
                    toast(msg);
                    return;
                }
                history.add(res);
                if (onSuccess != null) onSuccess.run();
                afterTransform(m, null);
            });
        });
    }

    private void afterTransform(Mesh m, String note) {
        busy = false;
        progress.setVisibility(View.GONE);
        if (m != current) return;
        clearDeviation();
        pointInfo = null;
        comparePick = null;
        refreshCompare();
        aPicks.clear();
        mPicks.clear();
        analysis = null;
        refreshAnalysis();
        glView.renderer.meshChanged();
        applyClip();
        updateInfo(note);
        refreshAlign();
        refreshMeasure();
        refreshClipText();
        glView.requestRender();
    }

    // ---------------------------------------------------------------- Abrir

    private View buildOpenPanel() {
        LinearLayout p = panel();
        p.addView(row(btn("Escolher arquivo...", v -> openPicker())));
        p.addView(label("Recentes:"));
        recentList = new LinearLayout(this);
        recentList.setOrientation(LinearLayout.VERTICAL);
        p.addView(recentList);
        p.addView(row(btn("Limpar lista", v -> {
            getSharedPreferences("recentes", MODE_PRIVATE).edit().clear().apply();
            refreshRecent();
        })));
        return p;
    }

    private List<String[]> recents() {
        List<String[]> out = new ArrayList<>();
        String raw = getSharedPreferences("recentes", MODE_PRIVATE).getString("lista", "");
        for (String line : raw.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) out.add(new String[]{line.substring(0, tab), line.substring(tab + 1)});
        }
        return out;
    }

    private void addRecent(Uri uri, String name) {
        String u = uri.toString();
        List<String[]> list = recents();
        StringBuilder sb = new StringBuilder();
        sb.append(u).append('\t').append(name.replace('\n', ' ').replace('\t', ' '));
        int n = 1;
        for (String[] r : list) {
            if (r[0].equals(u) || n >= MAX_RECENT) continue;
            sb.append('\n').append(r[0]).append('\t').append(r[1]);
            n++;
        }
        getSharedPreferences("recentes", MODE_PRIVATE).edit().putString("lista", sb.toString()).apply();
    }

    private void refreshRecent() {
        if (recentList == null) return;
        recentList.removeAllViews();
        List<String[]> list = recents();
        if (list.isEmpty()) {
            TextView t = text();
            t.setText("Nenhum arquivo aberto ainda.");
            recentList.addView(t);
            return;
        }
        for (String[] r : list) {
            final Uri uri = Uri.parse(r[0]);
            Button b = btn(r[1], v -> { selectTool(TOOL_OPEN); load(uri); });
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setPadding(dp(14), 0, dp(14), 0);
            b.setSingleLine(true);
            b.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            recentList.addView(row(b));
        }
    }

    // ---------------------------------------------------------------- Análise

    private View buildAnalyzePanel() {
        LinearLayout p = panel();
        analyzeText = text();
        p.addView(analyzeText);
        bordersBtn = btn("Bordas: visíveis", v -> {
            showBorders = !showBorders;
            bordersBtn.setText(showBorders ? "Bordas: visíveis" : "Bordas: ocultas");
            updateOverlay();
        });
        materialBtn = btn("Material: PLA", v -> {
            material = (material + 1) % MAT_NAMES.length;
            materialBtn.setText("Material: " + MAT_NAMES[material]);
            refreshAnalysis();
        });
        p.addView(row(btn("Analisar", v -> runAnalysis()), bordersBtn, materialBtn));
        refreshAnalysis();
        return p;
    }

    private void runAnalysis() {
        final Mesh m = current;
        if (m == null) { toast("Abra um arquivo primeiro"); return; }
        if (m.pointCloud) { toast("Nuvem de pontos não tem superfície para analisar"); return; }
        if (busy) return;
        busy = true;
        progress.setVisibility(View.VISIBLE);
        analyzeText.setText("Analisando...");
        worker.execute(() -> {
            MeshAnalysis a = null;
            String err = null;
            try {
                a = MeshAnalysis.run(m);
            } catch (OutOfMemoryError e) {
                err = "Memória insuficiente para analisar esta malha.";
            } catch (Exception e) {
                err = e.toString();
            }
            final MeshAnalysis res = a;
            final String msg = err;
            runOnUiThread(() -> {
                busy = false;
                progress.setVisibility(View.GONE);
                if (m != current) return;
                if (res == null) { toast(msg); refreshAnalysis(); return; }
                analysis = res;
                refreshAnalysis();
                updateOverlay();
            });
        });
    }

    private void refreshAnalysis() {
        if (analyzeText == null) return;
        MeshAnalysis a = analysis;
        if (a == null) {
            analyzeText.setText("Verifica se a malha está fechada (pronta para impressão ou CAD), mostra furos e bordas abertas em vermelho, ilhas soltas, área, volume e massa estimada.");
            return;
        }
        StringBuilder s = new StringBuilder();
        s.append(a.closed ? "✔ Malha fechada (estanque)" : "✖ Malha aberta").append('\n');
        s.append("Área: ").append(f2(a.area)).append(" mm² (").append(f2(a.area / 100.0)).append(" cm²)\n");
        if (a.closed) {
            double cm3 = a.volume / 1000.0;
            s.append("Volume: ").append(f2(cm3)).append(" cm³\n");
            s.append("Massa em ").append(MAT_NAMES[material]).append(" maciço: ")
             .append(f2(cm3 * MAT_DENS[material])).append(" g\n");
        } else {
            s.append("Volume: não confiável com a malha aberta (≈ ").append(f2(a.volume / 1000.0)).append(" cm³)\n");
        }
        s.append("Bordas abertas: ").append(String.format(BR, "%,d", a.boundaryEdges));
        if (a.holes > 0) s.append(" em ").append(a.holes).append(a.holes == 1 ? " contorno (furo)" : " contornos (furos)");
        s.append('\n');
        s.append("Ilhas (partes separadas): ").append(a.shells).append('\n');
        if (a.nonManifoldEdges > 0) s.append("Arestas não manifold: ").append(String.format(BR, "%,d", a.nonManifoldEdges)).append('\n');
        if (a.degenerate > 0) s.append("Triângulos degenerados: ").append(String.format(BR, "%,d", a.degenerate)).append('\n');
        if (a.inverted) s.append("Normais invertidas (malha de dentro para fora)\n");
        if (a.boundaryEdges > MeshAnalysis.MAX_OVERLAY_EDGES) s.append("Exibindo as primeiras ").append(String.format(BR, "%,d", MeshAnalysis.MAX_OVERLAY_EDGES)).append(" bordas.\n");
        analyzeText.setText(s.toString().trim());
    }

    // ---------------------------------------------------------------- Comparar

    private View buildComparePanel() {
        LinearLayout p = panel();
        compareText = text();
        p.addView(compareText);
        showRefBtn = btn("CAD: visível", v -> {
            showRefMesh = !showRefMesh;
            showRefBtn.setText(showRefMesh ? "CAD: visível" : "CAD: oculto");
            glView.renderer.setShowReference(showRefMesh);
            glView.requestRender();
        });
        p.addView(row(btn("Abrir CAD...", v -> openReferencePicker()), showRefBtn));
        p.addView(row(btn("Alinhar automático", v -> icpAlign(false)), btn("Refinar", v -> icpAlign(true))));
        p.addView(row(btn("Calcular desvios", v -> computeDeviation()), btn("Limpar mapa", v -> {
            clearDeviation();
            pointInfo = null;
            comparePick = null;
            refreshCompare();
            updateOverlay();
        })));
        p.addView(label("Tolerância (± mm):"));
        for (int i = 0; i < TOLS.length; i++) {
            final int k = i;
            tolBtns[i] = btn(String.format(BR, "%.2f", TOLS[i]), v -> {
                tolIdx = k;
                refreshCompare();
                recolor();
            });
        }
        p.addView(row(tolBtns));
        legend = new LegendView(this);
        legend.setTolerance(TOLS[tolIdx]);
        p.addView(legend, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        refreshCompare();
        return p;
    }

    private void openReferencePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_OPEN_REF);
    }

    private void loadReference(Uri uri) {
        final ContentResolver cr = getContentResolver();
        if (busy) return;
        busy = true;
        progress.setVisibility(View.VISIBLE);
        compareText.setText("Carregando CAD...");
        worker.execute(() -> {
            String name = "referência";
            try (Cursor c = cr.query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                }
            } catch (Exception ignored) {
            }
            Mesh r = null;
            Deviation.RefIndex idx = null;
            String err = null;
            try {
                r = MeshLoader.load(() -> {
                    InputStream in = cr.openInputStream(uri);
                    if (in == null) throw new java.io.IOException("Não foi possível abrir o arquivo");
                    return in;
                }, name, -1);
                if (r.pointCloud) throw new java.io.IOException("A referência precisa ser uma malha (STL, PLY ou OBJ com faces)");
                idx = new Deviation.RefIndex(r);
            } catch (OutOfMemoryError e) {
                err = "Memória insuficiente para a referência";
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final Mesh fr = r;
            final Deviation.RefIndex fi = idx;
            final String fe = err;
            runOnUiThread(() -> {
                busy = false;
                progress.setVisibility(View.GONE);
                if (fe != null) {
                    toast("Erro no CAD: " + fe);
                    refreshCompare();
                    return;
                }
                refMesh = fr;
                refIndex = fi;
                clearDeviation();
                alignInfo = null;
                glView.renderer.setReference(fr);
                glView.requestRender();
                refreshCompare();
            });
        });
    }

    private void icpAlign(boolean refineOnly) {
        final Mesh m = current;
        final Mesh r = refMesh;
        final Deviation.RefIndex idx = refIndex;
        if (m == null) { toast("Abra o scan primeiro"); return; }
        if (idx == null) { toast("Abra o CAD de referência primeiro"); return; }
        compareText.setText(refineOnly ? "Refinando alinhamento (ICP)..." : "Alinhando (pode levar alguns segundos)...");
        final Icp.Result[] holder = new Icp.Result[1];
        runTransform(() -> {
            Icp.Result res = refineOnly ? Icp.refineOnly(m, idx) : Icp.align(m, r, idx);
            if (res == null) throw new IllegalStateException("Não foi possível alinhar. Pré-alinhe em Alinhar e use Refinar.");
            holder[0] = res;
            return res.transform;
        }, () -> {
            Icp.Result res = holder[0];
            String q = String.format(BR, "Alinhamento: %.0f%% dos pontos casaram, RMS %.3f mm", res.matchedFraction * 100, res.rms);
            if (res.matchedFraction < 0.85) {
                q += "\n⚠ Alinhamento duvidoso. Se o scan tem mesa ou suporte, limpe no Revo Scan, ou pré-alinhe em Alinhar e use Refinar.";
            }
            alignInfo = q;
            glView.post(this::computeDeviation);
        });
    }

    private void computeDeviation() {
        final Mesh m = current;
        final Deviation.RefIndex idx = refIndex;
        if (m == null || idx == null) { toast("Abra o scan e o CAD"); return; }
        if (busy) return;
        busy = true;
        progress.setVisibility(View.VISIBLE);
        compareText.setText("Calculando desvios...");
        final Mesh l = currentLite;
        worker.execute(() -> {
            float[] d = null, dl = null;
            String err = null;
            try {
                final int[] last = {-1};
                d = Deviation.compute(m, idx, Deviation.MAX_SEARCH, pct -> {
                    if (pct / 5 != last[0]) {
                        last[0] = pct / 5;
                        runOnUiThread(() -> { if (busy) compareText.setText("Calculando desvios... " + pct + "%"); });
                    }
                });
                if (l != null) dl = Deviation.compute(l, idx, Deviation.MAX_SEARCH, null);
            } catch (OutOfMemoryError e) {
                err = "Memória insuficiente para o mapa de desvios";
            } catch (Exception e) {
                err = e.toString();
            }
            final float[] fd = d, fdl = dl;
            final String fe = err;
            runOnUiThread(() -> {
                busy = false;
                progress.setVisibility(View.GONE);
                if (m != current) return;
                if (fe != null) { toast(fe); refreshCompare(); return; }
                if (!devActive) {
                    scanOrigColors = m.colors;
                    liteOrigColors = l != null ? l.colors : null;
                }
                devMesh = m;
                devLite = l;
                scanDev = fd;
                liteDev = fdl;
                devActive = true;
                recolor();
            });
        });
    }

    /** Pinta o scan com as cores do desvio para a tolerância atual. */
    private void recolor() {
        if (legend != null) legend.setTolerance(TOLS[tolIdx]);
        if (!devActive || scanDev == null) { refreshCompare(); return; }
        double tol = TOLS[tolIdx], range = LegendView.range(tol);
        devMesh.colors = Deviation.colorize(scanDev, tol, range);
        if (devLite != null && liteDev != null) devLite.colors = Deviation.colorize(liteDev, tol, range);
        glView.renderer.reupload();
        glView.requestRender();
        refreshCompare();
    }

    private void clearDeviation() {
        if (!devActive) return;
        if (devMesh != null) devMesh.colors = scanOrigColors;
        if (devLite != null) devLite.colors = liteOrigColors;
        devActive = false;
        scanDev = liteDev = null;
        devMesh = devLite = null;
        glView.renderer.reupload();
        glView.requestRender();
    }

    private void comparePoint(Picker.Hit h) {
        final Deviation.RefIndex idx = refIndex;
        if (idx == null) return;
        final double[] p = h.p;
        worker.execute(() -> {
            double d = idx.signedDistance(p[0], p[1], p[2], Deviation.MAX_SEARCH, new double[4]);
            runOnUiThread(() -> {
                comparePick = p;
                pointInfo = Double.isNaN(d) ? "Ponto tocado: sem correspondência no CAD"
                        : String.format(BR, "Ponto tocado: %+.3f mm", d);
                refreshCompare();
                updateOverlay();
            });
        });
    }

    private String deviationSummary() {
        Deviation.Stats st = Deviation.stats(scanDev, TOLS[tolIdx]);
        return String.format(BR, "Desvio ±%.2f: %.1f%% dentro · máx %+.3f · mín %+.3f · RMS %.3f mm",
                TOLS[tolIdx], st.inTol, st.max, st.min, st.rms);
    }

    private void refreshCompare() {
        if (compareText == null) return;
        for (int i = 0; i < tolBtns.length; i++) setSel(tolBtns[i], i == tolIdx);
        StringBuilder s = new StringBuilder();
        if (refMesh == null) {
            s.append("1. Abra o scan (Abrir) e o CAD de referência (STL do NX).\n")
             .append("2. Alinhar automático (peça inteira) ou pré-alinhe em Alinhar e use Refinar.\n")
             .append("3. Toque na peça para ver o desvio em qualquer ponto.");
        } else {
            s.append("CAD: ").append(refMesh.name).append(String.format(BR, " (%,d triângulos)", refMesh.triangleCount));
            if (alignInfo != null) s.append('\n').append(alignInfo);
            if (devActive && scanDev != null) {
                double tol = TOLS[tolIdx];
                Deviation.Stats st = Deviation.stats(scanDev, tol);
                double tot = Math.max(1, st.matched + st.unmatched);
                s.append(String.format(BR, "\n%.1f%% dentro de ±%.2f · %.1f%% acima · %.1f%% abaixo", st.inTol, tol, st.above, st.below));
                s.append(String.format(BR, "\nMédia %+.3f · Desvio padrão %.3f · RMS %.3f mm", st.mean, st.std, st.rms));
                s.append(String.format(BR, "\nMáx %+.3f · Mín %+.3f mm", st.max, st.min));
                if (st.unmatched > 0) {
                    s.append(String.format(BR, "\nSem correspondência (cinza, além de %.2f mm): %.1f%%",
                            Deviation.cutoff(tol), 100.0 * st.unmatched / tot));
                }
            } else if (current != null) {
                s.append("\nAlinhe e toque em Calcular desvios.");
            }
        }
        if (pointInfo != null) s.append('\n').append(pointInfo);
        compareText.setText(s.toString());
    }

    // ---------------------------------------------------------------- Apresentação

    private void setPresenting(boolean on) {
        presenting = on;
        if (on && tool != TOOL_NONE) selectTool(tool);
        bottom.setVisibility(on ? View.GONE : View.VISIBLE);
        info.setVisibility(on || current == null ? View.GONE : View.VISIBLE);
        watermark.setVisibility(on ? View.VISIBLE : View.GONE);
        glView.setPresenting(on);
        if (on) toast("Toque na tela para sair");
    }

    // ---------------------------------------------------------------- Salvar

    private View buildSavePanel() {
        LinearLayout p = panel();
        saveText = text();
        saveText.setText("A foto e os arquivos exportados já saem com o alinhamento aplicado. Com o mapa de desvios ativo, a foto leva as estatísticas e o PLY leva as cores do mapa.");
        p.addView(saveText);
        p.addView(row(
                btn("Foto da tela", v -> captureAndShare()),
                btn("Enviar STL", v -> shareMesh())));
        p.addView(row(
                btn("Salvar STL...", v -> createDoc(".stl", REQ_SAVE_STL)),
                btn("Salvar PLY...", v -> createDoc(".ply", REQ_SAVE_PLY))));
        return p;
    }

    private String baseName() {
        Mesh m = current;
        String n = m == null ? "peca" : m.name;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (n.isEmpty()) n = "peca";
        return history.isEmpty() ? n : n + "_alinhado";
    }

    private void createDoc(String ext, int req) {
        if (current == null) { toast("Abra um arquivo primeiro"); return; }
        if (ext.equals(".stl") && current.pointCloud) { toast("Nuvem de pontos: use PLY"); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, baseName() + ext);
        startActivityForResult(i, req);
    }

    private void exportTo(Uri uri, boolean ply) {
        final Mesh m = current;
        if (m == null) return;
        final ContentResolver cr = getContentResolver();
        progress.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            String msg;
            try (OutputStream os = cr.openOutputStream(uri, "wt")) {
                if (os == null) throw new java.io.IOException("Destino inválido");
                if (ply) Exporter.writePly(m, os); else Exporter.writeStl(m, os);
                msg = "Arquivo salvo";
            } catch (Exception e) {
                msg = "Erro ao salvar: " + e.getMessage();
            }
            final String fm = msg;
            runOnUiThread(() -> { progress.setVisibility(View.GONE); toast(fm); });
        });
    }

    private void shareMesh() {
        final Mesh m = current;
        if (m == null) { toast("Abra um arquivo primeiro"); return; }
        final boolean ply = m.pointCloud;
        final String name = baseName() + (ply ? ".ply" : ".stl");
        progress.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            try {
                File f = freshShareFile(name);
                try (OutputStream os = new FileOutputStream(f)) {
                    if (ply) Exporter.writePly(m, os); else Exporter.writeStl(m, os);
                }
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    share(f, "application/octet-stream", "Enviar " + name);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { progress.setVisibility(View.GONE); toast("Erro: " + e.getMessage()); });
            }
        });
    }

    private void captureAndShare() {
        final Mesh m = current;
        if (m == null) { toast("Abra um arquivo primeiro"); return; }
        final String caption = m.name + "   X " + f2(m.max[0] - m.min[0]) + " · Y " + f2(m.max[1] - m.min[1])
                + " · Z " + f2(m.max[2] - m.min[2]) + " mm";
        final String measure = devActive ? deviationSummary()
                : (tool == TOOL_MEASURE && measureText != null && hasMeasureResult())
                ? measureText.getText().toString().replace('\n', ' ') : null;
        glView.renderer.capture(bmp -> worker.execute(() -> {
            try {
                Bitmap out = withFooter(bmp, caption, measure);
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File f = freshShareFile("captura_" + ts + ".png");
                try (OutputStream os = new FileOutputStream(f)) {
                    out.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
                runOnUiThread(() -> share(f, "image/png", "Compartilhar imagem"));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Erro na captura: " + e.getMessage()));
            }
        }));
        glView.requestRender();
    }

    private boolean hasMeasureResult() {
        return mPicks.size() >= (diameterMode ? 3 : 2);
    }

    private Bitmap withFooter(Bitmap src, String line1, String line2) {
        float d = getResources().getDisplayMetrics().density;
        int lines = line2 == null ? 2 : 3;
        int fh = Math.round((14 + 20 * lines) * d);
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight() + fh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(src, 0, 0, null);
        Paint bg = new Paint();
        bg.setColor(0xFF12161C);
        c.drawRect(0, src.getHeight(), src.getWidth(), out.getHeight(), bg);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Color.WHITE);
        t.setTextSize(14 * d);
        float y = src.getHeight() + 24 * d;
        c.drawText(line1, 12 * d, y, t);
        if (line2 != null) {
            y += 20 * d;
            t.setColor(0xFFFFD54F);
            c.drawText(line2, 12 * d, y, t);
        }
        y += 20 * d;
        t.setColor(0xFF90A4AE);
        t.setTextSize(12 * d);
        c.drawText("Visualizador 3D · MoviAI Engenharia Digital", 12 * d, y, t);
        return out;
    }

    private File freshShareFile(String name) {
        File dir = ShareProvider.dir(this);
        File[] old = dir.listFiles();
        if (old != null) for (File o : old) o.delete();
        return new File(dir, name);
    }

    private void share(File f, String mime, String title) {
        Uri uri = ShareProvider.uriFor(f);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.setClipData(ClipData.newRawUri(f.getName(), uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, title));
    }

    // ================================================================ marcadores

    private void updateOverlay() {
        List<Float> pts = new ArrayList<>(), ptc = new ArrayList<>(), ln = new ArrayList<>(), lnc = new ArrayList<>();
        float[] yellow = {1f, 0.84f, 0.2f}, magenta = {1f, 0.3f, 0.85f}, cyan = {0.3f, 0.95f, 1f};
        if (tool == TOOL_MEASURE) {
            for (Picker.Hit h : mPicks) add(pts, ptc, h.p, yellow);
            if (!diameterMode && mPicks.size() >= 2) {
                add(ln, lnc, mPicks.get(0).p, yellow);
                add(ln, lnc, mPicks.get(1).p, yellow);
            }
            if (diameterMode && mPicks.size() >= 3) {
                circleLines(mPicks.get(0).p, mPicks.get(1).p, mPicks.get(2).p, ln, lnc, cyan, pts, ptc);
            }
        } else if (tool == TOOL_ANALYZE && showBorders && analysis != null && analysis.boundaryLines.length > 0) {
            float[] bl = analysis.boundaryLines;
            float[] bc = new float[bl.length];
            for (int i = 0; i < bc.length; i += 3) { bc[i] = 1f; bc[i + 1] = 0.25f; bc[i + 2] = 0.25f; }
            glView.renderer.setOverlay(null, null, bl, bc);
            glView.requestRender();
            return;
        } else if (tool == TOOL_COMPARE && comparePick != null) {
            add(pts, ptc, comparePick, new float[]{1f, 1f, 1f});
        } else if (tool == TOOL_ALIGN) {
            for (Picker.Hit h : aPicks) add(pts, ptc, h.p, magenta);
            if (edgeMode && aPicks.size() >= 2) {
                add(ln, lnc, aPicks.get(0).p, magenta);
                add(ln, lnc, aPicks.get(1).p, magenta);
            }
        }
        if (pts.isEmpty() && ln.isEmpty()) glView.renderer.clearOverlay();
        else glView.renderer.setOverlay(arr(pts), arr(ptc), arr(ln), arr(lnc));
        glView.requestRender();
    }

    private void circleLines(double[] a, double[] b, double[] c, List<Float> ln, List<Float> lnc, float[] col,
                             List<Float> pts, List<Float> ptc) {
        double[] cc = Align.circle(a, b, c);
        if (cc == null) return;
        double[] ctr = {cc[0], cc[1], cc[2]};
        double[] u = Align.sub(a, ctr);
        double[] n = Align.cross(Align.sub(b, a), Align.sub(c, a));
        if (!Align.normalize(n)) return;
        double[] v = Align.cross(n, u);
        int seg = 72;
        double[] prev = null;
        for (int i = 0; i <= seg; i++) {
            double t = 2 * Math.PI * i / seg;
            double[] q = {ctr[0] + u[0] * Math.cos(t) + v[0] * Math.sin(t),
                          ctr[1] + u[1] * Math.cos(t) + v[1] * Math.sin(t),
                          ctr[2] + u[2] * Math.cos(t) + v[2] * Math.sin(t)};
            if (prev != null) {
                add(ln, lnc, prev, col);
                add(ln, lnc, q, col);
            }
            prev = q;
        }
        add(pts, ptc, ctr, col);
    }

    private static void add(List<Float> p, List<Float> c, double[] v, float[] col) {
        p.add((float) v[0]); p.add((float) v[1]); p.add((float) v[2]);
        c.add(col[0]); c.add(col[1]); c.add(col[2]);
    }

    private static float[] arr(List<Float> l) {
        float[] a = new float[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    // ================================================================ helpers de UI

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(8), dp(8), dp(8), dp(8));
        return p;
    }

    private TextView text() {
        TextView t = new TextView(this);
        t.setTextColor(0xFFECEFF1);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(dp(6), dp(2), dp(6), dp(8));
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFF90A4AE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(dp(6), dp(8), dp(6), dp(2));
        return t;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            r.addView(v, lp);
        }
        return r;
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setBackground(roundBg(C_BTN));
        b.setOnClickListener(l);
        return b;
    }

    private Button chip(String text, View.OnClickListener l) {
        Button b = btn(text, l);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setLayoutParams(chipParams());
        return b;
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        return lp;
    }

    private Button toolButton(String text, View.OnClickListener l) {
        Button b = btn(text, l);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void setSel(Button b, boolean sel) {
        if (b != null) b.setBackground(roundBg(sel ? C_SEL : C_BTN));
    }

    private GradientDrawable roundBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        return g;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String f2(double v) {
        return String.format(BR, "%.2f", v);
    }

    private static String xyz(double[] p) {
        return "X " + f2(p[0]) + " · Y " + f2(p[1]) + " · Z " + f2(p[2]);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
