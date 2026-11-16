package com.esde.artgrabber;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Reads the app list of a Sunshine (or Apollo) game-streaming host, the server Moonlight connects to:
 * the PC's name from its public server info, and its apps from its settings server after logging in.
 * Sunshine uses a certificate it made itself, so it is trusted the first time and pinned after that:
 * if it ever changes, nothing (passwords included) is sent. No Android code: tested on the desktop.
 */
public class SunshineClient {

    /** Default ports: Moonlight's info port, and the settings (web UI) port one above it. */
    public static final int INFO_PORT = 47989;

    public static class Result {
        public String pcName;                       // as Moonlight shows it
        public final List<String> apps = new ArrayList<String>();
        public String fingerprint;                  // the settings server's certificate, to pin
        public String kind;                         // "Sunshine" or "Apollo"
    }

    public static class WrongPasswordException extends IOException {
        public WrongPasswordException() { super("Sunshine didn't accept that username and password."); }
    }

    public static class CertificateChangedException extends IOException {
        public CertificateChangedException() {
            super("Your PC's security certificate has changed since last time. If you reinstalled Sunshine that's expected: "
                    + "forget this PC and try again. Otherwise something else may be pretending to be your PC.");
        }
    }

    private final String host;
    private final int infoPort, webPort;
    private final String pinned;   // SHA-256 of the certificate seen before, or null

    /** @param infoPort the port Moonlight uses (47989 unless changed in Sunshine); settings are on infoPort + 1 */
    public SunshineClient(String host, int infoPort, String pinnedFingerprint) {
        this.host = host.trim().replaceAll("^\\[|\\]$", "");
        this.infoPort = infoPort > 0 ? infoPort : INFO_PORT;
        this.webPort = this.infoPort + 1;
        this.pinned = pinnedFingerprint;
    }

    private String hostForUrl() { return host.contains(":") ? "[" + host + "]" : host; }

