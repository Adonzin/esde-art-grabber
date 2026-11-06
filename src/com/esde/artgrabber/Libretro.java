package com.esde.artgrabber;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RetroArch's thumbnail collection (github.com/libretro-thumbnails): box art, in-game screenshots,
 * title screens and logos for retro systems, named after No-Intro ROM names. No key needed.
 */
public class Libretro {

    /** ES-DE media type -> libretro folder. */
    static final Map<String, String> TYPES = new HashMap<String, String>();
    static {
        TYPES.put("covers", "Named_Boxarts");
        TYPES.put("screenshots", "Named_Snaps");
        TYPES.put("titlescreens", "Named_Titles");
        TYPES.put("marquees", "Named_Logos");
    }

    /** ES-DE system folder -> libretro system name. */
    static final Map<String, String> SYSTEMS = new HashMap<String, String>();
    private static void sys(String libretro, String... esde) { for (String e : esde) SYSTEMS.put(e, libretro); }
    static {
        sys("Nintendo - Nintendo Entertainment System", "nes", "famicom", "nesh");
        sys("Nintendo - Family Computer Disk System", "fds");
        sys("Nintendo - Super Nintendo Entertainment System", "snes", "sfc", "snesna", "snesh");
        sys("Nintendo - Satellaview", "satellaview");
        sys("Nintendo - Sufami Turbo", "sufami");
        sys("Nintendo - Nintendo 64", "n64", "n64h");
        sys("Nintendo - Nintendo 64DD", "n64dd");
        sys("Nintendo - Game Boy", "gb", "gbh");
        sys("Nintendo - Game Boy Color", "gbc", "gbch");
        sys("Nintendo - Game Boy Advance", "gba", "gbah");
        sys("Nintendo - Nintendo DS", "nds");
        sys("Nintendo - Nintendo 3DS", "n3ds", "3ds");
        sys("Nintendo - GameCube", "gc", "gamecube");
        sys("Nintendo - Wii", "wii");
        sys("Nintendo - Wii U", "wiiu");
        sys("Nintendo - Virtual Boy", "virtualboy");
        sys("Nintendo - Pokemon Mini", "pokemini");
        sys("Sega - Master System - Mark III", "mastersystem", "markiii");
        sys("Sega - Mega Drive - Genesis", "megadrive", "genesis", "megadrivejp", "genh", "mdh");
        sys("Sega - Mega-CD - Sega CD", "segacd", "megacd", "megacdjp");
        sys("Sega - 32X", "sega32x", "sega32xjp", "sega32xna");
        sys("Sega - Game Gear", "gamegear");
        sys("Sega - SG-1000", "sg-1000", "sg1000");
        sys("Sega - Saturn", "saturn", "saturnjp");
        sys("Sega - Dreamcast", "dreamcast");
        sys("Sega - PICO", "pico");
        sys("Sega - Naomi", "naomi");
        sys("Sega - Naomi 2", "naomi2");
        sys("Atomiswave", "atomiswave");
        sys("Sony - PlayStation", "psx");
        sys("Sony - PlayStation 2", "ps2");
        sys("Sony - PlayStation 3", "ps3");
        sys("Sony - PlayStation 4", "ps4");
        sys("Sony - PlayStation Portable", "psp");
        sys("Sony - PlayStation Vita", "psvita");
        sys("Microsoft - Xbox", "xbox");
        sys("Microsoft - Xbox 360", "xbox360");
        sys("Microsoft - MSX", "msx", "msx1");
        sys("Microsoft - MSX2", "msx2");
        sys("Atari - 2600", "atari2600");
        sys("Atari - 5200", "atari5200");
        sys("Atari - 7800", "atari7800");
        sys("Atari - Jaguar", "atarijaguar", "jaguar");
        sys("Atari - Lynx", "atarilynx", "lynx");
        sys("Atari - ST", "atarist");
        sys("Atari - 8-bit Family", "atari800", "atarixe");
        sys("NEC - PC Engine - TurboGrafx 16", "pcengine", "tg16");
        sys("NEC - PC Engine CD - TurboGrafx-CD", "pcenginecd", "tg-cd");
        sys("NEC - PC Engine SuperGrafx", "supergrafx");
        sys("NEC - PC-FX", "pcfx");
        sys("NEC - PC-88", "pc88");
        sys("NEC - PC-98", "pc98");
        sys("SNK - Neo Geo", "neogeo");
        sys("SNK - Neo Geo CD", "neogeocd", "neogeocdjp");
        sys("SNK - Neo Geo Pocket", "ngp");
        sys("SNK - Neo Geo Pocket Color", "ngpc");
        sys("Bandai - WonderSwan", "wonderswan");
        sys("Bandai - WonderSwan Color", "wonderswancolor");
        sys("Coleco - ColecoVision", "colecovision");
        sys("Mattel - Intellivision", "intellivision");
        sys("GCE - Vectrex", "vectrex");
        sys("Magnavox - Odyssey2", "odyssey2");
        sys("Philips - Videopac+", "videopac");
        sys("Philips - CD-i", "cdimono1", "cdi");
        sys("Fairchild - Channel F", "channelf");
        sys("Watara - Supervision", "supervision");
        sys("Tiger - Game.com", "gamecom");
        sys("The 3DO Company - 3DO", "3do");
        sys("Commodore - 64", "c64");
        sys("Commodore - Amiga", "amiga", "amiga600", "amiga1200");
        sys("Commodore - CD32", "amigacd32", "cd32");
        sys("Commodore - CDTV", "cdtv");
        sys("Commodore - VIC-20", "vic20");
        sys("Commodore - Plus-4", "plus4");
        sys("Commodore - PET", "pet");
        sys("Amstrad - CPC", "amstradcpc");
        sys("Amstrad - GX4000", "gx4000");
        sys("Sinclair - ZX Spectrum", "zxspectrum");
        sys("Sinclair - ZX 81", "zx81");
        sys("Sharp - X68000", "x68000");
        sys("Sharp - X1", "x1");
        sys("MAME", "mame", "arcade", "mame-advmame", "mame-mame4all");
        sys("FBNeo - Arcade Games", "fbneo", "fba");
        sys("DOS", "dos", "pc");
        sys("ScummVM", "scummvm");
        sys("TIC-80", "tic80");
        sys("WASM-4", "wasm4");
        sys("Arduboy Inc - Arduboy", "arduboy");
        sys("Vircon32", "vircon32");
        sys("LowRes NX", "lowresnx");
        sys("GamePark - GP32", "gp32");
        sys("Hartung - Game Master", "gamemaster");
        sys("Casio - PV-1000", "pv1000");
        sys("Epoch - Super Cassette Vision", "scv");
        sys("VTech - CreatiVision", "crvision", "creativision");
        sys("VTech - V.Smile", "vsmile");
        sys("Emerson - Arcadia 2001", "arcadia");
        sys("Entex - Adventure Vision", "advision");
        sys("Spectravideo - SVI-318 - SVI-328", "spectravideo");
        sys("Thomson - MOTO", "moto");
    }

