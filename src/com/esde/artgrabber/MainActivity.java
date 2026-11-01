package com.esde.artgrabber;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    // ---- palette
    static final int BG = Color.parseColor("#101218");
    static final int CARD = Color.parseColor("#1B1F29");
    static final int CARD_HI = Color.parseColor("#252B38");
    static final int TEXT = Color.parseColor("#E9ECF2");
    static final int MUTED = Color.parseColor("#8E96A8");
    static final int ACCENT = Color.parseColor("#4F8CFF");
    static final int GOOD = Color.parseColor("#4CD08A");
    static final int WARN = Color.parseColor("#FFB547");

    static final String[][] MEDIA_LABELS = {
            {"covers", "Box covers"},
            {"marquees", "Logos (marquees)"},
            {"fanart", "Fanart / backgrounds"},
            {"screenshots", "Screenshots (wide art)"},
            {"titlescreens", "Title screens (wide art)"}};

    SharedPreferences prefs;
    final Handler ui = new Handler(Looper.getMainLooper());

    TextView keyStatus, romPath, mediaPath, systemsLabel, statusText, logView, accessText;
    LinearLayout accessCard;
    ScrollView logScroll;
    ProgressBar progress;
    Button runBtn, previewBtn, stopBtn, fixBtn;
    final Map<String, CheckBox> mediaBoxes = new HashMap<String, CheckBox>();
    CheckBox overwriteBox, appIconBox;

    File esdeDir, romDir, mediaDir;
    Set<String> chosenSystems = null; // null = all
    volatile Engine engine;
    Thread worker;
    final ThumbLoader thumbs = new ThumbLoader();
    final ArrayDeque<String> logLines = new ArrayDeque<String>();
    final List<Engine.Miss> misses = new ArrayList<Engine.Miss>();
    boolean logPending = false;

    // ================================================================= lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        loadSettings();
        ensureStorageAccess();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccessCard();
        if (romDir == null || esdeDir == null) detectFolders(false);
        else updateLabels();
    }

    @Override
    protected void onDestroy() {
        thumbs.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (worker != null && worker.isAlive()) moveTaskToBack(true);
        else super.onBackPressed();
    }

    // ================================================================= UI construction

    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    Drawable box(int fill, int stroke, float radius, int strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (strokeW > 0) g.setStroke(dp(strokeW), stroke);
        return g;
    }

    /** Background that shows a bright outline when focused with the D-pad. */
    Drawable focusable(int fill, int pressed) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, box(pressed, Color.WHITE, 10, 2));
        s.addState(new int[]{android.R.attr.state_pressed}, box(pressed, 0, 10, 0));
        s.addState(new int[]{-android.R.attr.state_enabled}, box(Color.parseColor("#2A2F3A"), 0, 10, 0));
        s.addState(new int[]{}, box(fill, 0, 10, 0));
        return s;
    }

    Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? Color.WHITE : TEXT);
        b.setStateListAnimator(null);
        b.setBackground(primary ? focusable(ACCENT, Color.parseColor("#3B74E0"))
                : focusable(CARD_HI, Color.parseColor("#323A4B")));
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setMinHeight(dp(46));
        return b;
    }

    TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(box(CARD, 0, 14, 0));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        if (title != null) {
            TextView t = text(title.toUpperCase(), 12, MUTED, true);
            t.setLetterSpacing(0.08f);
            c.addView(t);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        parent.addView(c, lp);
        return c;
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    void addWeighted(LinearLayout row, View v, float w, int marginLeftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, w);
        lp.leftMargin = dp(marginLeftDp);
        row.addView(v, lp);
    }

    void buildUi() {
        boolean wide = getResources().getDisplayMetrics().widthPixels > getResources().getDisplayMetrics().heightPixels;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        // ---------------- left: settings
        ScrollView leftScroll = new ScrollView(this);
        leftScroll.setFillViewport(true);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        leftScroll.addView(left);

        TextView title = text("ES-DE Art Grabber", 24, TEXT, true);
        left.addView(title);
        TextView sub = text("Box art, logos and backgrounds from SteamGridDB, saved straight into ES-DE.", 14, MUTED, false);
        sub.setPadding(0, dp(2), 0, dp(14));
        left.addView(sub);

        // storage access warning (hidden when fine)
        accessCard = card(left, null);
        accessCard.setBackground(box(Color.parseColor("#3A2A12"), WARN, 14, 1));
        accessText = text("", 14, WARN, false);
        accessCard.addView(accessText);
        Button grant = button("Allow file access", true);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
        glp.topMargin = dp(10);
        accessCard.addView(grant, glp);
        grant.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openAllFilesSettings(); }
        });
        accessCard.setVisibility(View.GONE);

        // API key
        LinearLayout k = card(left, "SteamGridDB API key");
        keyStatus = text("", 15, TEXT, false);
        keyStatus.setPadding(0, dp(6), 0, dp(10));
        k.addView(keyStatus);
        LinearLayout kr = row();
        Button enterKey = button("Enter key", true);
        Button getKey = button("Get a free key", false);
        addWeighted(kr, enterKey, 1, 0);
        addWeighted(kr, getKey, 1, 10);
        k.addView(kr);
        enterKey.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askForKey(); }
        });
        getKey.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.steamgriddb.com/profile/preferences/api")));
                } catch (Exception e) {
                    toast("Open steamgriddb.com/profile/preferences/api in a browser");
                }
            }
        });

        // folders
        LinearLayout f = card(left, "Folders");
        f.addView(spacer(6));
        f.addView(text("ROMs folder", 13, MUTED, false));
        LinearLayout fr1 = row();
        romPath = text("—", 14, TEXT, false);
        Button chRom = button("Change", false);
        addWeighted(fr1, romPath, 1, 0);
        fr1.addView(chRom);
        f.addView(fr1);
        f.addView(spacer(10));
        f.addView(text("ES-DE media folder (downloaded_media)", 13, MUTED, false));
        LinearLayout fr2 = row();
        mediaPath = text("—", 14, TEXT, false);
        Button chMedia = button("Change", false);
        addWeighted(fr2, mediaPath, 1, 0);
        fr2.addView(chMedia);
        f.addView(fr2);
        f.addView(spacer(10));
        Button detect = button("Auto-detect from ES-DE settings", false);
        f.addView(detect, new LinearLayout.LayoutParams(-1, -2));
        chRom.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickFolder(true); }
        });
        chMedia.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickFolder(false); }
        });
        detect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { detectFolders(true); }
        });

        // what to fetch
        LinearLayout m = card(left, "What to download");
        m.addView(spacer(4));
        for (String[] ml : MEDIA_LABELS) {
            CheckBox cb = checkbox(ml[1]);
            mediaBoxes.put(ml[0], cb);
            m.addView(cb);
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean c) { saveSettings(); }
            });
        }
        appIconBox = checkbox("Use app icons for Android apps & emulators");
        m.addView(appIconBox);
        TextView appHelp = text("For apps you've imported into ES-DE: in the androidapps and emulators systems, "
                + "each app's own icon from your device becomes its cover, logo, screenshot and mix image "
                + "(never the background). Android games get the icon as their "
                + "logo, plus covers and backgrounds from SteamGridDB. Just keep those systems selected.", 12, MUTED, false);
        appHelp.setPadding(dp(38), 0, dp(6), dp(6));
        m.addView(appHelp);
        appIconBox.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c) { saveSettings(); }
        });
        overwriteBox = checkbox("Replace art that already exists");
        overwriteBox.setTextColor(WARN);
        m.addView(overwriteBox);
        overwriteBox.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c) { saveSettings(); }
        });

        // systems
        LinearLayout s = card(left, "Systems");
        systemsLabel = text("All systems", 15, TEXT, false);
        systemsLabel.setPadding(0, dp(6), 0, dp(10));
        s.addView(systemsLabel);
        Button chooseSys = button("Choose systems", false);
        s.addView(chooseSys, new LinearLayout.LayoutParams(-1, -2));
        chooseSys.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { chooseSystems(); }
        });

        // manual mode
        LinearLayout mm = card(left, "Pick art by hand");
        TextView mmHelp = text("Choose a game, then browse every cover, logo and background SteamGridDB has "
                + "for it and tap the ones you want.", 14, MUTED, false);
        mmHelp.setPadding(0, dp(6), 0, dp(10));
        mm.addView(mmHelp);
        Button manualBtn = button("Choose a game…", true);
        mm.addView(manualBtn, new LinearLayout.LayoutParams(-1, -2));
        manualBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickArtByHand(); }
        });

        // ---------------- right: run + log
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);

        LinearLayout actions = row();
        previewBtn = button("Preview matches", false);
        runBtn = button("Download art", true);
        addWeighted(actions, previewBtn, 1, 0);
        addWeighted(actions, runBtn, 1.4f, 10);
        right.addView(actions);
        previewBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startRun(true); }
        });
        runBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startRun(false); }
        });

        LinearLayout status = card(right, null);
        LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) status.getLayoutParams();
        slp.topMargin = dp(12);
        slp.height = 0;
        slp.weight = 1;
        statusText = text("Ready. Preview first to see what will be matched.", 15, TEXT, true);
        status.addView(statusText);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(CARD_HI));
        progress.setMax(100);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, dp(10));
        plp.topMargin = dp(10);
        plp.bottomMargin = dp(10);
        status.addView(progress, plp);

        logScroll = new ScrollView(this);
        logScroll.setBackground(box(Color.parseColor("#0C0E13"), 0, 10, 0));
        logScroll.setPadding(dp(10), dp(8), dp(10), dp(8));
        logView = text("", 12, Color.parseColor("#C9CFDB"), false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(false);
        logScroll.addView(logView);
        logScroll.setFocusable(true);
        status.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout after = row();
        after.setPadding(0, dp(10), 0, 0);
        stopBtn = button("Stop", false);
        fixBtn = button("Fix unmatched", true);
        addWeighted(after, stopBtn, 1, 0);
        addWeighted(after, fixBtn, 1.4f, 10);
        status.addView(after);
        stopBtn.setVisibility(View.GONE);
        fixBtn.setVisibility(View.GONE);
        stopBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Engine e = engine;
                if (e != null) e.stop = true;
                stopBtn.setEnabled(false);
                stopBtn.setText("Stopping…");
            }
        });
        fixBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMisses(); }
        });

        if (wide) {
            root.addView(leftScroll, new LinearLayout.LayoutParams(0, -1, 1));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 1.15f);
            rlp.leftMargin = dp(16);
            root.addView(right, rlp);
        } else {
            root.addView(leftScroll, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, 0, 1);
            rlp.topMargin = dp(12);
            root.addView(right, rlp);
        }
        setContentView(root);
        runBtn.requestFocus();
    }

    View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    CheckBox checkbox(String label) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(TEXT);
        cb.setTextSize(15);
        cb.setButtonTintList(ColorStateList.valueOf(ACCENT));
        cb.setPadding(dp(6), dp(8), dp(6), dp(8));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, box(CARD_HI, Color.WHITE, 8, 2));
        s.addState(new int[]{}, box(Color.TRANSPARENT, 0, 8, 0));
        cb.setBackground(s);
        return cb;
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
    }

    // ================================================================= settings

    boolean loading = false;

    void loadSettings() {
        loading = true;   // setChecked() below fires listeners that would save half-loaded settings
        Set<String> savedSys = prefs.getStringSet("systems", null);
        chosenSystems = savedSys == null ? null : new HashSet<String>(savedSys);
        String e = prefs.getString("esde", null), r = prefs.getString("roms", null), m = prefs.getString("media", null);
        esdeDir = e == null ? null : new File(e);
        romDir = r == null ? null : new File(r);
        mediaDir = m == null ? null : new File(m);
        Set<String> media = prefs.getStringSet("mediaTypes",
                new HashSet<String>(Arrays.asList("covers", "marquees", "fanart", "screenshots")));
        for (Map.Entry<String, CheckBox> en : mediaBoxes.entrySet()) en.getValue().setChecked(media.contains(en.getKey()));
        overwriteBox.setChecked(prefs.getBoolean("overwrite", false));
        appIconBox.setChecked(prefs.getBoolean("appIcons", true));
        Set<String> sys = prefs.getStringSet("systems", null);
        chosenSystems = sys == null ? null : new HashSet<String>(sys);
        loading = false;
        updateLabels();
    }

    void saveSettings() {
        if (loading) return;
        SharedPreferences.Editor ed = prefs.edit();
        if (esdeDir != null) ed.putString("esde", esdeDir.getPath());
        if (romDir != null) ed.putString("roms", romDir.getPath());
        if (mediaDir != null) ed.putString("media", mediaDir.getPath());
        Set<String> media = new HashSet<String>();
        for (Map.Entry<String, CheckBox> en : mediaBoxes.entrySet()) if (en.getValue().isChecked()) media.add(en.getKey());
        ed.putStringSet("mediaTypes", media);
        ed.putBoolean("overwrite", overwriteBox.isChecked());
        ed.putBoolean("appIcons", appIconBox.isChecked());
        if (chosenSystems == null) ed.remove("systems");
        else ed.putStringSet("systems", new HashSet<String>(chosenSystems));
        ed.apply();
    }

    void updateLabels() {
        String key = prefs.getString("key", "");
        if (key.length() == 0) {
            keyStatus.setText("Not set yet — you need a free key from SteamGridDB.");
            keyStatus.setTextColor(WARN);
        } else {
            keyStatus.setText("✓ Key saved (…" + key.substring(Math.max(0, key.length() - 4)) + ")");
            keyStatus.setTextColor(GOOD);
        }
        String extra = "";
        if (romDir != null && canUseStorage()) {
            int other = 0;
            for (File d : Engine.discoverSystems(romDir, esdeDir, storageRoots()).values()) {
                File p = d.getParentFile();
                if (p == null || !p.getAbsolutePath().equals(romDir.getAbsolutePath())) other++;
            }
            if (other > 0) extra = "\n+ " + other + " system folder" + (other == 1 ? "" : "s") + " found elsewhere (SD card etc.)";
        }
        romPath.setText(romDir == null ? "Not found — tap Change" : romDir.getPath() + extra);
        mediaPath.setText(mediaDir == null ? "Not found — tap Change" : mediaDir.getPath());
        systemsLabel.setText(chosenSystems == null ? "All systems"
                : chosenSystems.size() + " selected: " + Engine.join(sorted(chosenSystems)));
    }

    static List<String> sorted(Set<String> s) {
        List<String> l = new ArrayList<String>(s);
        java.util.Collections.sort(l, String.CASE_INSENSITIVE_ORDER);
        return l;
    }

    Map<String, Long> manualMatches() {
        Map<String, Long> out = new HashMap<String, Long>();
        try {
            JSONObject o = new JSONObject(prefs.getString("manual", "{}"));
            Iterator<String> it = o.keys();
            while (it.hasNext()) { String k = it.next(); out.put(k, o.getLong(k)); }
        } catch (Exception ignored) { }
        return out;
    }

    void saveManualMatch(String key, long id) {
        try {
            JSONObject o = new JSONObject(prefs.getString("manual", "{}"));
            o.put(key, id);
            prefs.edit().putString("manual", o.toString()).apply();
        } catch (Exception ignored) { }
    }

    // ================================================================= API key

    void askForKey() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setHint("Paste your API key");
        et.setText(prefs.getString("key", ""));
        et.setSingleLine(true);
        // pre-fill from the clipboard if it holds something that looks like a key
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = cm.getPrimaryClip();
            if (cd != null && cd.getItemCount() > 0) {
                CharSequence c = cd.getItemAt(0).getText();
                if (c != null && c.toString().trim().matches("[A-Za-z0-9]{24,64}")) et.setText(c.toString().trim());
            }
        } catch (Exception ignored) { }
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        TextView help = text("On steamgriddb.com: log in → Preferences → API → Generate API key. "
                + "Copy it, then paste it here (long-press the box → Paste).", 13, MUTED, false);
        wrap.addView(help);
        wrap.addView(et);
        dialog().setTitle("SteamGridDB API key").setView(wrap)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        prefs.edit().putString("key", et.getText().toString().trim()).apply();
                        updateLabels();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ================================================================= storage

    boolean hasAllFilesAccess() {
        try {
            Object r = Environment.class.getMethod("isExternalStorageManager").invoke(null);
            return Boolean.TRUE.equals(r);
        } catch (Exception e) {
            return false;
        }
    }

    boolean canUseStorage() {
        if (hasAllFilesAccess()) return true;
        // Android 11+: without "All files access" ROM files and folders can be hidden from the app
        if (android.os.Build.VERSION.SDK_INT >= 30) return false;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return false;
        File probe = romDir != null ? romDir : Environment.getExternalStorageDirectory();
        return probe.list() != null;
    }

    void ensureStorageAccess() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        refreshAccessCard();
        detectFolders(false);
    }

    void refreshAccessCard() {
        if (canUseStorage()) {
            accessCard.setVisibility(View.GONE);
        } else {
            accessText.setText("This app needs full access to your files so it can see every system folder "
                    + "(internal storage and SD card) and save artwork into ES-DE's media folder. "
                    + "Tap below and turn on \"Allow access to manage all files\".");
            accessCard.setVisibility(View.VISIBLE);
        }
    }

    void openAllFilesSettings() {
        try {
            startActivity(new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
                    Uri.parse("package:" + getPackageName())));
            return;
        } catch (Exception ignored) { }
        try {
            startActivity(new Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"));
            return;
        } catch (Exception ignored) { }
        ensureStorageAccess();
    }

    List<File> storageRoots() {
        List<File> roots = new ArrayList<File>();
        roots.add(Environment.getExternalStorageDirectory());
        File[] st = new File("/storage").listFiles();
        if (st != null) for (File d : st) {
            String n = d.getName();
            if (!n.equals("emulated") && !n.equals("self") && d.isDirectory()) roots.add(d);
        }
        return roots;
    }

    void detectFolders(boolean announce) {
        List<File> roots = storageRoots();
        File e = Engine.findEsdeDir(roots);
        File r = Engine.findRomDir(e, roots);
        File m = Engine.defaultMediaDir(e);
        if (e != null) esdeDir = e;
        if (r != null && (announce || romDir == null)) romDir = r;
        if (m != null && (announce || mediaDir == null)) mediaDir = m;
        saveSettings();
        updateLabels();
        if (announce) {
            toast(e == null ? "Couldn't find the ES-DE folder. Set the folders with Change."
                    : "Found ES-DE at " + e.getPath());
        }
    }

    /** Simple controller-friendly folder browser. */
    void pickFolder(final boolean forRoms) {
        File start = forRoms ? romDir : mediaDir;
        if (start == null || !start.isDirectory()) start = Environment.getExternalStorageDirectory();
        browse(start, forRoms);
    }

    void browse(final File dir, final boolean forRoms) {
        final List<File> entries = new ArrayList<File>();
        final List<String> labels = new ArrayList<String>();
        if (dir.getParentFile() != null && !dir.getPath().equals("/storage")) {
            entries.add(dir.getParentFile());
            labels.add("⬆  Up one level");
        } else if (!dir.getPath().equals("/storage")) {
            entries.add(new File("/storage"));
            labels.add("⬆  All storage");
        }
        if (dir.getPath().equals("/storage")) {
            for (File r : storageRoots()) { entries.add(r); labels.add("💾  " + r.getPath()); }
        } else {
            File[] kids = dir.listFiles();
            if (kids != null) {
                Arrays.sort(kids, new java.util.Comparator<File>() {
                    public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
                });
                for (File k : kids) {
                    if (k.isDirectory() && !k.getName().startsWith(".")) { entries.add(k); labels.add("📁  " + k.getName()); }
                }
            }
        }
        dialog().setTitle(dir.getPath())
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { browse(entries.get(w), forRoms); }
                })
                .setPositiveButton("Use this folder", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (forRoms) romDir = dir; else mediaDir = dir;
                        chosenSystems = forRoms ? null : chosenSystems;
                        saveSettings();
                        updateLabels();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ================================================================= systems

    /** Engine that sees every system folder on the device, not just one ROMs folder. */
    Engine newEngine() {
        return new Engine(romDir, esdeDir, mediaDir, Engine.discoverSystems(romDir, esdeDir, storageRoots()));
    }

    /** Short human label for where a system folder lives. */
    String where(File sysDir) {
        File parent = sysDir.getParentFile();
        if (parent == null) return sysDir.getPath();
        if (romDir != null && parent.getAbsolutePath().equals(romDir.getAbsolutePath())) return null;
        String p = parent.getPath();
        String internal = Environment.getExternalStorageDirectory().getPath();
        if (p.startsWith(internal)) return "Internal" + p.substring(internal.length());
        if (p.startsWith("/storage/")) return "SD card" + p.substring(p.indexOf('/', 9) < 0 ? p.length() : p.indexOf('/', 9));
        return p;
    }

    void chooseSystems() {
        if (!canUseStorage()) {
            refreshAccessCard();
            toast("Allow full file access first, so every system folder is visible.");
            openAllFilesSettings();
            return;
        }
        final Engine eng = newEngine();
        final List<String> all = eng.systems();
        if (all.isEmpty()) {
            toast("No system folders found. Use Change to pick the folder that holds your systems.");
            return;
        }
        final boolean[] checked = new boolean[all.size()];
        final String[] labels = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            checked[i] = chosenSystems == null || chosenSystems.contains(all.get(i));
            String w = where(eng.systemDirs.get(all.get(i)));
            labels[i] = w == null ? all.get(i) : all.get(i) + "   ·  " + w;
        }
        final AlertDialog dlg = dialog().setTitle("Systems to scan (" + all.size() + " found)")
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int w, boolean c) {
                        checked[w] = c;
                        updateToggle((AlertDialog) d, checked);
                    }
                })
                .setPositiveButton("Done", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Set<String> s = new HashSet<String>();
                        for (int i = 0; i < all.size(); i++) if (checked[i]) s.add(all.get(i));
                        if (s.isEmpty()) { toast("No systems selected — keeping all systems."); s = null; }
                        chosenSystems = s == null || s.size() == all.size() ? null : s;
                        saveSettings();
                        updateLabels();
                    }
                })
                .setNeutralButton("Select all", null)
                .setNegativeButton("Cancel", null).show();
        updateToggle(dlg, checked);
        // one tap selects everything, or clears everything if all are already ticked
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean allOn = true;
                for (boolean b : checked) allOn &= b;
                ListView lv = dlg.getListView();
                for (int i = 0; i < checked.length; i++) {
                    checked[i] = !allOn;
                    lv.setItemChecked(i, !allOn);
                }
                updateToggle(dlg, checked);
            }
        });
    }

    void updateToggle(AlertDialog d, boolean[] checked) {
        boolean allOn = true;
        for (boolean b : checked) allOn &= b;
        Button b = d.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (b != null) b.setText(allOn ? "Deselect all" : "Select all");
    }

    // ================================================================= running

    Engine.Options options(boolean dry) {
        Engine.Options o = new Engine.Options();
        o.media = new ArrayList<String>();
        for (String[] ml : MEDIA_LABELS) if (mediaBoxes.get(ml[0]).isChecked()) o.media.add(ml[0]);
        o.overwrite = overwriteBox.isChecked();
        o.appMarquees = appIconBox.isChecked();
        o.dryRun = dry;
        o.systems = chosenSystems;
        o.manualMatches = manualMatches();
        return o;
    }

    void startRun(final boolean dry) {
        if (worker != null && worker.isAlive()) return;
        final String key = prefs.getString("key", "");
        if (key.length() == 0) { askForKey(); return; }
        if (!canUseStorage()) { refreshAccessCard(); toast("Allow file access first."); return; }
        if (romDir == null || !romDir.isDirectory()) { toast("Set your ROMs folder first."); return; }
        if (mediaDir == null) { toast("Set the ES-DE media folder first."); return; }
        final Engine.Options o = options(dry);
        if (o.media.isEmpty()) { toast("Tick at least one kind of art to download."); return; }
        if (o.appMarquees && !o.media.contains("marquees")) o.media.add("marquees");

        logLines.clear();
        logView.setText("");
        misses.clear();
        fixBtn.setVisibility(View.GONE);
        stopBtn.setVisibility(View.VISIBLE);
        stopBtn.setEnabled(true);
        stopBtn.setText("Stop");
        runBtn.setEnabled(false);
        previewBtn.setEnabled(false);
        progress.setProgress(0);
        statusText.setText(dry ? "Previewing…" : "Downloading art…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        stopBtn.requestFocus();

        final Engine e = newEngine();
        e.appArt = new DeviceAppArt(getPackageManager());
        engine = e;
        worker = new Thread(new Runnable() {
            public void run() {
                final Engine.Result r = e.run(new Sgdb(key), o, new Engine.Listener() {
                    public void log(String line) { appendLog(line); }
                    public void progress(final int done, final int total) {
                        ui.post(new Runnable() {
                            public void run() {
                                progress.setMax(Math.max(1, total));
                                progress.setProgress(done);
                                statusText.setText((dry ? "Previewing… " : "Downloading… ") + done + " / " + total);
                            }
                        });
                    }
                });
                ui.post(new Runnable() {
                    public void run() { finished(r, dry); }
                });
            }
        });
        worker.start();
    }

    void appendLog(String line) {
        synchronized (logLines) {
            for (String l : line.split("\n")) {
                logLines.addLast(l);
                while (logLines.size() > 600) logLines.removeFirst();
            }
            if (logPending) return;
            logPending = true;
        }
        ui.postDelayed(new Runnable() {
            public void run() {
                StringBuilder b = new StringBuilder();
                synchronized (logLines) {
                    for (String l : logLines) b.append(l).append('\n');
                    logPending = false;
                }
                logView.setText(b);
                logScroll.post(new Runnable() {
                    public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        }, 150);
    }

    void finished(Engine.Result r, boolean dry) {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        engine = null;
        stopBtn.setVisibility(View.GONE);
        runBtn.setEnabled(true);
        previewBtn.setEnabled(true);
        if (r.fatal != null) {
            statusText.setText(r.fatal);
            statusText.setTextColor(WARN);
            askForKey();
            return;
        }
        statusText.setTextColor(TEXT);
        String s = r.games + " games · " + r.matched + " matched · "
                + r.saved + (dry ? " images would be saved" : " images saved")
                + (r.noMatch > 0 ? " · " + r.noMatch + " not found" : "")
                + (r.cleaned > 0 ? " · " + r.cleaned + " unneeded copies removed" : "")
                + (r.failed > 0 ? " · " + r.failed + " errors" : "");
        statusText.setText(s);
        appendLog("\n" + s + (dry ? "\nLooks right? Tap Download art."
                : (r.saved > 0 ? "\nRestart ES-DE to see the new art." : "")));
        misses.addAll(r.misses);
        if (!misses.isEmpty()) {
            fixBtn.setText("Fix " + misses.size() + " unmatched");
            fixBtn.setVisibility(View.VISIBLE);
            fixBtn.requestFocus();
        } else {
            runBtn.requestFocus();
        }
    }

    // ================================================================= fixing unmatched games

    void showMisses() {
        if (misses.isEmpty()) { fixBtn.setVisibility(View.GONE); return; }
        String[] items = new String[misses.size()];
        for (int i = 0; i < items.length; i++) items[i] = misses.get(i).toString();
        dialog().setTitle("Pick a game to match by hand")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final Engine.Miss miss = misses.get(w);
                        searchFor(miss.game, miss.query, new GamePicked() {
                            public void picked(final Sgdb.Game g) {
                                dialog().setTitle(g.name)
                                        .setMessage("Download the best art automatically, or choose each image yourself?")
                                        .setPositiveButton("Choose images", new DialogInterface.OnClickListener() {
                                            public void onClick(DialogInterface d2, int w2) {
                                                saveManualMatch(miss.game.key(), g.id);
                                                misses.remove(miss);
                                                refreshFixButton();
                                                openArtBrowser(miss.game, g);
                                            }
                                        })
                                        .setNegativeButton("Automatic", new DialogInterface.OnClickListener() {
                                            public void onClick(DialogInterface d2, int w2) {
                                                applyManual(miss, g, prefs.getString("key", ""));
                                            }
                                        }).show();
                            }
                        });
                    }
                })
                .setNegativeButton("Close", null).show();
    }

    interface GamePicked { void picked(Sgdb.Game game); }

    void refreshFixButton() {
        if (misses.isEmpty()) fixBtn.setVisibility(View.GONE);
        else { fixBtn.setText("Fix " + misses.size() + " unmatched"); fixBtn.setVisibility(View.VISIBLE); }
    }

    /** Lets the user find the right SteamGridDB title for one file. */
    void searchFor(final Engine.GameFile file, String query, final GamePicked cb) {
        final String key = prefs.getString("key", "");
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(4), dp(20), 0);
        wrap.addView(text(file.system + " / " + file.rel, 13, MUTED, false));
        LinearLayout r = row();
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(query);
        Button go = button("Search", true);
        addWeighted(r, et, 1, 0);
        r.addView(go);
        wrap.addView(r);
        final TextView info = text("", 13, MUTED, false);
        wrap.addView(info);
        final ListView list = new ListView(this);
        final ArrayAdapter<Sgdb.Game> adapter = new ArrayAdapter<Sgdb.Game>(this,
                android.R.layout.simple_list_item_1, new ArrayList<Sgdb.Game>()) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, cv, parent);
                tv.setTextColor(TEXT);
                return tv;
            }
        };
        list.setAdapter(adapter);
        wrap.addView(list, new LinearLayout.LayoutParams(-1, dp(260)));
        final AlertDialog dlg = dialog().setTitle("Which game is \"" + file.base() + "\"?").setView(wrap)
                .setNegativeButton("Cancel", null).show();
        final Runnable doSearch = new Runnable() {
            public void run() {
                final String q = et.getText().toString().trim();
                if (q.length() == 0) return;
                info.setText("Searching…");
                new Thread(new Runnable() {
                    public void run() {
                        List<Sgdb.Game> res;
                        String err = null;
                        try { res = new Sgdb(key).search(q); }
                        catch (Exception ex) { res = new ArrayList<Sgdb.Game>(); err = ex.getMessage(); }
                        final List<Sgdb.Game> fr = res;
                        final String fe = err;
                        ui.post(new Runnable() {
                            public void run() {
                                adapter.clear();
                                adapter.addAll(fr);
                                info.setText(fe != null ? "Error: " + fe
                                        : fr.isEmpty() ? "No results — try a shorter or different name."
                                        : "Pick the right game:");
                                if (!fr.isEmpty()) list.requestFocus();
                            }
                        });
                    }
                }).start();
            }
        };
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doSearch.run(); }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Sgdb.Game g = adapter.getItem(pos);
                dlg.dismiss();
                cb.picked(g);
            }
        });
        doSearch.run();
    }

    void applyManual(final Engine.Miss miss, final Sgdb.Game game, final String key) {
        if (mediaDir == null) return;
        final Engine.Options o = options(false);
        statusText.setText("Downloading art for " + game.name + "…");
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try {
                    Engine e = newEngine();
                    Map<String, File> have = new HashMap<String, File>();
                    for (String m : o.media) {
                        File ex = Engine.existing(e.mediaFolder(miss.game, m), miss.game.base());
                        if (ex != null) have.put(m, ex);
                    }
                    List<String> got = e.fetchArt(new Sgdb(key), o, null, miss.game, game.id, o.media, have);
                    saveManualMatch(miss.game.key(), game.id);
                    msg = got.isEmpty() ? game.name + " has no art of the selected types on SteamGridDB."
                            : "Saved " + Engine.join(got) + " for " + miss.game.base();
                } catch (Exception ex) {
                    msg = "Failed: " + ex.getMessage();
                }
                final String fm = msg;
                ui.post(new Runnable() {
                    public void run() {
                        appendLog("  ✎ " + fm);
                        statusText.setText(fm);
                        misses.remove(miss);
                        refreshFixButton();
                        if (misses.isEmpty()) runBtn.requestFocus(); else showMisses();
                    }
                });
            }
        }).start();
    }

    // ================================================================= manual art picking

    static class GameEntry {
        final Engine.GameFile file;
        final String title;
        GameEntry(Engine.GameFile f, String t) { file = f; title = t; }
        @Override public String toString() { return title + "   ·  " + file.system; }
    }

    /** Step 1: list every game on the device, filterable by typing. */
    void pickArtByHand() {
        if (prefs.getString("key", "").length() == 0) { askForKey(); return; }
        if (!canUseStorage()) { refreshAccessCard(); toast("Allow file access first."); return; }
        if (mediaDir == null) { toast("Set the ES-DE media folder first."); return; }
        final AlertDialog wait = dialog().setTitle("Finding your games…").setMessage("Scanning system folders.").show();
        new Thread(new Runnable() {
            public void run() {
                Engine e = newEngine();
                final List<GameEntry> all = new ArrayList<GameEntry>();
                for (String sys : e.systems()) {
                    Map<String, String> names = e.titles(sys);
                    for (Engine.GameFile g : e.scanSystem(sys)) all.add(new GameEntry(g, e.searchName(g, names)));
                }
                java.util.Collections.sort(all, new java.util.Comparator<GameEntry>() {
                    public int compare(GameEntry a, GameEntry b) { return a.title.compareToIgnoreCase(b.title); }
                });
                ui.post(new Runnable() {
                    public void run() {
                        wait.dismiss();
                        if (all.isEmpty()) toast("No games found in your system folders.");
                        else showGameList(all);
                    }
                });
            }
        }).start();
    }

    void showGameList(final List<GameEntry> all) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(4), dp(20), 0);
        final EditText filter = new EditText(this);
        filter.setSingleLine(true);
        filter.setHint("Type to filter " + all.size() + " games");
        wrap.addView(filter);
        final List<GameEntry> shown = new ArrayList<GameEntry>(all);
        final ArrayAdapter<GameEntry> adapter = new ArrayAdapter<GameEntry>(this,
                android.R.layout.simple_list_item_1, shown) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, cv, parent);
                tv.setTextColor(TEXT);
                return tv;
            }
        };
        final ListView list = new ListView(this);
        list.setAdapter(adapter);
        wrap.addView(list, new LinearLayout.LayoutParams(-1, dp(320)));
        final AlertDialog dlg = dialog().setTitle("Pick a game").setView(wrap)
                .setNegativeButton("Close", null).show();
        filter.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable s) {
                String q = Names.norm(s.toString());
                shown.clear();
                for (GameEntry g : all) {
                    if (q.length() == 0 || Names.norm(g.title + " " + g.file.base() + " " + g.file.system).contains(q)) shown.add(g);
                }
                adapter.notifyDataSetChanged();
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                final GameEntry ge = shown.get(pos);
                dlg.dismiss();
                // reuse an earlier hand-picked match if there is one
                final Long known = manualMatches().get(ge.file.key());
                if (known != null) {
                    dialog().setTitle(ge.title)
                            .setMessage("You matched this file to a SteamGridDB game before. Use that match?")
                            .setPositiveButton("Use it", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    openArtBrowser(ge.file, new Sgdb.Game(known, ge.title, 0));
                                }
                            })
                            .setNegativeButton("Search again", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) { searchThenBrowse(ge); }
                            }).show();
                } else {
                    searchThenBrowse(ge);
                }
            }
        });
        list.requestFocus();
    }

    void searchThenBrowse(final GameEntry ge) {
        searchFor(ge.file, ge.title, new GamePicked() {
            public void picked(Sgdb.Game g) {
                saveManualMatch(ge.file.key(), g.id);
                openArtBrowser(ge.file, g);
            }
        });
    }

    static final String[][] ART_TABS = {
            {"covers", "Covers"}, {"marquees", "Logos"}, {"fanart", "Fanart"},
            {"screenshots", "Screenshots"}, {"titlescreens", "Title screens"}};

    /** Step 3: full-screen browser of every SteamGridDB image for one game, per ES-DE art type. */
    void openArtBrowser(final Engine.GameFile file, final Sgdb.Game game) {
        final String key = prefs.getString("key", "");
        final Sgdb api = new Sgdb(key);
        final Engine eng = newEngine();
        final android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout top = row();
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text(game.name, 20, TEXT, true));
        titles.addView(text(file.system + " / " + file.rel, 12, MUTED, false));
        addWeighted(top, titles, 1, 0);
        Button close = button("Done", true);
        top.addView(close);
        root.addView(top);

        LinearLayout tabs = row();
        tabs.setPadding(0, dp(10), 0, dp(10));
        final Button[] tabBtns = new Button[ART_TABS.length];
        for (int i = 0; i < ART_TABS.length; i++) {
            tabBtns[i] = button(ART_TABS[i][1], false);
            tabBtns[i].setTextSize(13);
            addWeighted(tabs, tabBtns[i], 1, i == 0 ? 0 : 6);
        }
        root.addView(tabs);

        LinearLayout current = row();
        current.setBackground(box(CARD, 0, 10, 0));
        current.setPadding(dp(10), dp(8), dp(10), dp(8));
        final android.widget.ImageView curImg = new android.widget.ImageView(this);
        curImg.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        current.addView(curImg, new LinearLayout.LayoutParams(dp(90), dp(56)));
        final TextView curText = text("", 14, TEXT, false);
        addWeighted(current, curText, 1, 12);
        final Button removeBtn = button("Remove", false);
        current.addView(removeBtn);
        root.addView(current);

        final TextView info = text("", 13, MUTED, false);
        info.setPadding(0, dp(8), 0, dp(6));
        root.addView(info);

        final android.widget.GridView grid = new android.widget.GridView(this);
        grid.setNumColumns(android.widget.GridView.AUTO_FIT);
        grid.setHorizontalSpacing(dp(10));
        grid.setVerticalSpacing(dp(10));
        grid.setStretchMode(android.widget.GridView.STRETCH_SPACING_UNIFORM);
        grid.setSelector(box(Color.TRANSPARENT, Color.WHITE, 8, 3));
        grid.setDrawSelectorOnTop(true);
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));

        final String[] tab = {ART_TABS[0][0]};
        final List<Sgdb.Image> imgs = new ArrayList<Sgdb.Image>();
        final int[] cell = {dp(120), dp(180)};

        final android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            public int getCount() { return imgs.size(); }
            public Object getItem(int i) { return imgs.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View cv, ViewGroup parent) {
                android.widget.ImageView iv = cv instanceof android.widget.ImageView ? (android.widget.ImageView) cv
                        : new android.widget.ImageView(MainActivity.this);
                iv.setLayoutParams(new android.widget.AbsListView.LayoutParams(cell[0], cell[1]));
                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                iv.setBackground(box(CARD_HI, 0, 8, 0));
                iv.setPadding(dp(4), dp(4), dp(4), dp(4));
                thumbs.load(imgs.get(i).thumbOrUrl(), iv, Math.max(cell[0], cell[1]));
                return iv;
            }
        };
        grid.setAdapter(adapter);

        final Runnable showCurrent = new Runnable() {
            public void run() {
                File f = eng.currentArt(file, tab[0]);
                if (f == null) {
                    curImg.setImageDrawable(null);
                    curText.setText("No " + tabLabel(tab[0]).toLowerCase() + " art saved yet");
                    removeBtn.setVisibility(View.GONE);
                } else {
                    thumbs.forget(f.getPath());
                    thumbs.load(f.getPath(), curImg, dp(120));
                    curText.setText("Current " + tabLabel(tab[0]).toLowerCase() + " · " + f.getName());
                    removeBtn.setVisibility(View.VISIBLE);
                }
            }
        };

        final Runnable[] loadTab = new Runnable[1];
        loadTab[0] = new Runnable() {
            public void run() {
                for (int i = 0; i < ART_TABS.length; i++) {
                    boolean sel = ART_TABS[i][0].equals(tab[0]);
                    tabBtns[i].setBackground(sel ? focusable(ACCENT, Color.parseColor("#3B74E0"))
                            : focusable(CARD_HI, Color.parseColor("#323A4B")));
                    tabBtns[i].setTextColor(sel ? Color.WHITE : TEXT);
                }
                boolean tall = tab[0].equals("covers");
                cell[0] = tall ? dp(120) : dp(220);
                cell[1] = tall ? dp(180) : (tab[0].equals("marquees") ? dp(90) : dp(103));
                grid.setColumnWidth(cell[0]);
                showCurrent.run();
                imgs.clear();
                adapter.notifyDataSetChanged();
                info.setText("Loading " + tabLabel(tab[0]).toLowerCase() + " from SteamGridDB…");
                final String want = tab[0];
                new Thread(new Runnable() {
                    public void run() {
                        List<Sgdb.Image> res;
                        String err = null;
                        try { res = Engine.allImages(api, game.id, want); }
                        catch (Exception ex) { res = new ArrayList<Sgdb.Image>(); err = ex.getMessage(); }
                        final List<Sgdb.Image> fr = res;
                        final String fe = err;
                        ui.post(new Runnable() {
                            public void run() {
                                if (!want.equals(tab[0])) return;
                                imgs.clear();
                                imgs.addAll(fr);
                                adapter.notifyDataSetChanged();
                                info.setText(fe != null ? "Couldn't load: " + fe
                                        : fr.isEmpty() ? "SteamGridDB has no " + tabLabel(want).toLowerCase() + " for this game."
                                        : fr.size() + " to choose from — select one to use it.");
                                if (!fr.isEmpty()) grid.requestFocus();
                            }
                        });
                    }
                }).start();
            }
        };

        for (int i = 0; i < ART_TABS.length; i++) {
            final String t = ART_TABS[i][0];
            tabBtns[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { tab[0] = t; loadTab[0].run(); }
            });
        }

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                final Sgdb.Image im = imgs.get(pos);
                final String forTab = tab[0];
                final android.widget.ImageView preview = new android.widget.ImageView(MainActivity.this);
                preview.setAdjustViewBounds(true);
                preview.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                preview.setPadding(dp(20), dp(10), dp(20), 0);
                preview.setMaxHeight(dp(300));
                thumbs.load(im.thumbOrUrl(), preview, dp(400));
                String size = im.width > 0 ? im.width + "×" + im.height : "";
                dialog().setTitle("Use as " + tabLabel(forTab).toLowerCase() + "?")
                        .setMessage(size)
                        .setView(preview)
                        .setPositiveButton("Use this", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dd, int w) {
                                info.setText("Saving…");
                                new Thread(new Runnable() {
                                    public void run() {
                                        String msg;
                                        try {
                                            File f = eng.saveChosen(api, file, forTab, im.url);
                                            msg = "Saved " + tabLabel(forTab).toLowerCase() + " → " + f.getName();
                                        } catch (Exception ex) {
                                            msg = "Couldn't save: " + ex.getMessage();
                                        }
                                        final String fm = msg;
                                        ui.post(new Runnable() {
                                            public void run() {
                                                info.setText(fm);
                                                appendLog("  ✎ " + file.base() + ": " + fm);
                                                if (forTab.equals(tab[0])) showCurrent.run();
                                            }
                                        });
                                    }
                                }).start();
                            }
                        })
                        .setNegativeButton("Back", null).show();
            }
        });

        removeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final File f = eng.currentArt(file, tab[0]);
                if (f == null) return;
                dialog().setTitle("Remove this " + tabLabel(tab[0]).toLowerCase() + "?")
                        .setMessage(f.getPath())
                        .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dd, int w) {
                                if (f.delete()) info.setText("Removed.");
                                else info.setText("Couldn't remove " + f.getName());
                                showCurrent.run();
                            }
                        })
                        .setNegativeButton("Cancel", null).show();
            }
        });

        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { d.dismiss(); }
        });
        d.setContentView(root);
        d.show();
        loadTab[0].run();
    }

    static String tabLabel(String media) {
        for (String[] t : ART_TABS) if (t[0].equals(media)) return t[1];
        return media;
    }
}
