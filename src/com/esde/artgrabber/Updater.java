package com.esde.artgrabber;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Checks the project's GitHub releases for a newer version and downloads its APK. No Android code. */
public class Updater {
    public static final String REPO = "Adonzin/esde-art-grabber";
    protected String apiBase = "https://api.github.com/repos/";

    public static class Release {
        public final String version, notes, apkUrl, page;
        public final long size;
        Release(String version, String notes, String apkUrl, long size, String page) {
            this.version = version; this.notes = notes; this.apkUrl = apkUrl; this.size = size; this.page = page;
        }
    }

    public static class NoReleaseException extends IOException {
        public NoReleaseException(String m) { super(m); }
    }

    public interface Progress { void update(long done, long total); }

    /** The latest published release, or throws NoReleaseException if there is none to see. */
    public Release latest() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(apiBase + REPO + "/releases/latest").openConnection();
        c.setRequestProperty("User-Agent", "ESDE-ArtGrabber-Updater");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try {
            int code = c.getResponseCode();
            if (code == 404) throw new NoReleaseException("No releases found. The repository has to be public "
                    + "and have at least one release for the app to see updates.");
            if (code == 403 || code == 429) throw new IOException("GitHub is limiting requests right now – try again in an hour.");
            if (code != 200) throw new IOException("GitHub answered HTTP " + code);
            JSONObject o = new JSONObject(Sgdb.readAll(c.getInputStream()));
            String tag = o.optString("tag_name", "");
            JSONArray assets = o.optJSONArray("assets");
            String url = null;
            long size = 0;
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a != null && a.optString("name").toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                        url = a.optString("browser_download_url");
                        size = a.optLong("size");
                        break;
                    }
                }
            }
            if (url == null || url.length() == 0) throw new NoReleaseException("The latest release (" + tag + ") has no APK attached.");
            return new Release(tag.replaceFirst("^[vV]", ""), o.optString("body", ""), url, size, o.optString("html_url"));
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected reply from GitHub");
        } finally {
            c.disconnect();
        }
    }

    /** True if version a is newer than b ("1.10" > "1.9"). */
    public static boolean newer(String a, String b) {
        int[] x = parts(a), y = parts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    static int[] parts(String v) {
        String[] s = v.replaceFirst("^[vV]", "").split("[^0-9]+");
        int n = 0;
        int[] out = new int[s.length];
        for (String p : s) if (p.length() > 0) out[n++] = Integer.parseInt(p.length() > 6 ? p.substring(0, 6) : p);
        int[] r = new int[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    /** Downloads the release APK to dest (follows GitHub's redirect to its file host). */
    public void download(Release r, File dest, Progress p) throws IOException {
        String url = r.apkUrl;
        HttpURLConnection c = null;
        for (int hop = 0; hop < 5; hop++) {   // follow redirects ourselves: some Android versions won't across hosts
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "ESDE-ArtGrabber-Updater");
            c.setRequestProperty("Accept", "application/octet-stream");
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code != 200) { c.disconnect(); throw new IOException("download failed (HTTP " + code + ")"); }
            break;
        }
        File tmp = new File(dest.getPath() + ".part");
        File dir = dest.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        InputStream in = null;
        FileOutputStream out = null;
        try {
            long total = c.getContentLength() > 0 ? c.getContentLength() : r.size;
            in = c.getInputStream();
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[65536];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (p != null) p.update(done, total);
            }
            out.close();
            out = null;
            if (r.size > 0 && done != r.size) throw new IOException("download incomplete – try again");
            if (dest.exists() && !dest.delete()) throw new IOException("can't replace " + dest);
            if (!tmp.renameTo(dest)) throw new IOException("can't save the update");
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
            if (out != null) try { out.close(); } catch (IOException ignored) { }
            if (tmp.exists()) tmp.delete();
            c.disconnect();
        }
    }
}
