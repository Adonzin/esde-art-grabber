package com.esde.artgrabber;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.*;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** SunshineClient against local stand-ins for Sunshine and Apollo (self-signed HTTPS, like the real ones). */
public class SunshineTest {
    static int fails = 0;
    static void check(boolean c, String w) { if (!c) { System.out.println("FAIL " + w); fails++; } else System.out.println("  ok  " + w); }
    static final List<String> authSeen = Collections.synchronizedList(new ArrayList<String>());

    static void send(HttpExchange x, int code, String body, String... headers) throws IOException {
        for (int i = 0; i + 1 < headers.length; i += 2) x.getResponseHeaders().add(headers[i], headers[i + 1]);
        byte[] b = body.getBytes("UTF-8");
        x.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) x.getResponseBody().write(b);
        x.close();
    }

    static SSLContext selfSigned(File dir, String alias) throws Exception {
        File ks = new File(dir, alias + ".p12");
        Process p = new ProcessBuilder("keytool", "-genkeypair", "-keystore", ks.getPath(), "-storetype", "PKCS12", "-alias", alias,
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-storepass", "secret", "-keypass", "secret",
                "-dname", "CN=Sunshine Gamestream Host").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor();
        KeyStore k = KeyStore.getInstance("PKCS12");
        k.load(new FileInputStream(ks), "secret".toCharArray());
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(k, "secret".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /** Starts info (HTTP) on port p and settings (HTTPS) on p+1. mode: sunshine | apollo */
    static Object[] start(final String mode, SSLContext tls) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            int p = 30000 + new Random().nextInt(20000);
            try {
                HttpServer info = HttpServer.create(new InetSocketAddress("127.0.0.1", p), 0);
                HttpsServer web = HttpsServer.create(new InetSocketAddress("127.0.0.1", p + 1), 0);
                web.setHttpsConfigurator(new HttpsConfigurator(tls));
                info.createContext("/serverinfo", new HttpHandler() { public void handle(HttpExchange x) throws IOException {
                    send(x, 200, "<?xml version=\"1.0\"?><root status_code=\"200\"><hostname>GAMING-PC &amp; Co</hostname></root>");
                }});
                final String apps = "{\"env\":{},\"apps\":[{\"name\":\"Desktop\"},{\"name\":\"Steam Big Picture\"},{\"name\":\"Cyberpunk 2077\"},{\"name\":\"Desktop\"},{\"name\":\"\"}]}";
                web.createContext("/api/apps", new HttpHandler() { public void handle(HttpExchange x) throws IOException {
                    String auth = x.getRequestHeaders().getFirst("Authorization");
                    String cookie = x.getRequestHeaders().getFirst("Cookie");
                    authSeen.add(auth == null ? "-" : auth);
                    boolean ok = mode.equals("sunshine") ? ("Basic " + Base64.getEncoder().encodeToString("admin:hunter2".getBytes())).equals(auth)
                            : "auth=s3ss10n".equals(cookie);
                    if (ok) send(x, 200, apps);
                    else send(x, 401, "", "WWW-Authenticate", "Basic realm=\"Sunshine Gamestream Host\"");
                }});
                web.createContext("/api/login", new HttpHandler() { public void handle(HttpExchange x) throws IOException {
                    String body = new String(x.getRequestBody().readAllBytes(), "UTF-8");
                    if (!mode.equals("apollo")) { send(x, 404, ""); return; }
                    if (body.contains("\"admin\"") && body.contains("\"hunter2\"") && "application/json".equals(x.getRequestHeaders().getFirst("Content-Type")))
                        send(x, 200, "", "Set-Cookie", "auth=s3ss10n; Secure; SameSite=Strict; Max-Age=2592000; Path=/");
                    else send(x, 401, "");
                }});
                info.start();
                web.start();
                return new Object[]{p, info, web};
            } catch (java.net.BindException e) { /* try another port */ }
        }
        throw new IOException("no free ports");
    }

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        dir.mkdirs();
        SSLContext tls = selfSigned(dir, "sunshine"), other = selfSigned(dir, "impostor");

        check(SunshineClient.isLocal("192.168.1.20") && SunshineClient.isLocal("10.0.0.5") && SunshineClient.isLocal("127.0.0.1")
                && SunshineClient.isLocal("fd00::1"), "home-network addresses accepted");
        check(!SunshineClient.isLocal("8.8.8.8") && !SunshineClient.isLocal("not a host name.invalid"), "internet addresses refused");

        Object[] s = start("sunshine", tls);
        int port = (Integer) s[0];
        SunshineClient c = new SunshineClient("127.0.0.1", port, null);
        check(c.pcName().equals("GAMING-PC & Co"), "PC name exactly as Moonlight shows it (no login needed)");
        SunshineClient.Result r = c.fetch("admin", "hunter2");
        check(r.apps.equals(Arrays.asList("Desktop", "Steam Big Picture", "Cyberpunk 2077")), "Sunshine: all apps, no duplicates or blanks: " + r.apps);
        check("Sunshine".equals(r.kind) && r.fingerprint != null && r.fingerprint.length() == 64, "remembers the certificate it saw");
        boolean wrong = false;
        try { c.fetch("admin", "nope"); } catch (SunshineClient.WrongPasswordException e) { wrong = true; }
        check(wrong, "wrong password reported clearly");

        SunshineClient pinned = new SunshineClient("127.0.0.1", port, r.fingerprint);
        check(pinned.fetch("admin", "hunter2").apps.size() == 3, "same certificate next time: works");
        ((HttpServer) s[1]).stop(0);
        ((HttpsServer) s[2]).stop(0);

        // something else answering on the same address with a different certificate
        Object[] fake = start("sunshine", other);
        int before = authSeen.size();
        boolean changed = false;
        try { new SunshineClient("127.0.0.1", (Integer) fake[0], r.fingerprint).fetch("admin", "hunter2"); }
        catch (SunshineClient.CertificateChangedException e) { changed = true; }
        check(changed, "different certificate: refused with a clear warning");
        check(authSeen.size() == before, "...and the password was never sent");
        ((HttpServer) fake[1]).stop(0);
        ((HttpsServer) fake[2]).stop(0);

        Object[] ap = start("apollo", tls);
        SunshineClient.Result ra = new SunshineClient("127.0.0.1", (Integer) ap[0], null).fetch("admin", "hunter2");
        check("Apollo".equals(ra.kind) && ra.apps.size() == 3, "Apollo: logs in with a session cookie, same app list");
        boolean wrongA = false;
        try { new SunshineClient("127.0.0.1", (Integer) ap[0], null).fetch("admin", "bad"); } catch (SunshineClient.WrongPasswordException e) { wrongA = true; }
        check(wrongA, "Apollo: wrong password reported clearly");
        ((HttpServer) ap[1]).stop(0);
        ((HttpsServer) ap[2]).stop(0);

        boolean unreachable = false;
        try { new SunshineClient("127.0.0.1", 9, null).pcName(); } catch (IOException e) { unreachable = e.getMessage().contains("Is the PC on"); }
        check(unreachable, "PC off: says so");

        System.out.println(fails == 0 ? "SUNSHINE TESTS OK" : fails + " FAILURES");
        if (fails > 0) System.exit(1);
    }
}
