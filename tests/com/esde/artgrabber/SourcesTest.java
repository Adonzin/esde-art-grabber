package com.esde.artgrabber;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.*;

/** Libretro + Steam + SteamGridDB working together, against a local stand-in for all of them. */
public class SourcesTest {
    static final List<String> hits = Collections.synchronizedList(new ArrayList<String>());
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    static int fails = 0;

    static void check(boolean c, String w) {
        if (!c) { System.out.println("FAIL " + w); fails++; } else System.out.println("  ok  " + w);
    }

    static void send(HttpExchange x, int code, String type, byte[] body) throws IOException {
        if (type != null) x.getResponseHeaders().set("Content-Type", type);
        boolean head = x.getRequestMethod().equals("HEAD");
        x.sendResponseHeaders(code, head ? -1 : body.length);
        if (!head) x.getResponseBody().write(body);
        x.close();
    }

    static byte[] png() {
        byte[] b = new byte[400];
        System.arraycopy(PNG, 0, b, 0, PNG.length);
        return b;
    }

    static final String[] SNES_BOX = {
            "Legend of Zelda, The - A Link to the Past (Europe)",
            "Legend of Zelda, The - A Link to the Past (USA)",
            "Legend of Zelda, The - A Link to the Past (USA, Europe) (Switch Online)",
            "Super Mario World (Europe) (Rev 1)",
            "Super Mario World (USA)",
            "Super Mario World (Japan) (Beta)",
            "Advanced Dungeons _ Dragons - Eye of the Beholder (USA)",
            "Chrono Trigger (USA)"};

    public static void main(String[] a) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        srv.createContext("/", new HttpHandler() {
            public void handle(HttpExchange x) throws IOException {
                String raw = x.getRequestURI().getRawPath();
                String p = URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8");
                hits.add(x.getRequestMethod() + " " + p);
                // ---- GitHub tree API
                if (p.startsWith("/gh/Nintendo_-_Super_Nintendo_Entertainment_System/git/trees/master")) {
                    StringBuilder t = new StringBuilder("{\"tree\":[");
                    for (String n : SNES_BOX) t.append("{\"path\":\"Named_Boxarts/").append(n).append(".png\",\"type\":\"blob\"},");
                    t.append("{\"path\":\"Named_Snaps/Super Mario World (USA).png\",\"type\":\"blob\"},");
                    t.append("{\"path\":\"Named_Logos/Super Mario World (USA).png\",\"type\":\"blob\"},");
                    t.append("{\"path\":\"Named_Titles\",\"type\":\"tree\"}],\"truncated\":false}");
                    send(x, 200, "application/json", t.toString().getBytes("UTF-8"));
                    return;
                }
                if (p.startsWith("/gh/")) { send(x, 403, "application/json", "{\"message\":\"API rate limit exceeded\"}".getBytes()); return; }
                // ---- thumbnails.libretro.com index pages (fallback when GitHub is rate limited)
                if (p.equals("/lr/Sega - Mega Drive - Genesis/Named_Boxarts/")) {
                    String html = "<a href=\"../\">../</a><a href=\"Sonic%20The%20Hedgehog%20%28USA%2C%20Europe%29.png\">Sonic</a>"
                            + "<a href=\"Streets%20of%20Rage%202%20%28USA%29.png\">SoR2</a>";
                    send(x, 200, "text/html", html.getBytes("UTF-8"));
                    return;
                }
                if (p.startsWith("/lr/Sega - Mega Drive - Genesis/Named_Boxarts/Sonic The Hedgehog (USA, Europe).png")) {
                    send(x, 200, "image/png", png()); return;
                }
                // ---- raw.githubusercontent.com
                if (p.startsWith("/raw/Nintendo_-_Super_Nintendo_Entertainment_System/master/")) {
                    String f = p.substring("/raw/Nintendo_-_Super_Nintendo_Entertainment_System/master/".length());
                    boolean known = f.startsWith("Named_Boxarts/") && Arrays.asList(SNES_BOX).contains(f.substring(14, f.length() - 4))
                            || f.equals("Named_Snaps/Super Mario World (USA).png") || f.equals("Named_Logos/Super Mario World (USA).png");
                    if (known) send(x, 200, "image/png", png());
                    else send(x, 404, "text/plain", "404: Not Found".getBytes());
                    return;
                }
                // ---- Steam CDN
                if (p.startsWith("/steam/")) {
                    if (p.equals("/steam/1145360/library_600x900.jpg") || p.equals("/steam/413150/library_600x900.jpg")
                            || p.equals("/steam/413150/library_hero.jpg")) { send(x, 200, "image/jpeg", new byte[5000]); return; }
                    if (p.equals("/steam/1145360/logo.png")) { send(x, 200, "image/png", png()); return; }
                    if (p.equals("/steam/1145360/header.jpg")) { send(x, 200, "text/html", "<html>moved</html>".getBytes()); return; }
                    send(x, 404, "text/html", "not found".getBytes());
                    return;
                }
                // ---- SteamGridDB
                if (p.startsWith("/api/games/steam/1145360")) { send(x, 200, "application/json", "{\"success\":true,\"data\":{\"id\":7,\"name\":\"Hades\"}}".getBytes()); return; }
                if (p.startsWith("/api/games/id/8")) {
                    send(x, 200, "application/json", "{\"success\":true,\"data\":{\"id\":8,\"name\":\"Stardew Valley\",\"external_platform_data\":{\"steam\":[{\"id\":\"413150\"}]}}}".getBytes());
                    return;
                }
                if (p.startsWith("/api/games/id/")) { send(x, 200, "application/json", "{\"success\":true,\"data\":{\"id\":1}}".getBytes()); return; }
                if (p.startsWith("/api/search/autocomplete/Stardew")) {
                    send(x, 200, "application/json", "{\"success\":true,\"data\":[{\"id\":8,\"name\":\"Stardew Valley\"}]}".getBytes()); return;
                }
                if (p.startsWith("/api/search/autocomplete/Super Mario World")) {
                    send(x, 200, "application/json", "{\"success\":true,\"data\":[{\"id\":2,\"name\":\"Super Mario World\"}]}".getBytes()); return;
                }
                if (p.startsWith("/api/search/")) { send(x, 200, "application/json", "{\"success\":true,\"data\":[]}".getBytes()); return; }
                if (p.startsWith("/api/grids/") || p.startsWith("/api/heroes/") || p.startsWith("/api/logos/")) {
                    String kind = p.split("/")[2];
                    send(x, 200, "application/json", ("{\"success\":true,\"data\":[{\"url\":\"" + base + "/cdn/" + kind + ".png\",\"score\":1}]}").getBytes());
                    return;
                }
                if (p.startsWith("/cdn/")) { send(x, 200, "image/png", png()); return; }
                send(x, 404, "text/plain", "?".getBytes());
            }
        });
        srv.start();

