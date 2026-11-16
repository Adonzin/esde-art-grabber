package com.esde.artgrabber;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Gets the games for a Moonlight category from the PC itself: finds Sunshine/Apollo hosts on the home network the
 * way Moonlight does, logs in to Sunshine's settings, and lists every app it streams.
 */
class MoonlightImport {

    interface Done { void done(String pcName, List<String> chosen, String host); }

    private final MainActivity a;
    private final SharedPreferences prefs;

    MoonlightImport(MainActivity a) {
        this.a = a;
        this.prefs = a.prefs;
    }

    static class Host {
        final String name, address;
        final int port;
        Host(String name, String address, int port) { this.name = name; this.address = address; this.port = port; }
        @Override public String toString() { return name + "   ·  " + address; }
    }

    /** Step 1: pick the PC (found on the network, remembered, or typed in). */
    void start(final String title, final String rememberedHost, final List<String> current, final Done cb) {
        final List<Host> hosts = new ArrayList<Host>();
        final ArrayAdapter<Host> adapter = new ArrayAdapter<Host>(a, android.R.layout.simple_list_item_1, hosts) {
            @Override public View getView(int pos, View cv, android.view.ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, cv, parent);
                tv.setTextColor(MainActivity.TEXT);
                return tv;
            }
        };
        if (rememberedHost != null && rememberedHost.length() > 0) hosts.add(new Host("Last used", rememberedHost, port(rememberedHost)));

        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(a.dp(20), a.dp(4), a.dp(20), 0);
        final TextView status = a.text("Looking for PCs running Sunshine on your network…", 13, MainActivity.MUTED, false);
        wrap.addView(status);
        ListView lv = new ListView(a);
        lv.setAdapter(adapter);
        wrap.addView(lv, new LinearLayout.LayoutParams(-1, a.dp(200)));
        wrap.addView(a.text("Your PC needs Sunshine (or Apollo) running, and this device on the same network.", 12, MainActivity.MUTED, false));

