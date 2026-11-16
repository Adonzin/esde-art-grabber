package com.esde.artgrabber;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A category's custom look: up to three pictures (main, logo, background), each from a picture file or an installed
 * app's icon, plus a colour used behind app icons. Applying writes the pictures into a theme copy wherever that theme
 * keeps per-system art; every file written is recorded so the look can be updated or removed without touching
 * anything else. Pictures are drawn by a Renderer (Android bitmaps on the device, a stand-in in tests).
 */
public class CategoryLooks {

    public static final String FILE = "file", APP = "app";

    public static class Picture {
        public String kind, value;   // FILE + stored copy's path, or APP + package name
        public String label;         // shown in the editor
        public Picture(String kind, String value, String label) { this.kind = kind; this.value = value; this.label = label; }
    }

    public static class Look {
        public String system, fullName, description = "";
        public String color = "2B3A55";
        public final Map<String, Picture> pictures = new LinkedHashMap<String, Picture>();
        /** theme folder name -> files written there (and in ES-DE's own folder for ~/ slots) */
        public final Map<String, List<String>> applied = new LinkedHashMap<String, List<String>>();
        public boolean isEmpty() { return pictures.isEmpty(); }
    }

    public interface Renderer {
        /**
         * Draws pic at step.width x step.height: cropped to fill if step.cover, otherwise fitted on transparency.
         * App icons on filled slots sit centred on the colour. Returns encoded bytes: step.format for png/jpg/webp,
         * PNG for svg slots (wrapped afterwards).
         */
        byte[] render(Picture pic, ThemeArtPlan.Step step, String colorHex) throws IOException;
    }

    public static class Result {
        public int written, kept;
        public final List<String> skipped = new ArrayList<String>();
        public final List<String> errors = new ArrayList<String>();
    }

    public final File esdeDir;

    public CategoryLooks(File esdeDir) { this.esdeDir = esdeDir; }

    File dir(String system) { return new File(esdeDir, "commander/looks/" + system); }

    /** Folder name ES-DE's "~" means for themes: the one holding ES-DE/. */
    public File home() { return esdeDir.getParentFile(); }

    // ================================================================ storage

    public Look load(String system) {
        Look l = new Look();
        l.system = system;
        String t = EsdeConfig.read(new File(dir(system), "look.json"));
        if (t == null) return l;
        try {
            JSONObject o = new JSONObject(t);
            l.fullName = o.optString("fullName", system);
            l.description = o.optString("description", "");
            l.color = o.optString("color", l.color);
            JSONObject pics = o.optJSONObject("pictures");
            if (pics != null) {
                Iterator<String> it = pics.keys();
                while (it.hasNext()) {
                    String role = it.next();
                    JSONObject p = pics.getJSONObject(role);
                    l.pictures.put(role, new Picture(p.optString("kind"), p.optString("value"), p.optString("label")));
                }
            }
            JSONObject ap = o.optJSONObject("applied");
            if (ap != null) {
                Iterator<String> it = ap.keys();
                while (it.hasNext()) {
                    String theme = it.next();
                    JSONArray a = ap.getJSONArray(theme);
                    List<String> files = new ArrayList<String>();
                    for (int i = 0; i < a.length(); i++) files.add(a.getString(i));
                    l.applied.put(theme, files);
                }
            }
        } catch (JSONException ignored) { }
        return l;
    }

    public void save(Look l) throws IOException {
        try {
            JSONObject pics = new JSONObject();
            for (Map.Entry<String, Picture> e : l.pictures.entrySet())
                pics.put(e.getKey(), new JSONObject().put("kind", e.getValue().kind).put("value", e.getValue().value)
                        .put("label", e.getValue().label == null ? "" : e.getValue().label));
            JSONObject ap = new JSONObject();
            for (Map.Entry<String, List<String>> e : l.applied.entrySet()) ap.put(e.getKey(), new JSONArray(e.getValue()));
            JSONObject o = new JSONObject().put("system", l.system).put("fullName", l.fullName == null ? l.system : l.fullName)
                    .put("description", l.description).put("color", l.color).put("pictures", pics).put("applied", ap);
            EsdeConfig.writeBytes(new File(dir(l.system), "look.json"), o.toString(2).getBytes("UTF-8"));
        } catch (JSONException e) {
            throw new IOException("couldn't save the look");
        }
    }

