package com.esde.artgrabber;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;

/** The in-app updater against a stand-in for GitHub's releases API and file host. */
public class UpdaterTest {
    static int fails = 0;
    static void check(boolean c, String w) { if (!c) { System.out.println("FAIL " + w); fails++; } else System.out.println("  ok  " + w); }

    public static void main(String[] a) throws Exception {
        final byte[] apk = new byte[123456];
        apk[0] = 'P'; apk[1] = 'K';
        final String[] mode = {"ok"};
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        srv.createContext("/", new HttpHandler() { public void handle(HttpExchange x) throws IOException {
            String p = x.getRequestURI().getPath();
            byte[] body;
            if (p.equals("/api/" + Updater.REPO + "/releases/latest")) {
                if (mode[0].equals("private")) { x.sendResponseHeaders(404, -1); x.close(); return; }
                String assets = mode[0].equals("noapk") ? "[{\"name\":\"notes.txt\",\"browser_download_url\":\"x\",\"size\":1}]"
                        : "[{\"name\":\"source.zip\",\"browser_download_url\":\"" + base + "/zip\",\"size\":9},"
                        + "{\"name\":\"ES-DE-Art-Grabber.apk\",\"browser_download_url\":\"" + base + "/dl/ES-DE-Art-Grabber.apk\",\"size\":" + apk.length + "}]";
                body = ("{\"tag_name\":\"v1.11\",\"body\":\"New stuff\",\"html_url\":\"" + base + "/rel\",\"assets\":" + assets + "}").getBytes();
                x.sendResponseHeaders(200, body.length); x.getResponseBody().write(body); x.close(); return;
            }
            if (p.equals("/dl/ES-DE-Art-Grabber.apk")) {   // GitHub redirects downloads to another host
                x.getResponseHeaders().set("Location", base + "/objects/abc123?token=xyz");
                x.sendResponseHeaders(302, -1); x.close(); return;
            }
            if (p.equals("/objects/abc123")) {
                int n = mode[0].equals("short") ? 1000 : apk.length;
                x.sendResponseHeaders(200, n); x.getResponseBody().write(apk, 0, n); x.close(); return;
            }
            x.sendResponseHeaders(404, -1); x.close();
        }});
        srv.start();

        check(Updater.newer("1.10", "1.9"), "1.10 is newer than 1.9 (not compared as text)");
        check(!Updater.newer("1.9", "1.10") && !Updater.newer("1.10", "1.10") && Updater.newer("2.0", "1.99"), "version comparison");
        check(Updater.newer("v1.11", "1.10"), "tag prefix 'v' ignored");

        Updater u = new Updater();
        u.apiBase = base + "/api/";
        Updater.Release r = u.latest();
        check(r.version.equals("1.11") && r.apkUrl.endsWith(".apk") && r.notes.equals("New stuff"), "reads the latest release and picks the APK asset");
        File dest = new File(a[0], "updates/update.apk");
        final long[] last = {0};
        u.download(r, dest, new Updater.Progress() { public void update(long d, long t) { last[0] = d; } });
        check(dest.length() == apk.length && last[0] == apk.length, "download follows GitHub's redirect and reports progress");

        mode[0] = "short";
        boolean threw = false;
        try { u.download(r, dest, null); } catch (IOException e) { threw = e.getMessage().contains("incomplete"); }
        check(threw && dest.length() == apk.length, "cut-off download rejected; previous file untouched");

        mode[0] = "private";
        String msg = "";
        try { u.latest(); } catch (Updater.NoReleaseException e) { msg = e.getMessage(); }
        check(msg.contains("public"), "private repo / no release explained: " + msg);

        mode[0] = "noapk";
        msg = "";
        try { u.latest(); } catch (Updater.NoReleaseException e) { msg = e.getMessage(); }
        check(msg.contains("no APK"), "release without an APK explained");

        srv.stop(0);
        System.out.println(fails == 0 ? "UPDATER TESTS OK" : fails + " FAILURES");
        if (fails > 0) System.exit(1);
    }
}
