package com.esde.artgrabber;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.*;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Theme layout detection and art planning. Builds a miniature theme that uses every layout trick seen in real
 * themes (PS5 Menu, Linear, Modern, Slate, Canvas, Iconic, Art Book Next, NSO Menu). If a folder of real themes
 * is given as the second argument, every theme in it is checked too.
 */
public class ThemeTest {
    static int fails = 0;

    static void check(boolean c, String w) {
        if (!c) { System.out.println("FAIL " + w); fails++; } else System.out.println("  ok  " + w);
    }

    static void write(File f, String t) throws IOException {
        f.getParentFile().mkdirs();
        Writer w = new FileWriter(f);
        w.write(t);
        w.close();
    }

    static void png(File f, int w, int h) throws IOException {
        f.getParentFile().mkdirs();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", f);
    }

    /** A minimal lossless WebP header: enough for size detection. */
    static void webp(File f, int w, int h) throws IOException {
        f.getParentFile().mkdirs();
        byte[] b = new byte[30];
        System.arraycopy("RIFF".getBytes(), 0, b, 0, 4);
        System.arraycopy("WEBPVP8L".getBytes(), 0, b, 8, 8);
        int bits = (w - 1) | (h - 1) << 14;
        b[20] = 0x2f;
        b[21] = (byte) bits; b[22] = (byte) (bits >> 8); b[23] = (byte) (bits >> 16); b[24] = (byte) (bits >> 24);
        FileOutputStream o = new FileOutputStream(f);
        o.write(b);
        o.close();
    }

    static ThemeLayout.Slot slot(ThemeLayout t, String pattern) {
        for (ThemeLayout.Slot s : t.slots) if (s.pattern.equals(pattern)) return s;
        return null;
    }

