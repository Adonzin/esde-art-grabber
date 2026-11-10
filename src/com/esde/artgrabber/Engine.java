package com.esde.artgrabber;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scans an ES-DE ROMs folder and fills downloaded_media with SteamGridDB art. No Android dependencies. */
public class Engine {

    // ---------------------------------------------------------------- media types

    public static class Source {
        final String kind, dims;
        Source(String kind, String dims) { this.kind = kind; this.dims = dims; }
    }

    /** ES-DE media folder -> SteamGridDB sources, tried in order. */
    public static final Map<String, Source[]> MEDIA = new LinkedHashMap<String, Source[]>();
    static {
        MEDIA.put("covers", new Source[]{new Source("grids", "600x900"), new Source("grids", "342x482,660x930")});
        MEDIA.put("marquees", new Source[]{new Source("logos", null)});
        MEDIA.put("fanart", new Source[]{new Source("heroes", null)});
        MEDIA.put("screenshots", new Source[]{new Source("grids", "920x430,460x215"), new Source("heroes", null)});
        MEDIA.put("titlescreens", new Source[]{new Source("grids", "920x430,460x215")});
    }

    static final String[] IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg"};
    static final Set<String> IGNORE_EXTS = new HashSet<String>(Arrays.asList(
            ".txt", ".xml", ".nfo", ".md", ".pdf", ".ini", ".cfg", ".json", ".log", ".sav", ".srm",
            ".state", ".bak", ".db", ".dat", ".sfv", ".md5", ".sha1", ".png", ".jpg", ".jpeg", ".webp",
            ".gif", ".bmp", ".mp4", ".mkv", ".nomedia", ".part"));
    /**
     * Launcher marker files (GameNative, Winlator, app importers). Always treated as games: ES-DE writes
     * systeminfo.txt once, so an older one may not list extensions newer ES-DE versions support.
     */
    static final Set<String> MARKER_EXTS = new HashSet<String>(Arrays.asList(
            ".epic", ".gog", ".steam", ".amazon", ".pcgame", ".desktop", ".app"));
    static final Set<String> IGNORE_DIRS = new HashSet<String>(Arrays.asList(
            "media", "images", "videos", "downloaded_images", "downloaded_media", "manuals",
            "snap", "snaps", "boxart", "_to_delete"));

    // ---------------------------------------------------------------- callbacks & results

    public interface Listener {
        void log(String line);
        void progress(int done, int total);
    }

    /** Supplies artwork for apps installed on the device (implemented with PackageManager on Android). */
    public interface AppArt {
        boolean isInstalled(String pkg);
        /** Package of the installed app whose name matches this label ("Chrome" -> com.android.chrome), or null. */
        String packageForLabel(String label);
        /** Writes the app's banner/logo/icon as a PNG. Returns false if the app has no usable art. */
        boolean saveMarquee(String pkg, File dest) throws IOException;
    }

    /** Systems whose entries are apps, not games: SteamGridDB isn't searched for these. */
    static final Set<String> APP_SYSTEMS = new HashSet<String>(Arrays.asList("androidapps", "emulators"));

    public static class Options {
        public boolean appMarquees = true;
        public boolean useSteam = true, useSgdb = true, useLibretro = true;
        public List<String> media = new ArrayList<String>(Arrays.asList("covers", "marquees", "fanart", "screenshots"));
        public Set<String> systems = null;   // null = all
        public boolean overwrite = false;
        public boolean dryRun = false;
        public double threshold = 0.72;
        public Map<String, Long> manualMatches = new HashMap<String, Long>(); // "system/rel" -> game id
    }

    public static class GameFile {
        public final String system, rel;
        public final boolean isDir;
        GameFile(String system, String rel, boolean isDir) { this.system = system; this.rel = rel; this.isDir = isDir; }
        public String key() { return system + "/" + rel; }
        public String base() {
            String f = new File(rel).getName();
            int dot = f.lastIndexOf('.');
            return dot > 0 ? f.substring(0, dot) : f;
        }
        public String sub() { String p = new File(rel).getParent(); return p == null ? "" : p; }
    }

    public static class Miss {
        public final GameFile game;
        public final String query;
        Miss(GameFile g, String q) { game = g; query = q; }
        @Override public String toString() { return game.system + " / " + game.base(); }
    }

    public static class Result {
        public int games, matched, saved, kept, noMatch, noArt, failed, appEntries, cleaned, appSkipped;
        public final List<Miss> misses = new ArrayList<Miss>();
        public String fatal;
    }

    // ---------------------------------------------------------------- folders

    public final File romDir, mediaDir, esdeDir;
    /** system name -> folder holding its games (systems can live in different places). */
    public final Map<String, File> systemDirs;
    public volatile boolean stop = false;
    public AppArt appArt;
    public Libretro libretro = new Libretro(null);
    public SteamArt steam = new SteamArt();

