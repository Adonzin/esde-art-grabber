package com.esde.artgrabber;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A category's custom look: three pictures (from files or app icons), applied to an editable theme copy. */
class LookEditor {
    private final MainActivity a;
    private final EsdeConfig.Category c;
    private final Runnable onChange;
    private final CategoryLooks looks;
    private final CategoryLooks.Look look;
    private final LookRenderer renderer;
    private File copy;
    private AlertDialog dlg;

    static final String[][] ROLES = {
            {ThemeArtPlan.MAIN, "Main picture", "The big carousel tile and other artwork"},
            {ThemeArtPlan.LOGO, "Logo", "Logos and name logos"},
            {ThemeArtPlan.BACKGROUND, "Background", "Backgrounds, if the theme shows them per system"}};
    static final String[][] COLORS = {
            {"2B3A55", "Midnight"}, {"0F0F12", "Black"}, {"F2F2F2", "White"}, {"C62828", "Red"}, {"2E7D32", "Green"},
            {"1565C0", "Blue"}, {"6A1B9A", "Purple"}, {"EF6C00", "Orange"}};

    LookEditor(MainActivity a, EsdeConfig.Category c, Runnable onChange) {
        this.a = a;
        this.c = c;
        this.onChange = onChange;
        this.looks = new CategoryLooks(a.esdeDir);
        this.look = looks.load(c.name);
        this.look.fullName = c.fullName;
        this.renderer = new LookRenderer(a.getPackageManager());
    }

    void open() {
        ThemeCopies tc = new ThemeCopies(a.esdeDir);
        List<File> copies = new ArrayList<File>();
        for (File d : tc.localThemes()) if (ThemeCopies.isCopy(d.getName())) copies.add(d);
        if (copies.isEmpty()) {
            a.dialog().setTitle("Make an editable theme first")
                    .setMessage("Custom looks are saved into an editable copy of your ES-DE theme. Make one on the Themes screen "
                            + "(it takes a minute), then come back here.")
                    .setPositiveButton("Open themes", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { new ThemesScreen(a).open(); }
                    })
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        String cur = tc.currentTheme();
        copy = cur != null && tc.copyDir(cur).isDirectory() ? tc.copyDir(cur) : copies.get(0);
        for (String t : look.applied.keySet()) for (File d : copies) if (d.getName().equals(t)) copy = d;
        show(copies);
    }