    /** Only addresses on your own network: Sunshine refuses others anyway, and passwords shouldn't travel further. */
    public static boolean isLocal(String host) {
        try {
            InetAddress a = InetAddress.getByName(host.replaceAll("^\\[|\\]$", ""));
            return a.isSiteLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                    || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xFE) == 0xFC);   // IPv6 unique-local
        } catch (Exception e) {
            return false;
        }
    }

    // ================================================================ PC name (no login needed)

    private static final Pattern HOSTNAME = Pattern.compile("<hostname>([^<]*)</hostname>");

    /** The name Moonlight shows for this PC, from the public server info. */
    public String pcName() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + hostForUrl() + ":" + infoPort + "/serverinfo").openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            Matcher m = HOSTNAME.matcher(Sgdb.readAll(c.getInputStream()));
            if (!m.find()) throw new IOException("this doesn't look like a Sunshine PC");
            return EsdeConfig.unx(m.group(1).trim());
        } catch (java.net.ConnectException e) {
            throw new IOException("Couldn't reach " + host + ". Is the PC on, with Sunshine running?");
        } finally {
            c.disconnect();
        }
    }

    // ================================================================ app list (login needed)

    /** Logs in and reads every app in Sunshine's list. */
    public Result fetch(String username, String password) throws IOException {
        if (!isLocal(host)) throw new IOException("For safety, only PCs on your home network are supported (an address like 192.168.x.x).");
        Result r = new Result();
        r.pcName = pcName();
        Pin pin = new Pin();
        // Sunshine: the password goes with the request
        HttpsURLConnection c = open("/api/apps", pin);
        c.setRequestProperty("Authorization", "Basic " + ThemeArtPlan.base64((username + ":" + password).getBytes("UTF-8")));
        int code;
        try {
            code = c.getResponseCode();
        } catch (java.net.ConnectException e) {
            throw new IOException("Couldn't reach Sunshine's settings on " + host + ":" + webPort + ".");
        } catch (IOException e) {
            if (pin.changed) throw new CertificateChangedException();
            throw new IOException("Couldn't make a secure connection to " + host + ": " + e.getMessage());
        }
        String body;
        if (code == 200) {
            body = Sgdb.readAll(c.getInputStream());
            r.kind = "Sunshine";
        } else if (code == 401 || code == 302 || code == 307 || code == 403) {
            c.disconnect();
            if (code == 403) throw new IOException("Sunshine refused the connection: it only allows devices on your home network.");
            // Apollo: log in once, then use the session cookie
            String cookie = apolloLogin(username, password, pin);
            if (cookie == null) throw new WrongPasswordException();
            HttpsURLConnection c2 = open("/api/apps", pin);
            c2.setRequestProperty("Cookie", "auth=" + cookie);
            if (c2.getResponseCode() != 200) throw new WrongPasswordException();
            body = Sgdb.readAll(c2.getInputStream());
            c2.disconnect();
            r.kind = "Apollo";
        } else {
            throw new IOException("Sunshine answered HTTP " + code);
        }
        c.disconnect();
        r.fingerprint = pin.seen;
        try {
            JSONArray apps = new JSONObject(body).optJSONArray("apps");
            if (apps != null) for (int i = 0; i < apps.length(); i++) {
                JSONObject a = apps.optJSONObject(i);
                String name = a == null ? "" : a.optString("name").trim();
                if (name.length() > 0 && !r.apps.contains(name)) r.apps.add(name);
            }
        } catch (JSONException e) {
            throw new IOException("Sunshine's app list couldn't be read");
        }
        return r;
    }

    /** Apollo's login: returns the session cookie, or null if the username or password is wrong. */
    private String apolloLogin(String username, String password, Pin pin) throws IOException {
        HttpsURLConnection c = open("/api/login", pin);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try {
            String json = new JSONObject().put("username", username).put("password", password).toString();
            OutputStream o = c.getOutputStream();
            o.write(json.getBytes("UTF-8"));
            o.close();
        } catch (JSONException e) {
            throw new IOException("login failed");
        }
        int code = c.getResponseCode();
        if (code == 404) return null;   // not Apollo either
        if (code != 200) return null;
        for (int i = 0; ; i++) {
            String k = c.getHeaderFieldKey(i), v = c.getHeaderField(i);
            if (k == null && v == null && i > 0) break;
            if (k != null && k.equalsIgnoreCase("Set-Cookie") && v != null) {
                Matcher m = Pattern.compile("(?:^|;\\s*)auth=([^;]+)").matcher(v);
                if (m.find()) { c.disconnect(); return m.group(1); }
            }
            if (i > 60) break;
        }
        c.disconnect();
        return null;
    }

    // ================================================================ TLS: trust the first time, then pin

    class Pin {
        String seen;
        boolean changed;
    }

    private HttpsURLConnection open(String path, final Pin pin) throws IOException {
        HttpsURLConnection c = (HttpsURLConnection) new URL("https://" + hostForUrl() + ":" + webPort + path).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(12000);
        c.setInstanceFollowRedirects(false);
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String type) { }
                public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                    if (chain == null || chain.length == 0) throw new CertificateException("no certificate");
                    String fp = fingerprint(chain[0]);
                    pin.seen = fp;
                    if (pinned != null && !pinned.equalsIgnoreCase(fp)) { pin.changed = true; throw new CertificateException("certificate changed"); }
                }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            c.setSSLSocketFactory(ctx.getSocketFactory());
        } catch (Exception e) {
            throw new IOException("secure connection setup failed");
        }
        // Sunshine's certificate isn't made out to your PC's address; it is identified by its pinned fingerprint instead
        c.setHostnameVerifier(new HostnameVerifier() { public boolean verify(String h, SSLSession s) { return true; } });
        return c;
    }

    static String fingerprint(X509Certificate cert) throws CertificateException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new CertificateException("couldn't read the certificate");
        }
    }
}