    public Engine(File romDir, File esdeDir, File mediaDir) {
        this(romDir, esdeDir, mediaDir, discoverSystems(romDir, esdeDir, new ArrayList<File>()));
    }

    public Engine(File romDir, File esdeDir, File mediaDir, Map<String, File> systemDirs) {
        this.romDir = romDir;
        this.esdeDir = esdeDir;
        this.mediaDir = mediaDir;
        this.systemDirs = systemDirs;
    }

    static final String[] ROM_FOLDER_NAMES = {"ROMs", "Roms", "roms", "ROMS", "Emulation/roms", "Emulation/ROMs"};

    /** Every ROMs-style folder we can find: the chosen one, ES-DE's setting, then each storage root. */
    public static List<File> romRoots(File chosen, File esdeDir, List<File> storageRoots) {
        List<File> roots = new ArrayList<File>();
        if (chosen != null) addRoot(roots, chosen);
        if (esdeDir != null) {
            String v = esdeSetting(esdeDir, "ROMDirectory");
            if (v != null && v.length() > 0) addRoot(roots, new File(v));
        }
        for (File st : storageRoots) for (String n : ROM_FOLDER_NAMES) addRoot(roots, new File(st, n));
        return roots;
    }

    private static void addRoot(List<File> roots, File f) {
        if (!f.isDirectory()) return;
        String c;
        try { c = f.getCanonicalPath(); } catch (IOException e) { c = f.getAbsolutePath(); }
        for (File r : roots) {
            String rc;
            try { rc = r.getCanonicalPath(); } catch (IOException e) { rc = r.getAbsolutePath(); }
            if (rc.equalsIgnoreCase(c)) return;
        }
        roots.add(f);
    }

    /**
     * All system folders across every ROMs root, plus systems ES-DE was told to load from custom
     * paths (ES-DE/custom_systems/es_systems.xml). If a system exists in several places, the copy
     * with the most files wins.
     */
    public static Map<String, File> discoverSystems(File chosen, File esdeDir, List<File> storageRoots) {
        Map<String, File> out = new java.util.TreeMap<String, File>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Integer> sizes = new HashMap<String, Integer>();
        for (File root : romRoots(chosen, esdeDir, storageRoots)) {
            File[] ds = root.listFiles();
            if (ds == null) continue;
            for (File d : ds) {
                if (!d.isDirectory() || d.getName().startsWith(".")) continue;
                offer(out, sizes, d.getName(), d);
            }
        }
        if (esdeDir != null) {
            String text = readSmall(new File(esdeDir, "custom_systems/es_systems.xml"), 4 << 20);
            if (text != null) {
                String romPath = chosen != null ? chosen.getPath() : esdeSetting(esdeDir, "ROMDirectory");
                Matcher sm = Pattern.compile("(?s)<system>(.*?)</system>").matcher(text);
                while (sm.find()) {
                    Matcher n = Pattern.compile("<name>\\s*(.*?)\\s*</name>").matcher(sm.group(1));
                    Matcher p = Pattern.compile("<path>\\s*(.*?)\\s*</path>").matcher(sm.group(1));
                    if (!n.find() || !p.find()) continue;
                    String path = unescape(p.group(1));
                    if (path.contains("%ROMPATH%")) {
                        if (romPath == null) continue;
                        path = path.replace("%ROMPATH%", romPath);
                    }
                    File d = new File(path.replace("\\", "/"));
                    if (d.isDirectory()) offer(out, sizes, unescape(n.group(1)), d);
                }
            }
        }
        return out;
    }

    private static void offer(Map<String, File> out, Map<String, Integer> sizes, String name, File dir) {
        String[] kids = dir.list();
        int n = kids == null ? 0 : kids.length;
        String k = name.toLowerCase(Locale.ROOT);
        Integer prev = sizes.get(k);
        if (prev == null || n > prev) {
            out.remove(name);
            out.put(name, dir);
            sizes.put(k, n);
        }
    }

    File sysDir(String system) {
        File d = systemDirs.get(system);
        return d != null ? d : new File(romDir, system);
    }

