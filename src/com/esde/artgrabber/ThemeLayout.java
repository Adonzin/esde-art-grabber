package com.esde.artgrabber;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out where an ES-DE theme keeps each system's own artwork and info, by reading the theme's XML:
 * every value that contains ${system.theme} (directly, or through variables that change with variants and
 * colour schemes) is a "slot" that a new category needs its own file in. Each slot gets a role and the size
 * and format of the matching file of a reference system, so a category's art can be made to fit.
 * No Android code: tested on the desktop against real themes.
 */
public class ThemeLayout {

    /** Something the theme looks up per system. */
    public static class Slot {
        /** Path with {S} where the system name goes; relative to the theme folder, or starting with ~/ */
        public String pattern;
        public String ext;            // .png .jpg .webp .svg .xml ...
        public String role;           // tile, logo, wordmark, background, artwork, icon, controller, overlay, info, other
        public String label;          // short human description
        public final Set<String> usedBy = new LinkedHashSet<String>();
        public boolean outside;       // in ES-DE's folder (~/ES-DE/...), not inside the theme
        public boolean folderPerSystem;
        public File refFile;          // the reference system's file, if it exists
        public int refW, refH;
        public int existing;          // how many systems already have a file here
        public boolean custom;        // a customisation hook the theme offers (empty folder meant for your own art)

        public boolean isImage() { return IMAGE_EXTS.contains(ext); }

        public File fileFor(File themeDir, File esdeHome, String system) {
            String p = pattern.replace("{S}", system);
            if (p.startsWith("~/")) return new File(esdeHome, p.substring(2));
            return new File(themeDir, p);
        }

        @Override public String toString() {
            return String.format(Locale.ROOT, "%-10s %-55s %s%s%s%s", role, pattern,
                    refW > 0 ? refW + "x" + refH : (refFile != null ? "found" : "-"),
                    outside ? "  (outside theme)" : "", custom ? "  (customisation slot)" : "", "  [" + existing + " systems]");
        }
    }

    static final Set<String> IMAGE_EXTS = new LinkedHashSet<String>(Arrays.asList(".png", ".jpg", ".jpeg", ".webp", ".svg", ".gif"));
    static final String[] REFERENCE_SYSTEMS = {"androidapps", "android", "androidgames", "windows", "pc", "snes", "nes", "psx", "genesis", "megadrive"};

    public final File dir;
    public final String themeName;
    public final List<Slot> slots = new ArrayList<Slot>();
    public final List<String> notes = new ArrayList<String>();
    public String referenceSystem;

    private ThemeLayout(File dir, String themeName) { this.dir = dir; this.themeName = themeName; }

    public List<Slot> imageSlots() {
        List<Slot> out = new ArrayList<Slot>();
        for (Slot s : slots) if (s.isImage()) out.add(s);
        return out;
    }

    // ================================================================ reading

    private static final Pattern VARS_BLOCK = Pattern.compile("(?s)<variables>(.*?)</variables>");
    private static final Pattern VAR_DEF = Pattern.compile("(?s)<([A-Za-z_][\\w.-]*)>(.*?)</\\1>");
    private static final Pattern VALUE = Pattern.compile("(?s)<([A-Za-z_][\\w.-]*)(\\s[^>]*)?>([^<]*\\$\\{[^<]*)</\\1>");
    private static final Pattern REF = Pattern.compile("\\$\\{([A-Za-z_][\\w.-]*)\\}");
    private static final Pattern OPEN_EL = Pattern.compile("<(image|video|carousel|grid|textlist|animation|gif|lottie|text|datetime|rating|badges|gameselector|helpsystem|systemstatus)(?=[\\s>/])(\\s+name=\"([^\"]*)\")?[^>]*>");
    private static final Pattern VIEW = Pattern.compile("<view\\s+name=\"([^\"]*)\"");