    public static String libretroName(String esdeSystem) {
        return SYSTEMS.get(esdeSystem.toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------- listings

    protected String apiBase = "https://api.github.com/repos/libretro-thumbnails/";
    protected String rawBase = "https://raw.githubusercontent.com/libretro-thumbnails/";
    protected String altBase = "https://thumbnails.libretro.com/";
    private final File cacheDir;
    private final Map<String, Map<String, List<String>>> listings = new HashMap<String, Map<String, List<String>>>();
    static final long CACHE_MS = 7L * 24 * 3600 * 1000;

    public Libretro(File cacheDir) { this.cacheDir = cacheDir; }

    static String repoName(String libretroName) { return libretroName.replace(' ', '_'); }

    /** type folder -> image names (without .png) for one system. Cached on disk for a week. */
    public synchronized Map<String, List<String>> listing(String lrName) throws IOException {
        Map<String, List<String>> m = listings.get(lrName);
        if (m != null) return m;
        File cache = cacheDir == null ? null : new File(cacheDir, "libretro/" + repoName(lrName) + ".txt");
        if (cache != null && cache.isFile() && System.currentTimeMillis() - cache.lastModified() < CACHE_MS) {
            m = readCache(cache);
            if (m != null) { listings.put(lrName, m); return m; }
        }
        IOException err = null;
        try {
            m = fromGitHub(lrName);
        } catch (IOException e) {
            err = e;
        }
        if (m == null || m.isEmpty()) {
            try {
                m = fromIndexPages(lrName);
            } catch (IOException e) {
                if (err == null) err = e;
            }
        }
        if (m == null || m.isEmpty()) {
            if (err != null) throw new IOException("couldn't list Libretro images: " + err.getMessage());
            m = new HashMap<String, List<String>>();
        } else if (cache != null) {
            writeCache(cache, m);
        }
        listings.put(lrName, m);
        return m;
    }

    private Map<String, List<String>> fromGitHub(String lrName) throws IOException {
        String json = get(apiBase + Sgdb.enc(repoName(lrName)) + "/git/trees/master?recursive=1", "application/vnd.github+json");
        if (json == null) return null;
        Map<String, List<String>> m = new HashMap<String, List<String>>();
        try {
            JSONArray tree = new JSONObject(json).optJSONArray("tree");
            if (tree == null) return null;
            for (int i = 0; i < tree.length(); i++) {
                JSONObject e = tree.optJSONObject(i);
                if (e == null || !"blob".equals(e.optString("type"))) continue;
                String p = e.optString("path");
                int slash = p.indexOf('/');
                if (slash < 0 || !p.toLowerCase(Locale.ROOT).endsWith(".png")) continue;
                add(m, p.substring(0, slash), p.substring(slash + 1, p.length() - 4));
            }
        } catch (org.json.JSONException e) {
            throw new IOException("bad listing from GitHub");
        }
        return m;
    }

    private static final Pattern HREF = Pattern.compile("href=\"([^\"?/]+\\.png)\"", Pattern.CASE_INSENSITIVE);

    /** Fallback: RetroArch's thumbnail server's folder index pages. */
    private Map<String, List<String>> fromIndexPages(String lrName) throws IOException {
        Map<String, List<String>> m = new HashMap<String, List<String>>();
        for (String type : TYPES.values()) {
            String html = get(altBase + pathEnc(lrName) + "/" + type + "/", "text/html");
            if (html == null) continue;
            Matcher h = HREF.matcher(html);
            while (h.find()) {
                String f = URLDecoder.decode(h.group(1).replace("+", "%2B"), "UTF-8");
                add(m, type, f.substring(0, f.length() - 4));
            }
        }
        return m;
    }

    private static void add(Map<String, List<String>> m, String type, String name) {
        List<String> l = m.get(type);
        if (l == null) { l = new ArrayList<String>(); m.put(type, l); }
        l.add(name);
    }

    private static Map<String, List<String>> readCache(File f) {
        Map<String, List<String>> m = new HashMap<String, List<String>>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0) add(m, line.substring(0, tab), line.substring(tab + 1));
            }
            return m;
        } catch (IOException e) {
            return null;
        } finally {
            if (r != null) try { r.close(); } catch (IOException ignored) { }
        }
    }

    private static void writeCache(File f, Map<String, List<String>> m) {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            for (Map.Entry<String, List<String>> e : m.entrySet())
                for (String n : e.getValue()) w.write(e.getKey() + "\t" + n + "\n");
        } catch (IOException ignored) {
        } finally {
            if (w != null) try { w.close(); } catch (IOException ignored) { }
        }
    }

    // ---------------------------------------------------------------- matching

    /** Libretro replaces these characters in file names with '_'. */
    static String safeName(String s) { return s.replaceAll("[&*/:`<>?\\\\|\"]", "_"); }

    private static final Pattern TAG = Pattern.compile("\\(([^)]*)\\)");
    private static final String[] REGION_ORDER = {"usa", "world", "europe", "japan"};

    /** Fast lookups over one folder's names. */
    public static class Index {
        final List<String> names;
        final java.util.Set<String> exact = new java.util.HashSet<String>();
        final Map<String, String> lower = new HashMap<String, String>();
        final Map<String, List<String>> byTitle = new HashMap<String, List<String>>();
        final Map<String, List<String>> byPrefix = new HashMap<String, List<String>>();
        final Map<String, String> normOf = new HashMap<String, String>();

        public Index(List<String> names) {
            this.names = names == null ? new ArrayList<String>() : names;
            for (String n : this.names) {
                exact.add(n);
                lower.put(n.toLowerCase(Locale.ROOT), n);
                String nn = Names.norm(Names.clean(n));
                normOf.put(n, nn);
                put(byTitle, nn, n);
                put(byPrefix, prefix(nn), n);
            }
        }
        public boolean has(String name) { return exact.contains(name); }
        public boolean isEmpty() { return names.isEmpty(); }
        private static void put(Map<String, List<String>> m, String k, String v) {
            List<String> l = m.get(k);
            if (l == null) { l = new ArrayList<String>(); m.put(k, l); }
            l.add(v);
        }
        static String prefix(String nn) { return nn.length() <= 3 ? nn : nn.substring(0, 3); }
    }

    private final Map<String, Index> indexes = new HashMap<String, Index>();

    /** Index for one system + folder (built once per run). */
    public synchronized Index index(String lrName, String type) throws IOException {
        String k = lrName + "/" + type;
        Index ix = indexes.get(k);
        if (ix == null) {
            ix = new Index(listing(lrName).get(type));
            indexes.put(k, ix);
        }
        return ix;
    }

    /**
     * Picks the image for a ROM: exact file name first, then the same title in the closest region
     * (fewest extra tags), then a very close fuzzy match. Null if nothing is close enough.
     */
    public static String match(Index ix, String romBase, String title) {
        if (ix == null || ix.isEmpty()) return null;
        String exact = safeName(romBase);
        if (ix.has(exact)) return exact;
        String ci = ix.lower.get(exact.toLowerCase(Locale.ROOT));
        if (ci != null) return ci;

        List<String> wantTags = tags(romBase);
        String clean = Names.clean(romBase);
        String[] queries = title != null && !Names.norm(title).equals(Names.norm(clean))
                ? new String[]{clean, title} : new String[]{clean};
        for (String q : queries) {
            String nq = Names.norm(safeName(q));
            if (nq.isEmpty()) continue;
            // same title, any region: pick the best-fitting release
            String pick = pickRelease(ix.byTitle.get(nq), wantTags, 10);
            if (pick != null) return pick;
            // close spelling only among names that start the same way and are a similar length
            List<String> cands = ix.byPrefix.get(Index.prefix(nq));
            if (cands == null) continue;
            String bestN = null;
            double best = -1;
            for (String n : cands) {
                String nn = ix.normOf.get(n);
                if (Math.abs(nn.length() - nq.length()) > Math.max(3, nq.length() / 5)) continue;
                double sim = Names.similarity(nq, nn);
                if (sim < 0.9) continue;
                double sc = sim * 10 + releaseScore(n, wantTags);
                if (sc > best) { best = sc; bestN = n; }
            }
            if (bestN != null) return bestN;
        }
        return null;
    }

    /** Convenience for a plain list (tests, one-off use). */
    public static String match(List<String> names, String romBase, String title) {
        return match(new Index(names), romBase, title);
    }

    private static String pickRelease(List<String> cands, List<String> wantTags, double base) {
        if (cands == null || cands.isEmpty()) return null;
        String bestN = null;
        double best = -1e9;
        for (String n : cands) {
            double sc = base + releaseScore(n, wantTags);
            if (sc > best) { best = sc; bestN = n; }
        }
        return bestN;
    }

    /** Prefer the ROM's own region/tags, then common regions, then plain releases over betas and hacks. */
    private static double releaseScore(String name, List<String> wantTags) {
        List<String> t = tags(name);
        double s = 0;
        for (String w : wantTags) if (t.contains(w)) s += 1;
        for (int i = 0; i < REGION_ORDER.length; i++) if (t.contains(REGION_ORDER[i])) { s += 0.4 - i * 0.1; break; }
        s -= 0.05 * t.size();
        for (String bad : new String[]{"beta", "proto", "demo", "sample", "hack", "pirate", "unl", "switch online"})
            if (t.contains(bad)) s -= 0.5;
        return s;
    }

    private static List<String> tags(String s) {
        List<String> out = new ArrayList<String>();
        Matcher m = TAG.matcher(s);
        while (m.find()) for (String p : m.group(1).split(",")) {
            String t = p.trim().toLowerCase(Locale.ROOT);
            if (t.length() > 0) out.add(t);
        }
        return out;
    }

    // ---------------------------------------------------------------- download

    /** Downloads one thumbnail. False if neither host has it. */
    public boolean download(String lrName, String type, String name, File dest) throws IOException {
        String file = pathEnc(name) + ".png";
        String[] urls = {
                rawBase + Sgdb.enc(repoName(lrName)) + "/master/" + type + "/" + file,
                altBase + pathEnc(lrName) + "/" + type + "/" + file};
        for (String u : urls) if (fetchTo(u, dest)) return true;
        return false;
    }

    static String pathEnc(String s) { return Sgdb.enc(s); }

    private boolean fetchTo(String url, File dest) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "ESDE-ArtGrabber/1.0");
        c.setConnectTimeout(15000);
        c.setReadTimeout(45000);
        File tmp = new File(dest.getPath() + ".part");
        InputStream in = null;
        FileOutputStream out = null;
        try {
            int code;
            try { code = c.getResponseCode(); } catch (IOException e) { return false; }
            if (code != 200) return false;
            in = c.getInputStream();
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[65536];
            byte[] head = new byte[8];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                if (total < 8) System.arraycopy(buf, 0, head, total, Math.min(8 - total, n));
                out.write(buf, 0, n);
                total += n;
            }
            out.close();
            out = null;
            // must really be a PNG (GitHub answers some misses with a text page)
            if (total < 100 || head[0] != (byte) 0x89 || head[1] != 'P' || head[2] != 'N' || head[3] != 'G') return false;
            if (dest.exists() && !dest.delete()) throw new IOException("can't replace " + dest);
            if (!tmp.renameTo(dest)) throw new IOException("can't write " + dest);
            return true;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
            if (out != null) try { out.close(); } catch (IOException ignored) { }
            if (tmp.exists()) tmp.delete();
            c.disconnect();
        }
    }

    private static String get(String url, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "ESDE-ArtGrabber/1.0");
        c.setRequestProperty("Accept", accept);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        try {
            int code = c.getResponseCode();
            if (code == 404) return null;
            if (code == 403 || code == 429) throw new IOException("rate limited (HTTP " + code + ")");
            if (code != 200) throw new IOException("HTTP " + code);
            return Sgdb.readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }
}