    /** Reads ES-DE/settings/es_settings.xml for a setting value, or null. */
    public static String esdeSetting(File esdeDir, String name) {
        File f = new File(esdeDir, "settings/es_settings.xml");
        String text = readSmall(f, 1 << 20);
        if (text == null) return null;
        Matcher m = Pattern.compile("<string\\s+name\\s*=\\s*\"" + Pattern.quote(name) + "\"\\s+value\\s*=\\s*\"([^\"]*)\"").matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    public static File findEsdeDir(List<File> storageRoots) {
        for (File root : storageRoots) {
            File d = new File(root, "ES-DE");
            if (new File(d, "settings").isDirectory() || new File(d, "downloaded_media").isDirectory()) return d;
        }
        for (File root : storageRoots) {
            File d = new File(root, "ES-DE");
            if (d.isDirectory()) return d;
        }
        return null;
    }

    /** ES-DE's configured ROMs folder, else the ROMs-style folder holding the most systems. */
    public static File findRomDir(File esdeDir, List<File> storageRoots) {
        if (esdeDir != null) {
            String v = esdeSetting(esdeDir, "ROMDirectory");
            if (v != null && v.length() > 0 && new File(v).isDirectory()) return new File(v);
        }
        File best = null;
        int bestN = -1;
        for (File r : romRoots(null, null, storageRoots)) {
            File[] ds = r.listFiles();
            int n = 0;
            if (ds != null) for (File d : ds) if (d.isDirectory()) n++;
            if (n > bestN) { best = r; bestN = n; }
        }
        return best;
    }

    public static File defaultMediaDir(File esdeDir) {
        if (esdeDir == null) return null;
        String v = esdeSetting(esdeDir, "MediaDirectory");
        if (v != null && v.length() > 0) return new File(v);
        return new File(esdeDir, "downloaded_media");
    }

    /** System folders (sorted). */
    public List<String> systems() {
        List<String> out = new ArrayList<String>(systemDirs.keySet());
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    // ---------------------------------------------------------------- scanning

    /** Extensions ES-DE lists for a system in its systeminfo.txt, or null if unknown. */
    static Set<String> systemExtensions(File sysDir) {
        String text = readSmall(new File(sysDir, "systeminfo.txt"), 1 << 20);
        if (text == null) return null;
        Set<String> exts = new HashSet<String>();
        boolean grab = false;
        Pattern p = Pattern.compile("(?<![\\w/])\\.[A-Za-z0-9_+\\-]{1,12}\\b");
        for (String line : text.split("\\r?\\n")) {
            if (line.toLowerCase(Locale.ROOT).contains("extension")) { grab = true; continue; }
            if (!grab) continue;
            Matcher m = p.matcher(line);
            boolean any = false;
            while (m.find()) { exts.add(m.group().toLowerCase(Locale.ROOT)); any = true; }
            if (!any && !exts.isEmpty()) break;
        }
        return exts.isEmpty() ? null : exts;
    }

    /** Extensions skipped in the last scanSystem() call because ES-DE doesn't list them for that system. */
    Map<String, Integer> lastSkipped = new HashMap<String, Integer>();

    public List<GameFile> scanSystem(String system) {
        File sysDir = sysDir(system);
        Set<String> exts = systemExtensions(sysDir);
        List<GameFile> games = new ArrayList<GameFile>();
        lastSkipped = new java.util.TreeMap<String, Integer>();
        walk(system, sysDir, "", exts, games, 0);
        // files referenced by .m3u playlists are hidden by ES-DE
        Set<String> hidden = new HashSet<String>();
        for (GameFile g : games) {
            if (!g.rel.toLowerCase(Locale.ROOT).endsWith(".m3u")) continue;
            String text = readSmall(new File(sysDir, g.rel), 1 << 16);
            if (text == null) continue;
            String parent = g.sub();
            for (String line : text.split("\\r?\\n")) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                hidden.add(normPath(parent.length() == 0 ? line : parent + "/" + line));
            }
        }
        List<GameFile> out = new ArrayList<GameFile>();
        for (GameFile g : games) if (!hidden.contains(normPath(g.rel))) out.add(g);
        return out;
    }

    private void walk(String system, File dir, String rel, Set<String> exts, List<GameFile> out, int depth) {
        if (depth > 6) return;
        File[] items = dir.listFiles();
        if (items == null) return;
        Arrays.sort(items, new java.util.Comparator<File>() {
            public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
        });
        for (File f : items) {
            String name = f.getName();
            if (name.startsWith(".")) continue;
            String r = rel.length() == 0 ? name : rel + "/" + name;
            String ext = ext(name);
            if (f.isDirectory()) {
                if (IGNORE_DIRS.contains(name.toLowerCase(Locale.ROOT))) continue;
                if (ext.length() > 0 && exts != null && exts.contains(ext)) out.add(new GameFile(system, r, true));
                else walk(system, f, r, exts, out, depth + 1);
            } else {
                if (exts != null) {
                    if (!exts.contains(ext) && !MARKER_EXTS.contains(ext)) {
                        if (ext.length() > 0 && !IGNORE_EXTS.contains(ext)) {
                            Integer c = lastSkipped.get(ext);
                            lastSkipped.put(ext, c == null ? 1 : c + 1);
                        }
                        continue;
                    }
                }
                else if (ext.length() == 0 || IGNORE_EXTS.contains(ext)) continue;
                out.add(new GameFile(system, r, false));
            }
        }
    }

    /** rel path -> name from ES-DE's gamelist.xml (gives real titles for cryptic files like a1b2c3.epic). */
    Map<String, String> gamelistNames(String system) {
        Map<String, String> names = new HashMap<String, String>();
        File[] lists = {
                esdeDir == null ? null : new File(esdeDir, "gamelists/" + system + "/gamelist.xml"),
                new File(sysDir(system), "gamelist.xml")};
        Pattern game = Pattern.compile("(?s)<game\\b.*?</game>");
        Pattern path = Pattern.compile("(?s)<path>(.*?)</path>");
        Pattern nm = Pattern.compile("(?s)<name>(.*?)</name>");
        for (File f : lists) {
            if (f == null) continue;
            String text = readSmall(f, 32 << 20);
            if (text == null) continue;
            Matcher g = game.matcher(text);
            while (g.find()) {
                Matcher p = path.matcher(g.group()), n = nm.matcher(g.group());
                if (p.find() && n.find()) {
                    String pv = unescape(p.group(1).trim());
                    if (pv.startsWith("./")) pv = pv.substring(2);
                    String nv = unescape(n.group(1).trim());
                    if (nv.length() > 0) names.put(normPath(pv), nv);
                }
            }
        }
        return names;
    }

    static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");

    /**
     * The installed Android package an entry points at, if any. Third-party importers write
     * "<App Name>.app" files containing the package name; as a fallback the file name itself may be one.
     */
    String installedPackage(GameFile g) { return installedPackage(g, null); }

    /** Also tries the app's name (file name, or ES-DE title) – for entries that don't store a package name. */
    String installedPackage(GameFile g, String title) {
        File f = new File(sysDir(g.system), g.rel);
        if (f.isFile() && f.length() > 0 && f.length() <= 1024) {
            String text = readSmall(f, 1024);
            if (text != null) {
                for (String line : text.split("\\r?\\n")) {
                    line = line.trim();
                    if (line.startsWith("package:")) line = line.substring(8).trim();
                    if (PACKAGE.matcher(line).matches() && appArt.isInstalled(line)) return line;
                }
            }
        }
        String base = g.base();
        if (PACKAGE.matcher(base).matches() && appArt.isInstalled(base)) return base;
        String byName = appArt.packageForLabel(base);
        if (byName == null && title != null) byName = appArt.packageForLabel(title);
        if (byName == null) byName = appArt.packageForLabel(Names.clean(base));
        return byName;
    }

    static final Pattern[] STEAM_ID = {
            Pattern.compile("(?i)steam://rungameid/(\\d{1,10})(?![\\w-])"),
            Pattern.compile("(?i)\\b(?:steam_?)?app_?id\\b[\"'\\s:=]+(\\d{1,10})(?![\\w-])")};

    /** Only Steam entries carry Steam app ids; Epic/GOG markers hold their own store ids. */
    static boolean mayHoldSteamId(GameFile g) {
        String ext = ext(g.rel);
        return g.system.equalsIgnoreCase("steam") || ext.equals(".steam");
    }

    static String steamAppId(File f) {
        if (!f.isFile() || f.length() > 8192) return null;
        String text = readSmall(f, 8192);
        if (text == null) return null;
        for (Pattern p : STEAM_ID) {
            Matcher m = p.matcher(text);
            if (m.find()) return m.group(1);
        }
        String t = text.trim();
        return t.matches("\\d{2,10}") ? t : null;
    }

    // ---------------------------------------------------------------- the run

    public Result run(Sgdb api, Options o, Listener l) {
        Result res = new Result();
        List<GameFile> all = new ArrayList<GameFile>();
        Map<String, Map<String, String>> names = new HashMap<String, Map<String, String>>();
        List<String> notes = new ArrayList<String>();
        l.log("Scanning " + systemDirs.size() + " system folders …");
        for (String s : systems()) {
            if (o.systems != null && !o.systems.contains(s)) continue;
            List<GameFile> g = scanSystem(s);
            if (!lastSkipped.isEmpty()) {
                List<String> parts = new ArrayList<String>();
                int total = 0;
                for (Map.Entry<String, Integer> en : lastSkipped.entrySet()) {
                    parts.add(en.getKey() + " ×" + en.getValue());
                    total += en.getValue();
                }
                notes.add("  ⓘ " + s + ": skipped " + total + " file" + (total == 1 ? "" : "s")
                        + " ES-DE doesn't list for this system (" + join(parts) + ")");
            }
            if (g.isEmpty()) continue;
            all.addAll(g);
            names.put(s, gamelistNames(s));
        }
        l.log("Found " + all.size() + " games. Fetching " + join(o.media) + (o.dryRun ? "  (preview only)" : ""));
        for (String n : notes) l.log(n);
        String lastSystem = null;
        for (int i = 0; i < all.size(); i++) {
            if (stop) { l.log("Stopped."); break; }
            GameFile g = all.get(i);
            l.progress(i, all.size());
            if (!g.system.equals(lastSystem)) { l.log("\n▸ " + g.system); lastSystem = g.system; }
            try {
                doGame(api, o, l, res, g, names.get(g.system));
            } catch (Sgdb.BadKeyException e) {
                res.fatal = "SteamGridDB rejected your API key. Check it in Settings.";
                l.log("✗ " + res.fatal);
                return res;
            } catch (IOException e) {
                res.failed++;
                l.log("  ! " + g.base() + " – " + e.getMessage());
            }
            if (api != null) synchronized (api.rejections) {
                for (String rj : api.rejections) l.log("    ⓘ SteamGridDB refused a request: " + rj + " – skipped it and carried on");
                api.rejections.clear();
            }
        }
        l.progress(all.size(), all.size());
        if (o.appMarquees && appArt != null && res.appEntries == 0 && !stop
                && (o.systems == null || o.systems.contains("androidapps") || o.systems.contains("emulators"))) {
            l.log("\nⓘ No Android app or emulator entries found, so no app-icon art was made."
                    + "\n   Import your apps into ES-DE first (ES-DE menu → Utilities → Game importer),"
                    + "\n   then run this again.");
        }
        return res;
    }

    void doGame(Sgdb api, Options o, Listener l, Result res, GameFile g, Map<String, String> names) throws IOException {
        res.games++;
        String base = g.base();
        if (g.rel.toLowerCase(Locale.ROOT).endsWith(".app") || APP_SYSTEMS.contains(g.system.toLowerCase(Locale.ROOT))
                || g.system.equalsIgnoreCase("androidgames")) res.appEntries++;
        String title = names == null ? null : names.get(normPath(g.rel));

        // Android apps & emulators aren't games: never search game databases for them (a "Chrome" browser
        // would otherwise get the 2003 game Chrome). Their own icon becomes every image except the background.
        if (APP_SYSTEMS.contains(g.system.toLowerCase(Locale.ROOT))) {
            String appPkg = o.appMarquees && appArt != null && !g.isDir ? installedPackage(g, title) : null;
            if (appPkg != null) { doAppEntry(o, l, res, g, appPkg); return; }
            res.appSkipped++;
            l.log("  · " + base + "  – " + (o.appMarquees ? "app not found on this device" : "app icons are switched off")
                    + "; skipped (apps never get game art)");
            return;
        }

        List<String> todo = new ArrayList<String>();
        Map<String, File> have = new HashMap<String, File>();
        for (String m : o.media) {
            File dir = mediaFolder(g, m);
            File existing = existing(dir, base);
            if (existing != null && !o.overwrite) { res.kept++; continue; }
            todo.add(m);
            if (existing != null) have.put(m, existing);
        }
        if (todo.isEmpty()) { l.log("  ✓ " + base + "  (already has art)"); return; }

        // Android games: the app's own artwork becomes the logo; SteamGridDB supplies the rest
        String pkg = o.appMarquees && appArt != null && !g.isDir ? installedPackage(g, title) : null;
        if (pkg != null) {
            List<String> got = new ArrayList<String>();
            if (todo.remove("marquees")) {
                File dir = mediaFolder(g, "marquees");
                File dest = new File(dir, base + ".png");
                boolean ok = true;
                if (!o.dryRun) {
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir.getPath());
                    ok = appArt.saveMarquee(pkg, dest);
                    File old = have.get("marquees");
                    if (ok && old != null && !old.getAbsolutePath().equals(dest.getAbsolutePath())) old.delete();
                }
                if (ok) { res.saved++; got.add("marquees (app icon)"); }
            }
            if (todo.isEmpty()) {
                res.matched++;
                if (got.isEmpty()) { res.noArt++; l.log("  ~ " + base + "  (" + pkg + " has no icon art)"); }
                else l.log("  + " + base + "  → " + pkg + "  " + join(got));
                return;
            }
            if (!got.isEmpty()) l.log("  + " + base + "  → " + pkg + "  " + join(got));
        }

        String query = names != null && names.get(normPath(g.rel)) != null ? names.get(normPath(g.rel)) : Names.clean(base);
        List<String> got = new ArrayList<String>();
        String matchedAs = null;

        // 1. Libretro thumbnails: real box art, screenshots, title screens and logos for retro systems
        String lr = o.useLibretro && libretro != null ? Libretro.libretroName(g.system) : null;
        if (lr != null && !lrFailed.contains(lr)) {
            try {
                String chosen = null;
                for (String m : new ArrayList<String>(todo)) {
                    String type = Libretro.TYPES.get(m);
                    if (type == null) continue;
                    Libretro.Index ix = libretro.index(lr, type);
                    String name = chosen != null && ix.has(chosen) ? chosen : Libretro.match(ix, base, query);
                    if (name == null) continue;
                    if (chosen == null) chosen = name;
                    File dir = mediaFolder(g, m);
                    File dest = new File(dir, base + ".png");
                    boolean ok = true;
                    if (!o.dryRun) {
                        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir.getPath());
                        ok = libretro.download(lr, type, name, dest);
                        if (ok) dropOld(have.get(m), dest);
                    }
                    if (ok) { todo.remove(m); res.saved++; got.add(m + " (Libretro)"); }
                }
                if (chosen != null) matchedAs = chosen;
            } catch (IOException e) {
                lrFailed.add(lr);
                l.log("  ⓘ Libretro thumbnails unavailable for " + g.system + " (" + e.getMessage() + ") – using SteamGridDB");
            }
        }

