package com.esde.artgrabber;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Editable copies of ES-DE themes. ES-DE on Android keeps downloaded themes in its private storage, so a copy is
 * made by downloading the theme again from the source ES-DE's own theme downloader uses, into ES-DE/themes,
 * renamed "<Name> (ESCommander)". No Android code: tested on the desktop.
 */
public class ThemeCopies {

    public static final String SUFFIX = "-escommander";
    public static final String MARKER = "escommander-theme.json";
    static final String LIST_PROJECT = "es-de/themes/themes-list";

    public static class Entry {
        public String name, reponame, url, author;
        public boolean deprecated;
        @Override public String toString() { return name + (author != null && author.length() > 0 ? "  ·  " + author : ""); }
    }

    public interface Progress { void update(String what, long done, long total); }

    public final File esdeDir;
    /** Overridable for tests. */
    protected String gitlabApi = "https://gitlab.com/api/v4/projects/";
    protected String githubCodeload = "https://codeload.github.com/";
    protected String gitlabWeb = "https://gitlab.com/";

    public ThemeCopies(File esdeDir) { this.esdeDir = esdeDir; }

    public File themesDir() { return new File(esdeDir, "themes"); }

    /** The theme ES-DE is set to use (its folder name), or null. */
    public String currentTheme() {
        String v = new EsdeConfig(esdeDir, esdeDir).setting("Theme");
        if (v == null || v.trim().isEmpty()) v = new EsdeConfig(esdeDir, esdeDir).setting("ThemeSet");   // older ES-DE versions
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    public static boolean isCopy(String folder) { return folder != null && folder.toLowerCase(Locale.ROOT).endsWith(SUFFIX); }

    /** The original theme a copy was made from ("ps5-menu-es-de-escommander" -> "ps5-menu-es-de"). */
    public static String originalOf(String folder) {
        return isCopy(folder) ? folder.substring(0, folder.length() - SUFFIX.length()) : folder;
    }

    public File copyDir(String reponame) { return new File(themesDir(), originalOf(reponame) + SUFFIX); }

    /** Themes in ES-DE/themes (the ones this app can read and edit). */
    public List<File> localThemes() {
        List<File> out = new ArrayList<File>();
        File[] ds = themesDir().listFiles();
        if (ds == null) return out;
        Arrays.sort(ds);
        for (File d : ds) if (d.isDirectory() && new File(d, "theme.xml").isFile() && !d.getName().endsWith(".partial")) out.add(d);
        return out;
    }

    // ================================================================ the official list

    /** ES-DE's official theme list (the one its theme downloader shows), Android-only entries included. */
    public List<Entry> officialList() throws IOException {
        String json = null;
        IOException last = null;
        for (String ref : new String[]{"master", "main", "HEAD"}) {
            try {
                json = get(gitlabApi + Sgdb.enc(LIST_PROJECT) + "/repository/files/themes.json/raw?ref=" + ref);
                if (json != null) break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (json == null) throw last != null ? last : new IOException("couldn't get ES-DE's theme list");
        return parseList(json);
    }

    static List<Entry> parseList(String json) throws IOException {
        List<Entry> out = new ArrayList<Entry>();
        try {
            JSONObject doc = new JSONObject(json);
            for (String key : new String[]{"themes", "themesAndroid"}) {
                JSONArray a = doc.optJSONArray(key);
                if (a == null) continue;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject t = a.optJSONObject(i);
                    if (t == null || t.optString("reponame").isEmpty() || t.optString("url").isEmpty()) continue;
                    Entry e = new Entry();
                    e.name = t.optString("name", t.optString("reponame"));
                    e.reponame = t.optString("reponame");
                    e.url = t.optString("url");
                    e.author = t.optString("author", "");
                    e.deprecated = t.optBoolean("deprecated");
                    out.add(e);
                }
            }
        } catch (JSONException e) {
            throw new IOException("ES-DE's theme list couldn't be read");
        }
        return out;
    }

    public static Entry find(List<Entry> list, String folder) {
        String want = originalOf(folder);
        for (Entry e : list) if (e.reponame.equalsIgnoreCase(want)) return e;
        return null;
    }

    // ================================================================ downloading a copy

    private static final Pattern GITHUB = Pattern.compile("^https?://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITLAB = Pattern.compile("^https?://gitlab\\.com/(.+?)(?:\\.git)?/?$", Pattern.CASE_INSENSITIVE);

    /** Zip download addresses for a theme's git address, most reliable first. */
    List<String> archiveUrls(String gitUrl) {
        List<String> out = new ArrayList<String>();
        Matcher gh = GITHUB.matcher(gitUrl.trim());
        if (gh.find()) {
            out.add(githubCodeload + gh.group(1) + "/" + gh.group(2) + "/zip/HEAD");
            return out;
        }
        Matcher gl = GITLAB.matcher(gitUrl.trim());
        if (gl.find()) {
            String path = gl.group(1);
            out.add(gitlabApi + Sgdb.enc(path) + "/repository/archive.zip");
            String repo = path.substring(path.lastIndexOf('/') + 1);
            for (String ref : new String[]{"main", "master"}) out.add(gitlabWeb + path + "/-/archive/" + ref + "/" + repo + "-" + ref + ".zip");
        }
        return out;
    }

    /**
     * Downloads the theme and installs it as "<Name> (ESCommander)", replacing an older copy only once the new one
     * is complete. The original theme isn't touched. Returns the copy's folder.
     */
    public File makeCopy(Entry e, File cacheDir, Progress p) throws IOException {
        List<String> urls = archiveUrls(e.url);
        if (urls.isEmpty()) throw new IOException("Don't know how to download from " + e.url);
        File zip = new File(cacheDir, "theme-download.zip");
        IOException last = null;
        boolean ok = false;
        for (String u : urls) {
            try {
                if (download(u, zip, p)) { ok = true; break; }
            } catch (IOException ex) {
                last = ex;
            }
        }
        if (!ok) throw last != null ? last : new IOException("The theme couldn't be downloaded.");
        File dest = copyDir(e.reponame);
        File partial = new File(dest.getPath() + ".partial");
        EsdeConfig.deleteTree(partial);
        try {
            unzipStrippingTop(zip, partial, p);
            if (!new File(partial, "theme.xml").isFile()) throw new IOException("The download doesn't look like an ES-DE theme.");
            renameTheme(partial, e.name);
            JSONObject m = new JSONObject().put("name", e.name).put("reponame", originalOf(e.reponame)).put("url", e.url)
                    .put("downloaded", System.currentTimeMillis());
            EsdeConfig.writeBytes(new File(partial, MARKER), m.toString(2).getBytes("UTF-8"));
        } catch (JSONException ex) {
            throw new IOException("couldn't record where the copy came from");
        } catch (IOException ex) {
            EsdeConfig.deleteTree(partial);
            throw ex;
        } finally {
            zip.delete();
        }
        File old = new File(dest.getPath() + ".old");
        EsdeConfig.deleteTree(old);
        if (dest.exists() && !dest.renameTo(old)) { EsdeConfig.deleteTree(partial); throw new IOException("can't replace the old copy"); }
        if (!partial.renameTo(dest)) {
            if (old.exists()) old.renameTo(dest);
            throw new IOException("can't install the copy");
        }
        EsdeConfig.deleteTree(old);
        return dest;
    }

    /** Folders left behind by an interrupted download or replacement (".partial", ".old"). */
    public List<File> leftovers() {
        List<File> out = new ArrayList<File>();
        File[] ds = themesDir().listFiles();
        if (ds == null) return out;
        for (File d : ds) {
            String n = d.getName();
            if (d.isDirectory() && (n.endsWith(SUFFIX + ".partial") || n.endsWith(SUFFIX + ".old"))) out.add(d);
        }
        return out;
    }

    /** Disk space a folder uses. */
    public static long size(File f) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += size(k);
        return n;
    }

    /**
     * Systems (folder names) that have no main carousel image in a theme. Lists the theme's own gaps as well as
     * files missing from a copy, so you can see what will show the theme's placeholder instead.
     */
    public static List<String> missingArt(File themeDir, List<String> systems) throws IOException {
        ThemeLayout l = ThemeLayout.read(themeDir, null);
        List<ThemeLayout.Slot> tiles = new ArrayList<ThemeLayout.Slot>();
        for (ThemeLayout.Slot s : l.slots) if ("tile".equals(s.role) && !s.outside && !s.custom && s.existing > 0) tiles.add(s);
        List<String> out = new ArrayList<String>();
        if (tiles.isEmpty()) return out;
        for (String sys : systems) {
            boolean found = false;
            for (ThemeLayout.Slot s : tiles) if (s.fileFor(themeDir, themeDir, sys).isFile()) { found = true; break; }
            if (!found) out.add(sys);
        }
        return out;
    }

    /** Where a copy came from, or null if the folder isn't a copy made by this app. */
    public Entry sourceOf(File copy) {
        String t = EsdeConfig.read(new File(copy, MARKER));
        if (t == null) return null;
        try {
            JSONObject o = new JSONObject(t);
            Entry e = new Entry();
            e.name = o.optString("name");
            e.reponame = o.optString("reponame");
            e.url = o.optString("url");
            return e;
        } catch (JSONException ex) {
            return null;
        }
    }

    public long downloadedAt(File copy) {
        String t = EsdeConfig.read(new File(copy, MARKER));
        try { return t == null ? 0 : new JSONObject(t).optLong("downloaded"); } catch (JSONException e) { return 0; }
    }

    /** Sets the name ES-DE shows: "<Name> (ESCommander)". */
    static void renameTheme(File dir, String fallbackName) throws IOException {
        File caps = new File(dir, "capabilities.xml");
        String t = EsdeConfig.read(caps);
        String name = fallbackName;
        if (t == null) {
            t = "<themeCapabilities>\n    <themeName>" + EsdeConfig.x(name + " (ESCommander)") + "</themeName>\n</themeCapabilities>\n";
        } else {
            Matcher m = Pattern.compile("<themeName>([^<]*)</themeName>").matcher(t);
            if (m.find()) {
                String cur = EsdeConfig.unx(m.group(1).trim());
                if (!cur.endsWith("(ESCommander)")) cur = cur + " (ESCommander)";
                t = t.substring(0, m.start()) + "<themeName>" + EsdeConfig.x(cur) + "</themeName>" + t.substring(m.end());
            } else {
                t = t.replaceFirst("<themeCapabilities>", Matcher.quoteReplacement("<themeCapabilities>\n    <themeName>"
                        + EsdeConfig.x(name + " (ESCommander)") + "</themeName>"));
            }
        }
        EsdeConfig.writeBytes(caps, t.getBytes("UTF-8"));
    }

    /**
     * Unpacks using the zip's index (at the end of the file), so a download that was cut short can't pass as a smaller
     * theme, and checks every file's size after writing it.
     */
    static int unzipStrippingTop(File zip, File dest, Progress p) throws IOException {
        if (!dest.mkdirs() && !dest.isDirectory()) throw new IOException("can't create " + dest);
        String canon = dest.getCanonicalPath() + File.separator;
        ZipFile zf;
        try {
            zf = new ZipFile(zip);
        } catch (IOException e) {
            throw new IOException("The download is incomplete or damaged. Try again (a steady Wi-Fi connection helps).");
        }
        int count = 0;
        try {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            byte[] buf = new byte[65536];
            int total = zf.size();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String name = ze.getName().replace('\\', '/');
                int slash = name.indexOf('/');
                if (slash < 0) continue;                       // top-level file outside the theme folder
                String rel = name.substring(slash + 1);
                if (rel.isEmpty() || rel.startsWith(".git/") || rel.equals(".git")) continue;
                File f = new File(dest, rel);
                if (!f.getCanonicalPath().startsWith(canon)) throw new IOException("unsafe file in download: " + name);
                if (ze.isDirectory()) { f.mkdirs(); continue; }
                File dir = f.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
                InputStream in = zf.getInputStream(ze);
                OutputStream out = new FileOutputStream(f);
                long written = 0;
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); written += n; }
                } finally {
                    out.close();
                    in.close();
                }
                if ((ze.getSize() >= 0 && written != ze.getSize()) || f.length() != written)
                    throw new IOException("\"" + rel + "\" didn't save completely. Is the storage full?");
                count++;
                if (p != null && (count % 50) == 0) p.update("Unpacking", count, total);
            }
        } finally {
            zf.close();
        }
        return count;
    }

    /** False on 404 (try the next address). */
    static boolean download(String url, File dest, Progress p) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "ESDE-Commander");
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code == 404) return false;
            if (code != 200) throw new IOException("download failed (HTTP " + code + ")");
            File dir = dest.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
            long total = c.getContentLength();
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(dest);
            long done = 0;
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (p != null) p.update("Downloading", done, total);
                }
            } finally {
                out.close();
                in.close();
            }
            if (total > 0 && done != total) throw new IOException("The download was cut off. Try again (a steady Wi-Fi connection helps).");
            return true;
        } finally {
            c.disconnect();
        }
    }

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "ESDE-Commander");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try {
            int code = c.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new IOException("HTTP " + code);
            return Sgdb.readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }
}
