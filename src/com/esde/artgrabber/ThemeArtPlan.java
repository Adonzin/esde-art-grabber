package com.esde.artgrabber;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a theme layout plus the pictures chosen for a category into the exact files to write: which picture
 * goes where, at what size and in which format, and the category's own copy of the theme's system info.
 * Rendering the pictures happens on the device; everything decided here is plain Java and tested on a desktop.
 */
public class ThemeArtPlan {

    /** The pictures a category can have. Any of them may be missing. */
    public static final String MAIN = "main", LOGO = "logo", BACKGROUND = "background";

    public static class Step {
        public File target;
        public ThemeLayout.Slot slot;
        public String source;      // MAIN / LOGO / BACKGROUND, or null for a copied info file
        public int width, height;
        public String format;      // png jpg webp svg xml
        public boolean cover;      // crop to fill (true) or fit inside on a transparent canvas (false)
        public String infoText;    // for info files: the finished file
        @Override public String toString() {
            return (source == null ? "info" : source) + " -> " + slot.pattern + " " + (width > 0 ? width + "x" + height + " " : "")
                    + format + (source != null ? (cover ? " cover" : " fit") : "");
        }
    }

    public final List<Step> steps = new ArrayList<Step>();
    public final List<String> skipped = new ArrayList<String>();

    /**
     * @param have which pictures were chosen (MAIN, LOGO, BACKGROUND)
     * @param themeDir the theme folder to write into (the editable copy)
     * @param esdeHome the folder that "~" means for the theme (the one holding ES-DE/)
     */
    public static ThemeArtPlan make(ThemeLayout layout, File themeDir, File esdeHome, String system, String fullName,
                                    String description, java.util.Set<String> have) {
        ThemeArtPlan p = new ThemeArtPlan();
        for (ThemeLayout.Slot s : layout.slots) {
            File target = s.fileFor(themeDir, esdeHome, system);
            if ("info".equals(s.role)) {
                if (s.refFile == null) { p.skipped.add(s.label + ": no example file to copy"); continue; }
                String text = ThemeLayout.readText(s.refFile);
                if (text == null) continue;
                Step st = new Step();
                st.target = target;
                st.slot = s;
                st.format = "xml";
                st.infoText = infoFor(text, fullName, description);
                p.steps.add(st);
                continue;
            }
            if (!s.isImage()) { p.skipped.add(s.label + ": not a picture"); continue; }
            String src = sourceFor(s.role, have);
            if (src == null) { p.skipped.add(s.label + ": " + whyNot(s.role)); continue; }
            Step st = new Step();
            st.target = target;
            st.slot = s;
            st.source = src;
            int[] size = sizeFor(s);
            st.width = size[0];
            st.height = size[1];
            st.format = s.ext.equals(".jpeg") ? "jpg" : s.ext.substring(1);
            // big pictures fill their space; logos sit on transparency so the theme's colours show around them
            st.cover = !(LOGO.equals(src) || "logo".equals(s.role) || "wordmark".equals(s.role));
            p.steps.add(st);
        }
        return p;
    }

    /** Which chosen picture fills a slot of this role. */
    static String sourceFor(String role, java.util.Set<String> have) {
        if ("tile".equals(role) || "artwork".equals(role) || "icon".equals(role))
            return have.contains(MAIN) ? MAIN : have.contains(LOGO) ? LOGO : null;
        if ("logo".equals(role)) return have.contains(LOGO) ? LOGO : have.contains(MAIN) ? MAIN : null;
        if ("wordmark".equals(role)) return have.contains(LOGO) ? LOGO : null;
        if ("background".equals(role)) return have.contains(BACKGROUND) ? BACKGROUND : null;
        return null;   // controllers, overlays and other decorations are left to the theme
    }

    static String whyNot(String role) {
        if ("wordmark".equals(role)) return "needs a logo picture";
        if ("background".equals(role)) return "needs a background picture";
        if ("controller".equals(role) || "overlay".equals(role) || "other".equals(role)) return "theme decoration, left alone";
        return "needs a picture";
    }

    static int[] sizeFor(ThemeLayout.Slot s) {
        int w = s.refW, h = s.refH;
        if (w <= 0 || h <= 0) {
            if ("background".equals(s.role)) { w = 1920; h = 1080; }
            else if ("logo".equals(s.role)) { w = 512; h = 512; }
            else if ("wordmark".equals(s.role)) { w = 168; h = 62; }
            else { w = 800; h = 800; }
        }
        if (s.ext.equals(".svg")) {
            // SVG sizes are just proportions: render the picture inside at a sharp resolution
            double k = Math.max(1, 1024.0 / Math.max(w, h));
            w = (int) Math.round(w * k);
            h = (int) Math.round(h * k);
        }
        return new int[]{w, h};
    }

