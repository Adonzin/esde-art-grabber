package com.esde.artgrabber;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads small previews into ImageViews off the main thread, with a small memory cache. */
public class ThumbLoader {
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> cache = new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > 90; }
    };

    /** Shows the image at url (http/https or a local file path) in v, scaled down to about maxPx. */
    public void load(final String url, final ImageView v, final int maxPx) {
        v.setTag(url);
        Bitmap hit;
        synchronized (cache) { hit = cache.get(url); }
        if (hit != null) { v.setImageBitmap(hit); return; }
        v.setImageDrawable(null);
        pool.execute(new Runnable() {
            public void run() {
                final Bitmap b = fetch(url, maxPx);
                if (b != null) synchronized (cache) { cache.put(url, b); }
                ui.post(new Runnable() {
                    public void run() {
                        if (url.equals(v.getTag())) v.setImageBitmap(b);
                    }
                });
            }
        });
    }

    public void forget(String url) { synchronized (cache) { cache.remove(url); } }

    public void shutdown() { pool.shutdownNow(); }

    static Bitmap fetch(String url, int maxPx) {
        try {
            byte[] data;
            if (url.startsWith("http")) {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestProperty("User-Agent", "ESDE-ArtGrabber/1.0");
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                try {
                    if (c.getResponseCode() != 200) return null;
                    data = readAll(c.getInputStream());
                } finally {
                    c.disconnect();
                }
            } else {
                File f = new File(url);
                if (!f.isFile()) return null;
                data = readAll(new java.io.FileInputStream(f));
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable t) {
            return null;
        }
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }
}