    /** Reads a theme folder. referenceSystem may be null (then the first common system that exists is used). */
    public static ThemeLayout read(File themeDir, String referenceSystem) throws IOException {
        String name = themeDir.getName();
        String caps = readText(new File(themeDir, "capabilities.xml"));
        if (caps != null) {
            Matcher m = Pattern.compile("<themeName>([^<]*)</themeName>").matcher(caps);
            if (m.find()) name = m.group(1).trim();
        }
        ThemeLayout t = new ThemeLayout(themeDir, name);
        List<File> xmls = new ArrayList<File>();
        collectXml(themeDir, xmls, 0);
        if (xmls.isEmpty()) throw new IOException("No theme files found in " + themeDir);

        Map<String, String> texts = new LinkedHashMap<String, String>();
        for (File f : xmls) {
            String s = readText(f);
            if (s != null) texts.put(f.getPath(), stripComments(s));
        }

        // 1. every variable definition (a name can have several values: variants, colour schemes, languages)
        Map<String, Set<String>> vars = new HashMap<String, Set<String>>();
        for (String s : texts.values()) {
            Matcher b = VARS_BLOCK.matcher(s);
            while (b.find()) {
                Matcher d = VAR_DEF.matcher(b.group(1));
                while (d.find()) {
                    Set<String> vals = vars.get(d.group(1));
                    if (vals == null) { vals = new LinkedHashSet<String>(); vars.put(d.group(1), vals); }
                    vals.add(d.group(2).trim());
                }
            }
        }

        // 2. where each variable is used (to know what a variable holding ${system.theme} is for)
        Map<String, Set<String>> usage = new HashMap<String, Set<String>>();
        Map<String, File> usageFile = new HashMap<String, File>();
        List<Object[]> direct = new ArrayList<Object[]>();   // {raw value, usage label, file}
        for (Map.Entry<String, String> e : texts.entrySet()) {
            String s = e.getValue();
            File f = new File(e.getKey());
            Matcher v = VALUE.matcher(s);
            while (v.find()) {
                String tag = v.group(1), raw = v.group(3).trim();
                if (insideVariables(s, v.start())) continue;
                String where = context(s, v.start(), tag);
                if (raw.contains("${system.theme}")) direct.add(new Object[]{raw, where, f});
                Matcher r = REF.matcher(raw);
                while (r.find()) {
                    String var = r.group(1);
                    if (var.startsWith("system.") || var.startsWith("lang")) continue;
                    Set<String> u = usage.get(var);
                    if (u == null) { u = new LinkedHashSet<String>(); usage.put(var, u); }
                    u.add(where);
                    usageFile.put(var, f);
                }
            }
        }

        // 3. variables whose value holds ${system.theme} are used wherever the variable is used
        for (Map.Entry<String, Set<String>> e : vars.entrySet()) {
            for (String val : e.getValue()) {
                if (!val.contains("${system.theme}")) continue;
                Set<String> u = usage.get(e.getKey());
                if (u == null || u.isEmpty()) continue;   // defined but never used
                for (String where : u) direct.add(new Object[]{val, where + " (via ${" + e.getKey() + "})", usageFile.get(e.getKey())});
            }
        }

        // 4. resolve every value into concrete patterns
        Map<String, Slot> byPattern = new LinkedHashMap<String, Slot>();
        for (Object[] d : direct) {
            String raw = (String) d[0];
            String where = (String) d[1];
            File file = (File) d[2];
            for (String resolved : expand(raw, vars, 0)) {
                String withS = resolved.replace("${system.theme}", "{S}");
                if (withS.contains("${")) continue;   // couldn't resolve (e.g. a value only ES-DE sets)
                String pat = normalise(themeDir, file, withS);
                if (pat == null) continue;
                Slot s = byPattern.get(pat);
                if (s == null) {
                    s = new Slot();
                    s.pattern = pat;
                    s.outside = pat.startsWith("~/");
                    s.folderPerSystem = pat.indexOf("{S}/") >= 0;
                    String last = pat.substring(pat.lastIndexOf('/') + 1);
                    int dot = last.lastIndexOf('.');
                    s.ext = dot >= 0 ? last.substring(dot).toLowerCase(Locale.ROOT) : "";
                    byPattern.put(pat, s);
                }
                s.usedBy.add(where);
            }
        }
        t.slots.addAll(byPattern.values());
        for (Slot s : t.slots) { s.role = role(s); s.label = label(s); }

        // 5. reference system: the first common one that has files in most slots
        t.referenceSystem = referenceSystem;
        if (t.referenceSystem == null) {
            int best = -1;
            for (String cand : REFERENCE_SYSTEMS) {
                int n = 0;
                for (Slot s : t.slots) if (!s.outside && s.fileFor(themeDir, themeDir, cand).exists()) n++;
                if (n > best) { best = n; t.referenceSystem = cand; }
            }
        }
        for (Slot s : t.slots) {
            String lp = s.pattern.toLowerCase(Locale.ROOT);
            s.custom = lp.contains("custom") && !lp.contains("metadata-custom");
            if (!s.outside) {
                File ref = s.fileFor(themeDir, themeDir, t.referenceSystem);
                if (ref.isFile()) {
                    s.refFile = ref;
                    int[] wh = imageSize(ref);
                    if (wh != null) { s.refW = wh[0]; s.refH = wh[1]; }
                }
                s.existing = countExisting(themeDir, s);
            }
        }
        // empty slots borrow the size of a filled slot with the same role and format family
        for (Slot s : t.slots) {
            if (s.refW > 0 || !s.isImage()) continue;
            for (Slot o : t.slots) {
                if (o.refW > 0 && o.role.equals(s.role) && (o.ext.equals(".svg") == s.ext.equals(".svg"))) { s.refW = o.refW; s.refH = o.refH; break; }
            }
            if (s.refW == 0) for (Slot o : t.slots) if (o.refW > 0 && o.role.equals(s.role)) { s.refW = o.refW; s.refH = o.refH; break; }
        }
        Collections.sort(t.slots, new Comparator<Slot>() {
            public int compare(Slot a, Slot b) {
                int ra = rank(a.role), rb = rank(b.role);
                return ra != rb ? ra - rb : b.existing - a.existing;
            }
        });
        for (Slot s : t.slots) if (s.outside) {
            t.notes.add("Also reads " + s.pattern + " from ES-DE's own folder, so art can be added there without copying the theme.");
            break;
        }
        return t;
    }