        final NsdManager nsd = (NsdManager) a.getSystemService(Context.NSD_SERVICE);
        final NsdManager.DiscoveryListener[] listener = new NsdManager.DiscoveryListener[1];
        final AlertDialog dlg = a.dialog().setTitle(title).setView(wrap)
                .setNeutralButton("Type an address…", null)
                .setNegativeButton("Cancel", null).show();
        listener[0] = new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String type) { }
            public void onDiscoveryStopped(String type) { }
            public void onStartDiscoveryFailed(String type, int code) { post(status, "Couldn't search the network. Type the PC's address instead."); }
            public void onStopDiscoveryFailed(String type, int code) { }
            public void onServiceLost(NsdServiceInfo s) { }
            public void onServiceFound(NsdServiceInfo s) {
                try {
                    nsd.resolveService(s, new NsdManager.ResolveListener() {
                        public void onResolveFailed(NsdServiceInfo i, int code) { }
                        public void onServiceResolved(final NsdServiceInfo i) {
                            if (i.getHost() == null) return;
                            final Host h = new Host(i.getServiceName(), i.getHost().getHostAddress(), i.getPort());
                            a.ui.post(new Runnable() {
                                public void run() {
                                    for (Host o : hosts) if (o.address.equals(h.address)) { if (o.name.equals("Last used")) { hosts.remove(o); break; } else return; }
                                    hosts.add(h);
                                    adapter.notifyDataSetChanged();
                                    status.setText("Pick your PC:");
                                }
                            });
                        }
                    });
                } catch (Exception ignored) { }   // resolving several at once can fail on older Android: others still come in
            }
        };
        try {
            nsd.discoverServices("_nvstream._tcp", NsdManager.PROTOCOL_DNS_SD, listener[0]);
        } catch (Exception e) {
            status.setText("Couldn't search the network. Type the PC's address instead.");
            listener[0] = null;
        }
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface d) {
                if (listener[0] != null) try { nsd.stopServiceDiscovery(listener[0]); } catch (Exception ignored) { }
            }
        });
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                Host h = hosts.get(pos);
                dlg.dismiss();
                login(h.address, h.port, current, cb);
            }
        });
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final EditText et = new EditText(a);
                et.setSingleLine(true);
                et.setHint("e.g. 192.168.1.20");
                et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
                if (rememberedHost != null) et.setText(rememberedHost);
                LinearLayout w = new LinearLayout(a);
                w.setPadding(a.dp(20), 0, a.dp(20), 0);
                w.addView(et, new LinearLayout.LayoutParams(-1, -2));
                a.dialog().setTitle("Your PC's address").setView(w)
                        .setPositiveButton("Next", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                String addr = et.getText().toString().trim();
                                if (addr.isEmpty()) return;
                                dlg.dismiss();
                                login(addr, port(addr), current, cb);
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
    }

    /** "192.168.1.20:47989" -> 47989; plain address -> the default port. */
    static int port(String addr) {
        int i = addr.lastIndexOf(':');
        if (i > 0 && addr.indexOf(':') == i) try { return Integer.parseInt(addr.substring(i + 1)); } catch (NumberFormatException ignored) { }
        return SunshineClient.INFO_PORT;
    }

    static String bare(String addr) {
        int i = addr.lastIndexOf(':');
        return i > 0 && addr.indexOf(':') == i ? addr.substring(0, i) : addr;
    }

    /** Step 2: Sunshine's username and password (the ones for its settings page). */
    private void login(final String address, final int port, final List<String> current, final Done cb) {
        final String host = bare(address);
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(a.dp(20), a.dp(4), a.dp(20), 0);
        wrap.addView(a.text("The username and password you use for Sunshine's settings page in your PC's browser. "
                + "They're only sent to your PC.", 13, MainActivity.MUTED, false));
        final EditText user = new EditText(a), pass = new EditText(a);
        user.setSingleLine(true);
        user.setHint("Username");
        user.setText(prefs.getString("sun.user." + host, ""));
        pass.setSingleLine(true);
        pass.setHint("Password");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setText(prefs.getString("sun.pass." + host, ""));
        final CheckBox remember = a.checkbox("Remember on this device");
        remember.setChecked(prefs.contains("sun.pass." + host));
        wrap.addView(user);
        wrap.addView(pass);
        wrap.addView(remember);
        a.dialog().setTitle("Log in to Sunshine on " + host).setView(wrap)
                .setPositiveButton("Get games", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        fetch(host, port, user.getText().toString().trim(), pass.getText().toString(), remember.isChecked(), current, cb);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Step 3: fetch, then let you tick which apps to include. */
    private void fetch(final String host, final int port, final String user, final String pass, final boolean remember,
                       final List<String> current, final Done cb) {
        final AlertDialog wait = a.dialog().setMessage("Getting the games from " + host + "…").setCancelable(false).show();
        new Thread(new Runnable() {
            public void run() {
                SunshineClient.Result r = null;
                Exception err = null;
                try {
                    r = new SunshineClient(host, port, prefs.getString("sun.fp." + host, null)).fetch(user, pass);
                } catch (Exception e) {
                    err = e;
                }
                final SunshineClient.Result fr = r;
                final Exception fe = err;
                a.ui.post(new Runnable() {
                    public void run() {
                        wait.dismiss();
                        if (fe instanceof SunshineClient.CertificateChangedException) {
                            a.dialog().setTitle("Certificate changed").setMessage(fe.getMessage())
                                    .setPositiveButton("Forget this PC", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int w) {
                                            prefs.edit().remove("sun.fp." + host).remove("sun.user." + host).remove("sun.pass." + host).apply();
                                            a.toast("Forgotten. Try again to connect fresh.");
                                        }
                                    }).setNegativeButton("Cancel", null).show();
                            return;
                        }
                        if (fe != null) {
                            a.dialog().setTitle("Couldn't get the games").setMessage(fe.getMessage()).setPositiveButton("OK", null).show();
                            return;
                        }
                        SharedPreferences.Editor ed = prefs.edit().putString("sun.fp." + host, fr.fingerprint).putString("sun.user." + host, user);
                        if (remember) ed.putString("sun.pass." + host, pass); else ed.remove("sun.pass." + host);
                        ed.apply();
                        pick(fr, host, port, current, cb);
                    }
                });
            }
        }).start();
    }

    private void pick(final SunshineClient.Result r, final String host, final int port, List<String> current, final Done cb) {
        if (r.apps.isEmpty()) { a.toast("Sunshine on " + r.pcName + " has no apps listed."); return; }
        final String[] names = r.apps.toArray(new String[0]);
        // everything starts ticked: on a refresh that keeps what you had and adds anything new
        final boolean[] ticked = new boolean[names.length];
        java.util.Arrays.fill(ticked, true);
        a.dialog().setTitle(r.pcName + " · " + names.length + " app" + (names.length == 1 ? "" : "s"))
                .setMultiChoiceItems(names, ticked, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int w, boolean c) { ticked[w] = c; }
                })
                .setPositiveButton("Use these", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        List<String> chosen = new ArrayList<String>();
                        for (int i = 0; i < names.length; i++) if (ticked[i]) chosen.add(names[i]);
                        cb.done(r.pcName, chosen, port == SunshineClient.INFO_PORT ? host : host + ":" + port);
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    static void post(final TextView t, final String s) { t.post(new Runnable() { public void run() { t.setText(s); } }); }
}
