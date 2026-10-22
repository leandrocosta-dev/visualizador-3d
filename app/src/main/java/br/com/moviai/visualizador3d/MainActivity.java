package br.com.moviai.visualizador3d;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_OPEN = 1;
    private static final Locale BR = new Locale("pt", "BR");

    private ModelView glView;
    private TextView info;
    private TextView center;
    private ProgressBar progress;
    private Button modeButton;
    private LinearLayout bottomBar;
    private int mode = ModelRenderer.MODE_SOLID;
    private Mesh current;
    private volatile int loadId = 0;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(28, 33, 41));

        glView = new ModelView(this);
        root.addView(glView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        info = new TextView(this);
        info.setTextColor(Color.WHITE);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        info.setPadding(dp(12), dp(8), dp(12), dp(8));
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

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(8), dp(8), dp(8), dp(8));

        Button open = makeButton("Abrir");
        open.setOnClickListener(v -> openPicker());
        modeButton = makeButton("Sólido");
        modeButton.setOnClickListener(v -> cycleMode());
        Button fit = makeButton("Enquadrar");
        fit.setOnClickListener(v -> glView.resetView());
        bottomBar.addView(open, weight());
        bottomBar.addView(modeButton, weight());
        bottomBar.addView(fit, weight());
        root.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // Android 15 desenha atrás das barras do sistema: afasta os controles delas.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            int left = insets.getSystemWindowInsetLeft();
            int right = insets.getSystemWindowInsetRight();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) info.getLayoutParams();
            lp.setMargins(dp(10) + left, dp(10) + top, dp(10) + right, dp(10));
            info.setLayoutParams(lp);
            bottomBar.setPadding(dp(8) + left, dp(8), dp(8) + right, dp(8) + bottom);
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
        if (req == REQ_OPEN && result == RESULT_OK && data != null && data.getData() != null) {
            load(data.getData());
        }
    }

    private void cycleMode() {
        mode = (mode + 1) % 3;
        String[] names = {"Sólido", "Arame", "Pontos"};
        modeButton.setText(names[mode]);
        glView.setMode(mode);
    }

    private void load(Uri uri) {
        final int id = ++loadId;
        final ContentResolver cr = getContentResolver();
        progress.setVisibility(View.VISIBLE);
        center.setVisibility(View.VISIBLE);
        center.setText("Carregando...");

        new Thread(() -> {
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
                runOnUiThread(() -> {
                    if (id != loadId) return;
                    showMesh(m, ms);
                });
            } catch (OutOfMemoryError e) {
                final String n = name;
                runOnUiThread(() -> showError(n, "Arquivo grande demais para a memória do aparelho."));
            } catch (Exception e) {
                final String n = name;
                final String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> showError(n, msg));
            }
        }, "carregador").start();
    }

    private void showMesh(Mesh m, long ms) {
        current = m;
        progress.setVisibility(View.GONE);
        center.setVisibility(View.GONE);
        glView.setMesh(m);

        float dx = m.max[0] - m.min[0], dy = m.max[1] - m.min[1], dz = m.max[2] - m.min[2];
        StringBuilder sb = new StringBuilder();
        sb.append(m.name).append('\n');
        sb.append(m.format).append('\n');
        sb.append(String.format(BR, "%,d vértices", m.vertexCount));
        if (!m.pointCloud) sb.append(String.format(BR, " · %,d triângulos", m.triangleCount));
        sb.append('\n');
        sb.append(String.format(BR, "X %.2f · Y %.2f · Z %.2f", dx, dy, dz));
        sb.append(String.format(BR, "\nCarregado em %.1f s", ms / 1000f));
        info.setText(sb.toString());
        info.setVisibility(View.VISIBLE);
    }

    private void showError(String name, String msg) {
        progress.setVisibility(View.GONE);
        center.setVisibility(View.VISIBLE);
        center.setText("Não foi possível abrir " + name + "\n\n" + msg);
        if (current == null) info.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------ helpers de UI

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setBackground(roundBg(0xCC2E3B4E));
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private GradientDrawable roundBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
