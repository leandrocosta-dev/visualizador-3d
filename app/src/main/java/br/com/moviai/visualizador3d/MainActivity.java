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
    private static final int REQ_SAVE_DXF = 5;
    private static final int REQ_SAVE_PDF = 6;
    private static final Locale BR = new Locale("pt", "BR");

    private static final int TOOL_NONE = -1, TOOL_OPEN = 0, TOOL_VIEW = 1, TOOL_MEASURE = 2, TOOL_CUT = 3,
            TOOL_ALIGN = 4, TOOL_ANALYZE = 5, TOOL_COMPARE = 6, TOOL_SAVE = 7,
            TOOL_NOTES = 8, TOOL_QUOTE = 9;
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
    private final View[] panels = new View[10];
    private final Button[] toolButtons = new Button[10];
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
    private int measureMode = 0; // 0 distância, 1 diâmetro 3 pts, 2 plano, 3 cilindro, 4 esfera, 5 face×face
    private final Button[] modeBtns = new Button[6];
    // vetor da medição de distância: 0 livre, 1 X, 2 Y, 3 Z, 4 normal no 1º ponto, 5 do último ajuste
    private int distDir = 0;
    private static final String[] DIR_NAMES = {"Livre", "X", "Y", "Z", "Normal", "Ajuste"};
    private final Button[] dirBtns = new Button[6];
    private LinearLayout dirRow;
    private double[] normalDir;
    private Picker.Hit normalFor;
    private int regionSize = 1;
    private static final String[] REGION_NAMES = {"Região: pequena", "Região: média", "Região: grande"};
    private static final double[] REGION_FRAC = {0.03, 0.10, 0.35};
    private Button regionBtn;
    private Fit.Topology topo;
    private volatile Mesh topoMesh;
    private Fit.Result fitPrev, fitCur;

    // Perfil 2D
    private FrameLayout sectionOverlay;
    private SectionView sectionView;
    private TextView sectionText;
    private Section curSection;
    private final List<double[]> secPicks = new ArrayList<>();
    private boolean secRadius = false;
    private Button secDistBtn, secRadBtn;

    // Relatório
    private final ArrayList<String> reportItems = new ArrayList<>();
    private Button itemsBtn;
    private Uri pendingPdfUri;
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

    // Notas
    private NotesView notesView;
    private final List<NotesView.Note> notes = new ArrayList<>();
    private LinearLayout notesList;

    // Espessura
    private static final double[] THICK_MIN = {0.6, 0.8, 1.0, 1.2, 1.5, 2.0, 3.0};
    private int thickIdx = 2;
    private boolean thickActive = false;
    private Thickness.Result thickRes;
    private float[] thickVals, thickLiteVals;
    private byte[] thickOrigColors, thickLiteOrigColors;
    private Mesh thickMesh, thickLite;
    private double thickReach;
    private Deviation.RefIndex selfIndex;
    private Mesh selfIndexMesh;
    private String thickPoint;
    private Button thickMinBtn;

    // Orçamento
    private final Quote.Params qp = new Quote.Params();
    private Quote.Result qres;
    private TextView quoteText;
    private Button qTechBtn, qMatBtn, qFillBtn, qQtyBtn;
    private static final double[] INFILLS = {0.10, 0.15, 0.20, 0.30, 0.50, 1.00};

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
        notesView = new NotesView(this);
        root.addView(notesView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        glView.renderer.setFrameListener(notesView::onFrame);

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
        panels[TOOL_NOTES] = buildNotesPanel();
        panels[TOOL_QUOTE] = buildQuotePanel();

        HorizontalScrollView tbScroll = new HorizontalScrollView(this);
        tbScroll.setHorizontalScrollBarEnabled(false);
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(dp(6), dp(4), dp(6), dp(8));
        String[] names = {"Abrir", "Vista", "Medir", "Corte", "Alinhar", "Análise", "Comparar", "Salvar", "Notas", "Orçar"};
        int[] order = {TOOL_OPEN, TOOL_VIEW, TOOL_MEASURE, TOOL_NOTES, TOOL_CUT, TOOL_ALIGN, TOOL_ANALYZE,
                TOOL_COMPARE, TOOL_QUOTE, TOOL_SAVE};
        for (int t : order) {
            toolButtons[t] = toolButton(names[t], v -> selectTool(t));
            toolbar.addView(toolButtons[t]);
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

        root.addView(buildSectionOverlay(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
            sectionOverlay.setPadding(left, top, right, bot);
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
        if (sectionOverlay != null && sectionOverlay.getVisibility() == View.VISIBLE) {
            sectionOverlay.setVisibility(View.GONE);
            return;
        }
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
        else if (req == REQ_SAVE_DXF) saveDxf(uri);
        else if (req == REQ_SAVE_PDF) startReport(uri);
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
        fitPrev = fitCur = null;
        topoMesh = null;
        topo = null;
        thickActive = false;
        thickRes = null;
        thickVals = thickLiteVals = null;
        thickOrigColors = thickLiteOrigColors = null;
        thickMesh = thickLite = null;
        thickPoint = null;
        selfIndex = null;
        selfIndexMesh = null;
        qres = null;
        notes.clear();
        notesView.setNotes(notes);
        refreshNotes();
        refreshQuote();
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
        if (tool != TOOL_MEASURE && tool != TOOL_ALIGN && tool != TOOL_COMPARE && tool != TOOL_NOTES
                && !(tool == TOOL_ANALYZE && thickActive)) return;
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
                else if (forTool == TOOL_NOTES) askNote(h.p);
                else if (forTool == TOOL_ANALYZE) thicknessAt(h);
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
        p.addView(row(btn("Modo apresentação", v -> setPresenting(true)), btn("Ver em realidade aumentada", v -> openAr())));
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
        String[] names = {"Distância", "Ø 3 pts", "Plano", "Cilindro", "Esfera", "Face×Face"};
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            modeBtns[i] = btn(names[i], v -> {
                if (k == 5 || measureMode == 5) fitPrev = fitCur = null;
                measureMode = k;
                mPicks.clear();
                refreshMeasure();
            });
        }
        p.addView(row(modeBtns[0], modeBtns[1], modeBtns[5]));
        p.addView(row(modeBtns[2], modeBtns[3], modeBtns[4]));
        for (int i = 0; i < DIR_NAMES.length; i++) {
            final int k = i;
            dirBtns[i] = btn(DIR_NAMES[i], v -> {
                if (k == 5 && (fitCur == null || fitCur.dir == null)) {
                    toast("Ajuste antes um plano ou cilindro (modos Plano/Cilindro)");
                    return;
                }
                distDir = k;
                ensureNormal();
                refreshMeasure();
            });
        }
        TextView dl = label("Medir na direção:");
        dirRow = new LinearLayout(this);
        dirRow.setOrientation(LinearLayout.VERTICAL);
        dirRow.addView(dl);
        dirRow.addView(row(dirBtns));
        p.addView(dirRow);
        regionBtn = btn(REGION_NAMES[regionSize], v -> {
            regionSize = (regionSize + 1) % REGION_NAMES.length;
            regionBtn.setText(REGION_NAMES[regionSize]);
        });
        p.addView(row(regionBtn,
                btn("Limpar", v -> { mPicks.clear(); fitPrev = fitCur = null; refreshMeasure(); }),
                btn("+ Relatório", v -> addMeasureToReport())));
        p.addView(row(btn("Eixo/normal → +Z", v -> fitAxisToZ()), btn("Origem aqui", v -> fitOrigin())));
        refreshMeasure();
        return p;
    }

    private void addMeasurePick(Picker.Hit h) {
        if (measureMode >= 2) {
            runFit(h);
            return;
        }
        int need = measureMode == 1 ? 3 : 2;
        if (mPicks.size() >= need) mPicks.clear();
        mPicks.add(h);
        if (measureMode == 0) ensureNormal();
        refreshMeasure();
    }

    private void runFit(Picker.Hit h) {
        final Mesh m = current;
        if (m == null || busy) return;
        if (m.pointCloud && m.normals == null && measureMode == 3) {
            toast("Cilindro em nuvem de pontos precisa de normais no arquivo");
            return;
        }
        final int type = measureMode == 5 ? Fit.PLANE : measureMode - 2;
        final boolean pair = measureMode == 5;
        final double frac = REGION_FRAC[regionSize];
        busy = true;
        progress.setVisibility(View.VISIBLE);
        measureText.setText("Ajustando " + Fit.NAMES[type].toLowerCase(BR) + "...");
        worker.execute(() -> {
            Fit.Result r = null;
            String err = null;
            try {
                if (topoMesh != m) {
                    topo = new Fit.Topology(m);
                    topoMesh = m;
                }
                float dx = m.max[0] - m.min[0], dy = m.max[1] - m.min[1], dz = m.max[2] - m.min[2];
                double diag = Math.sqrt(dx * dx + dy * dy + dz * dz);
                int seed = topo.nearest(h.p);
                int[] reg = Fit.grow(topo, seed, frac * diag, 15, type == Fit.PLANE ? 12 : 0);
                r = Fit.fit(topo, reg, type);
                if (r == null) err = "Não foi possível ajustar. Toque no meio da superfície ou aumente a região.";
            } catch (OutOfMemoryError e) {
                err = "Memória insuficiente";
            } catch (Exception e) {
                err = e.toString();
            }
            final Fit.Result fr = r;
            final String fe = err;
            runOnUiThread(() -> {
                busy = false;
                progress.setVisibility(View.GONE);
                if (m != current) return;
                if (fe != null) { toast(fe); refreshMeasure(); return; }
                if (pair && fitPrev != null && fitCur != null) fitCur = null; // 3ª face recomeça o par
                fitPrev = fitCur;
                fitCur = fr;
                refreshMeasure();
            });
        });
    }

    /** Calcula (em segundo plano) a normal da superfície no 1º ponto, por plano ajustado na vizinhança. */
    private void ensureNormal() {
        if (distDir != 4 || mPicks.isEmpty()) return;
        final Picker.Hit h = mPicks.get(0);
        if (normalFor == h && normalDir != null) return;
        final Mesh m = current;
        if (m == null) return;
        normalFor = h;
        normalDir = null;
        worker.execute(() -> {
            float dx = m.max[0] - m.min[0], dy = m.max[1] - m.min[1], dz = m.max[2] - m.min[2];
            double diag = Math.sqrt(dx * dx + dy * dy + dz * dz);
            List<Picker.Hit> one = new ArrayList<>();
            one.add(h);
            double[] n = Align.fitPlaneNormal(m, one, Math.max(diag * 0.012, 1e-6));
            runOnUiThread(() -> {
                if (normalFor != h || m != current) return;
                normalDir = n;
                if (n == null) toast("Não foi possível obter a normal nesse ponto");
                refreshMeasure();
            });
        });
    }

    /** Vetor unitário da medição atual, ou null se livre / indisponível. */
    private double[] measureDir() {
        switch (distDir) {
            case 1: return new double[]{1, 0, 0};
            case 2: return new double[]{0, 1, 0};
            case 3: return new double[]{0, 0, 1};
            case 4: return normalDir;
            case 5: return fitCur != null && fitCur.dir != null ? fitCur.dir : null;
            default: return null;
        }
    }

    private String dirLabel() {
        switch (distDir) {
            case 1: case 2: case 3: return "em " + DIR_NAMES[distDir];
            case 4: return "na normal do 1º ponto";
            case 5: return fitCur != null ? "no " + (fitCur.type == Fit.PLANE ? "vetor normal do plano" : "eixo do cilindro") : "";
            default: return "";
        }
    }

    /** Face × face: ângulo, distância perpendicular e variação ao longo da segunda face. */
    private String describeFaces() {
        if (fitCur == null) {
            return "Toque a 1ª face plana (fica laranja) e depois a 2ª (fica verde). "
                    + "A face inteira é reconhecida até as arestas vivas, então o ruído e a paralaxe não afetam.";
        }
        if (fitPrev == null) {
            return "Face 1: planicidade " + f3(fitCur.extent) + " mm · " + String.format(BR, "%,d", fitCur.count)
                    + " pts\nToque a 2ª face.";
        }
        Fit.Result a = fitPrev, b = fitCur;
        double ang = Fit.angle(a, b);
        double dAB = Math.abs(Align.dot(Align.sub(b.point, a.point), a.dir));
        double dBA = Math.abs(Align.dot(Align.sub(a.point, b.point), b.dir));
        double dist = (dAB + dBA) / 2;
        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        Fit.Topology t = topo;
        if (t != null && b.region != null) {
            for (int v : b.region) {
                double dd = Math.abs((t.pos[v * 3] - a.point[0]) * a.dir[0] + (t.pos[v * 3 + 1] - a.point[1]) * a.dir[1]
                        + (t.pos[v * 3 + 2] - a.point[2]) * a.dir[2]);
                if (dd < mn) mn = dd;
                if (dd > mx) mx = dd;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (ang < 2) {
            sb.append("Faces paralelas · distância ").append(f3(dist)).append(" mm")
              .append(String.format(BR, " · ângulo %.2f°", ang));
        } else if (Math.abs(ang - 90) < 2) {
            sb.append("Faces perpendiculares · ").append(String.format(BR, "ângulo %.2f°", ang));
        } else {
            sb.append(String.format(BR, "Ângulo entre faces %.2f°", ang))
              .append(" · distância no centro ").append(f3(dist)).append(" mm");
        }
        if (mn <= mx) sb.append("\nVariação ao longo da face 2: ").append(f3(mn)).append(" a ").append(f3(mx)).append(" mm");
        sb.append("\nPlanicidade: face 1 ").append(f3(a.extent)).append(" · face 2 ").append(f3(b.extent)).append(" mm");
        return sb.toString();
    }

    private String describeFit(Fit.Result r) {
        switch (r.type) {
            case Fit.CYLINDER:
                return String.format(BR, "Cilindro Ø %.3f mm (raio %.3f)\nEixo (%.3f; %.3f; %.3f) · altura %.2f · RMS %.3f · %,d pts",
                        2 * r.radius, r.radius, r.dir[0], r.dir[1], r.dir[2], r.extent, r.rms, r.count);
            case Fit.SPHERE:
                return String.format(BR, "Esfera Ø %.3f mm (raio %.3f)\nCentro %s · RMS %.3f · %,d pts",
                        2 * r.radius, r.radius, xyz(r.point), r.rms, r.count);
            default:
                return String.format(BR, "Plano: planicidade %.3f mm · RMS %.3f\nNormal (%.3f; %.3f; %.3f) · %,d pts",
                        r.extent, r.rms, r.dir[0], r.dir[1], r.dir[2], r.count);
        }
    }

    private void refreshMeasure() {
        if (measureText == null) return;
        for (int i = 0; i < modeBtns.length; i++) setSel(modeBtns[i], i == measureMode);
        regionBtn.setVisibility(measureMode >= 2 ? View.VISIBLE : View.INVISIBLE);
        if (distDir == 5 && (fitCur == null || fitCur.dir == null)) distDir = 0;
        dirRow.setVisibility(measureMode == 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < dirBtns.length; i++) setSel(dirBtns[i], i == distDir);
        int n = mPicks.size();
        String s;
        if (measureMode == 0) {
            if (n < 2) {
                s = n == 0 ? "Toque o primeiro ponto na peça." : "Ponto 1: " + xyz(mPicks.get(0).p) + "\nToque o segundo ponto.";
            } else {
                double[] a = mPicks.get(0).p, b = mPicks.get(1).p;
                double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double[] dir = measureDir();
                if (distDir == 0) {
                    s = "Distância: " + f3(d) + " mm\nΔX " + f3(Math.abs(dx)) + " · ΔY " + f3(Math.abs(dy)) + " · ΔZ " + f3(Math.abs(dz));
                } else if (dir == null) {
                    s = "Calculando a normal no 1º ponto...";
                } else {
                    double proj = Math.abs(dx * dir[0] + dy * dir[1] + dz * dir[2]);
                    double lat = Math.sqrt(Math.max(0, d * d - proj * proj));
                    s = "Distância " + dirLabel() + ": " + f3(proj) + " mm\n3D " + f3(d)
                            + " · desalinhamento lateral " + f3(lat) + " mm";
                    if (distDir == 4 || distDir == 5) {
                        s += String.format(BR, "\nVetor (%.3f; %.3f; %.3f)", dir[0], dir[1], dir[2]);
                    }
                }
            }
        } else if (measureMode == 1) {
            if (n < 3) {
                s = "Toque 3 pontos na borda do furo ou do arco (" + n + " de 3).";
            } else {
                double[] c = Align.circle(mPicks.get(0).p, mPicks.get(1).p, mPicks.get(2).p);
                if (c == null) s = "Os 3 pontos estão alinhados. Toque novamente.";
                else s = "Ø " + f3(2 * c[3]) + " mm · raio " + f3(c[3]) + "\nCentro " + xyz(c);
            }
        } else if (measureMode == 5) {
            s = describeFaces();
        } else {
            if (fitCur == null) {
                s = "Toque no meio da superfície (" + Fit.NAMES[measureMode - 2].toLowerCase(BR)
                        + "). A região cresce até as arestas vivas, limitada pelo tamanho escolhido.";
            } else {
                s = describeFit(fitCur);
                if (fitPrev != null) {
                    double ang = Fit.angle(fitPrev, fitCur);
                    double dist = Fit.distance(fitPrev, fitCur);
                    s += "\nEm relação ao anterior (" + Fit.NAMES[fitPrev.type] + "):";
                    if (!Double.isNaN(ang)) s += String.format(BR, " ângulo %.2f°", ang);
                    s += String.format(BR, " · distância %.3f mm", dist);
                }
            }
        }
        measureText.setText(s);
        updateOverlay();
    }

    private void addMeasureToReport() {
        if (!hasMeasureResult()) { toast("Faça uma medição primeiro"); return; }
        addReportItem(measureText.getText().toString());
    }

    private void addReportItem(String s) {
        reportItems.add(s.replace("\n", " · "));
        toast("Adicionado ao relatório (" + reportItems.size() + ")");
        refreshItemsBtn();
    }

    private void fitAxisToZ() {
        final Fit.Result r = fitCur;
        if (r == null || r.dir == null) { toast("Ajuste um plano ou cilindro primeiro"); return; }
        runTransform(() -> Mat4.about(Mat4.rotationBetween(r.dir, new double[]{0, 0, 1}),
                r.point[0], r.point[1], r.point[2]), null);
    }

    private void fitOrigin() {
        final Fit.Result r = fitCur;
        if (r == null) { toast("Ajuste uma primitiva primeiro"); return; }
        runTransform(() -> Mat4.translation(-r.point[0], -r.point[1], -r.point[2]), null);
    }

    private void fitOverlay(Fit.Result r, float[] col, List<Float> pts, List<Float> ptc, List<Float> ln, List<Float> lnc) {
        Fit.Topology t = topo;
        if (r == null || t == null || topoMesh != current) return;
        int step = Math.max(1, r.region.length / 3000);
        for (int i = 0; i < r.region.length; i += step) {
            int v = r.region[i];
            add(pts, ptc, new double[]{t.pos[v * 3], t.pos[v * 3 + 1], t.pos[v * 3 + 2]}, col);
        }
        if (r.type == Fit.CYLINDER) {
            double h = Math.max(r.extent / 2, r.radius);
            double[] a = {r.point[0] - r.dir[0] * h, r.point[1] - r.dir[1] * h, r.point[2] - r.dir[2] * h};
            double[] b = {r.point[0] + r.dir[0] * h, r.point[1] + r.dir[1] * h, r.point[2] + r.dir[2] * h};
            add(ln, lnc, a, col);
            add(ln, lnc, b, col);
        } else if (r.type == Fit.PLANE) {
            double len = Math.max(current.radius * 0.15, 1e-3);
            add(ln, lnc, r.point, col);
            add(ln, lnc, new double[]{r.point[0] + r.dir[0] * len, r.point[1] + r.dir[1] * len, r.point[2] + r.dir[2] * len}, col);
        }
        add(pts, ptc, r.point, new float[]{1f, 1f, 1f});
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
        p.addView(row(btn("Perfil 2D (medir e DXF)", v -> openSection())));
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
            runOnUiThread(() -> {
                transformNotes(inv);
                afterTransform(m, null);
            });
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
            runOnUiThread(() -> {
                transformNotes(inv);
                afterTransform(m, null);
            });
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
                transformNotes(res);
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
        clearThickness();
        qres = null;
        refreshQuote();
        pointInfo = null;
        comparePick = null;
        fitPrev = fitCur = null;
        topoMesh = null;
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
        p.addView(label("Espessura de parede:"));
        thickMinBtn = btn("Mín. " + f2(THICK_MIN[thickIdx]) + " mm", v -> {
            thickIdx = (thickIdx + 1) % THICK_MIN.length;
            thickMinBtn.setText("Mín. " + f2(THICK_MIN[thickIdx]) + " mm");
            if (thickActive) {
                if (THICK_MIN[thickIdx] * 6 > thickReach) computeThickness();
                else recolorThickness();
            }
        });
        p.addView(row(btn("Mapa de espessura", v -> computeThickness()), thickMinBtn, btn("Limpar", v -> {
            clearThickness();
            refreshAnalysis();
            updateOverlay();
        })));
        p.addView(row(btn("+ Relatório", v -> {
            if (thickActive) addReportItem(thicknessSummary());
            else if (analysis != null) addReportItem(analyzeText.getText().toString());
            else toast("Analise a malha primeiro");
        })));
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
                if (quoteAfterAnalysis) {
                    quoteAfterAnalysis = false;
                    updateQuote();
                }
            });
        });
    }

    private void refreshAnalysis() {
        if (analyzeText == null) return;
        MeshAnalysis a = analysis;
        if (thickActive && thickRes != null) {
            String t = thicknessSummary();
            if (thickPoint != null) t += "\n" + thickPoint;
            if (a != null) t += "\n\n" + analysisText(a);
            analyzeText.setText(t);
            return;
        }
        if (a == null) {
            analyzeText.setText("Verifica se a malha está fechada (pronta para impressão ou CAD), mostra furos e bordas abertas em vermelho, ilhas soltas, área, volume e massa estimada.");
            return;
        }
        analyzeText.setText(analysisText(a));
    }

    private String analysisText(MeshAnalysis a) {
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
        return s.toString().trim();
    }

    // ---------------------------------------------------------------- Espessura de parede

    private void computeThickness() {
        final Mesh m = current;
        if (m == null) { toast("Abra um arquivo primeiro"); return; }
        if (m.pointCloud) { toast("Nuvem de pontos não tem superfície para medir espessura"); return; }
        if (busy) return;
        clearDeviation();
        busy = true;
        progress.setVisibility(View.VISIBLE);
        analyzeText.setText("Calculando espessura...");
        final Mesh l = currentLite;
        final double reach = THICK_MIN[thickIdx] * 6;
        worker.execute(() -> {
            Thickness.Result r = null, rl = null;
            String err = null;
            try {
                if (topoMesh != m) {
                    topo = new Fit.Topology(m);
                    topoMesh = m;
                }
                if (selfIndexMesh != m) {
                    selfIndex = new Deviation.RefIndex(m);
                    selfIndexMesh = m;
                }
                final int[] last = {-1};
                r = Thickness.compute(m, topo, selfIndex, reach, pct -> {
                    if (pct / 5 != last[0]) {
                        last[0] = pct / 5;
                        runOnUiThread(() -> { if (busy) analyzeText.setText("Calculando espessura... " + pct + "%"); });
                    }
                });
                if (l != null) {
                    rl = Thickness.compute(l, new Fit.Topology(l), new Deviation.RefIndex(l), reach, null);
                }
            } catch (OutOfMemoryError e) {
                err = "Memória insuficiente para o mapa de espessura";
            } catch (Exception e) {
                err = e.toString();
            }
            final Thickness.Result fr = r, frl = rl;
            final String fe = err;
            runOnUiThread(() -> {
                busy = false;
                progress.setVisibility(View.GONE);
                if (m != current) return;
                if (fe != null) { toast(fe); refreshAnalysis(); return; }
                if (!thickActive) {
                    thickOrigColors = m.colors;
                    thickLiteOrigColors = l != null ? l.colors : null;
                }
                thickMesh = m;
                thickLite = l;
                thickRes = fr;
                thickVals = fr.perVertex;
                thickLiteVals = frl != null ? frl.perVertex : null;
                thickReach = reach;
                thickPoint = null;
                thickActive = true;
                recolorThickness();
            });
        });
    }

    private void recolorThickness() {
        if (!thickActive) return;
        double mn = THICK_MIN[thickIdx];
        thickMesh.colors = Thickness.colorize(thickVals, mn);
        if (thickLite != null && thickLiteVals != null) thickLite.colors = Thickness.colorize(thickLiteVals, mn);
        glView.renderer.reupload();
        glView.requestRender();
        refreshAnalysis();
        updateOverlay();
    }

    private void clearThickness() {
        if (!thickActive) return;
        if (thickMesh != null) thickMesh.colors = thickOrigColors;
        if (thickLite != null) thickLite.colors = thickLiteOrigColors;
        thickActive = false;
        thickRes = null;
        thickVals = thickLiteVals = null;
        thickMesh = thickLite = null;
        thickPoint = null;
        glView.renderer.reupload();
        glView.requestRender();
    }

    private String thicknessSummary() {
        Thickness.Result r = thickRes;
        double mn = THICK_MIN[thickIdx];
        StringBuilder s = new StringBuilder();
        if (r == null || Double.isNaN(r.min)) {
            s.append("Espessura: nenhuma parede abaixo de ").append(f2(thickReach)).append(" mm");
        } else {
            s.append("Espessura mínima encontrada: ").append(f3(r.min)).append(" mm (marcador branco)");
        }
        s.append(String.format(BR, "\n%.1f%% da superfície abaixo de %.2f mm", Thickness.percentBelow(thickVals, mn), mn));
        s.append("\nVermelho < mín. · amarelo até 2× · verde até 3× · azul: grossa");
        return s.toString();
    }

    private void thicknessAt(Picker.Hit h) {
        final Deviation.RefIndex idx = selfIndex;
        if (idx == null || h.normal == null) return;
        final double[] p = h.p;
        final double[] n = h.normal;
        worker.execute(() -> {
            double sg = idx.sign;
            double eps = Math.max(idx.diag * 1e-5, 1e-4);
            double d = idx.raycast(p[0], p[1], p[2], -n[0] * sg, -n[1] * sg, -n[2] * sg, eps, idx.diag, true);
            runOnUiThread(() -> {
                thickPoint = Double.isNaN(d) ? "Ponto tocado: sem parede oposta"
                        : "Ponto tocado: espessura " + f3(d) + " mm";
                comparePick = p;
                refreshAnalysis();
                updateOverlay();
            });
        });
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
        p.addView(row(btn("+ Relatório", v -> {
            if (pointInfo != null) addReportItem("Comparação · " + pointInfo);
            else if (devActive) addReportItem(deviationSummary());
            else toast("Calcule os desvios ou toque num ponto");
        })));
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
        clearThickness();
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

    // ---------------------------------------------------------------- Perfil 2D

    private View buildSectionOverlay() {
        sectionOverlay = new FrameLayout(this);
        sectionOverlay.setBackgroundColor(0xFF12161C);
        sectionOverlay.setVisibility(View.GONE);
        sectionOverlay.setClickable(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        sectionText = text();
        sectionText.setPadding(dp(12), dp(10), dp(12), dp(6));
        col.addView(sectionText);
        sectionView = new SectionView(this);
        sectionView.setTapListener(this::onSectionTap);
        col.addView(sectionView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout bar = panel();
        secDistBtn = btn("Distância", v -> { secRadius = false; secPicks.clear(); refreshSection(); });
        secRadBtn = btn("Raio 3 pts", v -> { secRadius = true; secPicks.clear(); refreshSection(); });
        bar.addView(row(secDistBtn, secRadBtn,
                btn("Limpar", v -> { secPicks.clear(); refreshSection(); }),
                btn("+ Relatório", v -> {
                    if (secPicks.size() >= (secRadius ? 3 : 2)) addReportItem(sectionHeader() + " · " + sectionMeasure());
                    else toast("Faça uma medição no perfil primeiro");
                })));
        bar.addView(row(
                btn("Salvar DXF...", v -> {
                    if (curSection == null) return;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/dxf");
                    i.putExtra(Intent.EXTRA_TITLE, dxfName());
                    startActivityForResult(i, REQ_SAVE_DXF);
                }),
                btn("Enviar DXF", v -> shareDxf()),
                btn("Fechar", v -> sectionOverlay.setVisibility(View.GONE))));
        col.addView(bar);
        sectionOverlay.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return sectionOverlay;
    }

    private void openSection() {
        final Mesh m = current;
        if (m == null) { toast("Abra um arquivo primeiro"); return; }
        if (m.pointCloud) { toast("Nuvem de pontos não tem superfície para cortar"); return; }
        if (clipAxis < 0) { toast("Escolha o eixo do corte (X, Y ou Z) primeiro"); return; }
        if (busy) return;
        final int axis = clipAxis;
        final double pos = m.min[axis] + clipFrac * (m.max[axis] - m.min[axis]);
        busy = true;
        progress.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            Section sec = null;
            String err = null;
            try {
                sec = Section.slice(m, axis, pos);
            } catch (OutOfMemoryError e) {
                err = "Memória insuficiente";
            } catch (Exception e) {
                err = e.toString();
            }
            final Section fs = sec;
            final String fe = err;
            runOnUiThread(() -> {
                busy = false;
                progress.setVisibility(View.GONE);
                if (fe != null) { toast(fe); return; }
                if (fs.polylines.isEmpty()) { toast("O plano não corta a peça nessa posição"); return; }
                curSection = fs;
                secPicks.clear();
                sectionView.setSection(fs);
                sectionOverlay.setVisibility(View.VISIBLE);
                refreshSection();
            });
        });
    }

    private void onSectionTap(double u, double v, double tol) {
        if (curSection == null) return;
        double[] n = curSection.nearest(u, v);
        if (n[2] > tol) { toast("Toque mais perto do contorno"); return; }
        int need = secRadius ? 3 : 2;
        if (secPicks.size() >= need) secPicks.clear();
        secPicks.add(new double[]{n[0], n[1]});
        refreshSection();
    }

    private String sectionHeader() {
        Section s = curSection;
        return "Seção em " + Section.axisName(s.axis) + " = " + f2(s.value) + " mm";
    }

    private String sectionMeasure() {
        Section s = curSection;
        String un = Section.axisName(s.uAxis), vn = Section.axisName(s.vAxis);
        if (!secRadius) {
            double[] a = secPicks.get(0), b = secPicks.get(1);
            double du = b[0] - a[0], dv = b[1] - a[1];
            return "Distância " + f3(Math.hypot(du, dv)) + " mm (Δ" + un + " " + f3(Math.abs(du)) + " · Δ" + vn + " " + f3(Math.abs(dv)) + ")";
        }
        double[] c = Align.circle(new double[]{secPicks.get(0)[0], secPicks.get(0)[1], 0},
                new double[]{secPicks.get(1)[0], secPicks.get(1)[1], 0},
                new double[]{secPicks.get(2)[0], secPicks.get(2)[1], 0});
        if (c == null) return "pontos alinhados";
        return "Raio " + f3(c[3]) + " mm (Ø " + f3(2 * c[3]) + ") · centro " + un + " " + f3(c[0]) + " · " + vn + " " + f3(c[1]);
    }

    private void refreshSection() {
        Section s = curSection;
        if (s == null) return;
        setSel(secDistBtn, !secRadius);
        setSel(secRadBtn, secRadius);
        int closedCount = 0;
        for (boolean b : s.closed) if (b) closedCount++;
        StringBuilder t = new StringBuilder(sectionHeader());
        t.append(String.format(BR, " · %d contorno%s (%d fechado%s) · %,d pontos",
                s.polylines.size(), s.polylines.size() == 1 ? "" : "s", closedCount, closedCount == 1 ? "" : "s", s.pointCount()));
        int need = secRadius ? 3 : 2;
        if (secPicks.size() >= need) t.append('\n').append(sectionMeasure());
        else t.append('\n').append(secRadius ? "Toque 3 pontos no arco (" : "Toque 2 pontos no contorno (")
              .append(secPicks.size()).append(" de ").append(need).append("). Pinça: zoom · toque duplo: enquadrar.");
        sectionText.setText(t.toString());
        sectionView.setMarks(secPicks, secRadius);
    }

    private String dxfName() {
        Section s = curSection;
        return baseName() + "_secao_" + Section.axisName(s.axis) + String.format(java.util.Locale.US, "%.2f", s.value).replace('.', '_').replace('-', 'm') + ".dxf";
    }

    private void saveDxf(Uri uri) {
        final Section s = curSection;
        if (s == null) return;
        final ContentResolver cr = getContentResolver();
        worker.execute(() -> {
            String msg;
            try (OutputStream os = cr.openOutputStream(uri, "wt")) {
                if (os == null) throw new java.io.IOException("Destino inválido");
                s.writeDxf(os);
                msg = "DXF salvo";
            } catch (Exception e) {
                msg = "Erro ao salvar: " + e.getMessage();
            }
            final String fm = msg;
            runOnUiThread(() -> toast(fm));
        });
    }

    private void shareDxf() {
        final Section s = curSection;
        if (s == null) return;
        final String name = dxfName();
        worker.execute(() -> {
            try {
                File f = freshShareFile(name);
                try (OutputStream os = new FileOutputStream(f)) {
                    s.writeDxf(os);
                }
                runOnUiThread(() -> share(f, "application/dxf", "Enviar DXF"));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Erro: " + e.getMessage()));
            }
        });
    }

    // ---------------------------------------------------------------- Relatório

    private void refreshItemsBtn() {
        if (itemsBtn != null) itemsBtn.setText("Itens: " + reportItems.size() + " · limpar");
    }

    private void showReportDialog(Runnable after) {
        final android.content.SharedPreferences sp = getSharedPreferences("relatorio", MODE_PRIVATE);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(8), dp(20), 0);
        final android.widget.EditText cli = field(l, "Cliente", sp.getString("cliente", ""));
        final android.widget.EditText pec = field(l, "Peça / ordem de serviço", sp.getString("peca", ""));
        final android.widget.EditText res = field(l, "Responsável técnico (nome e CREA)", sp.getString("responsavel", ""));
        final android.widget.EditText obs = field(l, "Observações", sp.getString("obs", ""));
        new android.app.AlertDialog.Builder(this)
                .setTitle("Dados do relatório")
                .setView(l)
                .setPositiveButton(after == null ? "Salvar" : "Gerar PDF", (d, w) -> {
                    sp.edit().putString("cliente", cli.getText().toString().trim())
                            .putString("peca", pec.getText().toString().trim())
                            .putString("responsavel", res.getText().toString().trim())
                            .putString("obs", obs.getText().toString().trim())
                            .putBoolean("definido", true).apply();
                    if (after != null) after.run();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private android.widget.EditText field(LinearLayout l, String hint, String value) {
        TextView lab = label(hint);
        l.addView(lab);
        android.widget.EditText e = new android.widget.EditText(this);
        e.setText(value);
        e.setSingleLine(!hint.startsWith("Observ"));
        l.addView(e);
        return e;
    }

    /** Gera o PDF. target null = compartilhar; senão grava no arquivo escolhido. */
    private void startReport(Uri target) {
        if (current == null) { toast("Abra um arquivo primeiro"); return; }
        if (busy) return;
        if (!getSharedPreferences("relatorio", MODE_PRIVATE).getBoolean("definido", false)) {
            showReportDialog(() -> startReport(target));
            return;
        }
        pendingPdfUri = target;
        busy = true;
        progress.setVisibility(View.VISIBLE);
        toast("Gerando relatório...");
        if (tool != TOOL_NONE && tool != TOOL_MEASURE && tool != TOOL_COMPARE && tool != TOOL_ANALYZE) selectTool(tool);
        final float[] cam = glView.renderer.saveCamera();
        glView.renderer.setBackground(0.96f, 0.97f, 0.98f);
        final Object[] views = {null, "ISO", ModelRenderer.VIEW_FRONT, ModelRenderer.VIEW_TOP};
        final List<Bitmap> imgs = new ArrayList<>();
        captureChain(views, 0, imgs, () -> {
            glView.renderer.restoreCamera(cam);
            glView.renderer.setDefaultBackground();
            glView.requestRender();
            buildPdf(imgs);
        });
    }

    private void captureChain(Object[] views, int i, List<Bitmap> out, Runnable done) {
        if (i >= views.length) { done.run(); return; }
        Object v = views[i];
        if ("ISO".equals(v)) glView.renderer.resetView();
        else if (v instanceof float[][]) glView.renderer.setView((float[][]) v);
        glView.renderer.capture(bmp0 -> {
            final float[] cm = glView.renderer.lastMvp;
            runOnUiThread(() -> {
            Bitmap bmp = bmp0;
            if (!notes.isEmpty() && cm != null) {
                bmp = bmp0.copy(Bitmap.Config.ARGB_8888, true);
                NotesView.draw(new Canvas(bmp), notes, cm, bmp.getWidth(), bmp.getHeight(),
                        getResources().getDisplayMetrics().density, 1f);
            }
            int w = bmp.getWidth(), h = bmp.getHeight();
            float f = Math.min(1f, 1000f / Math.max(w, h));
            out.add(f < 1f ? Bitmap.createScaledBitmap(bmp, Math.round(w * f), Math.round(h * f), true) : bmp);
            captureChain(views, i + 1, out, done);
            });
        });
        glView.requestRender();
    }

    private void buildPdf(List<Bitmap> imgs) {
        final Mesh m = current;
        android.content.SharedPreferences sp = getSharedPreferences("relatorio", MODE_PRIVATE);
        final Report.Data d = new Report.Data();
        d.title = devActive ? "Relatório de inspeção dimensional" : "Relatório dimensional";
        d.date = new SimpleDateFormat("dd/MM/yyyy HH:mm", BR).format(new Date());
        String cli = sp.getString("cliente", ""), pec = sp.getString("peca", ""), res = sp.getString("responsavel", ""), obs = sp.getString("obs", "");
        if (!cli.isEmpty()) d.info.add(new String[]{"Cliente", cli});
        if (!pec.isEmpty()) d.info.add(new String[]{"Peça / OS", pec});
        d.info.add(new String[]{"Arquivo do scan", m.name});
        if (refMesh != null) d.info.add(new String[]{"Referência CAD", refMesh.name});
        d.info.add(new String[]{"Dimensões (X · Y · Z)", f2(m.max[0] - m.min[0]) + " · " + f2(m.max[1] - m.min[1]) + " · " + f2(m.max[2] - m.min[2]) + " mm"});
        d.info.add(new String[]{"Malha", String.format(BR, "%,d vértices", m.vertexCount)
                + (m.pointCloud ? " (nuvem de pontos)" : String.format(BR, " · %,d triângulos", m.triangleCount))});
        if (!history.isEmpty()) d.info.add(new String[]{"Alinhamento", "malha alinhada ao sistema de coordenadas de trabalho"});
        if (!res.isEmpty()) d.info.add(new String[]{"Responsável técnico", res});
        d.images.addAll(imgs);
        d.captions.add("Vista de trabalho");
        d.captions.add("Isométrica");
        d.captions.add("Frontal");
        d.captions.add("Superior");
        if (devActive && scanDev != null) {
            double tol = TOLS[tolIdx];
            d.legendTol = tol;
            Deviation.Stats st = Deviation.stats(scanDev, tol);
            List<String> c = new ArrayList<>();
            c.add(String.format(BR, "Tolerância: ± %.2f mm", tol));
            c.add(String.format(BR, "Dentro da tolerância: %.1f %% · acima: %.1f %% · abaixo: %.1f %%", st.inTol, st.above, st.below));
            c.add(String.format(BR, "Desvio médio: %+.3f mm · desvio padrão: %.3f mm · RMS: %.3f mm", st.mean, st.std, st.rms));
            c.add(String.format(BR, "Desvio máximo: %+.3f mm · desvio mínimo: %+.3f mm", st.max, st.min));
            if (st.unmatched > 0) {
                c.add(String.format(BR, "Pontos sem correspondência (além de %.2f mm): %.1f %%",
                        Deviation.cutoff(tol), 100.0 * st.unmatched / Math.max(1, st.matched + st.unmatched)));
            }
            if (alignInfo != null) c.add(alignInfo.replace("⚠ ", "Atenção: "));
            d.sections.put("Comparação com o modelo CAD", c);
        }
        if (analysis != null) {
            MeshAnalysis a = analysis;
            List<String> c = new ArrayList<>();
            c.add("Malha fechada (estanque): " + (a.closed ? "sim" : "não"));
            c.add("Área da superfície: " + f2(a.area) + " mm²");
            if (a.closed) c.add("Volume: " + f2(a.volume / 1000.0) + " cm³ · massa em " + MAT_NAMES[material] + ": " + f2(a.volume / 1000.0 * MAT_DENS[material]) + " g");
            c.add(String.format(BR, "Bordas abertas: %,d · contornos (furos): %d · ilhas: %d", a.boundaryEdges, a.holes, a.shells));
            d.sections.put("Análise da malha", c);
        }
        List<String> items = new ArrayList<>();
        for (int i = 0; i < reportItems.size(); i++) items.add((i + 1) + ". " + reportItems.get(i));
        if (thickActive && thickRes != null) {
            List<String> c = new ArrayList<>();
            for (String line : thicknessSummary().split("\n")) c.add(line);
            d.sections.put("Espessura de parede", c);
        }
        d.sections.put("Medições", items);
        if (!notes.isEmpty()) {
            List<String> c = new ArrayList<>();
            for (int i = 0; i < notes.size(); i++) c.add((i + 1) + ". " + notes.get(i).text);
            d.sections.put("Anotações", c);
        }
        if (!obs.isEmpty()) {
            List<String> o = new ArrayList<>();
            o.add(obs);
            d.sections.put("Observações", o);
        }
        final Uri target = pendingPdfUri;
        final ContentResolver cr = getContentResolver();
        final String fname = "Relatorio_" + baseName() + ".pdf";
        worker.execute(() -> {
            String err = null;
            File f = null;
            try {
                if (target != null) {
                    try (OutputStream os = cr.openOutputStream(target, "wt")) {
                        if (os == null) throw new java.io.IOException("Destino inválido");
                        new Report().write(d, os);
                    }
                } else {
                    f = freshShareFile(fname);
                    try (OutputStream os = new FileOutputStream(f)) {
                        new Report().write(d, os);
                    }
                }
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final File ff = f;
            final String fe = err;
            runOnUiThread(() -> {
                busy = false;
                progress.setVisibility(View.GONE);
                if (fe != null) { toast("Erro no relatório: " + fe); return; }
                if (ff != null) share(ff, "application/pdf", "Enviar relatório");
                else toast("Relatório salvo");
            });
        });
    }

    // ---------------------------------------------------------------- Notas

    private View buildNotesPanel() {
        LinearLayout p = panel();
        TextView t = text();
        t.setText("Toque num ponto da peça para criar uma anotação numerada. Ela acompanha a peça e sai na foto e no relatório.");
        p.addView(t);
        notesList = new LinearLayout(this);
        notesList.setOrientation(LinearLayout.VERTICAL);
        p.addView(notesList);
        p.addView(row(btn("Apagar todas", v -> {
            notes.clear();
            notesView.setNotes(notes);
            refreshNotes();
        })));
        refreshNotes();
        return p;
    }

    private void askNote(double[] pt) {
        final android.widget.EditText e = new android.widget.EditText(this);
        e.setHint("Ex.: furo danificado, recriar ressalto");
        LinearLayout l = new LinearLayout(this);
        l.setPadding(dp(20), dp(8), dp(20), 0);
        l.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new android.app.AlertDialog.Builder(this)
                .setTitle("Anotação " + (notes.size() + 1))
                .setView(l)
                .setPositiveButton("Adicionar", (d, w) -> {
                    String txt = e.getText().toString().trim();
                    if (txt.isEmpty()) return;
                    notes.add(new NotesView.Note(pt.clone(), txt));
                    notesView.setNotes(notes);
                    refreshNotes();
                    glView.requestRender();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void refreshNotes() {
        if (notesList == null) return;
        notesList.removeAllViews();
        for (int i = 0; i < notes.size(); i++) {
            final int k = i;
            TextView tv = text();
            tv.setText((i + 1) + ". " + notes.get(i).text);
            Button del = btn("Apagar", v -> {
                notes.remove(k);
                notesView.setNotes(notes);
                refreshNotes();
            });
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(del, new LinearLayout.LayoutParams(dp(84), dp(36)));
            notesList.addView(r);
        }
    }

    /** As notas são pontos da peça: acompanham alinhamentos e desfazer. */
    private void transformNotes(float[] t) {
        if (notes.isEmpty() || t == null) return;
        for (NotesView.Note n : notes) {
            double[] o = new double[4];
            Mat4.transform(t, n.p[0], n.p[1], n.p[2], 1, o);
            n.p = new double[]{o[0], o[1], o[2]};
        }
        notesView.setNotes(notes);
    }

    // ---------------------------------------------------------------- Orçamento

    private View buildQuotePanel() {
        loadQuoteParams();
        LinearLayout p = panel();
        quoteText = text();
        p.addView(quoteText);
        qTechBtn = btn("", v -> {
            qp.tech = qp.tech == Quote.FDM ? Quote.RESIN : Quote.FDM;
            // troca para um material compatível com a tecnologia
            if (Quote.IS_RESIN[qp.material] != (qp.tech == Quote.RESIN)) qp.material = qp.tech == Quote.RESIN ? 4 : 0;
            loadQuoteParams();
            updateQuote();
        });
        qMatBtn = btn("", v -> {
            int i = qp.material;
            do { i = (i + 1) % Quote.MATERIALS.length; } while (Quote.IS_RESIN[i] != (qp.tech == Quote.RESIN));
            qp.material = i;
            loadQuoteParams();
            updateQuote();
        });
        qFillBtn = btn("", v -> {
            if (qp.tech == Quote.RESIN) {
                qp.hollow = !qp.hollow;
            } else {
                int i = 0;
                for (int k = 0; k < INFILLS.length; k++) if (Math.abs(INFILLS[k] - qp.infill) < 1e-6) i = k;
                qp.infill = INFILLS[(i + 1) % INFILLS.length];
            }
            updateQuote();
        });
        p.addView(row(qTechBtn, qMatBtn, qFillBtn));
        qQtyBtn = btn("", v -> {});
        p.addView(row(btn("− peça", v -> { if (qp.qty > 1) qp.qty--; updateQuote(); }), qQtyBtn,
                btn("+ peça", v -> { qp.qty++; updateQuote(); })));
        p.addView(row(btn("Calcular", v -> computeQuote()), btn("Parâmetros...", v -> quoteParamsDialog()),
                btn("Enviar ao cliente", v -> shareQuote())));
        p.addView(row(btn("+ Relatório", v -> {
            if (qres == null) { toast("Calcule o orçamento primeiro"); return; }
            addReportItem("Orçamento · " + quoteText.getText().toString());
        })));
        refreshQuote();
        return p;
    }

    private android.content.SharedPreferences qprefs() {
        return getSharedPreferences("orcamento", MODE_PRIVATE);
    }

    private static final double[] DEFAULT_PRICE = {120, 130, 120, 180, 250, 350};

    private void loadQuoteParams() {
        android.content.SharedPreferences sp = qprefs();
        String t = qp.tech == Quote.FDM ? "fdm" : "res";
        qp.pricePerKg = sp.getFloat("preco_" + qp.material, (float) DEFAULT_PRICE[qp.material]);
        qp.machinePerHour = sp.getFloat("hora_" + t, qp.tech == Quote.FDM ? 6f : 8f);
        qp.setup = sp.getFloat("taxa", 15f);
        qp.margin = sp.getFloat("margem", 60f) / 100.0;
        qp.flow = sp.getFloat("vazao", 8f);
        qp.secPerLayer = sp.getFloat("seg_camada", 8f);
        qp.layer = sp.getFloat("camada_res", 0.05f);
    }

    private void quoteParamsDialog() {
        loadQuoteParams();
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(8), dp(20), 0);
        String mat = Quote.MATERIALS[qp.material];
        final android.widget.EditText price = numField(l, "Preço do " + mat + " (R$/kg)", qp.pricePerKg);
        final android.widget.EditText hour = numField(l, "Hora de máquina " + (qp.tech == Quote.FDM ? "FDM" : "resina") + " (R$/h)", qp.machinePerHour);
        final android.widget.EditText setup = numField(l, "Taxa fixa por peça: preparo e acabamento (R$)", qp.setup);
        final android.widget.EditText margin = numField(l, "Margem sobre o custo (%)", qp.margin * 100);
        final android.widget.EditText flow = numField(l, "FDM: vazão média (mm³/s; 8 normal, 15 rápida)", qp.flow);
        final android.widget.EditText spl = numField(l, "Resina: segundos por camada", qp.secPerLayer);
        final android.widget.EditText lay = numField(l, "Resina: altura de camada (mm)", qp.layer);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Parâmetros do orçamento")
                .setView(wrapScroll(l))
                .setPositiveButton("Salvar", (d, w) -> {
                    String t = qp.tech == Quote.FDM ? "fdm" : "res";
                    qprefs().edit()
                            .putFloat("preco_" + qp.material, num(price, qp.pricePerKg))
                            .putFloat("hora_" + t, num(hour, qp.machinePerHour))
                            .putFloat("taxa", num(setup, qp.setup))
                            .putFloat("margem", num(margin, qp.margin * 100))
                            .putFloat("vazao", Math.max(0.5f, num(flow, qp.flow)))
                            .putFloat("seg_camada", Math.max(0.5f, num(spl, qp.secPerLayer)))
                            .putFloat("camada_res", Math.max(0.01f, num(lay, qp.layer)))
                            .apply();
                    loadQuoteParams();
                    updateQuote();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private View wrapScroll(View v) {
        ScrollView sv = new ScrollView(this);
        sv.addView(v);
        return sv;
    }

    private android.widget.EditText numField(LinearLayout l, String hint, double value) {
        l.addView(label(hint));
        android.widget.EditText e = new android.widget.EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(String.format(Locale.US, "%.2f", value));
        e.setSingleLine(true);
        l.addView(e);
        return e;
    }

    private static float num(android.widget.EditText e, double def) {
        try {
            return Float.parseFloat(e.getText().toString().trim().replace(',', '.'));
        } catch (Exception ex) {
            return (float) def;
        }
    }

    private void computeQuote() {
        final Mesh m = current;
        if (m == null) { toast("Abra um arquivo primeiro"); return; }
        if (m.pointCloud) { toast("Nuvem de pontos não tem volume. Gere a malha antes."); return; }
        if (analysis != null) { updateQuote(); return; }
        runAnalysis();
        // a análise roda em segundo plano; o orçamento é calculado quando ela termina
        quoteText.setText("Calculando área e volume...");
        quoteAfterAnalysis = true;
    }

    private boolean quoteAfterAnalysis = false;

    private void updateQuote() {
        Mesh m = current;
        MeshAnalysis a = analysis;
        if (m == null || a == null) { refreshQuote(); return; }
        double height = m.max[2] - m.min[2];
        qres = Quote.compute(qp, a.area, a.volume, height, a.closed);
        refreshQuote();
    }

    private void refreshQuote() {
        if (quoteText == null) return;
        qTechBtn.setText(qp.tech == Quote.FDM ? "FDM" : "Resina");
        qMatBtn.setText(Quote.MATERIALS[qp.material]);
        qFillBtn.setText(qp.tech == Quote.RESIN ? (qp.hollow ? "Oca (2 mm)" : "Maciça")
                : String.format(BR, "Preench. %.0f%%", qp.infill * 100));
        qQtyBtn.setText(qp.qty + (qp.qty == 1 ? " peça" : " peças"));
        Quote.Result r = qres;
        if (r == null || current == null) {
            quoteText.setText("Estimativa de custo e preço de impressão 3D a partir do volume e da área da peça. "
                    + "A altura usada é o Z atual: alinhe a peça na orientação de impressão antes.");
            return;
        }
        StringBuilder s = new StringBuilder();
        s.append(qp.tech == Quote.FDM ? "FDM" : "Resina").append(" · ").append(Quote.MATERIALS[qp.material]).append(" · ")
         .append(qFillBtn.getText()).append(" · ").append(qQtyBtn.getText()).append('\n');
        s.append(String.format(BR, "Material: %.1f g por peça (%.1f cm³) · %s\n", r.massG, r.usedVolumeCm3, Quote.money(r.material)));
        s.append("Tempo de impressão: ").append(Quote.hours(r.timeH)).append(qp.tech == Quote.FDM && qp.qty > 1 ? " por peça" : "")
         .append(" · máquina ").append(Quote.money(r.machine)).append('\n');
        s.append("Taxa fixa: ").append(Quote.money(r.setup)).append(" · custo total: ").append(Quote.money(r.cost)).append('\n');
        s.append(String.format(BR, "Preço com margem de %.0f%%: ", qp.margin * 100)).append(Quote.money(r.price));
        if (qp.qty > 1) s.append(" (").append(Quote.money(r.unitPrice)).append(" por peça)");
        if (r.approx) s.append("\n⚠ Malha aberta: o volume é aproximado. Feche os furos para um orçamento exato.");
        quoteText.setText(s.toString());
    }

    private void shareQuote() {
        Mesh m = current;
        Quote.Result r = qres;
        if (m == null || r == null) { toast("Calcule o orçamento primeiro"); return; }
        String body = "Orçamento de impressão 3D · MoviAI Engenharia Digital\n\n"
                + "Peça: " + m.name + "\n"
                + "Dimensões: " + f2(m.max[0] - m.min[0]) + " × " + f2(m.max[1] - m.min[1]) + " × " + f2(m.max[2] - m.min[2]) + " mm\n"
                + "Processo: " + (qp.tech == Quote.FDM ? "impressão FDM" : "impressão em resina") + " em " + Quote.MATERIALS[qp.material]
                + (qp.tech == Quote.FDM ? String.format(BR, ", preenchimento %.0f%%", qp.infill * 100) : (qp.hollow ? ", oca" : ", maciça")) + "\n"
                + "Quantidade: " + qp.qty + "\n"
                + "Valor: " + Quote.money(r.price) + (qp.qty > 1 ? " (" + Quote.money(r.unitPrice) + " por peça)" : "") + "\n\n"
                + "Valores estimados a partir do modelo 3D; podem variar após a análise final de orientação e suportes.";
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, body);
        startActivity(Intent.createChooser(i, "Enviar orçamento"));
    }

    // ---------------------------------------------------------------- Realidade aumentada

    private void openAr() {
        Mesh m = current;
        if (m == null) { toast("Abra um arquivo primeiro"); return; }
        try {
            com.google.ar.core.ArCoreApk.Availability av = com.google.ar.core.ArCoreApk.getInstance().checkAvailability(this);
            if (av == com.google.ar.core.ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
                toast("Este celular não é compatível com o ARCore do Google.");
                return;
            }
        } catch (Exception ignored) {
        }
        // malhas grandes usam a versão leve para manter a fluidez da câmera
        // a versão completa vai até 4 milhões de triângulos; acima disso usa a leve
        Mesh l = currentLite;
        boolean heavy = m.pointCloud ? m.vertexCount > 6_000_000 : MeshAnalysis.triCount(m) > 4_000_000;
        ArActivity.pending = (heavy && l != null) ? l : m;
        startActivity(new Intent(this, ArActivity.class));
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
        p.addView(label("Relatório técnico em PDF:"));
        p.addView(row(btn("Gerar e enviar PDF", v -> startReport(null)), btn("Salvar PDF...", v -> {
            if (current == null) { toast("Abra um arquivo primeiro"); return; }
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/pdf");
            i.putExtra(Intent.EXTRA_TITLE, "Relatorio_" + baseName() + ".pdf");
            startActivityForResult(i, REQ_SAVE_PDF);
        })));
        itemsBtn = btn("Itens: 0 · limpar", v -> {
            reportItems.clear();
            refreshItemsBtn();
        });
        p.addView(row(btn("Dados do relatório", v -> showReportDialog(null)), itemsBtn));
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
        final List<NotesView.Note> ns = new ArrayList<>(notes);
        glView.renderer.capture(bmp -> {
            final float[] cm = glView.renderer.lastMvp;
            worker.execute(() -> {
            try {
                Bitmap out = withFooter(bmp, caption, measure);
                if (!ns.isEmpty() && cm != null) {
                    NotesView.draw(new Canvas(out), ns, cm, bmp.getWidth(), bmp.getHeight(),
                            getResources().getDisplayMetrics().density, 1f);
                }
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File f = freshShareFile("captura_" + ts + ".png");
                try (OutputStream os = new FileOutputStream(f)) {
                    out.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
                runOnUiThread(() -> share(f, "image/png", "Compartilhar imagem"));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Erro na captura: " + e.getMessage()));
            }
            });
        });
        glView.requestRender();
    }

    private boolean hasMeasureResult() {
        if (measureMode == 5) return fitPrev != null && fitCur != null;
        if (measureMode >= 2) return fitCur != null;
        return mPicks.size() >= (measureMode == 1 ? 3 : 2);
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
            if (measureMode == 0 && mPicks.size() >= 2) {
                double[] a = mPicks.get(0).p, b = mPicks.get(1).p;
                double[] dir = measureDir();
                if (distDir == 0 || dir == null) {
                    add(ln, lnc, a, yellow);
                    add(ln, lnc, b, yellow);
                } else {
                    float[] gray = {0.6f, 0.62f, 0.66f};
                    double t = (b[0] - a[0]) * dir[0] + (b[1] - a[1]) * dir[1] + (b[2] - a[2]) * dir[2];
                    double[] f = {a[0] + dir[0] * t, a[1] + dir[1] * t, a[2] + dir[2] * t};
                    add(ln, lnc, a, gray);
                    add(ln, lnc, b, gray);
                    add(ln, lnc, f, gray);
                    add(ln, lnc, b, gray);
                    add(ln, lnc, a, yellow);
                    add(ln, lnc, f, yellow);
                }
            }
            if (measureMode == 1 && mPicks.size() >= 3) {
                circleLines(mPicks.get(0).p, mPicks.get(1).p, mPicks.get(2).p, ln, lnc, cyan, pts, ptc);
            }
            if (measureMode >= 2) {
                fitOverlay(fitPrev, new float[]{1f, 0.6f, 0.2f}, pts, ptc, ln, lnc);
                fitOverlay(fitCur, new float[]{0.3f, 1f, 0.5f}, pts, ptc, ln, lnc);
            }
        } else if (tool == TOOL_ANALYZE) {
            float[] bl = null, bc = null;
            if (showBorders && analysis != null && analysis.boundaryLines.length > 0) {
                bl = analysis.boundaryLines;
                bc = new float[bl.length];
                for (int i = 0; i < bc.length; i += 3) { bc[i] = 1f; bc[i + 1] = 0.25f; bc[i + 2] = 0.25f; }
            }
            if (thickActive && thickRes != null && thickRes.minPoint != null) add(pts, ptc, thickRes.minPoint, new float[]{1f, 1f, 1f});
            if (thickActive && thickPoint != null && comparePick != null) add(pts, ptc, comparePick, new float[]{1f, 0.84f, 0.2f});
            if (bl == null && pts.isEmpty()) glView.renderer.clearOverlay();
            else glView.renderer.setOverlay(arr(pts), arr(ptc), bl, bc);
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

    private static String f3(double v) {
        return String.format(BR, "%.3f", v);
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