    static int rank(String role) {
        List<String> order = Arrays.asList("tile", "logo", "wordmark", "background", "artwork", "icon", "controller", "overlay", "other", "info");
        int i = order.indexOf(role);
        return i < 0 ? 99 : i;
    }

    /** Expands ${var} references into every combination of their known values (bounded). */
    static List<String> expand(String s, Map<String, Set<String>> vars, int depth) {
        Matcher r = REF.matcher(s);
        while (r.find()) {
            String var = r.group(1);
            if (var.equals("system.theme")) continue;
            Set<String> vals = vars.get(var);
            if (vals == null || depth > 6) return Collections.singletonList(s);
            List<String> out = new ArrayList<String>();
            for (String v : vals) {
                if (v.contains("${" + var + "}")) continue;
                for (String e : expand(s.substring(0, r.start()) + v + s.substring(r.end()), vars, depth + 1)) {
                    if (!out.contains(e)) out.add(e);
                    if (out.size() > 40) return out;
                }
            }
            return out;
        }
        return Collections.singletonList(s);
    }

    /** Makes a path relative to the theme folder ("~/" kept as-is). Null if it points outside the theme. */
    static String normalise(File themeDir, File usedIn, String p) {
        p = p.trim().replace('\\', '/');
        if (p.startsWith("~/")) return p;
        if (p.startsWith("/") || p.matches("^[A-Za-z]:/.*")) return null;
        // ES-DE resolves ./ against the file the value is in; themes almost always mean the theme root
        String base = "";
        if (usedIn != null) {
            String rel = themeDir.toURI().relativize(usedIn.getParentFile().toURI()).getPath();
            base = rel;
        }
        String fromFile = clean(base + strip(p)), fromRoot = clean(strip(p));
        if (fromFile == null && fromRoot == null) return null;
        if (fromFile != null && !fromFile.equals(fromRoot) && dirExists(themeDir, fromFile)) return fromFile;
        return fromRoot != null ? fromRoot : fromFile;
    }