        File R = new File(a[0]);
        File roms = new File(R, "ROMs"), media = new File(R, "media"), cache = new File(R, "cache");
        touch(new File(roms, "snes/Super Mario World.sfc"));                                  // no tags -> USA release
        touch(new File(roms, "snes/Legend of Zelda, The - A Link to the Past (Europe).smc")); // region kept
        touch(new File(roms, "snes/Advanced Dungeons & Dragons - Eye of the Beholder (USA).sfc"));
        touch(new File(roms, "snes/zelda3.sfc"));                                              // title from gamelist
        touch(new File(roms, "snes/Totally Unknown Homebrew.sfc"));
        touch(new File(roms, "megadrive/Sonic The Hedgehog (USA, Europe).gen"));
        write(new File(roms, "steam/Hades.steam"), "appid=1145360");
        write(new File(roms, "windows/Stardew Valley.epic"), "{\"appName\":\"abc123\"}");
        File esde = new File(R, "ES-DE");
        write(new File(esde, "gamelists/snes/gamelist.xml"),
                "<gameList><game><path>./zelda3.sfc</path><name>The Legend of Zelda: A Link to the Past</name></game></gameList>");

        Sgdb api = new Sgdb("KEY");
        api.apiBase = base + "/api";
        Engine e = new Engine(roms, esde, media);
        e.libretro = testLibretro(base, cache);
        e.steam = new SteamArt();
        e.steam.bases = new String[]{base + "/nothing-here/", base + "/steam/"};

        // ---- preview first: nothing written
        final List<String> log = new ArrayList<String>();
        Engine.Listener L = new Engine.Listener() {
            public void log(String s) { log.add(s); System.out.println("    | " + s); }
            public void progress(int d, int t) { }
        };
        Engine.Options o = new Engine.Options();
        o.dryRun = true;
        e.run(api, o, L);
        check(!media.exists(), "preview writes nothing");
        boolean headUsed = false;
        for (String h : hits) if (h.startsWith("HEAD /steam/1145360/library_600x900.jpg")) headUsed = true;
        check(headUsed, "preview checks Steam images without downloading them");