    private void show(final List<File> copies) {
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(a.dp(20), a.dp(6), a.dp(20), 0);

        LinearLayout tr = a.row();
        a.addWeighted(tr, a.text("Saved into: " + ThemesScreen.nameOf(copy), 14, MainActivity.TEXT, false), 1, 0);
        if (copies.size() > 1) {
            Button change = a.button("Change", false);
            tr.addView(change);
            change.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    String[] names = new String[copies.size()];
                    for (int i = 0; i < names.length; i++) names[i] = ThemesScreen.nameOf(copies.get(i));
                    a.dialog().setItems(names, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { copy = copies.get(w); dlg.dismiss(); show(copies); }
                    }).show();
                }
            });
        }
        wrap.addView(tr);

        for (final String[] role : ROLES) {
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(a.box(MainActivity.CARD_HI, 0, 10, 0));
            box.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.topMargin = a.dp(10);
            wrap.addView(box, blp);
            LinearLayout head = a.row();
            final ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackground(a.box(Color.parseColor("#" + look.color), 0, 6, 0));
            head.addView(iv, new LinearLayout.LayoutParams(a.dp(56), a.dp(56)));
            LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(a.dp(10), 0, 0, 0);
            texts.addView(a.text(role[1], 15, MainActivity.TEXT, true));
            CategoryLooks.Picture p = look.pictures.get(role[0]);
            texts.addView(a.text(p == null ? "None" : (CategoryLooks.APP.equals(p.kind) ? "Icon of " : "") + p.label, 13,
                    p == null ? MainActivity.MUTED : MainActivity.GOOD, false));
            texts.addView(a.text(role[2], 12, MainActivity.MUTED, false));
            a.addWeighted(head, texts, 1, 0);
            box.addView(head);
            if (p != null) {
                if (CategoryLooks.APP.equals(p.kind)) iv.setImageDrawable(renderer.icon(p.value));
                else a.thumbs.load(p.value, iv, a.dp(56));
            }
            LinearLayout btns = a.row();
            btns.setPadding(0, a.dp(8), 0, 0);
            Button file = a.button("Picture…", false), app = a.button("App icon…", false), clear = a.button("Clear", false);
            for (Button b : new Button[]{file, app, clear}) b.setTextSize(13);
            a.addWeighted(btns, file, 1, 0);
            a.addWeighted(btns, app, 1, 6);
            a.addWeighted(btns, clear, 1, 6);
            box.addView(btns);
            file.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    a.pickPicture(new MainActivity.PictureCallback() {
                        public void got(byte[] data, String name) {
                            try {
                                a.thumbs.forget(new File(looks.esdeDir, "commander/looks/" + c.name + "/" + role[0] + ".picture").getPath());
                                look.pictures.put(role[0], looks.storePicture(c.name, role[0], data, name));
                                looks.save(look);
                            } catch (IOException e) {
                                a.toast("Couldn't keep the picture: " + e.getMessage());
                            }
                            dlg.dismiss();
                            show(copies);
                        }
                    });
                }
            });
            app.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    chooseApp(new AppPicked() {
                        public void picked(String pkg, String label) {
                            look.pictures.put(role[0], new CategoryLooks.Picture(CategoryLooks.APP, pkg, label));
                            try { looks.save(look); } catch (IOException ignored) { }
                            dlg.dismiss();
                            show(copies);
                        }
                    });
                }
            });
            clear.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    look.pictures.remove(role[0]);
                    try { looks.save(look); } catch (IOException ignored) { }
                    dlg.dismiss();
                    show(copies);
                }
            });
        }

        final Button colour = a.button("Colour behind app icons: " + colourName(look.color), false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = a.dp(10);
        wrap.addView(colour, clp);
        colour.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String[] names = new String[COLORS.length];
                for (int i = 0; i < names.length; i++) names[i] = COLORS[i][1];
                a.dialog().setTitle("Colour behind app icons").setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        look.color = COLORS[w][0];
                        try { looks.save(look); } catch (IOException ignored) { }
                        dlg.dismiss();
                        show(copies);
                    }
                }).show();
            }
        });
        wrap.addView(a.text("App icons on big tiles sit in the middle of this colour, so they aren't cut off.", 12, MainActivity.MUTED, false));

        wrap.addView(a.text("Description (some themes show it)", 13, MainActivity.MUTED, false));
        final EditText desc = new EditText(a);
        desc.setText(look.description);
        desc.setHint("e.g. My streaming apps");
        wrap.addView(desc);

        ScrollView sc = new ScrollView(a);
        sc.addView(wrap);
        dlg = a.dialog().setTitle("Custom look: " + c.fullName).setView(sc)
                .setPositiveButton("Apply", null)
                .setNeutralButton(look.applied.containsKey(copy.getName()) ? "Remove look" : "Close", null)
                .setNegativeButton("Cancel", null).show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                look.description = desc.getText().toString().trim();
                if (look.isEmpty()) { a.toast("Choose at least one picture."); return; }
                dlg.dismiss();
                apply();
            }
        });
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
                if (look.applied.containsKey(copy.getName())) removeLook();
            }
        });
    }

    private void apply() {
        final AlertDialog wait = a.dialog().setMessage("Saving the look into " + ThemesScreen.nameOf(copy) + "…").setCancelable(false).show();
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try {
                    CategoryLooks.Result r = looks.apply(look, copy, renderer);
                    // the category now looks itself up by its own name, which is where the pictures went
                    if (!EsdeConfig.KIND_COLLECTION.equals(c.kind) && !c.name.equals(c.theme)) {
                        EsdeConfig cfg = new EsdeConfig(a.esdeDir, a.romDir);
                        c.theme = c.name;
                        if (EsdeConfig.KIND_APPS.equals(c.kind)) cfg.saveAppCategory(c, cfg.apps(c));
                        else cfg.saveMoonlightCategory(c, cfg.moonlightGames(c));
                    }
                    StringBuilder b = new StringBuilder("Saved " + r.written + " file" + (r.written == 1 ? "" : "s") + " into "
                            + ThemesScreen.nameOf(copy) + ".");
                    if (r.kept > 0) b.append("\nKept ").append(r.kept).append(" the theme already had.");
                    if (!r.errors.isEmpty()) b.append("\n\nProblems:\n• ").append(android.text.TextUtils.join("\n• ", r.errors));
                    if (!r.skipped.isEmpty()) b.append("\n\nNot used:\n• ").append(android.text.TextUtils.join("\n• ", dedupe(r.skipped)));
                    String cur = new ThemeCopies(a.esdeDir).currentTheme();
                    if (cur == null || !cur.equals(copy.getName()))
                        b.append("\n\nTo see it, close ES-DE and choose \"").append(ThemesScreen.nameOf(copy)).append("\" under UI Settings → Theme.");
                    else b.append("\n\nRestart ES-DE to see it.");
                    if (EsdeConfig.KIND_COLLECTION.equals(c.kind)) b.append(" Collections need \"Group custom collections\" set to Never.");
                    msg = b.toString();
                } catch (Exception e) {
                    msg = "Couldn't save the look: " + e.getMessage();
                }
                final String fm = msg;
                a.ui.post(new Runnable() {
                    public void run() {
                        wait.dismiss();
                        a.dialog().setTitle(c.fullName).setMessage(fm).setPositiveButton("OK", null).show();
                        a.appendLog("  ◈ " + c.fullName + ": custom look saved");
                        if (onChange != null) onChange.run();
                    }
                });
            }
        }).start();
    }

    private void removeLook() {
        try {
            int n = looks.remove(look, copy);
            a.toast("Removed " + n + " file" + (n == 1 ? "" : "s") + " from " + ThemesScreen.nameOf(copy) + ".");
        } catch (IOException e) {
            a.toast("Couldn't remove the look: " + e.getMessage());
        }
        if (onChange != null) onChange.run();
    }

    static List<String> dedupe(List<String> l) { return new ArrayList<String>(new java.util.LinkedHashSet<String>(l)); }

    static String colourName(String hex) {
        for (String[] c : COLORS) if (c[0].equalsIgnoreCase(hex)) return c[1];
        return "#" + hex;
    }

    interface AppPicked { void picked(String pkg, String label); }

    /** Installed apps you can open, to take an icon from. */
    private void chooseApp(final AppPicked cb) {
        PackageManager pm = a.getPackageManager();
        final Map<String, String> apps = new LinkedHashMap<String, String>();
        for (String cat : new String[]{Intent.CATEGORY_LAUNCHER, "android.intent.category.LEANBACK_LAUNCHER"}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String pkg = r.activityInfo.packageName;
                if (!apps.containsKey(pkg)) apps.put(pkg, String.valueOf(r.activityInfo.applicationInfo.loadLabel(pm)));
            }
        }
        final List<String> pkgs = new ArrayList<String>(apps.keySet());
        Collections.sort(pkgs, new Comparator<String>() {
            public int compare(String x, String y) { return apps.get(x).compareToIgnoreCase(apps.get(y)); }
        });
        // apps in this category first
        if (EsdeConfig.KIND_APPS.equals(c.kind)) {
            List<String> mine = new ArrayList<String>(new EsdeConfig(a.esdeDir, a.romDir).apps(c).values());
            List<String> first = new ArrayList<String>();
            for (String p : pkgs) if (mine.contains(p)) first.add(p);
            pkgs.removeAll(first);
            pkgs.addAll(0, first);
        }
        String[] labels = new String[pkgs.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = apps.get(pkgs.get(i));
        a.dialog().setTitle("Use which app's icon?").setItems(labels, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { cb.picked(pkgs.get(w), apps.get(pkgs.get(w))); }
        }).setNegativeButton("Cancel", null).show();
    }
}