    private static String strip(String p) { return p.startsWith("./") ? p.substring(2) : p; }

    private static boolean dirExists(File themeDir, String pattern) {
        String parent = pattern.contains("/") ? pattern.substring(0, pattern.lastIndexOf('/')) : "";
        int s = parent.indexOf("{S}");
        if (s >= 0) parent = parent.substring(0, s);
        return new File(themeDir, parent).isDirectory();
    }

    private static String clean(String p) {
        List<String> parts = new ArrayList<String>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) { if (parts.isEmpty()) return null; parts.remove(parts.size() - 1); }
            else parts.add(seg);
        }
        StringBuilder b = new StringBuilder();
        for (String seg : parts) { if (b.length() > 0) b.append('/'); b.append(seg); }
        return b.toString();
    }

    private static boolean insideVariables(String s, int pos) {
        int open = s.lastIndexOf("<variables>", pos), close = s.lastIndexOf("</variables>", pos);
        return open > close;
    }

    /** "carousel system-carousel staticImage (system view)" for the element a value sits in. */
    private static String context(String s, int pos, String tag) {
        String elType = null, elName = null;
        Matcher m = OPEN_EL.matcher(s);
        m.region(Math.max(0, pos - 20000), pos);
        while (m.find()) {
            if (m.group().endsWith("/>")) continue;
            int close = s.indexOf("</" + m.group(1) + ">", m.end());
            if (close < 0 || close > pos) { elType = m.group(1); elName = m.group(3); }   // still open at pos: innermost wins
        }
        String view = null;
        Matcher v = VIEW.matcher(s);
        v.region(0, pos);
        while (v.find()) view = v.group(1);
        StringBuilder b = new StringBuilder();
        if (elType != null) b.append(elType).append(elName != null ? " \"" + elName + "\"" : "").append(' ');
        b.append(tag);
        if (view != null && elType != null) b.append(" (").append(view).append(" view)");
        return b.toString();
    }

    static String role(Slot s) {
        String p = s.pattern.toLowerCase(Locale.ROOT);
        String u = s.usedBy.toString().toLowerCase(Locale.ROOT);
        if (s.ext.equals(".xml")) return "info";
        if (!s.isImage()) return "other";
        if (u.contains("staticimage") && !p.contains("logo") && !p.contains("marquee")) return "tile";
        if (p.contains("marquee") || p.contains("wordmark")) return "wordmark";
        if (p.contains("logo")) return s.ext.equals(".svg") ? "wordmark" : "logo";
        if (p.contains("background") || p.contains("/bg") || p.contains("fanart") || p.contains("wallpaper")) return "background";
        if (p.contains("controller")) return "controller";
        if (p.contains("overlay")) return "overlay";
        if (p.contains("icon")) return "icon";
        if (p.contains("art") || p.contains("screenshot") || p.contains("system")) return "artwork";
        return "other";
    }

    static String label(Slot s) {
        String p = s.pattern;
        int i = p.indexOf("{S}");
        String folder = p.substring(0, Math.max(0, i)).replaceAll("/$", "");
        folder = folder.replaceAll("^_?inc/", "").replaceAll("^systems?/", "");
        String nice = folder.replace('/', ' ').replace('-', ' ').replace('_', ' ').trim();
        String base;
        if ("tile".equals(s.role)) base = "Main carousel image";
        else if ("logo".equals(s.role)) base = "Logo";
        else if ("wordmark".equals(s.role)) base = "Name logo";
        else if ("background".equals(s.role)) base = "Background";
        else if ("info".equals(s.role)) base = "System info";
        else base = Character.toUpperCase(s.role.charAt(0)) + s.role.substring(1);
        return nice.isEmpty() ? base : base + " (" + nice + ")";
    }

    private static int countExisting(File themeDir, Slot s) {
        int i = s.pattern.indexOf("{S}");
        String parent = s.pattern.substring(0, i);
        File dir = new File(themeDir, parent);
        String[] names = dir.list();
        if (names == null) return 0;
        String suffix = s.pattern.substring(i + 3);
        int n = 0;
        if (s.folderPerSystem) {
            for (String nm : names) if (new File(dir, nm + suffix).exists()) n++;
        } else {
            for (String nm : names) if (nm.endsWith(suffix) && !nm.startsWith("_")) n++;
        }
        return n;
    }

    private static void collectXml(File d, List<File> out, int depth) {
        if (depth > 6) return;
        File[] fs = d.listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        for (File f : fs) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) collectXml(f, out, depth + 1);
            else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".xml") && f.length() < 2_000_000) out.add(f);
        }
    }

    static String stripComments(String s) { return s.replaceAll("(?s)<!--.*?-->", ""); }

    static String readText(File f) {
        if (f == null || !f.isFile()) return null;
        try {
            InputStream in = new FileInputStream(f);
            try {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toString("UTF-8");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    // ================================================================ image sizes (no Android needed)

    /** Width and height of a PNG, JPEG, WebP, GIF or SVG file, or null. */
    public static int[] imageSize(File f) {
        byte[] h = new byte[65536];
        int n;
        try {
            InputStream in = new FileInputStream(f);
            try { n = in.read(h); } finally { in.close(); }
        } catch (IOException e) {
            return null;
        }
        if (n < 30) return null;
        if ((h[0] & 0xFF) == 0x89 && h[1] == 'P') return new int[]{be32(h, 16), be32(h, 20)};
        if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F') return new int[]{le16(h, 6), le16(h, 8)};
        if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E') {
            String kind = new String(h, 12, 4);
            if (kind.equals("VP8X")) return new int[]{1 + le24(h, 24), 1 + le24(h, 27)};
            if (kind.equals("VP8L")) {
                int b = (h[21] & 0xFF) | (h[22] & 0xFF) << 8 | (h[23] & 0xFF) << 16 | (h[24] & 0xFF) << 24;
                return new int[]{1 + (b & 0x3FFF), 1 + ((b >> 14) & 0x3FFF)};
            }
            if (kind.equals("VP8 ")) return new int[]{le16(h, 26) & 0x3FFF, le16(h, 28) & 0x3FFF};
            return null;
        }
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8) {
            int i = 2;
            while (i + 9 < n) {
                if ((h[i] & 0xFF) != 0xFF) { i++; continue; }
                int marker = h[i + 1] & 0xFF;
                if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC)
                    return new int[]{be16(h, i + 7), be16(h, i + 5)};
                i += 2 + be16(h, i + 2);
            }
            return null;
        }
        String text = new String(h, 0, n, java.nio.charset.StandardCharsets.UTF_8);
        int svg = text.indexOf("<svg");
        if (svg >= 0) {
            String tag = text.substring(svg, Math.max(svg, text.indexOf('>', svg)));
            Matcher vb = Pattern.compile("viewBox\\s*=\\s*\"\\s*[-\\d.]+[\\s,]+[-\\d.]+[\\s,]+([\\d.]+)[\\s,]+([\\d.]+)").matcher(tag);
            if (vb.find()) return new int[]{(int) Math.round(Double.parseDouble(vb.group(1))), (int) Math.round(Double.parseDouble(vb.group(2)))};
            Matcher w = Pattern.compile("\\swidth\\s*=\\s*\"([\\d.]+)(px)?\"").matcher(tag), ht = Pattern.compile("\\sheight\\s*=\\s*\"([\\d.]+)(px)?\"").matcher(tag);
            if (w.find() && ht.find()) return new int[]{(int) Double.parseDouble(w.group(1)), (int) Double.parseDouble(ht.group(1))};
        }
        return null;
    }

    private static int be32(byte[] b, int i) { return (b[i] & 0xFF) << 24 | (b[i + 1] & 0xFF) << 16 | (b[i + 2] & 0xFF) << 8 | (b[i + 3] & 0xFF); }
    private static int be16(byte[] b, int i) { return (b[i] & 0xFF) << 8 | (b[i + 1] & 0xFF); }
    private static int le16(byte[] b, int i) { return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8; }
    private static int le24(byte[] b, int i) { return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8 | (b[i + 2] & 0xFF) << 16; }
}