        // 2. Official Steam art, when the entry carries its Steam app ID
        String appId = g.isDir || !mayHoldSteamId(g) ? null : steamAppId(new File(sysDir(g.system), g.rel));
        if (o.useSteam && steam != null && appId != null && !todo.isEmpty()) {
            if (steamStage(o, res, g, appId, todo, have, got) && matchedAs == null) matchedAs = "Steam app " + appId;
        }

        // 3. SteamGridDB for whatever is still missing
        Sgdb.Game game = null;
        double score = 0;
        if (!todo.isEmpty() && o.useSgdb && api != null) {
            Long manual = o.manualMatches.get(g.key());
            if (manual != null) { game = new Sgdb.Game(manual, "your pick", 0); score = 1; }
            if (game == null && appId != null) { game = api.gameBySteamId(appId); score = game != null ? 1 : 0; }
            if (game == null) {
                for (Sgdb.Game c : api.search(query)) {
                    double sc = Names.similarity(query, c.name);
                    if (sc > score) { score = sc; game = c; }
                }
                if (score < o.threshold) game = null;
            }
            if (game != null) {
                // the same game on Steam? then prefer its official art there too
                if (o.useSteam && steam != null && appId == null) {
                    String sid = api.steamIdFor(game.id);
                    if (sid != null) steamStage(o, res, g, sid, todo, have, got);
                }
                if (!todo.isEmpty()) got.addAll(fetchArt(api, o, res, g, game.id, todo, have));
                matchedAs = game.name + "  [" + Math.round(score * 100) + "%]";
            }
        }