    // ================================================================ system info

    private static final Pattern VAR = Pattern.compile("(?s)<([A-Za-z_][\\w.-]*)>(.*?)</\\1>");

    /**
     * The category's own system info: the example system's file with its name and description replaced,
     * colours and sizes kept, facts that would be wrong (maker, year...) emptied, translations removed.
     */
    static String infoFor(String example, String fullName, String description) {
        String t = example.replaceAll("(?s)\\s*<language\\s+name=\"[^\"]*\">.*?</language>", "");
        StringBuffer out = new StringBuffer();
        Matcher m = VAR.matcher(t);
        while (m.find()) {
            String tag = m.group(1), lower = tag.toLowerCase(Locale.ROOT), val = m.group(2);
            String repl;
            if (val.contains("<")) repl = m.group();   // a container (theme, variables...): keep as is
            else if (lower.equals("systemname") || lower.equals("systemfullname") || lower.equals("systemtitle")) repl = "<" + tag + ">" + EsdeConfig.x(fullName) + "</" + tag + ">";
            else if (lower.contains("description") || lower.equals("systeminfo")) repl = "<" + tag + ">" + EsdeConfig.x(description == null ? "" : description) + "</" + tag + ">";
            else if (lower.contains("color") || lower.contains("colour") || lower.contains("size") || lower.contains("aspect")
                    || lower.contains("type") || lower.contains("path") || lower.contains("image") || lower.contains("logo")) repl = m.group();
            else if (lower.startsWith("system")) repl = "<" + tag + "></" + tag + ">";   // maker, year, release date...
            else repl = m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(repl));
        }
        m.appendTail(out);
        String r = out.toString();
        // containers matched first swallow their children; run once more inside <variables> blocks
        Matcher v = Pattern.compile("(?s)(<variables>)(.*?)(</variables>)").matcher(r);
        StringBuffer o2 = new StringBuffer();
        while (v.find()) v.appendReplacement(o2, Matcher.quoteReplacement(v.group(1) + infoVars(v.group(2), fullName, description) + v.group(3)));
        v.appendTail(o2);
        return o2.toString();
    }

    private static String infoVars(String block, String fullName, String description) {
        StringBuffer out = new StringBuffer();
        Matcher m = VAR.matcher(block);
        while (m.find()) {
            String tag = m.group(1), lower = tag.toLowerCase(Locale.ROOT);
            String repl;
            if (lower.equals("systemname") || lower.equals("systemfullname") || lower.equals("systemtitle")) repl = "<" + tag + ">" + EsdeConfig.x(fullName) + "</" + tag + ">";
            else if (lower.contains("description") || lower.equals("systeminfo")) repl = "<" + tag + ">" + EsdeConfig.x(description == null ? "" : description) + "</" + tag + ">";
            else if (lower.contains("color") || lower.contains("colour") || lower.contains("size") || lower.contains("aspect")
                    || lower.contains("type") || lower.contains("path") || lower.contains("image") || lower.contains("logo")) repl = m.group();
            else if (lower.startsWith("system")) repl = "<" + tag + "></" + tag + ">";
            else repl = m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(repl));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ================================================================ SVG slots

    /**
     * An SVG that shows a PNG picture: ES-DE's SVG renderer (LunaSVG 3.5) draws embedded PNG/JPEG images,
     * so slots that must be .svg can hold any picture.
     */
    public static String svgWrapping(byte[] png, int width, int height) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"" + width
                + "\" height=\"" + height + "\" viewBox=\"0 0 " + width + " " + height + "\">\n"
                + "  <image x=\"0\" y=\"0\" width=\"" + width + "\" height=\"" + height + "\" preserveAspectRatio=\"xMidYMid meet\" "
                + "xlink:href=\"data:image/png;base64," + base64(png) + "\"/>\n"
                + "</svg>\n";
    }

    static String base64(byte[] b) {
        final String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder((b.length + 2) / 3 * 4);
        for (int i = 0; i < b.length; i += 3) {
            int n = (b[i] & 0xFF) << 16 | (i + 1 < b.length ? (b[i + 1] & 0xFF) << 8 : 0) | (i + 2 < b.length ? b[i + 2] & 0xFF : 0);
            sb.append(A.charAt(n >> 18 & 63)).append(A.charAt(n >> 12 & 63));
            sb.append(i + 1 < b.length ? A.charAt(n >> 6 & 63) : '=').append(i + 2 < b.length ? A.charAt(n & 63) : '=');
        }
        return sb.toString();
    }
}
