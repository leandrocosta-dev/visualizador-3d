package br.com.moviai.visualizador3d;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Projetos salvos no aparelho: pasta com a malha alinhada, a referência CAD, a miniatura e o projeto.json. */
final class ProjectStore {
    private ProjectStore() {}

    static final String MESH = "malha.ply", REF = "referencia.ply", THUMB = "miniatura.png", META = "projeto.json";

    static final class Info {
        String id, name;
        long saved;
        File dir;

        File mesh() { return new File(dir, MESH); }
        File ref() { return new File(dir, REF); }
        File thumb() { return new File(dir, THUMB); }
        File meta() { return new File(dir, META); }
    }

    static File root(Context c) {
        File d = new File(c.getFilesDir(), "projetos");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File newDir(Context c, String id) {
        File d = new File(root(c), id);
        d.mkdirs();
        return d;
    }

    static List<Info> list(Context c) {
        List<Info> out = new ArrayList<>();
        File[] dirs = root(c).listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            File meta = new File(d, META);
            if (!meta.isFile() || !new File(d, MESH).isFile()) continue;
            try {
                JSONObject o = new JSONObject(readText(meta));
                Info i = new Info();
                i.id = d.getName();
                i.dir = d;
                i.name = o.optString("name", d.getName());
                i.saved = o.optLong("saved", d.lastModified());
                out.add(i);
            } catch (Exception ignored) {
            }
        }
        out.sort((a, b) -> Long.compare(b.saved, a.saved));
        return out;
    }

    static void delete(Info i) {
        File[] fs = i.dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        i.dir.delete();
    }

    static String readText(File f) throws java.io.IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void writeText(File f, String s) throws java.io.IOException {
        try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) {
            os.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

}
