package com.esde.artgrabber;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes ES-DE's configuration for user-made categories: custom systems (custom_systems/es_systems.xml),
 * emulator find rules (custom_systems/es_find_rules.xml), custom collections (collections/custom-*.cfg) and the
 * collection switches in settings/es_settings.xml. Only entries this app created are ever changed, and every
 * file is backed up before a change so it can be undone. No Android code: tested on the desktop.
 */
public class EsdeConfig {

    public static final String KIND_APPS = "apps", KIND_MOONLIGHT = "moonlight", KIND_COLLECTION = "collection";
    static final String MARK = "<!-- ES-DE Commander -->";
    static final String MOONLIGHT_EMU = "MOONLIGHT";
    static final String MOONLIGHT_EXT = ".moonlight";

    /** One category made by this app. */
    public static class Category {
        public String kind, name, fullName, theme, pc;
        public boolean separate;   // collections: shown as their own item on the main screen

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject().put("kind", kind).put("name", name).put("fullName", fullName);
            if (theme != null) o.put("theme", theme);
            if (pc != null) o.put("pc", pc);
            if (separate) o.put("separate", true);
            return o;
        }

        static Category fromJson(JSONObject o) {
            Category c = new Category();
            c.kind = o.optString("kind");
            c.name = o.optString("name");
            c.fullName = o.optString("fullName", c.name);
            c.theme = o.has("theme") ? o.optString("theme") : null;
            c.pc = o.has("pc") ? o.optString("pc") : null;
            c.separate = o.optBoolean("separate");
            return c;
        }