    public static void main(String[] a) throws Exception {
        File T = new File(a[0], "fixture-es-de");
        // capabilities + PS5-style direct paths and per-system metadata include
        write(new File(T, "capabilities.xml"), "<themeCapabilities>\n  <themeName>Fixture</themeName>\n</themeCapabilities>\n");
        write(new File(T, "theme.xml"), "<theme>\n"
                + "  <include>./_inc/system/metadata/${system.theme}.xml</include>\n"
                + "  <!-- <image><path>./commented/${system.theme}.png</path></image> -->\n"
                + "  <variant name=\"a\"><variables><artDirectory>art</artDirectory></variables></variant>\n"
                + "  <variant name=\"b\"><variables><artDirectory>art_legacy</artDirectory></variables></variant>\n"
                + "  <variables><bgPath>./_inc/backgrounds/${system.theme}.jpg</bgPath><unusedPath>./unused/${system.theme}.png</unusedPath></variables>\n"
                + "  <view name=\"system\">\n"
                + "    <carousel name=\"system-carousel\">\n      <textColor>FFFFFF</textColor>\n"
                + "      <staticImage>./${artDirectory}/${system.theme}.webp</staticImage>\n    </carousel>\n"
                + "    <image name=\"system-marquee\"><path>./_inc/system/marquees/${system.theme}.svg</path></image>\n"
                + "    <image name=\"bg\"><path>${bgPath}</path></image>\n"
                + "    <image name=\"custom\"><path>~/ES-DE/theme-customizations/fixture/logos/${system.theme}.png</path>"
                + "<default>./_inc/system/logos/${system.theme}.png</default></image>\n"
                + "    <image name=\"controller\"><path>./${system.theme}/images/controller.svg</path></image>\n"
                + "  </view>\n</theme>\n");
        write(new File(T, "_inc/system/metadata/androidapps.xml"), "<theme>\n  <variables>\n    <systemName>Android Apps</systemName>\n"
                + "    <systemDescription>Android is a mobile operating system.</systemDescription>\n    <systemManufacturer>Google</systemManufacturer>\n"
                + "    <systemReleaseYear>2008</systemReleaseYear>\n    <systemColor>5A9035</systemColor>\n    <systemCoverSize>1-1</systemCoverSize>\n"
                + "  </variables>\n  <language name=\"de_DE\">\n    <variables>\n      <systemDescription>Android ist ...</systemDescription>\n"
                + "    </variables>\n  </language>\n</theme>\n");
        write(new File(T, "_inc/system/metadata/snes.xml"), "<theme><variables><systemName>SNES</systemName></variables></theme>");
        webp(new File(T, "art/androidapps.webp"), 800, 800);
        webp(new File(T, "art/snes.webp"), 800, 800);
        webp(new File(T, "art_legacy/androidapps.webp"), 600, 600);
        write(new File(T, "_inc/system/marquees/androidapps.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100%\" viewBox=\"0 0 168 62\"></svg>");
        png(new File(T, "_inc/system/logos/androidapps.png"), 256, 256);
        write(new File(T, "androidapps/images/controller.svg"), "<svg width=\"222\" height=\"489\"></svg>");

        ThemeLayout t = ThemeLayout.read(T, null);
        for (ThemeLayout.Slot s : t.slots) System.out.println("    " + s);
        check(t.themeName.equals("Fixture") && "androidapps".equals(t.referenceSystem), "theme name and reference system");
        ThemeLayout.Slot art = slot(t, "art/{S}.webp"), legacy = slot(t, "art_legacy/{S}.webp");
        check(art != null && legacy != null && "tile".equals(art.role), "variable that changes per variant gives one slot per variant");
        check(art.refW == 800 && legacy.refW == 600, "sizes read from WebP files");
        ThemeLayout.Slot mq = slot(t, "_inc/system/marquees/{S}.svg");
        check(mq != null && "wordmark".equals(mq.role) && mq.refW == 168 && mq.refH == 62, "SVG name logo with its proportions");
        ThemeLayout.Slot bg = slot(t, "_inc/backgrounds/{S}.jpg");
        check(bg != null && "background".equals(bg.role) && bg.refW == 0, "path held in a variable is found; no example file");
        check(slot(t, "unused/{S}.png") == null, "variables that are never used are ignored");
        check(slot(t, "commented/{S}.png") == null, "commented-out paths are ignored");
        ThemeLayout.Slot cust = slot(t, "~/ES-DE/theme-customizations/fixture/logos/{S}.png");
        check(cust != null && cust.outside && cust.custom, "customisation folder outside the theme recognised");
        ThemeLayout.Slot logo = slot(t, "_inc/system/logos/{S}.png");
        check(logo != null && logo.refW == 256 && "logo".equals(logo.role), "fallback (default) path also a slot");
        check(cust.refW == 256, "empty slot borrows the size of a filled one with the same role");
        ThemeLayout.Slot ctl = slot(t, "{S}/images/controller.svg");
        check(ctl != null && ctl.folderPerSystem && "controller".equals(ctl.role) && ctl.refH == 489, "folder-per-system layout");
        check(slot(t, "_inc/system/metadata/{S}.xml") != null && "info".equals(slot(t, "_inc/system/metadata/{S}.xml").role), "system info include");
        check(art.usedBy.iterator().next().startsWith("carousel \"system-carousel\" staticImage"), "knows which element uses it: " + art.usedBy);

        // ---- plan for a category
        File home = new File(a[0], "home");
        ThemeArtPlan p = ThemeArtPlan.make(t, T, home, "streaming", "Streaming & Video", "My apps",
                new HashSet<String>(Arrays.asList(ThemeArtPlan.MAIN, ThemeArtPlan.LOGO)));
        for (ThemeArtPlan.Step s : p.steps) System.out.println("    " + s);
        Map<String, ThemeArtPlan.Step> by = new HashMap<String, ThemeArtPlan.Step>();
        for (ThemeArtPlan.Step s : p.steps) by.put(s.slot.pattern, s);
        check(by.get("art/{S}.webp").source.equals("main") && by.get("art/{S}.webp").cover && by.get("art/{S}.webp").width == 800, "main picture fills the carousel slots at their size");
        check(by.get("_inc/system/logos/{S}.png").source.equals("logo") && !by.get("_inc/system/logos/{S}.png").cover, "logo fitted on transparency");
        ThemeArtPlan.Step svg = by.get("_inc/system/marquees/{S}.svg");
        check(svg != null && svg.format.equals("svg") && Math.abs(svg.width / (double) svg.height - 168 / 62.0) < 0.01 && svg.width >= 1024,
                "SVG slot: same proportions, sharp resolution");
        check(by.get("~/ES-DE/theme-customizations/fixture/logos/{S}.png").target.equals(new File(home, "ES-DE/theme-customizations/fixture/logos/streaming.png")),
                "~/ paths land in ES-DE's folder");
        check(!by.containsKey("_inc/backgrounds/{S}.jpg") && !by.containsKey("{S}/images/controller.svg"), "no background without a background picture; decorations left alone");
        ThemeArtPlan.Step info = by.get("_inc/system/metadata/{S}.xml");
        check(info != null && info.target.equals(new File(T, "_inc/system/metadata/streaming.xml")), "system info file for the category");
        String it = info.infoText;
        check(it.contains("<systemName>Streaming &amp; Video</systemName>") && it.contains("<systemDescription>My apps</systemDescription>"), "info has the category's name and description");
        check(it.contains("<systemColor>5A9035</systemColor>") && it.contains("<systemCoverSize>1-1</systemCoverSize>"), "colours and sizes kept");
        check(it.contains("<systemManufacturer></systemManufacturer>") && !it.contains("2008") && !it.contains("de_DE"), "wrong facts and translations removed");
        boolean valid;
        try { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(it.getBytes("UTF-8"))); valid = true; }
        catch (Exception e) { valid = false; }
        check(valid, "info file is valid XML");
        ThemeArtPlan bgPlan = ThemeArtPlan.make(t, T, home, "streaming", "S", "", new HashSet<String>(Arrays.asList(ThemeArtPlan.BACKGROUND)));
        boolean bgUsed = false;
        for (ThemeArtPlan.Step s : bgPlan.steps) if (s.slot.pattern.equals("_inc/backgrounds/{S}.jpg") && s.width == 1920 && s.format.equals("jpg")) bgUsed = true;
        check(bgUsed, "background picture goes to background slots (default 1920x1080)");

        // ---- SVG wrapper
        String w = ThemeArtPlan.svgWrapping(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, 1024, 378);
        check(w.contains("viewBox=\"0 0 1024 378\"") && w.contains("data:image/png;base64,iVBORw==") && w.split("base64,").length == 2, "SVG wraps the picture once, as a data URI");
        check(ThemeArtPlan.base64("Man".getBytes()).equals("TWFu") && ThemeArtPlan.base64("Ma".getBytes()).equals("TWE="), "base64");

        // ---- optional: a folder of real themes
        if (a.length > 1 && new File(a[1]).isDirectory()) {
            File[] themes = new File(a[1]).listFiles();
            Arrays.sort(themes);
            for (File d : themes) {
                if (!new File(d, "theme.xml").isFile()) continue;
                ThemeLayout r = ThemeLayout.read(d, null);
                ThemeArtPlan rp = ThemeArtPlan.make(r, d, home, "streaming", "Streaming", "", new HashSet<String>(Arrays.asList(ThemeArtPlan.MAIN, ThemeArtPlan.LOGO)));
                int pics = 0;
                boolean xmlOk = true;
                for (ThemeArtPlan.Step s : rp.steps) {
                    if (s.infoText == null) { pics++; continue; }
                    try { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(s.infoText.getBytes("UTF-8"))); }
                    catch (Exception e) { xmlOk = false; }
                }
                boolean tile = false;
                for (ThemeLayout.Slot s : r.slots) if ("tile".equals(s.role) || s.usedBy.toString().contains("staticImage")) tile = true;
                check(tile && pics > 0 && xmlOk, "real theme " + r.themeName + ": carousel slot found, " + pics + " pictures planned, info valid");
            }
        }

        System.out.println(fails == 0 ? "THEME TESTS OK" : fails + " FAILURES");
        if (fails > 0) System.exit(1);
    }
}
