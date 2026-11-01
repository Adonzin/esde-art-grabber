package com.esde.artgrabber;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Turns an installed app's own artwork into an ES-DE marquee PNG. */
public class DeviceAppArt implements Engine.AppArt {
    private final PackageManager pm;

    public DeviceAppArt(PackageManager pm) { this.pm = pm; }

    @Override
    public boolean isInstalled(String pkg) {
        try {
            pm.getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean saveMarquee(String pkg, File dest) throws IOException {
        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        // Wide TV banner looks best as a marquee, then a logo, then the launcher icon.
        Drawable d = null;
        Resources res = null;
        try { res = pm.getResourcesForApplication(ai); } catch (Exception ignored) { }
        if (ai.banner != 0) d = hiRes(res, ai.banner);
        if (d == null) try { d = pm.getApplicationBanner(ai); } catch (Exception ignored) { }
        if (d == null && ai.logo != 0) d = hiRes(res, ai.logo);
        if (d == null && ai.icon != 0) d = hiRes(res, ai.icon);
        if (d == null) try { d = pm.getApplicationIcon(ai); } catch (Exception ignored) { }
        if (d == null) return false;

        Bitmap bmp = render(d);
        if (bmp == null) return false;
        bmp = trim(bmp);
        File tmp = new File(dest.getPath() + ".part");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG encode failed");
        } finally {
            out.close();
        }
        if (dest.exists() && !dest.delete()) { tmp.delete(); throw new IOException("can't replace " + dest); }
        if (!tmp.renameTo(dest)) { tmp.delete(); throw new IOException("can't write " + dest); }
        return true;
    }

    private static Drawable hiRes(Resources res, int id) {
        if (res == null) return null;
        try {
            return res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Long side of the saved image: plenty for a handheld screen, small on disk. */
    static final int ICON_PX = 256, BANNER_PX = 400;

    private static Bitmap render(Drawable d) {
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        if (w <= 0 || h <= 0) { w = ICON_PX; h = ICON_PX; }
        float target = w > h * 1.3f ? BANNER_PX : ICON_PX;
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            Bitmap src = ((BitmapDrawable) d).getBitmap();
            int sw = src.getWidth(), sh = src.getHeight();
            if (Math.max(sw, sh) >= target) {
                float sc = target / Math.max(sw, sh);
                return Bitmap.createScaledBitmap(src, Math.max(1, Math.round(sw * sc)), Math.max(1, Math.round(sh * sc)), true)
                        .copy(Bitmap.Config.ARGB_8888, false);
            }
        }
        float scale = target / Math.max(w, h);
        int bw = Math.max(1, Math.round(w * scale)), bh = Math.max(1, Math.round(h * scale));
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, bw, bh);
        d.draw(c);
        return b;
    }

    /** Crops fully transparent margins so the logo fills ES-DE's marquee slot. */
    private static Bitmap trim(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int top = h, bottom = -1, left = w, right = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) > 8) {
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                    if (x < left) left = x;
                    if (x > right) right = x;
                }
            }
        }
        if (bottom < 0) return b;
        if (top == 0 && left == 0 && bottom == h - 1 && right == w - 1) return b;
        return Bitmap.createBitmap(b, left, top, right - left + 1, bottom - top + 1);
    }
}
