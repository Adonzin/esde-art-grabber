package com.esde.artgrabber;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/** Draws a category's pictures for each theme slot: real Android bitmaps, encoded in the slot's format. */
class LookRenderer implements CategoryLooks.Renderer {
    private final PackageManager pm;

    LookRenderer(PackageManager pm) { this.pm = pm; }

    /** Share of a filled tile an app icon takes up, centred on the colour. */
    static final float ICON_SHARE = 0.62f;

    @Override
    public byte[] render(CategoryLooks.Picture pic, ThemeArtPlan.Step s, String colorHex) throws IOException {
        int w = Math.max(1, s.width), h = Math.max(1, s.height);
        boolean opaque = "jpg".equals(s.format);
        int bg = parse(colorHex);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        if (CategoryLooks.APP.equals(pic.kind)) {
            Drawable d = icon(pic.value);
            if (d == null) throw new IOException(pic.label + " isn't installed any more");
            int size;
            if (s.cover) {
                c.drawColor(bg);
                size = Math.round(Math.min(w, h) * ICON_SHARE);
            } else {
                if (opaque) c.drawColor(bg);
                size = Math.min(w, h);
            }
            int left = (w - size) / 2, top = (h - size) / 2;
            d.setBounds(left, top, left + size, top + size);
            d.draw(c);
        } else {
            Bitmap src = decode(new File(pic.value), Math.max(w, h));
            if (src == null) throw new IOException("the picture couldn't be read");
            if (opaque) c.drawColor(s.cover ? Color.BLACK : bg);
            float sw = src.getWidth(), sh = src.getHeight();
            float scale = s.cover ? Math.max(w / sw, h / sh) : Math.min(w / sw, h / sh);
            float dw = sw * scale, dh = sh * scale;
            RectF dst = new RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
            c.drawBitmap(src, new Rect(0, 0, src.getWidth(), src.getHeight()), dst, paint);
            src.recycle();
        }

        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Bitmap.CompressFormat fmt = "jpg".equals(s.format) ? Bitmap.CompressFormat.JPEG
                : "webp".equals(s.format) ? Bitmap.CompressFormat.WEBP : Bitmap.CompressFormat.PNG;   // svg slots get a PNG to wrap
        if (!out.compress(fmt, fmt == Bitmap.CompressFormat.PNG ? 100 : 90, bo)) throw new IOException("couldn't encode the picture");
        out.recycle();
        return bo.toByteArray();
    }

    /** The app's icon at the highest resolution it has. */
    Drawable icon(String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            try {
                Resources res = pm.getResourcesForApplication(ai);
                if (ai.icon != 0) {
                    Drawable d = res.getDrawableForDensity(ai.icon, DisplayMetrics.DENSITY_XXXHIGH, null);
                    if (d != null) return d;
                }
            } catch (Exception ignored) { }
            return pm.getApplicationIcon(ai);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Decodes a picture no smaller than needed (keeps memory low on big photos). */
    static Bitmap decode(File f, int needPx) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= needPx) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o2);
    }

    static int parse(String hex) {
        try { return Color.parseColor("#" + hex.replace("#", "")); } catch (Exception e) { return Color.parseColor("#2B3A55"); }
    }
}
