package com.esde.artgrabber;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Theme copies (official list, download, install, rename) and custom looks (apply, keep, remove, re-apply). */
public class LooksTest {
    static int fails = 0;
    static void check(boolean c, String w) { if (!c) { System.out.println("FAIL " + w); fails++; } else System.out.println("  ok  " + w); }
    static final List<String> hits = Collections.synchronizedList(new ArrayList<String>());

    static void zipEntry(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes("UTF-8"));
        z.closeEntry();
    }

    /** A theme in a zip, laid out like a GitHub archive (everything inside one top folder). */
    static byte[] themeZip(String version, boolean evil) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bo);
        String top = "ps5-menu-es-de-main/";
        zipEntry(z, top + "capabilities.xml", "<themeCapabilities>\n  <themeName>PS5 Menu</themeName>\n  <variant name=\"a\"/>\n</themeCapabilities>\n");
        zipEntry(z, top + "theme.xml", "<theme>\n  <include>./_inc/system/metadata/${system.theme}.xml</include>\n  <view name=\"system\">\n"
                + "    <carousel name=\"system-carousel\"><staticImage>./_inc/system/icons/${system.theme}.webp</staticImage></carousel>\n"
                + "    <image name=\"system-marquee\"><path>./_inc/system/marquees/${system.theme}.svg</path></image>\n"
                + "    <image name=\"custom\"><path>~/ES-DE/theme-customizations/ps5/logos/${system.theme}.png</path></image>\n"
                + "  </view>\n</theme>\n");
        zipEntry(z, top + "_inc/system/metadata/androidapps.xml", "<theme><variables><systemName>Android Apps</systemName>"
                + "<systemColor>5A9035</systemColor><systemManufacturer>Google</systemManufacturer></variables></theme>");
        zipEntry(z, top + "_inc/system/marquees/androidapps.svg", "<svg viewBox=\"0 0 168 62\"></svg>");
        zipEntry(z, top + "_inc/system/icons/tools.webp", "the theme's own art for a system called tools");
        zipEntry(z, top + "VERSION", version);
        zipEntry(z, "pax_global_header", "ignored");
        if (evil) zipEntry(z, top + "../../escape.txt", "should never be written");
        z.close();
        return bo.toByteArray();
    }

    public static void main(String[] a) throws Exception {
        final String[] version = {"1"};
        final boolean[] evil = {false};
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        srv.createContext("/", new HttpHandler() { public void handle(HttpExchange x) throws IOException {
            String p = x.getRequestURI().toString();
            hits.add(p);
            byte[] body;
            int code = 200;
            if (p.startsWith("/gitlab/es-de%2Fthemes%2Fthemes-list/repository/files/themes.json/raw?ref=master")) {
                body = ("{\"latestStableRelease\":\"1\",\"themes\":[{\"name\":\"PS5 Menu\",\"reponame\":\"ps5-menu-es-de\","
                        + "\"url\":\"https://github.com/InitialDin/ps5-menu-es-de.git\",\"author\":\"InitialDin\"},"
                        + "{\"name\":\"Linear\",\"reponame\":\"linear-es-de\",\"url\":\"https://gitlab.com/es-de/themes/linear-es-de.git\"}],"
                        + "\"themesAndroid\":[{\"name\":\"Android Only\",\"reponame\":\"android-only-es-de\",\"url\":\"https://github.com/x/android-only-es-de\"}]}").getBytes();
            } else if (p.equals("/codeload/InitialDin/ps5-menu-es-de/zip/HEAD")) {
                body = themeZip(version[0], evil[0]);
            } else { code = 404; body = "nope".getBytes(); }
            x.sendResponseHeaders(code, body.length);
            x.getResponseBody().write(body);
            x.close();
        }});
        srv.start();

        File R = new File(a[0]);
        File esde = new File(R, "ES-DE");
        new File(esde, "settings").mkdirs();
        EsdeConfig.writeBytes(new File(esde, "settings/es_settings.xml"),
                "<?xml version=\"1.0\"?>\n<string name=\"Theme\" value=\"ps5-menu-es-de\" />\n".getBytes());
        ThemeCopies tc = new ThemeCopies(esde);
        tc.gitlabApi = base + "/gitlab/";
        tc.githubCodeload = base + "/codeload/";
        tc.gitlabWeb = base + "/gitlabweb/";

        check("ps5-menu-es-de".equals(tc.currentTheme()), "finds the theme ES-DE is set to use");
        List<ThemeCopies.Entry> list = tc.officialList();
        check(list.size() == 3 && list.get(2).name.equals("Android Only"), "reads ES-DE's official list, Android-only themes included");
        ThemeCopies.Entry ps5 = ThemeCopies.find(list, tc.currentTheme());
        check(ps5 != null && ps5.author.equals("InitialDin"), "matches the current theme in the list");
        check(ThemeCopies.find(list, "ps5-menu-es-de-escommander") == ps5, "a copy is matched to its original too");
        List<String> gl = tc.archiveUrls("https://gitlab.com/es-de/themes/linear-es-de.git");
        check(gl.get(0).equals(base + "/gitlab/es-de%2Fthemes%2Flinear-es-de/repository/archive.zip"), "GitLab themes: archive address");

        File cache = new File(R, "cache");
        File copy = tc.makeCopy(ps5, cache, null);
        check(copy.getName().equals("ps5-menu-es-de-escommander") && new File(copy, "theme.xml").isFile(), "copy installed as its own theme");
        check(EsdeConfig.read(new File(copy, "capabilities.xml")).contains("<themeName>PS5 Menu (ESCommander)</themeName>"), "renamed \"PS5 Menu (ESCommander)\"");
        check(!new File(copy, "pax_global_header").exists() && !new File(esde, "themes/pax_global_header").exists(), "archive's own top folder stripped");
        check(tc.sourceOf(copy) != null && tc.sourceOf(copy).url.contains("InitialDin"), "remembers where it came from");
        check(tc.localThemes().size() == 1 && !new File(copy.getPath() + ".partial").exists(), "no half-finished folders left");

        // ---- a custom look
        CategoryLooks looks = new CategoryLooks(esde);
        CategoryLooks.Look l = looks.load("streaming");
        l.fullName = "Streaming";
        l.description = "My apps";
        l.color = "AA2233";
        l.pictures.put(ThemeArtPlan.MAIN, looks.storePicture("streaming", "main", "PICTURE".getBytes(), "my-picture.jpg"));
        l.pictures.put(ThemeArtPlan.LOGO, new CategoryLooks.Picture(CategoryLooks.APP, "com.netflix.mediaclient", "Netflix"));
        final List<String> drawn = new ArrayList<String>();
        CategoryLooks.Renderer fake = new CategoryLooks.Renderer() {
            public byte[] render(CategoryLooks.Picture pic, ThemeArtPlan.Step s, String color) {
                String d = pic.kind + ":" + pic.value.replaceAll(".*/", "") + " " + s.width + "x" + s.height + " " + s.format + (s.cover ? " cover" : " fit") + " #" + color;
                drawn.add(d);
                return d.getBytes();
            }
        };
        CategoryLooks.Result res = looks.apply(l, copy, fake);
        for (String d : drawn) System.out.println("    drew " + d);
        File tile = new File(copy, "_inc/system/icons/streaming.webp"), mq = new File(copy, "_inc/system/marquees/streaming.svg");
        File info = new File(copy, "_inc/system/metadata/streaming.xml"), outside = new File(R, "ES-DE/theme-customizations/ps5/logos/streaming.png");
        check(EsdeConfig.read(tile).startsWith("file:main.picture") && EsdeConfig.read(tile).contains("cover"), "main picture drawn into the carousel slot, cropped to fill");
        check(EsdeConfig.read(mq).contains("<svg") && EsdeConfig.read(mq).contains("base64,"), "app icon logo wrapped into the SVG slot");
        check(EsdeConfig.read(outside) != null && EsdeConfig.read(outside).startsWith("app:com.netflix.mediaclient"), "customisation slot in ES-DE's folder filled too");
        check(EsdeConfig.read(info).contains("<systemName>Streaming</systemName>") && EsdeConfig.read(info).contains("5A9035"), "system info written for the category");
        check(res.written == 4 && res.errors.isEmpty(), "4 files written: " + res.written + " " + res.errors);
        check(looks.load("streaming").applied.get(copy.getName()).size() == 4, "written files remembered");

        // never overwrite the theme's own art
        CategoryLooks.Look t2 = looks.load("tools");
        t2.fullName = "Tools";
        t2.pictures.put(ThemeArtPlan.MAIN, looks.storePicture("tools", "main", "X".getBytes(), "x.png"));
        CategoryLooks.Result r2 = looks.apply(t2, copy, fake);
        check(EsdeConfig.read(new File(copy, "_inc/system/icons/tools.webp")).startsWith("the theme's own art") && r2.kept == 1, "a file the theme already has is kept, not overwritten");

        // changing the picture replaces only our files
        l.pictures.remove(ThemeArtPlan.LOGO);
        looks.apply(l, copy, fake);
        check(!mq.exists() && tile.isFile(), "removing the logo picture removes the name-logo file it made");
        check(EsdeConfig.read(outside).startsWith("file:main.picture"), "small logo slots fall back to the main picture");

        // remove the look
        check(looks.remove(looks.load("streaming"), copy) == 3 && !tile.exists() && !info.exists() && !outside.exists(), "removing the look deletes exactly its files");
        check(new File(copy, "_inc/system/icons/tools.webp").isFile() && new File(copy, "_inc/system/metadata/androidapps.xml").isFile(), "theme's own files untouched");

        // refresh: the copy is downloaded again and looks come back
        l = looks.load("streaming");
        l.pictures.put(ThemeArtPlan.LOGO, new CategoryLooks.Picture(CategoryLooks.APP, "com.netflix.mediaclient", "Netflix"));
        looks.apply(l, copy, fake);
        version[0] = "2";
        File again = tc.makeCopy(ps5, cache, null);
        check(EsdeConfig.read(new File(again, "VERSION")).equals("2") && !tile.exists(), "refresh replaces the copy with the latest version");
        List<String> rep = looks.reapplyAll(again, fake);
        check(tile.isFile() && mq.isFile() && outside.isFile() && rep.size() == 2, "looks put back after the refresh: " + rep);
        check(looks.load("streaming").applied.get(again.getName()).size() == 4, "no stale entries after re-applying");

        // a malicious archive can't write outside the theme folder
        evil[0] = true;
        boolean refused = false;
        try { tc.makeCopy(ps5, cache, null); } catch (IOException e) { refused = e.getMessage().contains("unsafe"); }
        check(refused && !new File(R, "escape.txt").exists() && !new File(esde, "escape.txt").exists() && new File(again, "theme.xml").isFile(),
                "unsafe archive refused; existing copy untouched");

        looks.forget("streaming");
        check(!tile.exists() && !outside.exists() && !new File(esde, "commander/looks/streaming").exists(), "deleting a category's look cleans up everything");

        srv.stop(0);
        System.out.println(fails == 0 ? "LOOKS TESTS OK" : fails + " FAILURES");
        if (fails > 0) System.exit(1);
    }
}
