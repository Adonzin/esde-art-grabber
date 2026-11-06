package com.esde.artgrabber;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Official artwork from Steam's own image servers, by Steam app ID. No key needed.
 * Newer games sometimes keep their art at hashed addresses this can't guess; those fall back to SteamGridDB.
 */
public class SteamArt {
    /** ES-DE media type -> Steam files to try, best first. */
    static final Map<String, String[]> FILES = new HashMap<String, String[]>();
    static {
        FILES.put("covers", new String[]{"library_600x900.jpg", "library_600x900_2x.jpg"});
        FILES.put("marquees", new String[]{"logo.png", "logo_2x.png"});
        FILES.put("fanart", new String[]{"library_hero.jpg", "library_hero_2x.jpg"});
        FILES.put("screenshots", new String[]{"capsule_616x353.jpg", "header.jpg"});
        FILES.put("titlescreens", new String[]{"capsule_616x353.jpg", "header.jpg"});
    }

    /** Hosts to try, in order (overridable for tests). */
    protected String[] bases = {
            "https://shared.steamstatic.com/store_item_assets/steam/apps/",
            "https://cdn.cloudflare.steamstatic.com/steam/apps/"};

    public static boolean supports(String media) { return FILES.containsKey(media); }

    /**
     * Saves the official image for one media type. Returns the extension saved (".jpg"/".png"),
     * or null if Steam has no such image for this game.
     */
    public String save(String appId, String media, File dir, String base, boolean dryRun) throws IOException {
        String[] files = FILES.get(media);
        if (files == null || !appId.matches("\\d{1,10}")) return null;
        for (String f : files) {
            String ext = f.endsWith(".png") ? ".png" : ".jpg";
            for (String b : bases) {
                String url = b + appId + "/" + f;
                if (dryRun ? exists(url) : fetch(url, new File(dir, base + ext))) return ext;
            }
        }
        return null;
    }

    /** Preview mode: checks the image is there without downloading it. */
    boolean exists(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setRequestProperty("User-Agent", "ESDE-ArtGrabber/1.0");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            String type = c.getContentType();
            return c.getResponseCode() == 200 && type != null && type.startsWith("image/");
        } catch (IOException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Downloads url to dest if it's really an image. False on 404 or anything that isn't an image. */
    boolean fetch(String url, File dest) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "ESDE-ArtGrabber/1.0");
        c.setConnectTimeout(15000);
        c.setReadTimeout(45000);
        c.setInstanceFollowRedirects(true);
        File tmp = new File(dest.getPath() + ".part");
        InputStream in = null;
        FileOutputStream out = null;
        try {
            int code;
            try {
                code = c.getResponseCode();
            } catch (IOException e) {
                return false;   // host unreachable: try the next one
            }
            String type = c.getContentType();
            if (code != 200 || type == null || !type.startsWith("image/")) return false;
            in = c.getInputStream();
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
            out.close();
            out = null;
            if (total < 200) return false;   // placeholder / empty response
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
}
