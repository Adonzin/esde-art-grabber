package com.esde.artgrabber;
import com.sun.net.httpserver.*;
import java.io.*; import java.net.*; import java.util.*;

public class HttpTest {
    static List<String> hits = Collections.synchronizedList(new ArrayList<String>());
    static void send(HttpExchange x, int code, String body) throws IOException {
        byte[] b = body.getBytes("UTF-8"); x.sendResponseHeaders(code, b.length); x.getResponseBody().write(b); x.close(); }
    static void check(boolean c, String w) { if (!c) { System.out.println("FAIL " + w); System.exit(1); } System.out.println("  ok  " + w); }

    public static void main(String[] a) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final int port = srv.getAddress().getPort();
        final String base = "http://127.0.0.1:" + port;
        srv.createContext("/", new HttpHandler() { public void handle(HttpExchange x) throws IOException {
            String p = x.getRequestURI().toString(); hits.add(p);
            if (!"Bearer KEY".equals(x.getRequestHeaders().getFirst("Authorization")) && p.startsWith("/api")) { send(x, 401, "{}"); return; }
            if (p.startsWith("/api/games/steam/")) { send(x, 400, "{\"success\":false,\"errors\":[\"Invalid steam id\"]}"); return; }
            if (p.startsWith("/api/search/autocomplete/Ooblets")) { send(x, 200, "{\"success\":true,\"data\":[{\"id\":5265,\"name\":\"Ooblets\",\"release_date\":1596585600}]}"); return; }
            if (p.startsWith("/api/logos/")) {
                if (p.contains("jpeg")) { send(x, 400, "{\"success\":false,\"errors\":[\"Invalid mime type\"]}"); return; }
                send(x, 200, "{\"success\":true,\"data\":[{\"url\":\"" + base + "/cdn/logo.png\",\"mime\":\"image/png\",\"score\":1}]}"); return; }
            if (p.startsWith("/api/heroes/")) {
                if (p.contains("mimes=")) { send(x, 400, "{\"success\":false,\"errors\":[\"Invalid mime type\"]}"); return; }
                send(x, 200, "{\"success\":true,\"data\":[{\"url\":\"" + base + "/cdn/hero.webp\",\"mime\":\"image/webp\",\"score\":9},"
                    + "{\"url\":\"" + base + "/cdn/hero.png\",\"mime\":\"image/png\",\"score\":2}]}"); return; }
            if (p.startsWith("/api/grids/")) {
                send(x, 200, "{\"success\":true,\"data\":[{\"url\":\"" + base + "/cdn/img.png\",\"score\":3}]}"); return; }
            if (p.startsWith("/cdn/")) { send(x, 200, "PNGDATA"); return; }
            send(x, 404, "{}");
        }});
        srv.start();
        File R = new File(a[0]);
        File roms = new File(R, "ROMs"), media = new File(R, "media");
        new File(roms, "windows").mkdirs();
        try (Writer w = new FileWriter(new File(roms, "windows/Ooblets.epic"))) { w.write("{\"appId\":\"9773aa1aa54f4f7b80e44bef04986cea\",\"store\":\"epic\"}"); }
        new File(roms, "steam").mkdirs();
        try (Writer w = new FileWriter(new File(roms, "steam/Ooblets.steam"))) { w.write("appid=1081870"); }

        Sgdb api = new Sgdb("KEY"); api.apiBase = base + "/api";
        Engine e = new Engine(roms, null, media);
        e.libretro = null; e.steam = null;
        final List<String> log = new ArrayList<>();
        Engine.Options o = new Engine.Options();
        Engine.Result r = e.run(api, o, new Engine.Listener() { public void log(String s){ log.add(s); System.out.println("    | " + s);} public void progress(int d,int t){} });
        boolean epicSteamLookup = false;
        for (String h : hits) if (h.startsWith("/api/games/steam/9773")) epicSteamLookup = true;
        check(!epicSteamLookup, ".epic file's Epic id is NOT sent as a Steam id");
        check(new File(media, "windows/covers/Ooblets.png").isFile(), "Ooblets cover saved for the .epic entry");
        check(new File(media, "windows/marquees/Ooblets.png").isFile(), "logo (marquee) saved: PNG-only request accepted");
        boolean jpegLogo = false; for (String h : hits) if (h.startsWith("/api/logos/") && h.contains("jpeg")) jpegLogo = true;
        check(!jpegLogo, "never asks for JPEG logos");
        check(new File(media, "windows/fanart/Ooblets.png").isFile(), "fanart: filter rejected -> retried without it, picked the PNG over a higher-scored WebP");
        boolean webp = false; for (String h : hits) if (h.contains("hero.webp")) webp = true;
        check(!webp, "WebP image never downloaded");
        check(new File(media, "steam/covers/Ooblets.png").isFile(), ".steam entry: rejected Steam-id lookup falls back to name search");
        boolean explained = false; for (String s : log) if (s.contains("Invalid steam id")) explained = true;
        check(explained, "log explains the rejection in SteamGridDB's words");
        check(r.failed == 0, "no game marked as failed: " + r.failed);
        boolean mimeNoise = false; for (String l2 : log) if (l2.contains("Invalid mime")) mimeNoise = true;
        check(!mimeNoise, "recovered file-type rejections aren't reported as errors");
        // ---- manual picking helpers
        Engine.GameFile gf = e.scanSystem("windows").get(0);
        List<Sgdb.Image> covers = Engine.allImages(api, 5265, "covers");
        check(covers.size() == 1, "allImages merges both cover sources without duplicates: " + covers.size());
        List<Sgdb.Image> shots = Engine.allImages(api, 5265, "screenshots");
        check(shots.size() == 2, "screenshots offer wide grids + backgrounds: " + shots.size());
        File oldJpg = new File(media, "windows/covers/Ooblets.jpg");
        new File(media, "windows/covers/Ooblets.png").delete();
        try (Writer w = new FileWriter(oldJpg)) { w.write("old"); }
        File saved = e.saveChosen(api, gf, "covers", base + "/cdn/picked.png");
        check(saved.getName().equals("Ooblets.png") && !oldJpg.exists(), "chosen image replaces the old one (other format removed)");
        check(e.currentArt(gf, "covers").equals(saved), "currentArt finds the saved image");
        check(e.searchName(gf, e.titles("windows")).equals("Ooblets"), "search name from file");
        srv.stop(0);
        System.out.println("HTTP TESTS OK");
    }
}