        if (matchedAs == null && got.isEmpty()) {
            res.noMatch++;
            if (o.useSgdb) res.misses.add(new Miss(g, query));
            l.log("  ✗ " + base + "  – no match for \"" + query + "\"");
            return;
        }
        res.matched++;
        if (got.isEmpty() && (errors == null || errors.isEmpty())) {
            res.noArt++;
            l.log("  ~ " + base + "  → " + matchedAs + "  (no art of these types)");
        } else if (!got.isEmpty()) {
            l.log("  + " + base + "  → " + (matchedAs == null ? "" : matchedAs + "  ") + join(got));
        }
        if (errors != null) { for (String err : errors) l.log("  ! " + base + "  – " + err); errors.clear(); }
    }

    /** Libretro systems that failed to list this run (rate limit, offline) – not retried per game. */
    final Set<String> lrFailed = new HashSet<String>();

    /** Tries Steam's official art for each wanted type. True if anything was (or would be) saved. */
    boolean steamStage(Options o, Result res, GameFile g, String appId, List<String> todo,
                       Map<String, File> have, List<String> got) throws IOException {
        boolean any = false;
        for (String m : new ArrayList<String>(todo)) {
            if (!SteamArt.supports(m)) continue;
            File dir = mediaFolder(g, m);
            if (!o.dryRun && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir.getPath());
            String ext = steam.save(appId, m, dir, g.base(), o.dryRun);
            if (ext == null) continue;
            if (!o.dryRun) dropOld(have.get(m), new File(dir, g.base() + ext));
            todo.remove(m);
            res.saved++;
            got.add(m + " (Steam)");
            any = true;
        }
        return any;
    }

    /** ES-DE shows one file per name: remove the old image if it was saved in another format. */
    static void dropOld(File old, File dest) {
        if (old != null && !old.getAbsolutePath().equals(dest.getAbsolutePath())) old.delete();
    }

    /** Downloads the requested media types for one game. Returns the types that were saved. */
    /** Per-image errors from the last fetchArt() call, shown in the log. */
    List<String> errors;

    public List<String> fetchArt(Sgdb api, Options o, Result res, GameFile g, long gameId,
                                 List<String> todo, Map<String, File> have) throws IOException {
        errors = new ArrayList<String>();
        List<String> got = new ArrayList<String>();
        for (String m : todo) {
            Source[] srcs = MEDIA.get(m);
            if (srcs == null) continue;
            for (Source s : srcs) {
                List<Sgdb.Image> imgs = api.images(s.kind, gameId, s.dims);
                if (imgs.isEmpty()) continue;
                try {
                String url = imgs.get(0).url;
                String ext = ext(url.replaceAll("[?#].*$", ""));
                if (ext.equals(".jpeg")) ext = ".jpg";
                if (!ext.equals(".png") && !ext.equals(".jpg")) ext = ".png";
                File dir = mediaFolder(g, m);
                File dest = new File(dir, g.base() + ext);
                if (!o.dryRun) {
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir.getPath());
                    File old = have == null ? null : have.get(m);
                    api.download(url, dest);
                    // ES-DE shows one file per name: drop the old one if it was another format
                    if (old != null && !old.getAbsolutePath().equals(dest.getAbsolutePath())) old.delete();
                }
                if (res != null) res.saved++;
                got.add(m);
                } catch (Sgdb.BadKeyException e) {
                    throw e;
                } catch (IOException e) {
                    // one broken image shouldn't stop the rest of this game's art
                    if (res != null) res.failed++;
                    if (errors != null) errors.add(m + ": " + e.getMessage());
                }
                break;
            }
        }
        return got;
    }

    /**
     * ES-DE image folders an app's icon fills for androidapps / emulators entries: the ones themes actually
     * show. Fanart is deliberately left out.
     */
    public static final String[] APP_ICON_MEDIA = {"covers", "marquees", "screenshots", "miximages"};

    /** Folders version 1.8 also filled; identical copies there are cleaned up. */
    static final String[] APP_ICON_DROPPED = {"titlescreens", "3dboxes", "backcovers", "physicalmedia"};

    /** Folders an app's icon never fills, so anything in them for an app entry came from a game database. */
    static final String[] APP_NO_ICON_MEDIA = {"fanart", "titlescreens", "3dboxes", "backcovers", "physicalmedia"};

    /**
     * Finds (and unless dryRun, deletes) images in the app systems that an earlier version took from game
     * databases: backgrounds and the other types app icons never fill. Only files named after an entry are touched.
     */
    public List<File> appGameArt(boolean dryRun) {
        List<File> out = new ArrayList<File>();
        for (String sys : systems()) {
            if (!APP_SYSTEMS.contains(sys.toLowerCase(Locale.ROOT))) continue;
            for (GameFile g : scanSystem(sys)) {
                for (String m : APP_NO_ICON_MEDIA) {
                    File f = existing(mediaFolder(g, m), g.base());
                    if (f == null) continue;
                    out.add(f);
                    if (!dryRun) f.delete();
                }
            }
        }
        return out;
    }

    void doAppEntry(Options o, Listener l, Result res, GameFile g, String pkg) throws IOException {
        String base = g.base();
        // remove the extra icon copies an earlier version made (only exact copies of this entry's logo)
        File logo = existing(mediaFolder(g, "marquees"), base);
        if (logo != null && !o.dryRun) {
            int removed = 0;
            for (String m : APP_ICON_DROPPED) {
                File f = existing(mediaFolder(g, m), base);
                if (f != null && sameBytes(f, logo) && f.delete()) removed++;
            }
            if (removed > 0) {
                res.cleaned += removed;
                l.log("  − " + base + "  removed " + removed + " unneeded icon cop" + (removed == 1 ? "y" : "ies"));
            }
        }
        List<String> todo = new ArrayList<String>();
        Map<String, File> have = new HashMap<String, File>();
        for (String m : APP_ICON_MEDIA) {
            File existing = existing(mediaFolder(g, m), base);
            if (existing != null && !o.overwrite) { res.kept++; continue; }
            todo.add(m);
            if (existing != null) have.put(m, existing);
        }
        if (todo.isEmpty()) { l.log("  ✓ " + base + "  (app – icon already used everywhere)"); return; }
        res.matched++;
        List<String> got = new ArrayList<String>();
        File first = null;
        for (String m : todo) {
            File dir = mediaFolder(g, m);
            File dest = new File(dir, base + ".png");
            if (!o.dryRun) {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir.getPath());
                if (first == null) {
                    if (!appArt.saveMarquee(pkg, dest)) {
                        res.noArt++;
                        l.log("  ~ " + base + "  (" + pkg + " has no icon art)");
                        return;
                    }
                    first = dest;
                } else {
                    copyFile(first, dest);
                }
                File old = have.get(m);
                if (old != null && !old.getAbsolutePath().equals(dest.getAbsolutePath())) old.delete();
            }
            res.saved++;
            got.add(m);
        }
        l.log("  + " + base + "  → " + pkg + "  app icon as " + join(got));
    }

    static boolean sameBytes(File a, File b) {
        if (a.length() != b.length()) return false;
        java.io.InputStream x = null, y = null;
        try {
            x = new java.io.BufferedInputStream(new FileInputStream(a));
            y = new java.io.BufferedInputStream(new FileInputStream(b));
            int c;
            while ((c = x.read()) != -1) if (c != y.read()) return false;
            return y.read() == -1;
        } catch (IOException e) {
            return false;
        } finally {
            try { if (x != null) x.close(); } catch (IOException ignored) { }
            try { if (y != null) y.close(); } catch (IOException ignored) { }
        }
    }

    static void copyFile(File from, File to) throws IOException {
        File tmp = new File(to.getPath() + ".part");
        java.io.InputStream in = new FileInputStream(from);
        java.io.OutputStream out = null;
        try {
            out = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            out = null;
            if (to.exists() && !to.delete()) throw new IOException("can't replace " + to);
            if (!tmp.renameTo(to)) throw new IOException("can't write " + to);
        } finally {
            in.close();
            if (out != null) out.close();
            if (tmp.exists()) tmp.delete();
        }
    }

    // ---------------------------------------------------------------- manual picking

    /** Every SteamGridDB image usable for one ES-DE media type (all sources, best first, no duplicates). */
    public static List<Sgdb.Image> allImages(Sgdb api, long gameId, String media) throws IOException {
        List<Sgdb.Image> out = new ArrayList<Sgdb.Image>();
        Set<String> seen = new HashSet<String>();
        Source[] srcs = MEDIA.get(media);
        if (srcs == null) return out;
        for (Source s : srcs) {
            for (Sgdb.Image im : api.images(s.kind, gameId, s.dims)) {
                if (seen.add(im.url)) out.add(im);
            }
        }
        return out;
    }

    /** Saves one chosen image as this game's art, replacing whatever was there in any format. */
    public File saveChosen(Sgdb api, GameFile g, String media, String url) throws IOException {
        String ext = ext(url.replaceAll("[?#].*$", ""));
        if (ext.equals(".jpeg")) ext = ".jpg";
        if (!ext.equals(".png") && !ext.equals(".jpg")) ext = ".png";
        File dir = mediaFolder(g, media);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir.getPath());
        File dest = new File(dir, g.base() + ext);
        File old = existing(dir, g.base());
        api.download(url, dest);
        if (old != null && !old.getAbsolutePath().equals(dest.getAbsolutePath())) old.delete();
        return dest;
    }

    /** The art currently saved for this game and media type, or null. */
    public File currentArt(GameFile g, String media) {
        return existing(mediaFolder(g, media), g.base());
    }

    /** Best search term for a game: its ES-DE title if known, else the cleaned file name. */
    public String searchName(GameFile g, Map<String, String> names) {
        String n = names == null ? null : names.get(normPath(g.rel));
        return n != null ? n : Names.clean(g.base());
    }

    public Map<String, String> titles(String system) { return gamelistNames(system); }

    public File mediaFolder(GameFile g, String media) {
        File d = new File(new File(mediaDir, g.system), media);
        return g.sub().length() == 0 ? d : new File(d, g.sub());
    }

    static File existing(File dir, String base) {
        for (String e : IMAGE_EXTS) {
            File f = new File(dir, base + e);
            if (f.isFile()) return f;
        }
        return null;
    }

    // ---------------------------------------------------------------- utils

    static String ext(String name) {
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        return dot > slash && dot > 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    static String normPath(String p) {
        List<String> parts = new ArrayList<String>();
        for (String s : p.replace('\\', '/').split("/")) {
            if (s.length() == 0 || s.equals(".")) continue;
            if (s.equals("..") && !parts.isEmpty()) parts.remove(parts.size() - 1);
            else parts.add(s);
        }
        return join(parts, "/");
    }

    static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    public static String join(List<String> l) { return join(l, ", "); }

    static String join(List<String> l, String sep) {
        StringBuilder b = new StringBuilder();
        for (String s : l) { if (b.length() > 0) b.append(sep); b.append(s); }
        return b.toString();
    }

    static String readSmall(File f, int max) {
        if (f == null || !f.isFile() || f.length() > max) return null;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder b = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) b.append(buf, 0, n);
            return b.toString();
        } catch (IOException e) {
            return null;
        } finally {
            if (r != null) try { r.close(); } catch (IOException ignored) {}
        }
    }
}
