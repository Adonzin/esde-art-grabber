package com.esde.artgrabber;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/** SteamGridDB v2 API client. */
public class Sgdb {
    public static class Game {
        public final long id;
        public final String name;
        public final int year;
        public Game(long id, String name, int year) { this.id = id; this.name = name; this.year = year; }
        @Override public String toString() { return year > 0 ? name + " (" + year + ")" : name; }
    }

    public static class Image {
        public final String url;
        public final int score;
        public String thumb, mime;
        public int width, height;
        public Image(String url, int score) { this.url = url; this.score = score; }
        public String thumbOrUrl() { return thumb != null && thumb.length() > 0 ? thumb : url; }
    }

    public static class BadKeyException extends IOException {
        public BadKeyException() { super("SteamGridDB rejected the API key"); }
    }

    private static final String API = "https://www.steamgriddb.com/api/v2";
    private static final String UA = "ESDE-ArtGrabber/1.0";
    private final String key;
    private final Map<String, List<Game>> searchCache = new HashMap<String, List<Game>>();
    private final Map<String, List<Image>> imageCache = new HashMap<String, List<Image>>();

    /** Last request SteamGridDB rejected (for the log), or null. */
    public final List<String> rejections = new ArrayList<String>();
    /** API address (overridable for testing). */
    protected String apiBase = API;

    public Sgdb(String key) { this.key = key; }

    /** SteamGridDB's own explanation from an error response, e.g. ": Invalid dimensions". */
    static String errorText(HttpURLConnection c) {
        try {
            InputStream es = c.getErrorStream();
            if (es == null) return "";
            String body = readAll(es);
            JSONObject o = new JSONObject(body);
            JSONArray errs = o.optJSONArray("errors");
            if (errs != null && errs.length() > 0) return ": " + errs.optString(0);
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    protected JSONObject get(String path) throws IOException { return get(path, true); }

    /** @param logRejection false when the caller has its own fallback for an HTTP 400 */
    protected JSONObject get(String path, boolean logRejection) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpURLConnection c = null;
            try {
                Thread.sleep(120);
                c = (HttpURLConnection) new URL(apiBase + path).openConnection();
                c.setRequestProperty("Authorization", "Bearer " + key);
                c.setRequestProperty("User-Agent", UA);
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                int code = c.getResponseCode();
                if (code == 401 || code == 403) throw new BadKeyException();
                // 404 = nothing there; 400 = SteamGridDB refused this particular lookup. Either way: no result,
                // so the caller falls back to searching by name instead of failing the whole game.
                if (code == 404 || code == 400) {
                    if (code == 400 && logRejection) {
                        String what = path.replaceAll("\\?.*$", "");
                        synchronized (rejections) { rejections.add("HTTP 400" + errorText(c) + " (" + what + ")"); }
                    }
                    return new JSONObject().put("success", false).put("rejected", code == 400).put("data", new JSONArray());
                }
                if (code == 429 || code >= 500) {
                    last = new IOException("HTTP " + code);
                    Thread.sleep(2000L * (attempt + 1));
                    continue;
                }
                if (code != 200) throw new IOException("SteamGridDB error HTTP " + code + errorText(c));
                return new JSONObject(readAll(c.getInputStream()));
            } catch (BadKeyException e) {
                throw e;
            } catch (InterruptedException e) {
                throw new IOException("stopped");
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ie) { throw new IOException("stopped"); }
            } catch (org.json.JSONException e) {
                throw new IOException("Bad response from SteamGridDB");
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw last != null ? last : new IOException("network error");
    }

    public List<Game> search(String term) throws IOException {
        List<Game> cached = searchCache.get(term);
        if (cached != null) return cached;
        JSONObject res = get("/search/autocomplete/" + enc(term));
        List<Game> out = new ArrayList<Game>();
        JSONArray data = res.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject g = data.optJSONObject(i);
                if (g == null) continue;
                long rd = g.optLong("release_date", 0);
                int year = rd > 0 ? 1970 + (int) (rd / 31556952L) : 0;
                out.add(new Game(g.optLong("id"), g.optString("name"), year));
            }
        }
        searchCache.put(term, out);
        return out;
    }

    public Game gameBySteamId(String appId) throws IOException {
        JSONObject res = get("/games/steam/" + enc(appId));
        JSONObject g = res.optBoolean("success") ? res.optJSONObject("data") : null;
        return g == null ? null : new Game(g.optLong("id"), g.optString("name"), 0);
    }

    /**
     * File types SteamGridDB accepts per kind of art (it rejects the whole request with "Invalid mime type"
     * otherwise). Only PNG/JPEG are asked for because ES-DE can't read WebP.
     */
    static String mimesFor(String kind) {
        if (kind.equals("grids") || kind.equals("heroes")) return "image/png,image/jpeg";
        return "image/png";   // logos and icons have no JPEG variant
    }

    /** kind = grids | heroes | logos | icons. Best-scored first, PNG/JPG only (ES-DE can't read WebP). */
    public List<Image> images(String kind, long gameId, String dimensions) throws IOException {
        String ck = kind + "/" + gameId + "/" + dimensions;
        List<Image> cached = imageCache.get(ck);
        if (cached != null) return cached;
        String q = "/" + kind + "/game/" + gameId + "?types=static&nsfw=false&humor=false"
                + (dimensions != null ? "&dimensions=" + enc(dimensions) : "");
        JSONObject res = get(q + "&mimes=" + enc(mimesFor(kind)), false);
        boolean filterLocally = false;
        if (res.optBoolean("rejected")) {
            // SteamGridDB didn't like the file-type filter: ask without it and keep PNG/JPG ourselves
            res = get(q, true);
            filterLocally = true;
        }
        List<Image> out = new ArrayList<Image>();
        JSONArray data = res.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject im = data.optJSONObject(i);
                if (im == null || im.optString("url").length() == 0) continue;
                String url = im.optString("url");
                if (filterLocally) {
                    String mime = im.optString("mime", "");
                    String path = url.replaceAll("[?#].*$", "").toLowerCase(java.util.Locale.ROOT);
                    boolean ok = mime.length() > 0 ? (mime.equals("image/png") || mime.equals("image/jpeg"))
                            : (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg"));
                    if (!ok) continue;
                }
                Image img = new Image(url, im.optInt("score"));
                img.thumb = im.optString("thumb", null);
                img.mime = im.optString("mime", null);
                img.width = im.optInt("width");
                img.height = im.optInt("height");
                out.add(img);
            }
        }
        // stable sort by score, highest first
        for (int i = 1; i < out.size(); i++) {
            Image x = out.get(i);
            int j = i - 1;
            while (j >= 0 && out.get(j).score < x.score) { out.set(j + 1, out.get(j)); j--; }
            out.set(j + 1, x);
        }
        imageCache.put(ck, out);
        return out;
    }

    public void download(String url, File dest) throws IOException {
        File tmp = new File(dest.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        InputStream in = null;
        FileOutputStream out = null;
        try {
            if (c.getResponseCode() != 200) throw new IOException("image download failed (HTTP " + c.getResponseCode() + ")");
            in = c.getInputStream();
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            out = null;
            if (dest.exists() && !dest.delete()) throw new IOException("can't replace " + dest);
            if (!tmp.renameTo(dest)) throw new IOException("can't write " + dest);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            if (out != null) try { out.close(); } catch (IOException ignored) {}
            if (tmp.exists()) tmp.delete();
            c.disconnect();
        }
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException e) { return s; }
    }

    static String readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}