    /** Keeps a private copy of a chosen picture so the look still works if the original is moved. */
    public Picture storePicture(String system, String role, byte[] data, String label) throws IOException {
        File f = new File(dir(system), role + ".picture");
        EsdeConfig.writeBytes(f, data);
        return new Picture(FILE, f.getAbsolutePath(), label);
    }

    public List<Look> all() {
        List<Look> out = new ArrayList<Look>();
        File[] ds = new File(esdeDir, "commander/looks").listFiles();
        if (ds == null) return out;
        for (File d : ds) if (new File(d, "look.json").isFile()) out.add(load(d.getName()));
        return out;
    }

    // ================================================================ applying

    /** Names a theme may look the category up by (collections: their name, and lower-case just in case). */
    static List<String> names(String system) {
        List<String> n = new ArrayList<String>();
        n.add(system);
        String lower = system.toLowerCase(Locale.ROOT);
        if (!lower.equals(system)) n.add(lower);
        return n;
    }

    /**
     * Writes the look into a theme (normally an ESCommander copy). Files from an earlier apply to this theme are
     * replaced; files the app didn't write are never overwritten.
     */
    public Result apply(Look l, File themeDir, Renderer r) throws IOException {
        Result res = new Result();
        remove(l, themeDir);
        ThemeLayout layout = ThemeLayout.read(themeDir, null);
        Set<String> have = new HashSet<String>(l.pictures.keySet());
        List<String> written = new ArrayList<String>();
        Set<String> doneTargets = new LinkedHashSet<String>();
        for (String name : names(l.system)) {
            ThemeArtPlan plan = ThemeArtPlan.make(layout, themeDir, home(), name, l.fullName == null ? name : l.fullName, l.description, have);
            if (name.equals(l.system)) res.skipped.addAll(plan.skipped);
            for (ThemeArtPlan.Step s : plan.steps) {
                String key = s.target.getAbsolutePath();
                if (!doneTargets.add(key)) continue;
                if (s.target.exists()) { res.kept++; continue; }   // the theme (or you) already has art here: keep it
                try {
                    byte[] data;
                    if (s.infoText != null) data = s.infoText.getBytes("UTF-8");
                    else {
                        byte[] img = r.render(l.pictures.get(s.source), s, l.color);
                        data = "svg".equals(s.format) ? ThemeArtPlan.svgWrapping(img, s.width, s.height).getBytes("UTF-8") : img;
                    }
                    EsdeConfig.writeBytes(s.target, data);
                    written.add(key);
                    res.written++;
                } catch (IOException e) {
                    res.errors.add(s.slot.label + ": " + e.getMessage());
                }
            }
        }
        l.applied.put(themeDir.getName(), written);
        save(l);
        return res;
    }

    /** Deletes what this look wrote into a theme (and empty folders it left). */
    public int remove(Look l, File themeDir) throws IOException {
        List<String> files = l.applied.remove(themeDir.getName());
        if (files == null) return 0;
        int n = 0;
        for (String p : files) {
            File f = new File(p);
            if (f.isFile() && f.delete()) n++;
            File parent = f.getParentFile();
            for (int i = 0; i < 3 && parent != null; i++, parent = parent.getParentFile()) {
                String[] left = parent.list();
                if (left == null || left.length > 0) break;
                parent.delete();
            }
        }
        save(l);
        return n;
    }

    /** After a theme copy is downloaded again: puts every look that was in it back. */
    public List<String> reapplyAll(File themeDir, Renderer r) {
        List<String> report = new ArrayList<String>();
        for (Look l : all()) {
            if (!l.applied.containsKey(themeDir.getName()) || l.isEmpty()) continue;
            // files inside the theme went with the old copy; ones in ES-DE's own folder are still there to replace
            List<String> outside = new ArrayList<String>();
            String root = themeDir.getAbsolutePath() + File.separator;
            for (String p : l.applied.get(themeDir.getName())) if (!p.startsWith(root)) outside.add(p);
            l.applied.put(themeDir.getName(), outside);
            try {
                Result res = apply(l, themeDir, r);
                report.add(l.fullName + ": " + res.written + " files");
            } catch (IOException e) {
                report.add(l.fullName + ": " + e.getMessage());
            }
        }
        return report;
    }

    /** Removes a look completely (when its category is deleted). */
    public void forget(String system) throws IOException {
        Look l = load(system);
        for (String theme : new ArrayList<String>(l.applied.keySet())) remove(l, new File(new File(esdeDir, "themes"), theme));
        EsdeConfig.deleteTree(dir(system));
    }
}