        // ---- real run
        log.clear();
        Engine.Result r = e.run(api, new Engine.Options(), L);
        File M = media;
        check(isPng(new File(M, "snes/covers/Super Mario World.png")), "SNES cover from Libretro (untagged ROM)");
        check(lastHit("Named_Boxarts/Super Mario World").contains("(USA)"), "picked the USA release, not Europe Rev 1 or the Japanese beta");
        check(isPng(new File(M, "snes/screenshots/Super Mario World.png")) && isPng(new File(M, "snes/marquees/Super Mario World.png")),
                "screenshot and logo from Libretro too");
        check(new File(M, "snes/fanart/Super Mario World.png").isFile() && hitContains("/api/heroes/game/2"),
                "fanart (not in Libretro) filled by SteamGridDB");
        check(hitContains("Legend of Zelda, The - A Link to the Past (Europe).png"), "region from the ROM name respected (Europe)");
        check(hitContains("/raw/Nintendo_-_Super_Nintendo_Entertainment_System/master/Named_Boxarts/Advanced Dungeons _ Dragons - Eye of the Beholder (USA).png"),
                "'&' mapped to '_' like Libretro's file names");
        check(new File(M, "snes/covers/zelda3.png").isFile() && hitContains("Legend of Zelda, The - A Link to the Past (USA).png"),
                "cryptic file name matched via its ES-DE title");
        check(!new File(M, "snes/covers/Totally Unknown Homebrew.png").exists(), "unknown homebrew gets no wrong Libretro art");
        check(isPng(new File(M, "megadrive/covers/Sonic The Hedgehog (USA, Europe).png")),
                "GitHub rate limited -> listing read from thumbnails.libretro.com instead");
        check(new File(M, "steam/covers/Hades.jpg").length() == 5000, "Steam game: official Steam cover");
        check(isPng(new File(M, "steam/marquees/Hades.png")) && !hitContains("/api/logos/game/7"), "Steam game: official logo, SteamGridDB not asked");
        check(new File(M, "steam/fanart/Hades.png").isFile(), "Steam game: missing hero filled by SteamGridDB");
        check(!new File(M, "steam/screenshots/Hades.jpg").exists() && new File(M, "steam/screenshots/Hades.png").isFile(),
                "non-image reply from Steam rejected, SteamGridDB used");
        check(new File(M, "windows/covers/Stardew Valley.jpg").length() == 5000 && new File(M, "windows/fanart/Stardew Valley.jpg").isFile(),
                "Epic game also on Steam: official Steam art via SteamGridDB's Steam link");
        check(new File(M, "windows/marquees/Stardew Valley.png").isFile(), "...and SteamGridDB logo where Steam had none");
        boolean lrNote = false;
        for (String s : log) if (s.contains("(Libretro)")) lrNote = true;
        check(lrNote, "log says which source each image came from");
        check(r.misses.size() == 1 && r.misses.get(0).game.base().equals("Totally Unknown Homebrew"), "only the homebrew is unmatched: " + r.misses);

        // ---- listings cached on disk: a new run doesn't ask GitHub again
        int before = count("/gh/Nintendo");
        Engine e2 = new Engine(roms, esde, new File(R, "media2"));
        e2.libretro = testLibretro(base, cache);
        e2.steam = null;
        Engine.Options o2 = new Engine.Options();
        o2.useSgdb = false;
        o2.systems = new HashSet<String>(Collections.singletonList("snes"));
        Engine.Result r2 = e2.run(null, o2, L);
        check(count("/gh/Nintendo") == before, "Libretro listing reused from cache");
        check(new File(R, "media2/snes/covers/Super Mario World.png").isFile(), "works with SteamGridDB switched off (no key needed)");
        check(r2.misses.isEmpty(), "no 'fix unmatched' entries when SteamGridDB is off");

        // ---- source switches
        int rawBefore = count("/raw/");
        Engine e3 = new Engine(roms, esde, new File(R, "media3"));
        e3.libretro = testLibretro(base, cache);
        e3.steam = new SteamArt();
        e3.steam.bases = new String[]{base + "/steam/"};
        Engine.Options o3 = new Engine.Options();
        o3.useLibretro = false;
        o3.useSteam = false;
        o3.systems = new HashSet<String>(Arrays.asList("snes", "steam"));
        e3.run(api, o3, L);
        check(new File(R, "media3/steam/covers/Hades.png").isFile() && !new File(R, "media3/steam/covers/Hades.jpg").exists(),
                "Steam switched off: cover from SteamGridDB");
        check(new File(R, "media3/snes/covers/Super Mario World.png").isFile() && count("/raw/") == rawBefore,
                "Libretro switched off: SNES art from SteamGridDB");

        srv.stop(0);
        System.out.println(fails == 0 ? "SOURCES TESTS OK" : fails + " FAILURES");
        if (fails > 0) System.exit(1);
    }

    static Libretro testLibretro(String base, File cache) {
        Libretro lr = new Libretro(cache);
        lr.apiBase = base + "/gh/";
        lr.rawBase = base + "/raw/";
        lr.altBase = base + "/lr/";
        return lr;
    }

    static boolean isPng(File f) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return b.length > 4 && b[0] == (byte) 0x89 && b[1] == 'P';
        } catch (IOException e) {
            return false;
        }
    }

    static boolean hitContains(String s) { synchronized (hits) { for (String h : hits) if (h.contains(s)) return true; } return false; }

    static String lastHit(String s) {
        String last = "";
        synchronized (hits) { for (String h : hits) if (h.contains(s) && h.startsWith("GET")) last = h; }
        return last;
    }

    static int count(String s) { int n = 0; synchronized (hits) { for (String h : hits) if (h.contains(s)) n++; } return n; }

    static void touch(File f) throws IOException { write(f, "x"); }

    static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Writer w = new FileWriter(f);
        w.write(text);
        w.close();
    }
}