        public String kindLabel() {
            return KIND_APPS.equals(kind) ? "Android app category"
                    : KIND_MOONLIGHT.equals(kind) ? "Moonlight category" : "Collection";
        }
    }

    public final File esdeDir, romDir;

    public EsdeConfig(File esdeDir, File romDir) {
        this.esdeDir = esdeDir;
        this.romDir = romDir;
    }

    File registryFile() { return new File(esdeDir, "commander/categories.json"); }
    File systemsFile() { return new File(esdeDir, "custom_systems/es_systems.xml"); }
    File findRulesFile() { return new File(esdeDir, "custom_systems/es_find_rules.xml"); }
    File settingsFile() { return new File(esdeDir, "settings/es_settings.xml"); }
    File collectionFile(String name) { return new File(esdeDir, "collections/custom-" + name + ".cfg"); }
    File backupRoot() { return new File(esdeDir, "commander/backups"); }

    /** Folder holding a system category's entries. */
    public File folder(Category c) { return new File(romDir, c.name); }

    // ================================================================ registry

    public List<Category> categories() {
        List<Category> out = new ArrayList<Category>();
        String t = read(registryFile());
        if (t == null) return out;
        try {
            JSONArray a = new JSONObject(t).optJSONArray("categories");
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(Category.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) { }
        return out;
    }

    public Category find(String kind, String name) {
        for (Category c : categories()) if (c.kind.equals(kind) && c.name.equalsIgnoreCase(name)) return c;
        return null;
    }

    private void saveRegistry(Tx tx, List<Category> cats) throws IOException {
        try {
            JSONArray a = new JSONArray();
            for (Category c : cats) a.put(c.toJson());
            tx.write(registryFile(), new JSONObject().put("categories", a).toString(2));
        } catch (JSONException e) {
            throw new IOException("couldn't save the category list");
        }
    }

    // ================================================================ names

    /** A system folder name made from a display name: "Streaming & Video" -> "streamingvideo". */
    public static String systemNameFor(String fullName) {
        String n = fullName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        return n.length() > 32 ? n.substring(0, 32) : n;
    }

    /** Why this system name can't be used, or null if it's fine. */
    public String nameProblem(String name, Category editing) {
        if (name == null || name.isEmpty()) return "Give the category a name with at least one letter or number.";
        if (BundledSystems.NAMES.contains(name)) return "\"" + name + "\" is one of ES-DE's own systems. Pick another name.";
        for (Category c : categories()) {
            if (c == editing || (editing != null && c.name.equals(editing.name) && c.kind.equals(editing.kind))) continue;
            if (c.name.equalsIgnoreCase(name)) return "You already have a category called \"" + c.fullName + "\".";
        }
        if (editing == null) {
            if (new File(romDir, name).exists()) return "A folder called \"" + name + "\" already exists in your ROMs folder.";
            if (systemBlock(read(systemsFile()), name) != null) return "Your custom systems file already has a system called \"" + name + "\".";
        }
        return null;
    }

    /** Collection names can't contain these characters (ES-DE removes them). */
    public static String collectionNameFor(String fullName) { return fullName.replaceAll("[*\",./:;<>\\\\|]", "").trim(); }

    /** File-name-safe version of an entry name. */
    public static String fileSafe(String s) { return s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").replaceAll("\\s+", " ").trim(); }

    // ================================================================ Android app categories

    /** Current apps in an app category: entry name -> package. */
    public Map<String, String> apps(Category c) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        File[] fs = folder(c).listFiles();
        if (fs == null) return out;
        Arrays.sort(fs);
        for (File f : fs) {
            if (!f.isFile() || !f.getName().toLowerCase(Locale.ROOT).endsWith(".app")) continue;
            String pkg = read(f);
            out.put(f.getName().substring(0, f.getName().length() - 4), pkg == null ? "" : pkg.trim());
        }
        return out;
    }

    /** Creates or updates an Android app category: apps maps display name -> package name. */
    public void saveAppCategory(Category c, Map<String, String> apps) throws IOException {
        c.kind = KIND_APPS;
        Tx tx = new Tx("Android app category \"" + c.fullName + "\"");
        File dir = folder(c);
        Set<String> keep = new LinkedHashSet<String>();
        for (Map.Entry<String, String> e : apps.entrySet()) {
            String file = fileSafe(e.getKey());
            if (file.isEmpty()) file = e.getValue();
            keep.add(file + ".app");
            File f = new File(dir, file + ".app");
            String want = e.getValue().trim() + "\n";
            if (!want.equals(read(f))) tx.write(f, want);
        }
        removeEntries(tx, dir, ".app", keep);
        String block = "    <system>\n"
                + "        <name>" + x(c.name) + "</name>\n"
                + "        <fullname>" + x(c.fullName) + "</fullname>\n"
                + "        <path>" + x(dir.getAbsolutePath()) + "</path>\n"
                + "        <extension>.app .APP</extension>\n"
                + "        <command label=\"Android App\">%ANDROIDAPP%=%FILEINJECT%</command>\n"
                + "        <platform>android</platform>\n"
                + "        <theme>" + x(c.theme != null ? c.theme : "androidapps") + "</theme>\n"
                + "    </system>";
        putSystem(tx, c.name, block);
        upsert(tx, c);
        tx.commit();
    }

    // ================================================================ Moonlight categories

    public List<String> moonlightGames(Category c) {
        List<String> out = new ArrayList<String>();
        File[] fs = folder(c).listFiles();
        if (fs == null) return out;
        Arrays.sort(fs);
        for (File f : fs) {
            String n = f.getName();
            if (f.isFile() && n.toLowerCase(Locale.ROOT).endsWith(MOONLIGHT_EXT)) out.add(n.substring(0, n.length() - MOONLIGHT_EXT.length()));
        }
        return out;
    }

    /** Characters that can't be in a file name, so a game with them can't be started by name. */
    public static boolean moonlightNameOk(String game) { return fileSafe(game).equals(game.trim()); }

    /**
     * Creates or updates a Moonlight category. Each game is a file named exactly like the game in Moonlight;
     * launching it opens Moonlight's shortcut screen with the computer's name and the game's name.
     */
    public void saveMoonlightCategory(Category c, List<String> games) throws IOException {
        c.kind = KIND_MOONLIGHT;
        if (c.pc == null || c.pc.trim().isEmpty()) throw new IOException("Enter the computer's name as Moonlight shows it.");
        if (c.pc.contains("\"")) throw new IOException("The computer name can't contain quote marks.");
        Tx tx = new Tx("Moonlight category \"" + c.fullName + "\"");
        File dir = folder(c);
        Set<String> keep = new LinkedHashSet<String>();
        for (String g : games) {
            String file = fileSafe(g);
            if (file.isEmpty()) continue;
            keep.add(file + MOONLIGHT_EXT);
            File f = new File(dir, file + MOONLIGHT_EXT);
            if (!f.isFile()) tx.write(f, g.trim() + "\n");
        }
        if (keep.isEmpty() && !dir.isDirectory()) tx.mkdirs(dir);
        removeEntries(tx, dir, MOONLIGHT_EXT, keep);
        String block = "    <system>\n"
                + "        <name>" + x(c.name) + "</name>\n"
                + "        <fullname>" + x(c.fullName) + "</fullname>\n"
                + "        <path>" + x(dir.getAbsolutePath()) + "</path>\n"
                + "        <extension>" + MOONLIGHT_EXT + "</extension>\n"
                + moonlightCommand("Moonlight", "com.limelight", c.pc)
                + moonlightCommand("Moonlight (root build)", "com.limelight.root", c.pc)
                + "        <platform>pc</platform>\n"
                + "        <theme>" + x(c.theme != null ? c.theme : "windows") + "</theme>\n"
                + "    </system>";
        putSystem(tx, c.name, block);
        putMoonlightRule(tx, false);   // no longer needed (older versions wrote one): the command names Moonlight itself
        upsert(tx, c);
        tx.commit();
    }

    /**
     * Opens Moonlight's shortcut screen directly, the same way ES-DE opens Android apps (%ANDROIDAPP%=package/activity),
     * so it doesn't depend on an emulator find rule. ES-DE passes the extras with the intent.
     */
    static String moonlightCommand(String label, String pkg, String pc) {
        return "        <command label=\"" + label + "\">%ANDROIDAPP%=" + pkg + "/com.limelight.ShortcutTrampoline %EXTRA_Name%=\""
                + x(pc.trim()) + "\" %EXTRA_AppName%=\"%BASENAME%\"</command>\n";
    }

    /**
     * Moonlight categories made before 2.4 launched through a MOONLIGHT find rule, which ES-DE didn't pick up on some
     * setups ("missing emulator find rules"). Rewrites them to the direct launch. Returns how many were fixed.
     */
    public int repairMoonlight() throws IOException {
        String text = read(systemsFile());
        if (text == null || !text.contains("%EMULATOR_" + MOONLIGHT_EMU + "%")) return 0;
        int n = 0;
        for (Category c : categories()) {
            if (!KIND_MOONLIGHT.equals(c.kind) || c.pc == null || c.pc.trim().isEmpty()) continue;
            String blk = systemBlock(text, c.name);
            if (blk == null || !blk.contains("%EMULATOR_" + MOONLIGHT_EMU + "%")) continue;
            saveMoonlightCategory(c, moonlightGames(c));
            n++;
        }
        return n;
    }

    private void putMoonlightRule(Tx tx, boolean wanted) throws IOException {
        String text = read(findRulesFile());
        String block = "    <emulator name=\"" + MOONLIGHT_EMU + "\">\n"
                + "        <!-- Moonlight game streaming: opens a game directly by computer and game name -->\n"
                + "        <rule type=\"androidpackage\">\n"
                + "            <entry>com.limelight/com.limelight.ShortcutTrampoline</entry>\n"
                + "            <entry>com.limelight.root/com.limelight.ShortcutTrampoline</entry>\n"
                + "        </rule>\n"
                + "    </emulator>";
        Pattern ours = Pattern.compile("(?s)[ \\t]*" + Pattern.quote(MARK) + "\\s*<emulator name=\"" + MOONLIGHT_EMU + "\">.*?</emulator>[ \\t]*\\r?\\n?");
        boolean anyOther = text != null && text.contains("<emulator name=\"" + MOONLIGHT_EMU + "\"") && !ours.matcher(text).find();
        if (anyOther) return;   // the user defined their own Moonlight rule: leave it alone
        if (text == null) {
            if (!wanted) return;
            text = "<?xml version=\"1.0\"?>\n<!-- Custom emulator find rules for ES-DE. Entries marked \"ES-DE Commander\" "
                    + "are managed by the ES-DE Commander app. -->\n<ruleList>\n</ruleList>\n";
        }
        String cleaned = ours.matcher(text).replaceAll("");
        if (wanted) cleaned = insertBefore(cleaned, "</ruleList>", "    " + MARK + "\n" + block + "\n");
        if (!cleaned.equals(text)) tx.write(findRulesFile(), cleaned);
    }

    // ================================================================ collections

    /** Paths in a collection (as written: absolute, or %ROMPATH%/...). */
    public List<String> collectionEntries(Category c) {
        List<String> out = new ArrayList<String>();
        String t = read(collectionFile(c.name));
        if (t == null) return out;
        String romPath = romPathSetting();
        for (String line : t.split("\\r?\\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            // ES-DE rewrites paths inside its ROM folder as %ROMPATH%/...
            if (l.startsWith("%ROMPATH%")) l = new File(romPath + l.substring("%ROMPATH%".length())).getAbsolutePath();
            out.add(l);
        }
        return out;
    }

    /** ES-DE's ROM folder as ES-DE itself sees it. */
    String romPathSetting() {
        String v = setting("ROMDirectory");
        return v != null && v.trim().length() > 0 ? v.trim().replaceAll("/+$", "") : romDir.getAbsolutePath();
    }

    /** Creates or updates a custom collection of existing entries and switches it on in ES-DE. */
    public void saveCollection(Category c, List<File> entries) throws IOException {
        c.kind = KIND_COLLECTION;
        Tx tx = new Tx("Collection \"" + c.fullName + "\"");
        StringBuilder b = new StringBuilder();
        for (File f : entries) b.append(f.getAbsolutePath()).append('\n');
        tx.write(collectionFile(c.name), b.toString());
        List<String> on = splitList(setting("CollectionSystemsCustom"));
        if (!containsIgnoreCase(on, c.name)) on.add(c.name);
        Collections.sort(on, String.CASE_INSENSITIVE_ORDER);
        putSetting(tx, "CollectionSystemsCustom", join(on));
        if (c.separate) putSetting(tx, "CollectionCustomGrouping", "never");
        upsert(tx, c);
        tx.commit();
    }

    // ================================================================ delete / rename

    /** Removes a category. Entry files this app made are removed; anything else in the folder is kept. */
    public void delete(Category c) throws IOException {
        Tx tx = new Tx("Removed \"" + c.fullName + "\"");
        if (KIND_COLLECTION.equals(c.kind)) {
            tx.delete(collectionFile(c.name));
            List<String> on = splitList(setting("CollectionSystemsCustom"));
            List<String> keep = new ArrayList<String>();
            for (String s : on) if (!s.equalsIgnoreCase(c.name)) keep.add(s);
            if (keep.size() != on.size()) putSetting(tx, "CollectionSystemsCustom", join(keep));
        } else {
            File dir = folder(c);
            removeEntries(tx, dir, KIND_APPS.equals(c.kind) ? ".app" : MOONLIGHT_EXT, Collections.<String>emptySet());
            String text = read(systemsFile());
            if (text != null) {
                String cleaned = removeSystem(text, c.name);
                if (!cleaned.equals(text)) tx.write(systemsFile(), cleaned);
            }
        }
        List<Category> cats = categories();
        List<Category> rest = new ArrayList<Category>();
        boolean moonlightLeft = false;
        for (Category o : cats) {
            if (o.kind.equals(c.kind) && o.name.equalsIgnoreCase(c.name)) continue;
            rest.add(o);
            if (KIND_MOONLIGHT.equals(o.kind)) moonlightLeft = true;
        }
        if (KIND_MOONLIGHT.equals(c.kind) && !moonlightLeft) putMoonlightRule(tx, false);
        saveRegistry(tx, rest);
        tx.commit();
        // remove the folder only if nothing else is in it
        File dir = folder(c);
        if (!KIND_COLLECTION.equals(c.kind) && dir.isDirectory()) {
            String[] left = dir.list();
            if (left != null && left.length == 0) dir.delete();
        }
    }

    // ================================================================ custom systems file

    private void putSystem(Tx tx, String name, String block) throws IOException {
        String text = read(systemsFile());
        if (text == null) {
            text = "<?xml version=\"1.0\"?>\n<!-- Custom systems for ES-DE. Entries marked \"ES-DE Commander\" are "
                    + "managed by the ES-DE Commander app. -->\n<systemList>\n</systemList>\n";
        }
        String existing = systemBlock(text, name);
        if (existing != null && !isOurs(text, existing)) throw new IOException("Your custom systems file already has a system called \"" + name + "\" that this app didn't make.");
        String cleaned = removeSystem(text, name);
        String out = insertBefore(cleaned, "</systemList>", "    " + MARK + "\n" + block + "\n");
        if (!out.equals(text)) tx.write(systemsFile(), out);
    }

    private static final String SYS_RE = "(?s)<system>(?:(?!</system>).)*?<name>\\s*%s\\s*</name>.*?</system>";

    /** The <system> block for this name, or null. */
    static String systemBlock(String text, String name) {
        if (text == null) return null;
        Matcher m = Pattern.compile(String.format(SYS_RE, Pattern.quote(name)), Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group() : null;
    }

    static boolean isOurs(String text, String block) {
        int i = text.indexOf(block);
        if (i < 0) return false;
        String before = text.substring(0, i).replaceAll("\\s+$", "");
        return before.endsWith(MARK);
    }

    /** Removes our block for this system (and its marker); systems the user wrote are never touched. */
    static String removeSystem(String text, String name) {
        Pattern p = Pattern.compile("(?s)[ \\t]*" + Pattern.quote(MARK) + "\\s*"
                + String.format(SYS_RE, Pattern.quote(name)) + "[ \\t]*\\r?\\n?", Pattern.CASE_INSENSITIVE);
        return p.matcher(text).replaceAll("");
    }

    static String insertBefore(String text, String closing, String insert) {
        int i = text.lastIndexOf(closing);
        if (i < 0) return text + insert;
        int lineStart = text.lastIndexOf('\n', i) + 1;
        return text.substring(0, lineStart) + insert + text.substring(lineStart);
    }

    // ================================================================ es_settings.xml

    public String setting(String key) {
        String t = read(settingsFile());
        if (t == null) return null;
        Matcher m = Pattern.compile("<string\\s+name=\"" + Pattern.quote(key) + "\"\\s+value=\"([^\"]*)\"").matcher(t);
        return m.find() ? unx(m.group(1)) : null;
    }

    private void putSetting(Tx tx, String key, String value) throws IOException {
        String t = read(settingsFile());
        String line = "<string name=\"" + key + "\" value=\"" + x(value) + "\" />";
        String out;
        if (t == null) {
            out = "<?xml version=\"1.0\"?>\n" + line + "\n";
        } else {
            Pattern p = Pattern.compile("<string\\s+name=\"" + Pattern.quote(key) + "\"\\s+value=\"[^\"]*\"\\s*/>");
            Matcher m = p.matcher(t);
            if (m.find()) {
                out = t.substring(0, m.start()) + line + t.substring(m.end());
            } else {
                String trimmed = t.replaceAll("\\s+$", "");
                int lastLine = trimmed.lastIndexOf('\n') + 1;
                // inside a root element if the file has one, otherwise at the end
                if (trimmed.substring(lastLine).trim().startsWith("</")) out = trimmed.substring(0, lastLine) + line + "\n" + trimmed.substring(lastLine) + "\n";
                else out = trimmed + "\n" + line + "\n";
            }
        }
        if (!out.equals(t)) tx.write(settingsFile(), out);
    }

    // ================================================================ helpers

    private void upsert(Tx tx, Category c) throws IOException {
        List<Category> cats = categories();
        boolean found = false;
        for (int i = 0; i < cats.size(); i++) {
            Category o = cats.get(i);
            if (o.kind.equals(c.kind) && o.name.equalsIgnoreCase(c.name)) { cats.set(i, c); found = true; }
        }
        if (!found) cats.add(c);
        saveRegistry(tx, cats);
    }

    /** Deletes entry files with this extension that aren't in keep (only that extension: nothing else is touched). */
    private static void removeEntries(Tx tx, File dir, String ext, Set<String> keep) throws IOException {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(ext) && !keep.contains(f.getName())) tx.delete(f);
        }
    }

    static List<String> splitList(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        for (String p : s.split(",")) if (p.trim().length() > 0) out.add(p.trim());
        return out;
    }

    static boolean containsIgnoreCase(List<String> l, String s) {
        for (String o : l) if (o.equalsIgnoreCase(s)) return true;
        return false;
    }

    static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) { if (b.length() > 0) b.append(','); b.append(s); }
        return b.toString();
    }

    static String x(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String unx(String s) {
        return s.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'").replace("&amp;", "&");
    }

    static String read(File f) {
        if (f == null || !f.isFile()) return null;
        try {
            return new String(readBytes(f), "UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    static byte[] readBytes(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    static void writeBytes(File f, byte[] data) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir.getPath());
        File tmp = new File(f.getPath() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
        } finally {
            out.close();
        }
        if (f.exists() && !f.delete()) { tmp.delete(); throw new IOException("can't replace " + f.getPath()); }
        if (!tmp.renameTo(f)) { tmp.delete(); throw new IOException("can't write " + f.getPath()); }
    }

    // ================================================================ backups & undo

    /** Records the original of every file before it changes, so the whole change can be undone. */
    class Tx {
        final String label;
        final File dir;
        final JSONArray files = new JSONArray();
        final Set<String> seen = new LinkedHashSet<String>();
        final List<File> madeDirs = new ArrayList<File>();

        Tx(String label) {
            this.label = label;
            this.dir = new File(backupRoot(), new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date()));
        }

        void before(File f) throws IOException {
            String key = f.getAbsolutePath();
            if (!seen.add(key)) return;
            try {
                JSONObject e = new JSONObject().put("path", key).put("existed", f.isFile());
                if (f.isFile()) {
                    String copy = "f" + files.length();
                    writeBytes(new File(dir, copy), readBytes(f));
                    e.put("copy", copy);
                }
                files.put(e);
            } catch (JSONException e) {
                throw new IOException("backup failed");
            }
        }

        void write(File f, String text) throws IOException {
            before(f);
            mkdirs(f.getParentFile());
            writeBytes(f, text.getBytes("UTF-8"));
        }

        void delete(File f) throws IOException {
            if (!f.exists()) return;
            before(f);
            if (!f.delete()) throw new IOException("can't remove " + f.getPath());
        }

        void mkdirs(File d) throws IOException {
            if (d == null || d.isDirectory()) return;
            List<File> chain = new ArrayList<File>();
            for (File p = d; p != null && !p.exists(); p = p.getParentFile()) chain.add(0, p);
            if (!d.mkdirs()) throw new IOException("can't create " + d.getPath());
            madeDirs.addAll(chain);
        }

        void commit() throws IOException {
            if (files.length() == 0) return;
            try {
                JSONArray dirs = new JSONArray();
                for (File d : madeDirs) dirs.put(d.getAbsolutePath());
                JSONObject m = new JSONObject().put("label", label).put("time", System.currentTimeMillis())
                        .put("files", files).put("madeDirs", dirs);
                writeBytes(new File(dir, "manifest.json"), m.toString(2).getBytes("UTF-8"));
            } catch (JSONException e) {
                throw new IOException("backup failed");
            }
            prune(20);
        }
    }

    private List<File> backups() {
        List<File> out = new ArrayList<File>();
        File[] ds = backupRoot().listFiles();
        if (ds == null) return out;
        for (File d : ds) if (new File(d, "manifest.json").isFile()) out.add(d);
        Collections.sort(out);
        return out;
    }

    private void prune(int keep) {
        List<File> bs = backups();
        for (int i = 0; i < bs.size() - keep; i++) deleteTree(bs.get(i));
    }

    /** What the last change was, or null if there's nothing to undo. */
    public String lastChange() {
        List<File> bs = backups();
        if (bs.isEmpty()) return null;
        String t = read(new File(bs.get(bs.size() - 1), "manifest.json"));
        try {
            return t == null ? null : new JSONObject(t).optString("label", "last change");
        } catch (JSONException e) {
            return null;
        }
    }

    /** Puts every file the last change touched back how it was. Returns what was undone, or null. */
    public String undo() throws IOException {
        List<File> bs = backups();
        if (bs.isEmpty()) return null;
        File d = bs.get(bs.size() - 1);
        try {
            JSONObject m = new JSONObject(read(new File(d, "manifest.json")));
            JSONArray files = m.getJSONArray("files");
            for (int i = files.length() - 1; i >= 0; i--) {
                JSONObject e = files.getJSONObject(i);
                File f = new File(e.getString("path"));
                if (e.optBoolean("existed")) writeBytes(f, readBytes(new File(d, e.getString("copy"))));
                else if (f.exists() && !f.delete()) throw new IOException("can't remove " + f.getPath());
            }
            JSONArray dirs = m.optJSONArray("madeDirs");
            if (dirs != null) for (int i = dirs.length() - 1; i >= 0; i--) {
                File dir = new File(dirs.getString(i));
                String[] left = dir.list();
                if (left != null && left.length == 0) dir.delete();
            }
            deleteTree(d);
            return m.optString("label", "last change");
        } catch (JSONException e) {
            throw new IOException("the backup is damaged");
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
